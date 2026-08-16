package com.string1225.pocketpilot.ui

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SshPrivateKeyImportTest {
    @Test
    fun acceptsOpenSshPrivateKey() {
        val value = openSshPrivateKey(cipherName = "none", kdfName = "none")
        assertEquals(value, SshPrivateKeyImport.decode(ByteArrayInputStream(value.toByteArray())))
    }

    @Test
    fun rejectsEncryptedOpenSshAndTraditionalPemKeys() {
        listOf(
            openSshPrivateKey(cipherName = "aes256-ctr", kdfName = "bcrypt"),
            "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\nDEK-Info: AES-256-CBC,00\nfixture\n-----END RSA PRIVATE KEY-----\n",
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                SshPrivateKeyImport.decode(ByteArrayInputStream(value.toByteArray()))
            }
        }
    }

    @Test
    fun rejectsPublicKey() {
        val value = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest fixture@example"
        assertFailsWith<IllegalArgumentException> {
            SshPrivateKeyImport.decode(ByteArrayInputStream(value.toByteArray()))
        }
    }

    @Test
    fun rejectsOversizedKeyBeforeDecoding() {
        assertFailsWith<IllegalArgumentException> {
            SshPrivateKeyImport.decode(
                ByteArrayInputStream(
                    ByteArray(SshPrivateKeyImport.MAX_PRIVATE_KEY_BYTES + 1) { 'A'.code.toByte() },
                ),
            )
        }
    }

    private fun openSshPrivateKey(cipherName: String, kdfName: String): String {
        val payload = ByteArrayOutputStream().apply {
            write("openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII))
            writeSshString(cipherName)
            writeSshString(kdfName)
        }.toByteArray()
        return buildString {
            appendLine("-----BEGIN OPENSSH PRIVATE KEY-----")
            appendLine(Base64.getEncoder().encodeToString(payload))
            appendLine("-----END OPENSSH PRIVATE KEY-----")
        }
    }

    private fun ByteArrayOutputStream.writeSshString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.US_ASCII)
        write(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        write(bytes)
    }
}
