package com.string1225.pocketpilot.integrations.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SshSecurityTest {
    @Test
    fun `only SHA256 host fingerprints are accepted`() {
        val valid = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))}"
        val policy = SshHostKeyPolicy.Sha256Fingerprint(valid)
        val verifier = StrictHostKeyVerifierFactory.create(policy)

        assertFalse(verifier.javaClass.name.contains("Promiscuous", ignoreCase = true))
        assertFailsWith<IllegalArgumentException> {
            SshHostKeyPolicy.Sha256Fingerprint("MD5:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00:00")
        }
        assertFailsWith<IllegalArgumentException> {
            SshHostKeyPolicy.Sha256Fingerprint("SHA1:AAAAAAAAAAAAAAAAAAAAAAAAAAA")
        }
        assertFailsWith<IllegalArgumentException> {
            SshHostKeyPolicy.Sha256Fingerprint("SHA256:not-base64")
        }
    }

    @Test
    fun `known hosts verifier requires at least one parseable strict entry`() {
        val encodedKey = encodedEd25519Key()
        val policy = SshHostKeyPolicy.KnownHosts("example.com ssh-ed25519 $encodedKey")
        val verifier = StrictHostKeyVerifierFactory.create(policy)

        assertFalse(verifier.javaClass.name.contains("Promiscuous", ignoreCase = true))
        assertFailsWith<IllegalArgumentException> {
            SshHostKeyPolicy.KnownHosts("# comments are not trust anchors\n")
        }
        assertFailsWith<IllegalArgumentException> {
            StrictHostKeyVerifierFactory.create(SshHostKeyPolicy.KnownHosts("this is not a valid entry"))
        }
    }

    @Test
    fun `server and execute inputs reject injection-prone structural values`() {
        val server = validServer()
        assertTrue(server.hostKeyPolicy is SshHostKeyPolicy.Sha256Fingerprint)

        assertFailsWith<IllegalArgumentException> { validServer(host = "host name") }
        assertFailsWith<IllegalArgumentException> { validServer(host = "user@example.com") }
        assertFailsWith<IllegalArgumentException> { validServer(username = "user\nroot") }
        assertFailsWith<IllegalArgumentException> { validServer(port = 0) }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireCommand("\u0000") }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireCommand("echo safe\u202erm -rf") }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireCommand("x".repeat(4097)) }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireTimeout(0) }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireOutputLimit(Int.MAX_VALUE) }
        assertFailsWith<IllegalArgumentException> { SshInputPolicy.requireOutputLimit(512 * 1024 + 1) }
    }

    @Test
    fun `approval command copy escapes layout controls and is fully bounded`() {
        val raw = "line1\nline2\t\\literal\u0001\u202e"

        assertEquals(
            "line1\\nline2\\t\\\\literal\\u0001\\u202E",
            SshApprovalFormatter.escapeCommand(raw, maximumCharacters = 128),
        )
        assertFailsWith<IllegalArgumentException> {
            SshApprovalFormatter.escapeCommand("\n".repeat(10), maximumCharacters = 19)
        }
    }

    @Test
    fun `multiline PEM is encoded for single-line storage and restored losslessly`() {
        val pem = (
            "-----BEGIN PRIVATE KEY-----\n" +
                "dGVzdC1vbmx5LWtleQ==\n" +
                "-----END PRIVATE KEY-----\n"
            ).toCharArray()
        val stored = SshStoredCredentialCodec.encodePrivateKey(pem)
        try {
            assertFalse(stored.any { it == '\r' || it == '\n' || it == '\u0000' })
            val restored = SshStoredCredentialCodec.decodePrivateKey(stored)
            try {
                assertTrue(restored.contentEquals(pem))
            } finally {
                restored.fill('\u0000')
            }
        } finally {
            stored.fill('\u0000')
            pem.fill('\u0000')
        }

        assertFailsWith<IllegalArgumentException> {
            SshStoredCredentialCodec.decodePrivateKey("not-a-private-key".toCharArray())
        }
    }

    @Test
    fun `password and private key secrets are defensive redacted and closeable`() {
        val passwordSource = "password-secret".toCharArray()
        val password = SshPasswordCredential(passwordSource)
        passwordSource.fill('x')
        assertFalse(password.toString().contains("password-secret"))
        assertTrue(String(password.copyPassword()) == "password-secret")
        password.close()
        assertFailsWith<IllegalStateException> { password.copyPassword() }

        val key = SshPrivateKeyCredential("private-key-secret".toCharArray(), passphrase = "phrase".toCharArray())
        assertFalse(key.toString().contains("private-key-secret"))
        key.close()
        assertFailsWith<IllegalStateException> { key.copyPrivateKey() }
    }

    @Test
    fun `executor validates command parameters before connecting or resolving credentials`() {
        var resolved = false
        val executor = SshjCommandExecutor(CredentialResolver {
            resolved = true
            SshPasswordCredential("unused".toCharArray())
        })
        val server = validServer()

        assertFailsWith<IllegalArgumentException> { executor.execute(server, " ") }
        assertFailsWith<IllegalArgumentException> { executor.execute(server, "true", timeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { executor.execute(server, "true", maxOutputBytes = 0) }
        assertFalse(resolved)
    }

    private fun validServer(
        host: String = "example.com",
        port: Int = 22,
        username: String = "pilot",
    ): SshServer {
        val fingerprint = "SHA256:${Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32))}"
        return SshServer(
            id = "server-1",
            name = "Test server",
            host = host,
            port = port,
            username = username,
            credential = SshCredentialRef.Password("credential-1"),
            hostKeyPolicy = SshHostKeyPolicy.Sha256Fingerprint(fingerprint),
        )
    }

    private fun encodedEd25519Key(): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            val algorithm = "ssh-ed25519".toByteArray()
            data.writeInt(algorithm.size)
            data.write(algorithm)
            data.writeInt(32)
            data.write(ByteArray(32) { it.toByte() })
        }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
