package com.string1225.pocketpilot.integrations.ssh

import com.string1225.pocketpilot.model.SshHostKeyCandidate
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier

/**
 * Performs only SSH transport negotiation. The candidate key is captured and
 * deliberately rejected, so no username, password or private key is sent.
 */
class SshHostKeyScanner {
    fun scan(host: String, port: Int, timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS): SshHostKeyCandidate {
        val normalizedHost = host.trim()
        SshInputPolicy.requireHost(normalizedHost)
        require(port in 1..65535) { "SSH port must be in 1..65535" }
        require(timeoutMillis in 1_000..30_000) { "SSH scan timeout is invalid" }
        val captured = AtomicReference<SshHostKeyCandidate?>()
        val verifier = object : HostKeyVerifier {
            override fun verify(hostname: String, remotePort: Int, key: PublicKey): Boolean {
                captured.compareAndSet(
                    null,
                    SshHostKeyCandidate(
                        host = normalizedHost,
                        port = port,
                        algorithm = KeyType.fromKey(key).toString(),
                        fingerprint = sha256Fingerprint(key),
                    ),
                )
                // Always abort before authentication. The scan result is an
                // untrusted candidate until the user confirms it out-of-band.
                return false
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        }
        SSHClient(SshjClientConfigFactory.create()).use { client ->
            client.addHostKeyVerifier(verifier)
            client.connectTimeout = timeoutMillis
            client.timeout = timeoutMillis
            runCatching { client.connect(normalizedHost, port) }
        }
        return captured.get()
            ?: throw IllegalStateException("SSH server did not present a host key")
    }

    internal fun sha256Fingerprint(key: PublicKey): String {
        val buffer = Buffer.PlainBuffer()
        buffer.putPublicKey(key)
        val encoded = buffer.compactData
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(encoded)
        } finally {
            encoded.fill(0)
        }
        return try {
            "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        } finally {
            digest.fill(0)
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
    }
}
