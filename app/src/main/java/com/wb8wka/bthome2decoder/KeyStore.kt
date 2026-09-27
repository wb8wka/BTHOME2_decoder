package com.wb8wka.bthome2decoder

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "bthome_keys")

/**
 * Persists per-device (per-MAC) 128-bit BTHome encryption keys as hex strings.
 */
class KeyStore(private val context: Context) {

    private fun keyFor(mac: String) = stringPreferencesKey("key_${mac.uppercase()}")

    suspend fun setKeyHex(mac: String, hexKey: String) {
        context.dataStore.edit { prefs ->
            if (hexKey.isBlank()) {
                prefs.remove(keyFor(mac))
            } else {
                prefs[keyFor(mac)] = hexKey.trim()
            }
        }
    }

    /** Emits the stored hex key for [mac] (or null if none is set) whenever it changes. */
    fun keyHexFlow(mac: String): Flow<String?> =
        context.dataStore.data.map { prefs -> prefs[keyFor(mac)] }

    /** Emits the full map of MAC -> hex key, used to resolve keys for every scanned device. */
    fun allKeysFlow(): Flow<Map<String, String>> =
        context.dataStore.data.map { prefs ->
            prefs.asMap().entries
                .filter { it.key.name.startsWith("key_") }
                .associate { it.key.name.removePrefix("key_") to (it.value as String) }
        }

    fun hexToBytesOrNull(hex: String?): ByteArray? {
        if (hex.isNullOrBlank()) return null
        val clean = hex.trim().replace(" ", "")
        if (clean.length != 32) return null
        return try {
            ByteArray(16) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        } catch (e: Exception) {
            null
        }
    }
}
