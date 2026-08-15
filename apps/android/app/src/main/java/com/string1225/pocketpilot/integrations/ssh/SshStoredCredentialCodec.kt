package com.string1225.pocketpilot.integrations.ssh

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Base64

/**
 * Adapts multiline PEM material to the credential store's single-line contract.
 * The envelope carries no secret metadata and is decoded only at the SSH execution boundary.
 */
object SshStoredCredentialCodec {
    private const val PRIVATE_KEY_PREFIX = "pocketpilot-private-key-v1:"
    private const val MAX_STORED_CHARACTERS = 16 * 1024

    fun encodePrivateKey(privateKey: CharArray): CharArray {
        require(privateKey.isNotEmpty()) { "SSH private key is empty" }
        require(
            privateKey.startsWith("-----BEGIN ") &&
                privateKey.contains("PRIVATE KEY-----") &&
                '\u0000' !in privateKey,
        ) { "SSH private key must be PEM-encoded private key material" }
        val encodedUtf8 = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(privateKey))
        val raw = encodedUtf8.copyRemainingBytes()
        val base64 = try {
            Base64.getEncoder().encode(raw)
        } finally {
            raw.fill(0)
            encodedUtf8.eraseBackingArray()
        }
        try {
            require(PRIVATE_KEY_PREFIX.length + base64.size <= MAX_STORED_CHARACTERS) {
                "SSH private key is too large"
            }
            return CharArray(PRIVATE_KEY_PREFIX.length + base64.size).also { result ->
                PRIVATE_KEY_PREFIX.toCharArray(result, destinationOffset = 0)
                base64.forEachIndexed { index, byte ->
                    require(byte.toInt() in 0x20..0x7e) { "Encoded SSH private key is invalid" }
                    result[PRIVATE_KEY_PREFIX.length + index] = byte.toInt().toChar()
                }
            }
        } finally {
            base64.fill(0)
        }
    }

    fun decodePrivateKey(stored: CharArray): CharArray {
        if (!stored.startsWith(PRIVATE_KEY_PREFIX)) {
            require(stored.startsWith("-----BEGIN ")) {
                "Stored SSH private key has an unsupported format"
            }
            return stored.copyOf()
        }
        require(stored.size <= MAX_STORED_CHARACTERS) { "Stored SSH private key is invalid" }
        val encodedLength = stored.size - PRIVATE_KEY_PREFIX.length
        require(encodedLength > 0) { "Stored SSH private key is invalid" }
        val encoded = ByteArray(encodedLength) { index ->
            val character = stored[PRIVATE_KEY_PREFIX.length + index]
            require(character.code in 0x20..0x7e) { "Stored SSH private key is invalid" }
            character.code.toByte()
        }
        val raw = try {
            Base64.getDecoder().decode(encoded)
        } catch (failure: IllegalArgumentException) {
            throw IllegalArgumentException("Stored SSH private key is invalid", failure)
        } finally {
            encoded.fill(0)
        }
        try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
            return CharArray(decoded.remaining()).also { decoded.get(it) }
        } catch (failure: Exception) {
            throw IllegalArgumentException("Stored SSH private key is invalid", failure)
        } finally {
            raw.fill(0)
        }
    }

    private fun CharArray.startsWith(prefix: String): Boolean =
        size >= prefix.length && prefix.indices.all { this[it] == prefix[it] }

    private fun CharArray.contains(fragment: String): Boolean =
        indices.any { start ->
            start + fragment.length <= size && fragment.indices.all { this[start + it] == fragment[it] }
        }

    private fun ByteBuffer.copyRemainingBytes(): ByteArray = ByteArray(remaining()).also(::get)

    private fun ByteBuffer.eraseBackingArray() {
        if (hasArray()) Arrays.fill(array(), 0.toByte())
    }
}
