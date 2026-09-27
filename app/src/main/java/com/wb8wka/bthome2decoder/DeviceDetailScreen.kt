package com.wb8wka.bthome2decoder

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    device: BleDevice,
    keyStore: KeyStore,
    onBack: () -> Unit
) {
    val storedKeyHex by keyStore.keyHexFlow(device.address).collectAsState(initial = null)
    var keyInput by remember(storedKeyHex) { mutableStateOf(storedKeyHex ?: "") }
    var keyError by remember { mutableStateOf<String?>(null) }

    val keyBytes = keyStore.hexToBytesOrNull(storedKeyHex)
    val parsed = remember(device.serviceData, storedKeyHex) {
        BTHomeParser.parse(device.serviceData, device.address, keyBytes)
    }
    val rawHex = device.serviceData.joinToString(" ") { "%02X".format(it) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(device.name?.takeIf { it.isNotBlank() } ?: device.address) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            item {
                Text("Address: ${device.address}", style = MaterialTheme.typography.bodyMedium)
                Text("RSSI: ${device.rssi} dBm", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Status: " + (if (parsed.encrypted) "Encrypted (v${parsed.version})" else "Unencrypted (v${parsed.version})") +
                        (if (parsed.triggerBased) ", trigger-based" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(16.dp))

                Text("Encryption key (32 hex chars / 16 bytes)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = keyInput,
                    onValueChange = { keyInput = it; keyError = null },
                    singleLine = true,
                    label = { Text("AES key hex") },
                    supportingText = { keyError?.let { Text(it) } },
                    isError = keyError != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        val trimmed = keyInput.trim()
                        if (trimmed.isNotEmpty() && keyStore.hexToBytesOrNull(trimmed) == null) {
                            keyError = "Key must be exactly 32 hex characters (16 bytes)"
                        } else {
                            keyStoreSetKey(keyStore, device.address, trimmed)
                        }
                    }) { Text("Save key") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        keyInput = ""
                        keyStoreSetKey(keyStore, device.address, "")
                    }) { Text("Clear") }
                }

                Spacer(Modifier.height(16.dp))
                Text("Raw service data (0xFCD2)", style = MaterialTheme.typography.titleSmall)
                Text(rawHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)

                if (parsed.decryptedPayloadHex != null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Decrypted payload", style = MaterialTheme.typography.titleSmall)
                    Text(parsed.decryptedPayloadHex, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }

                Spacer(Modifier.height(16.dp))
                Text("Decoded BTHome v2 fields", style = MaterialTheme.typography.titleSmall)
                if (parsed.error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(parsed.error, color = MaterialTheme.colorScheme.error)
                }
            }

            items(parsed.measurements) { m ->
                ListItem(
                    headlineContent = { Text(m.name) },
                    trailingContent = { Text("${m.value}${if (m.unit.isNotBlank()) " " + m.unit else ""}") },
                    supportingContent = { Text("object id 0x%02X".format(m.objectId)) }
                )
                HorizontalDivider()
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private fun keyStoreSetKey(keyStore: KeyStore, mac: String, hex: String) {
    GlobalScope.launch { keyStore.setKeyHex(mac, hex) }
}
