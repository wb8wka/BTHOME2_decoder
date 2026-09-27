package com.wb8wka.bthome2decoder

import java.time.Instant

data class Measurement(
    val objectId: Int,
    val name: String,
    val value: String,
    val unit: String
)

data class ParsedPacket(
    val encrypted: Boolean,
    val triggerBased: Boolean,
    val version: Int,
    val measurements: List<Measurement>,
    val error: String? = null,
    val decryptedPayloadHex: String? = null
)

/**
 * Decoder for BTHome v2 service-data advertisements (service UUID 0xFCD2).
 * Reference: https://bthome.io/format/  and  https://bthome.io/encryption/
 */
object BTHomeParser {

    fun parse(serviceData: ByteArray, macAddress: String, key: ByteArray?): ParsedPacket {
        if (serviceData.isEmpty()) {
            return ParsedPacket(false, false, 2, emptyList(), "Empty BTHome service data")
        }
        val infoByte = serviceData[0].toInt() and 0xFF
        val encrypted = (infoByte and 0x01) != 0
        val trigger = (infoByte and 0x04) != 0
        val version = (infoByte shr 5) and 0x07

        var payload = serviceData.copyOfRange(1, serviceData.size)
        var decryptedHex: String? = null

        if (encrypted) {
            if (key == null) {
                return ParsedPacket(true, trigger, version, emptyList(), "Encrypted packet \u2014 no key set for this device")
            }
            if (payload.size < 8) {
                return ParsedPacket(true, trigger, version, emptyList(), "Encrypted payload too short (${payload.size} bytes)")
            }
            val counterBytes = payload.copyOfRange(payload.size - 8, payload.size - 4)
            val mic = payload.copyOfRange(payload.size - 4, payload.size)
            val ciphertext = payload.copyOfRange(0, payload.size - 8)
            val macBytes = try {
                macAddressToBytes(macAddress)
            } catch (e: Exception) {
                return ParsedPacket(true, trigger, version, emptyList(), "Invalid MAC address: ${e.message}")
            }
            val uuidBytes = byteArrayOf(0xD2.toByte(), 0xFC.toByte())
            val nonce = macBytes + uuidBytes + byteArrayOf(infoByte.toByte()) + counterBytes
            payload = try {
                AesCcm.decrypt(key, nonce, ciphertext, mic)
            } catch (e: Exception) {
                return ParsedPacket(true, trigger, version, emptyList(), "Decryption failed: ${e.message}")
            }
            decryptedHex = payload.joinToString("") { "%02x".format(it) }
        }

        val measurements = mutableListOf<Measurement>()
        var i = 0
        while (i < payload.size) {
            val id = payload[i].toInt() and 0xFF
            val (consumed, measurement) = decodeAt(payload, i + 1, id)
            if (measurement == null) {
                measurements.add(Measurement(id, "unknown_0x%02X".format(id), "(stopped parsing \u2014 unrecognized object id)", ""))
                break
            }
            measurements.add(measurement)
            i += 1 + consumed
        }

        return ParsedPacket(encrypted, trigger, version, applyPostfixes(measurements), null, decryptedHex)
    }

    private fun applyPostfixes(list: List<Measurement>): List<Measurement> {
        val totalPerName = list.groupingBy { it.name }.eachCount()
        val runningIndex = mutableMapOf<String, Int>()
        return list.map { m ->
            val total = totalPerName[m.name] ?: 1
            if (total <= 1) return@map m
            val idx = (runningIndex[m.name] ?: 0) + 1
            runningIndex[m.name] = idx
            m.copy(name = "${m.name}_$idx")
        }
    }

    private fun macAddressToBytes(mac: String): ByteArray {
        val clean = mac.replace(":", "").replace("-", "")
        require(clean.length == 12) { "MAC address must be 6 bytes" }
        val bytes = ByteArray(6)
        for (idx in 0 until 6) {
            bytes[idx] = clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun readUInt(data: ByteArray, offset: Int, len: Int): Long {
        var v = 0L
        for (j in 0 until len) v = v or ((data[offset + j].toLong() and 0xFF) shl (8 * j))
        return v
    }

    private fun readSInt(data: ByteArray, offset: Int, len: Int): Long {
        var v = readUInt(data, offset, len)
        val signBit = 1L shl (len * 8 - 1)
        if (v and signBit != 0L) v -= (1L shl (len * 8))
        return v
    }

    private fun formatFactored(raw: Long, factor: Double): String {
        if (factor == 1.0) return raw.toString()
        val value = raw * factor
        var s = "%.6f".format(value).trimEnd('0')
        if (s.endsWith('.')) s += "0"
        return s
    }

    /**
     * Decodes a single object starting at [idx] (the byte right after the object id).
     * Returns (bytesConsumedAfterId, Measurement) or (0, null) for an unrecognized id,
     * signalling the caller to stop (per the BTHome spec, parsing cannot safely continue
     * past an unknown object id since its length is unknown).
     */
    private fun decodeAt(payload: ByteArray, idx: Int, id: Int): Pair<Int, Measurement?> {
        fun num(len: Int, signed: Boolean, factor: Double, name: String, unit: String): Pair<Int, Measurement?> {
            if (idx + len > payload.size) return Pair(payload.size - idx, Measurement(id, name, "(truncated)", unit))
            val raw = if (signed) readSInt(payload, idx, len) else readUInt(payload, idx, len)
            return Pair(len, Measurement(id, name, formatFactored(raw, factor), unit))
        }
        fun bin(name: String, offText: String, onText: String): Pair<Int, Measurement?> {
            if (idx >= payload.size) return Pair(0, Measurement(id, name, "(truncated)", ""))
            val v = payload[idx].toInt() and 0xFF
            return Pair(1, Measurement(id, name, if (v != 0) onText else offText, ""))
        }

        return when (id) {
            0x00 -> num(1, false, 1.0, "packet_id", "")
            0x51 -> num(2, false, 0.001, "acceleration", "m/s\u00b2")
            0x63 -> num(4, true, 0.000001, "acceleration", "m/s\u00b2")
            0x01 -> num(1, false, 1.0, "battery", "%")
            0x60 -> num(1, false, 1.0, "channel", "")
            0x12 -> num(2, false, 1.0, "co2", "ppm")
            0x56 -> num(2, false, 1.0, "conductivity", "\u00b5S/cm")
            0x09 -> num(1, false, 1.0, "count", "")
            0x3D -> num(2, false, 1.0, "count", "")
            0x3E -> num(4, false, 1.0, "count", "")
            0x59 -> num(1, true, 1.0, "count", "")
            0x5A -> num(2, true, 1.0, "count", "")
            0x5B -> num(4, true, 1.0, "count", "")
            0x43 -> num(2, false, 0.001, "current", "A")
            0x5D -> num(2, true, 0.001, "current", "A")
            0x08 -> num(2, true, 0.01, "dewpoint", "\u00b0C")
            0x5E -> num(2, false, 0.01, "direction", "\u00b0")
            0x40 -> num(2, false, 1.0, "distance", "mm")
            0x41 -> num(2, false, 0.1, "distance", "m")
            0x42 -> num(3, false, 0.001, "duration", "s")
            0x4D -> num(4, false, 0.001, "energy", "kWh")
            0x0A -> num(3, false, 0.001, "energy", "kWh")
            0x4B -> num(3, false, 0.001, "gas", "m3")
            0x4C -> num(4, false, 0.001, "gas", "m3")
            0x52 -> num(2, false, 0.001, "gyroscope", "\u00b0/s")
            0x03 -> num(2, false, 0.01, "humidity", "%")
            0x2E -> num(1, false, 1.0, "humidity", "%")
            0x05 -> num(3, false, 0.01, "illuminance", "lx")
            0x64 -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "light_level", "(truncated)", ""))
                val v = payload[idx].toInt() and 0xFF
                val txt = when (v) { 0 -> "dark"; 1 -> "twilight"; 2 -> "bright"; else -> "unknown($v)" }
                Pair(1, Measurement(id, "light_level", txt, ""))
            }
            0x06 -> num(2, false, 0.01, "mass", "kg")
            0x07 -> num(2, false, 0.01, "mass", "lb")
            0x14 -> num(2, false, 0.01, "moisture", "%")
            0x2F -> num(1, false, 1.0, "moisture", "%")
            0x0D -> num(2, false, 1.0, "pm2.5", "\u00b5g/m\u00b3")
            0x0E -> num(2, false, 1.0, "pm10", "\u00b5g/m\u00b3")
            0x0B -> num(3, false, 0.01, "power", "W")
            0x5C -> num(4, true, 0.01, "power", "W")
            0x5F -> num(2, false, 0.1, "precipitation", "mm")
            0x04 -> num(3, false, 0.01, "pressure", "hPa")
            0x54 -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "raw", "(truncated)", ""))
                val len = payload[idx].toInt() and 0xFF
                val start = idx + 1
                if (start + len > payload.size) return Pair(payload.size - idx, Measurement(id, "raw", "(truncated)", ""))
                val bytes = payload.copyOfRange(start, start + len)
                Pair(1 + len, Measurement(id, "raw", bytes.joinToString("") { "%02x".format(it) }, ""))
            }
            0x3F -> num(2, true, 0.1, "rotation", "\u00b0")
            0x61 -> num(2, false, 1.0, "rotational_speed", "rpm")
            0x65 -> num(1, false, 1.0, "settings_revision", "")
            0x44 -> num(2, false, 0.01, "speed", "m/s")
            0x62 -> num(4, true, 0.000001, "speed", "m/s")
            0x57 -> num(1, true, 1.0, "temperature", "\u00b0C")
            0x58 -> num(1, true, 0.35, "temperature", "\u00b0C")
            0x45 -> num(2, true, 0.1, "temperature", "\u00b0C")
            0x02 -> num(2, true, 0.01, "temperature", "\u00b0C")
            0x53 -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "text", "(truncated)", ""))
                val len = payload[idx].toInt() and 0xFF
                val start = idx + 1
                if (start + len > payload.size) return Pair(payload.size - idx, Measurement(id, "text", "(truncated)", ""))
                val bytes = payload.copyOfRange(start, start + len)
                Pair(1 + len, Measurement(id, "text", String(bytes, Charsets.UTF_8), ""))
            }
            0x50 -> {
                if (idx + 4 > payload.size) return Pair(payload.size - idx, Measurement(id, "timestamp", "(truncated)", ""))
                val secs = readUInt(payload, idx, 4)
                val instant = Instant.ofEpochSecond(secs)
                Pair(4, Measurement(id, "timestamp", instant.toString(), "UTC"))
            }
            0x13 -> num(2, false, 1.0, "tvoc", "\u00b5g/m\u00b3")
            0x0C -> num(2, false, 0.001, "voltage", "V")
            0x4A -> num(2, false, 0.1, "voltage", "V")
            0x4E -> num(4, false, 0.001, "volume", "L")
            0x47 -> num(2, false, 0.1, "volume", "L")
            0x48 -> num(2, false, 1.0, "volume", "mL")
            0x55 -> num(4, false, 0.001, "volume_storage", "L")
            0x49 -> num(2, false, 0.001, "volume_flow_rate", "m3/hr")
            0x46 -> num(1, false, 0.1, "uv_index", "")
            0x4F -> num(4, false, 0.001, "water", "L")

            // Binary sensors
            0x15 -> bin("battery_low", "Normal", "Low")
            0x16 -> bin("battery_charging", "Not Charging", "Charging")
            0x17 -> bin("carbon_monoxide", "Not detected", "Detected")
            0x18 -> bin("cold", "Normal", "Cold")
            0x19 -> bin("connectivity", "Disconnected", "Connected")
            0x1A -> bin("door", "Closed", "Open")
            0x1B -> bin("garage_door", "Closed", "Open")
            0x1C -> bin("gas_detected", "Clear", "Detected")
            0x0F -> bin("generic_boolean", "Off", "On")
            0x1D -> bin("heat", "Normal", "Hot")
            0x1E -> bin("light", "No light", "Light detected")
            0x1F -> bin("lock", "Locked", "Unlocked")
            0x20 -> bin("moisture_binary", "Dry", "Wet")
            0x21 -> bin("motion", "Clear", "Detected")
            0x22 -> bin("moving", "Not moving", "Moving")
            0x23 -> bin("occupancy", "Clear", "Detected")
            0x11 -> bin("opening", "Closed", "Open")
            0x24 -> bin("plug", "Unplugged", "Plugged in")
            0x10 -> bin("power_binary", "Off", "On")
            0x25 -> bin("presence", "Away", "Home")
            0x26 -> bin("problem", "OK", "Problem")
            0x27 -> bin("running", "Not Running", "Running")
            0x28 -> bin("safety", "Unsafe", "Safe")
            0x29 -> bin("smoke", "Clear", "Detected")
            0x2A -> bin("sound", "Clear", "Detected")
            0x2B -> bin("tamper", "Off", "On")
            0x2C -> bin("vibration", "Clear", "Detected")
            0x2D -> bin("window", "Closed", "Open")

            // Events
            0x3A -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "button", "(truncated)", ""))
                val v = payload[idx].toInt() and 0xFF
                val txt = when (v) {
                    0x00 -> "none"; 0x01 -> "press"; 0x02 -> "double_press"; 0x03 -> "triple_press"
                    0x04 -> "long_press"; 0x05 -> "long_double_press"; 0x06 -> "long_triple_press"
                    0x80 -> "hold_press"
                    else -> "unknown(0x%02X)".format(v)
                }
                Pair(1, Measurement(id, "button", txt, ""))
            }
            0x3B -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "command", "(truncated)", ""))
                val lenByte = payload[idx].toInt() and 0xFF
                val argLen = lenByte and 0x1F
                if (idx + 1 >= payload.size) return Pair(payload.size - idx, Measurement(id, "command", "(truncated)", ""))
                val opcode = payload[idx + 1].toInt() and 0xFF
                val arg = if (argLen > 0 && idx + 2 + argLen <= payload.size) payload[idx + 2].toInt() and 0xFF else null
                val txt = when (opcode) {
                    0x00 -> "off"; 0x01 -> "on"; 0x02 -> "toggle"
                    0x03 -> "step up${if (arg != null) " ($arg steps)" else ""}"
                    0x04 -> "step down${if (arg != null) " ($arg steps)" else ""}"
                    else -> "unknown opcode(0x%02X)".format(opcode)
                }
                Pair(2 + argLen, Measurement(id, "command", txt, ""))
            }
            0x3C -> {
                if (idx >= payload.size) return Pair(0, Measurement(id, "dimmer", "(truncated)", ""))
                val lenByte = payload[idx].toInt() and 0xFF
                val argLen = lenByte and 0x1F
                if (idx + 1 >= payload.size) return Pair(payload.size - idx, Measurement(id, "dimmer", "(truncated)", ""))
                val opcode = payload[idx + 1].toInt() and 0xFF
                val arg = if (argLen > 0 && idx + 2 + argLen <= payload.size) payload[idx + 2].toInt() and 0xFF else null
                val txt = when (opcode) {
                    0x00 -> "none"
                    0x01 -> "rotate left${if (arg != null) " ($arg steps)" else ""}"
                    0x02 -> "rotate right${if (arg != null) " ($arg steps)" else ""}"
                    else -> "unknown opcode(0x%02X)".format(opcode)
                }
                Pair(2 + argLen, Measurement(id, "dimmer", txt, ""))
            }

            // Device info
            0xF0 -> num(2, false, 1.0, "device_type_id", "")
            0xF1 -> {
                if (idx + 4 > payload.size) return Pair(payload.size - idx, Measurement(id, "firmware_version", "(truncated)", ""))
                val b0 = payload[idx].toInt() and 0xFF
                val b1 = payload[idx + 1].toInt() and 0xFF
                val b2 = payload[idx + 2].toInt() and 0xFF
                val b3 = payload[idx + 3].toInt() and 0xFF
                Pair(4, Measurement(id, "firmware_version", "$b3.$b2.$b1.$b0", ""))
            }
            0xF2 -> {
                if (idx + 3 > payload.size) return Pair(payload.size - idx, Measurement(id, "firmware_version", "(truncated)", ""))
                val b0 = payload[idx].toInt() and 0xFF
                val b1 = payload[idx + 1].toInt() and 0xFF
                val b2 = payload[idx + 2].toInt() and 0xFF
                Pair(3, Measurement(id, "firmware_version", "$b2.$b1.$b0", ""))
            }

            else -> Pair(0, null)
        }
    }
}
