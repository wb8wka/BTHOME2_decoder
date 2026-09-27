package com.wb8wka.bthome2decoder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceListScreen(
    devices: List<BleDevice>,
    filterText: String,
    diagnostics: BleScanner.Diagnostics,
    onFilterChange: (String) -> Unit,
    onClear: () -> Unit,
    onDeviceClick: (BleDevice) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BTHome v2 Devices") },
                actions = {
                    Icon(Icons.Filled.BluetoothSearching, contentDescription = "Scanning")
                    IconButton(onClick = onClear) { Icon(Icons.Filled.Clear, contentDescription = "Clear device list and counters") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = filterText,
                onValueChange = onFilterChange,
                label = { Text("Filter by MAC or name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            Text("Status: ${diagnostics.status}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp))
            Text(
                text = "Bluetooth: ${if (diagnostics.bluetoothEnabled) "on" else "off"}  •  BLE ads: ${diagnostics.advertisementsSeen}  •  BTHome: ${diagnostics.bthomePacketsSeen}" +
                    if (diagnostics.scanFailureCode != null) "  •  Error: ${diagnostics.scanFailureCode}" else "",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (devices.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No BTHome advertisements seen yet.") }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(devices, key = { it.address }) { device ->
                        DeviceRow(device = device, onClick = { onDeviceClick(device) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BleDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.name?.takeIf { it.isNotBlank() } ?: device.address) },
        supportingContent = { Text(device.address) },
        trailingContent = { Text("${device.rssi} dBm") },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    )
}
