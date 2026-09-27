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
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppRoot(viewModel)
            }
        }
    }
}

private fun requiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

@Composable
fun AppRoot(viewModel: MainViewModel) {
    var permissionsGranted by remember { mutableStateOf(false) }
    var selectedDevice by remember { mutableStateOf<BleDevice?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionsGranted = result.values.all { it }
        if (permissionsGranted) viewModel.startScan()
    }

    LaunchedEffect(Unit) {
        launcher.launch(requiredPermissions())
    }

    DisposableEffect(permissionsGranted) {
        if (permissionsGranted) viewModel.startScan()
        onDispose { viewModel.stopScan() }
    }

    if (!permissionsGranted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Bluetooth / location permissions are required to scan for BTHome devices.")
        }
        return
    }

    val devices by viewModel.filteredDevices.collectAsStateWithLifecycle()
    val filterText by viewModel.filterText.collectAsStateWithLifecycle()

    if (selectedDevice == null) {
        DeviceListScreen(
            devices = devices,
            filterText = filterText,
            onFilterChange = viewModel::setFilter,
            onDeviceClick = { selectedDevice = it }
        )
    } else {
        DeviceDetailScreen(
            device = selectedDevice!!,
            keyStore = viewModel.keyStore,
            onBack = { selectedDevice = null }
        )
    }
}
