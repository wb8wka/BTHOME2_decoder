package com.wb8wka.bthome2decoder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class BleScanner(context: Context) {

    data class Diagnostics(
        val advertisementsSeen: Long = 0,
        val bthomePacketsSeen: Long = 0,
        val scanFailureCode: Int? = null,
        val bluetoothEnabled: Boolean = false,
        val scanStarted: Boolean = false,
        val status: String = "Initializing"
    )

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BleDevice>> = _devices.asStateFlow()

    private val _diagnostics = MutableStateFlow(Diagnostics(bluetoothEnabled = isBluetoothEnabled()))
    val diagnostics: StateFlow<Diagnostics> = _diagnostics.asStateFlow()

    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            _diagnostics.update {
                it.copy(advertisementsSeen = it.advertisementsSeen + 1, bluetoothEnabled = isBluetoothEnabled())
            }
            val record = result.scanRecord ?: return
            val serviceData = extractBTHomeServiceData(record.serviceData, record.bytes) ?: return
            _diagnostics.update { it.copy(bthomePacketsSeen = it.bthomePacketsSeen + 1) }
            val entry = BleDevice(
                address = result.device.address,
                name = record.deviceName,
                rssi = result.rssi,
                serviceData = serviceData,
                lastSeen = System.currentTimeMillis()
            )
            _devices.update { current -> current + (entry.address to entry) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            _diagnostics.update {
                it.copy(scanFailureCode = errorCode, scanStarted = false, status = "Android scan failed: $errorCode")
            }
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) return
        if (!isBluetoothEnabled()) {
            _diagnostics.update { it.copy(bluetoothEnabled = false, scanStarted = false, status = "Bluetooth is off") }
            return
        }
        val leScanner = scanner
        if (leScanner == null) {
            _diagnostics.update { it.copy(scanStarted = false, status = "BLE scanner unavailable") }
            return
        }
        _diagnostics.update {
            it.copy(scanFailureCode = null, bluetoothEnabled = true, scanStarted = true, status = "Scanning")
        }
        try {
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            leScanner.startScan(null, settings, callback)
            scanning = true
        } catch (e: SecurityException) {
            _diagnostics.update {
                it.copy(scanStarted = false, status = "BLUETOOTH_SCAN permission missing: ${e.message}")
            }
        } catch (e: Exception) {
            _diagnostics.update { it.copy(scanStarted = false, status = "Could not start scan: ${e.message}") }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(callback)
        scanning = false
        _diagnostics.update { it.copy(scanStarted = false, status = "Scan stopped") }
    }

    fun clear() {
        _devices.value = emptyMap()
        _diagnostics.value = Diagnostics(bluetoothEnabled = isBluetoothEnabled(), scanStarted = scanning, status = if (scanning) "Scanning" else "Cleared")
    }

    private fun extractBTHomeServiceData(serviceDataMap: Map<ParcelUuid, ByteArray>?, rawRecord: ByteArray?): ByteArray? {
        serviceDataMap?.get(BTHOME_UUID)?.let { return it }
        serviceDataMap?.entries?.firstOrNull { (uuid, _) ->
            uuid.uuid.mostSignificantBits == BTHOME_UUID.uuid.mostSignificantBits &&
                uuid.uuid.leastSignificantBits == BTHOME_UUID.uuid.leastSignificantBits
        }?.value?.let { return it }
        return extractServiceDataFromRawAd(rawRecord)
    }

    private fun extractServiceDataFromRawAd(raw: ByteArray?): ByteArray? {
        if (raw == null) return null
        var offset = 0
        while (offset < raw.size) {
            val length = raw[offset].toInt() and 0xFF
            if (length == 0) break
            val next = offset + length + 1
            if (next > raw.size || length < 3) {
                offset += length + 1
                continue
            }
            if ((raw[offset + 1].toInt() and 0xFF) == 0x16) {
                val uuidLo = raw[offset + 2].toInt() and 0xFF
                val uuidHi = raw[offset + 3].toInt() and 0xFF
                if (uuidLo == 0xD2 && uuidHi == 0xFC) return raw.copyOfRange(offset + 4, next)
            }
            offset = next
        }
        return null
    }

    companion object {
        val BTHOME_UUID: ParcelUuid = ParcelUuid.fromString("0000FCD2-0000-1000-8000-00805F9B34FB")
    }
}
