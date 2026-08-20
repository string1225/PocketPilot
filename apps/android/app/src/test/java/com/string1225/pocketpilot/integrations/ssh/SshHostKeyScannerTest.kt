package com.string1225.pocketpilot.integrations.ssh

import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SshHostKeyScannerTest {
    @Test
    fun `produces an OpenSSH SHA256 fingerprint over the wire key`() {
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val first = generator.generateKeyPair().public
        val second = generator.generateKeyPair().public
        val scanner = SshHostKeyScanner()

        val firstFingerprint = scanner.sha256Fingerprint(first)
        val repeated = scanner.sha256Fingerprint(first)
        val secondFingerprint = scanner.sha256Fingerprint(second)

        assertEquals(firstFingerprint, repeated)
        assertTrue(firstFingerprint.matches(Regex("SHA256:[A-Za-z0-9+/]{43}")))
        assertNotEquals(firstFingerprint, secondFingerprint)
        SshInputPolicy.requireSha256Fingerprint(firstFingerprint)
    }
}
