package com.string1225.pocketpilot.integrations.ssh

import java.util.Arrays

sealed interface SshCredentialRef {
    val credentialId: String

    data class Password(override val credentialId: String) : SshCredentialRef {
        init {
            SshInputPolicy.requireIdentifier("credentialId", credentialId)
        }
    }

    data class PrivateKey(override val credentialId: String) : SshCredentialRef {
        init {
            SshInputPolicy.requireIdentifier("credentialId", credentialId)
        }
    }
}

sealed interface SshHostKeyPolicy {
    /** OpenSSH SHA-256 fingerprint, for example `SHA256:base64Digest`. */
    data class Sha256Fingerprint(val value: String) : SshHostKeyPolicy {
        init {
            SshInputPolicy.requireSha256Fingerprint(value)
        }
    }

    /** One or more OpenSSH known_hosts entries kept with the server configuration. */
    data class KnownHosts(val entries: String) : SshHostKeyPolicy {
        init {
            SshInputPolicy.requireKnownHosts(entries)
        }
    }
}

data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val credential: SshCredentialRef,
    val hostKeyPolicy: SshHostKeyPolicy,
    val description: String = "",
) {
    init {
        SshInputPolicy.requireIdentifier("server id", id)
        SshInputPolicy.requireDisplayName(name)
        SshInputPolicy.requireHost(host)
        require(port in 1..65535) { "SSH port must be in 1..65535" }
        SshInputPolicy.requireUsername(username)
        require(description.length <= 4096 && '\u0000' !in description) { "SSH server description is invalid" }
    }
}

/** Implementations resolve secrets from encrypted storage only when a connection needs them. */
fun interface CredentialResolver {
    fun resolve(reference: SshCredentialRef): SshCredential
}

sealed class SshCredential : AutoCloseable {
    abstract override fun close()

    final override fun toString(): String = "${this::class.simpleName}([REDACTED])"
}

class SshPasswordCredential(password: CharArray) : SshCredential() {
    private var value: CharArray? = password.copyOf()

    internal fun copyPassword(): CharArray =
        checkNotNull(value) { "Credential has already been closed" }.copyOf()

    override fun close() {
        value?.let { Arrays.fill(it, '\u0000') }
        value = null
    }
}

class SshPrivateKeyCredential(
    privateKey: CharArray,
    publicKey: CharArray? = null,
    passphrase: CharArray? = null,
) : SshCredential() {
    private var privateValue: CharArray? = privateKey.copyOf()
    private var publicValue: CharArray? = publicKey?.copyOf()
    private var passphraseValue: CharArray? = passphrase?.copyOf()

    internal fun copyPrivateKey(): CharArray =
        checkNotNull(privateValue) { "Credential has already been closed" }.copyOf()

    internal fun copyPublicKey(): CharArray? {
        check(privateValue != null) { "Credential has already been closed" }
        return publicValue?.copyOf()
    }

    internal fun copyPassphrase(): CharArray? {
        check(privateValue != null) { "Credential has already been closed" }
        return passphraseValue?.copyOf()
    }

    override fun close() {
        privateValue?.let { Arrays.fill(it, '\u0000') }
        publicValue?.let { Arrays.fill(it, '\u0000') }
        passphraseValue?.let { Arrays.fill(it, '\u0000') }
        privateValue = null
        publicValue = null
        passphraseValue = null
    }
}

data class SshCommandResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
    val durationMillis: Long,
)

/** Fully validated execution parameters. Construct this before requesting user approval. */
data class SshCommandRequest(
    val command: String,
    val timeoutMillis: Long = 60_000L,
    val maxOutputBytes: Int = 512 * 1024,
) {
    init {
        SshInputPolicy.requireCommand(command)
        SshInputPolicy.requireTimeout(timeoutMillis)
        SshInputPolicy.requireOutputLimit(maxOutputBytes)
    }
}

class SshCommandTimeoutException(
    val timeoutMillis: Long,
    val partialResult: SshCommandResult,
) : Exception("SSH command exceeded its ${timeoutMillis}ms timeout")
