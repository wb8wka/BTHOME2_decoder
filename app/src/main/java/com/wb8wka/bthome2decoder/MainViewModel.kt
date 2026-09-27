package com.wb8wka.bthome2decoder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val scanner = BleScanner(application)
    val keyStore = KeyStore(application)

    val devices: StateFlow<List<BleDevice>> =
        scanner.devices
            .combine(keyStore.allKeysFlow()) { deviceMap, _ -> deviceMap.values.sortedBy { it.address } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _filterText = MutableStateFlow("")
    val filterText: StateFlow<String> = _filterText

    val filteredDevices: StateFlow<List<BleDevice>> =
        devices.combine(_filterText) { list, filter ->
            if (filter.isBlank()) list
            else list.filter { d ->
                d.address.contains(filter, ignoreCase = true) ||
                    (d.name?.contains(filter, ignoreCase = true) == true)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setFilter(text: String) {
        _filterText.value = text
    }

    fun startScan() = scanner.startScan()
    fun stopScan() = scanner.stopScan()

    fun setKey(mac: String, hexKey: String) {
        viewModelScope.launch { keyStore.setKeyHex(mac, hexKey) }
    }

    override fun onCleared() {
        scanner.stopScan()
        super.onCleared()
    }
}
