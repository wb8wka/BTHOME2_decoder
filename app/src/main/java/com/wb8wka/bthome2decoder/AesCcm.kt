package com.wb8wka.bthome2decoder

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal AES-CCM implementation for the fixed BTHome v2 encryption profile:
 * 13-byte nonce, 4-byte MIC, no associated data. Built directly from a single-block
 * AES/ECB primitive (per NIST SP 800-38C) so no external crypto provider is required.
 *
 * Verified against the official BTHome worked example:
 * https://bthome.io/encryption/
 */
object AesCcm {

    class MicVerificationException(message: String) : Exception(message)

    private fun ecbEncryptBlock(key: ByteArray, block: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(block)
    }

    private fun intToBytesBE(value: Int, len: Int): ByteArray {
        val out = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    /**
     * Decrypts [ciphertext] and verifies [mic] against [key]/[nonce].
     * Throws [MicVerificationException] if the MIC does not match (wrong key or corrupt data).
     */
    fun decrypt(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray, mic: ByteArray): ByteArray {
        require(key.size == 16) { "BTHome encryption keys are 16 bytes (128-bit)" }
        require(nonce.size == 13) { "Nonce must be 13 bytes" }
        require(mic.size == 4) { "MIC must be 4 bytes" }
        val tagLen = 4
        val q = 15 - nonce.size // = 2 for a 13-byte nonce

        val plaintext = ByteArray(ciphertext.size)
        var s0: ByteArray? = null
        var blockIndex = 0
        var offset = 0
        while (offset < ciphertext.size || blockIndex == 0) {
            val ctrBlock = ByteArray(16)
            ctrBlock[0] = (q - 1).toByte()
            System.arraycopy(nonce, 0, ctrBlock, 1, nonce.size)
            val counterBytes = intToBytesBE(blockIndex, q)
            System.arraycopy(counterBytes, 0, ctrBlock, 16 - q, q)
            val s = ecbEncryptBlock(key, ctrBlock)
            if (blockIndex == 0) {
                s0 = s
                blockIndex++
                if (ciphertext.isEmpty()) break
                continue
            }
            val chunkLen = minOf(16, ciphertext.size - offset)
            for (j in 0 until chunkLen) {
                plaintext[offset + j] = (ciphertext[offset + j].toInt() xor s[j].toInt()).toByte()
            }
            offset += chunkLen
            blockIndex++
        }

        // CBC-MAC over B0 followed by the (zero-padded) plaintext blocks. No associated data.
        val flags = ((((tagLen - 2) / 2) shl 3) or (q - 1)).toByte()
        val b0 = ByteArray(16)
        b0[0] = flags
        System.arraycopy(nonce, 0, b0, 1, nonce.size)
        val lenBytes = intToBytesBE(plaintext.size, q)
        System.arraycopy(lenBytes, 0, b0, 16 - q, q)

        var y = ecbEncryptBlock(key, b0)
        if (plaintext.isNotEmpty()) {
            val rem = plaintext.size % 16
            val padded = if (rem == 0) plaintext else plaintext.copyOf(plaintext.size + (16 - rem))
            var pos = 0
            while (pos < padded.size) {
                val block = padded.copyOfRange(pos, pos + 16)
                val x = ByteArray(16) { idx -> (block[idx].toInt() xor y[idx].toInt()).toByte() }
                y = ecbEncryptBlock(key, x)
                pos += 16
            }
        }
        val computedTag = y.copyOfRange(0, tagLen)
        val s0Final = s0 ?: throw IllegalStateException("S0 keystream block was not computed")
        val maskedTag = ByteArray(tagLen) { idx -> (computedTag[idx].toInt() xor s0Final[idx].toInt()).toByte() }

        if (!maskedTag.contentEquals(mic)) {
            throw MicVerificationException("MIC verification failed \u2014 wrong key or corrupted advertisement")
        }
        return plaintext
    }
}
