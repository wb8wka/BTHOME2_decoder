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

/**
 * Scans BLE advertisements and extracts BTHome v2 service data (UUID 0xFCD2).
 *
 * Android vendors differ in how they normalize 16-bit UUIDs in ScanRecord.serviceData.
 * This scanner therefore checks the direct UUID lookup, every service-data map entry,
 * and (as a final fallback) parses the raw AD structures for Service Data 16-bit (0x16).
 */
class BleScanner(context: Context) {

    data class Diagnostics(
        val advertisementsSeen: Long = 0,
        val bthomePacketsSeen: Long = 0,
        val scanFailureCode: Int? = null,
        val bluetoothEnabled: Boolean = false
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
            _diagnostics.update { it.copy(scanFailureCode = errorCode, bluetoothEnabled = isBluetoothEnabled()) }
            scanning = false
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) return
        _diagnostics.update { it.copy(scanFailureCode = null, bluetoothEnabled = isBluetoothEnabled()) }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, callback)
        scanning = true
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!scanning) return
        scanner?.stopScan(callback)
        scanning = false
    }

    fun clear() {
        _devices.value = emptyMap()
        _diagnostics.value = Diagnostics(bluetoothEnabled = isBluetoothEnabled())
    }

    private fun extractBTHomeServiceData(
        serviceDataMap: Map<ParcelUuid, ByteArray>?,
        rawRecord: ByteArray?
    ): ByteArray? {
        // Normal Android representation: 0000fcd2-0000-1000-8000-00805f9b34fb.
        serviceDataMap?.get(BTHOME_UUID)?.let { return it }

        // Defensive map match: accommodates vendor-specific ParcelUuid normalization.
        serviceDataMap?.entries?.firstOrNull { (uuid, _) ->
            uuid.uuid.mostSignificantBits == BTHOME_UUID.uuid.mostSignificantBits &&
                uuid.uuid.leastSignificantBits == BTHOME_UUID.uuid.leastSignificantBits
        }?.value?.let { return it }

        // Last resort: parse AD structures directly. Type 0x16 is Service Data - 16-bit UUID;
        // UUID bytes are little-endian, so BTHome appears as D2 FC.
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
            val type = raw[offset + 1].toInt() and 0xFF
            if (type == 0x16) {
                val uuidLo = raw[offset + 2].toInt() and 0xFF
                val uuidHi = raw[offset + 3].toInt() and 0xFF
                if (uuidLo == 0xD2 && uuidHi == 0xFC) {
                    return raw.copyOfRange(offset + 4, next)
                }
            }
            offset = next
        }
        return null
    }

    companion object {
        val BTHOME_UUID: ParcelUuid = ParcelUuid.fromString("0000FCD2-0000-1000-8000-00805F9B34FB")
    }
}
