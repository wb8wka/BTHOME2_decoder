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
 * Scans for BLE advertisements and extracts BTHome (service UUID 0xFCD2) service data.
 */
class BleScanner(context: Context) {

    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner

    private val _devices = MutableStateFlow<Map<String, BleDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BleDevice>> = _devices.asStateFlow()

    private var scanning = false

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(BTHOME_UUID) ?: return
            val name = result.scanRecord?.deviceName
            val entry = BleDevice(
                address = result.device.address,
                name = name,
                rssi = result.rssi,
                serviceData = serviceData,
                lastSeen = System.currentTimeMillis()
            )
            _devices.update { current -> current + (entry.address to entry) }
        }

        override fun onScanFailed(errorCode: Int) {
            // Scan failures are surfaced implicitly: the device list simply stops updating.
        }
    }

    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (scanning) return
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
    }

    companion object {
        // Full 128-bit form of the 16-bit BTHome service UUID 0xFCD2.
        val BTHOME_UUID: ParcelUuid = ParcelUuid.fromString("0000FCD2-0000-1000-8000-00805F9B34FB")
    }
}
