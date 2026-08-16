package com.string1225.pocketpilot.integrations.ssh

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.Security
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.schmizz.sshj.DefaultConfig

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

    @Test
    fun `client config excludes unavailable Curve25519 and legacy key exchanges`() {
        val config = SshjClientConfigFactory.create(
            x25519Available = false,
            ecdhAvailable = true,
        )
        val names = config.keyExchangeFactories.map { it.name }

        assertFalse(names.any { it.startsWith("curve25519-") })
        assertFalse(names.contains("diffie-hellman-group-exchange-sha1"))
        assertFalse(names.contains("diffie-hellman-group1-sha1"))
        assertFalse(names.contains("diffie-hellman-group14-sha1"))
        assertTrue(names.contains("diffie-hellman-group-exchange-sha256"))
        assertTrue(names.contains("ecdh-sha2-nistp256"))
        assertTrue(names.contains("diffie-hellman-group14-sha256"))
    }

    @Test
    fun `client config retains Curve25519 only when its SSHJ primitives are available`() {
        val availableNames = SshjClientConfigFactory.create(
            x25519Available = true,
            ecdhAvailable = true,
        )
            .keyExchangeFactories
            .map { it.name }
        val unavailableNames = SshjClientConfigFactory.create(
            x25519Available = false,
            ecdhAvailable = true,
        )
            .keyExchangeFactories
            .map { it.name }

        assertTrue(availableNames.contains("curve25519-sha256"))
        assertFalse(unavailableNames.contains("curve25519-sha256"))
        assertTrue(unavailableNames.isNotEmpty())
    }

    @Test
    fun `client config excludes ECDH when EC primitives are unavailable`() {
        val names = SshjClientConfigFactory.create(
            x25519Available = false,
            ecdhAvailable = false,
        ).keyExchangeFactories.map { it.name }

        assertFalse(names.any { it.startsWith("ecdh-sha2-") })
        assertTrue(names.contains("diffie-hellman-group-exchange-sha256"))
        assertTrue(names.contains("diffie-hellman-group14-sha256"))
        assertTrue(names.contains("diffie-hellman-group16-sha512"))
        assertTrue(names.contains("diffie-hellman-group18-sha512"))
    }

    @Test
    fun `client config uses default JCA lookup without changing provider registration`() {
        val providersBefore = Security.getProviders().map { it.name }

        SshjClientConfigFactory.create()
        val digest = net.schmizz.sshj.common.SecurityUtils.getMessageDigest("SHA-256")

        assertEquals(providersBefore, Security.getProviders().map { it.name })
        assertEquals(32, digest.digest("probe".toByteArray()).size)
        assertEquals(null, net.schmizz.sshj.common.SecurityUtils.getSecurityProvider())
    }

    @Test
    fun `client config fails closed when no secure key exchange remains`() {
        assertFailsWith<IllegalStateException> {
            SshjClientConfigFactory.secureKeyExchangeFactories(
                candidates = DefaultConfig().keyExchangeFactories.filter {
                    it.name == "diffie-hellman-group1-sha1"
                },
                x25519Available = false,
                ecdhAvailable = false,
            )
        }
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
