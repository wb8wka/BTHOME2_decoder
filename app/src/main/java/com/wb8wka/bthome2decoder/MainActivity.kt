package com.wb8wka.bthome2decoder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot(viewModel) } }
    }
}

private fun requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_SCAN)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

@Composable
fun AppRoot(viewModel: MainViewModel) {
    var permissionGranted by remember { mutableStateOf(false) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionGranted = result.values.all { it }
        if (permissionGranted) viewModel.startScan()
    }
    LaunchedEffect(Unit) { launcher.launch(requiredPermissions()) }
    DisposableEffect(permissionGranted) {
        if (permissionGranted) viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    if (!permissionGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Bluetooth scan permission is required to scan for BTHome devices.") }
        return
    }

    val devices by viewModel.filteredDevices.collectAsState()
    val filterText by viewModel.filterText.collectAsState()
    val diagnostics by viewModel.diagnostics.collectAsState()
    val currentDevice = selectedAddress?.let { address -> devices.firstOrNull { it.address == address } }

    if (selectedAddress == null || currentDevice == null) {
        DeviceListScreen(
            devices = devices,
            filterText = filterText,
            diagnostics = diagnostics,
            onFilterChange = viewModel::setFilter,
            onClear = viewModel::clear,
            onDeviceClick = { selectedAddress = it.address }
        )
    } else {
        DeviceDetailScreen(device = currentDevice, keyStore = viewModel.keyStore, onBack = { selectedAddress = null })
    }
}
