package com.string1225.pocketpilot.integrations.ssh

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.DefaultSecurityProviderConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.kex.KeyExchange
import net.schmizz.sshj.userauth.password.PasswordUtils

/** Executes one non-interactive command using a fresh, strictly verified SSH connection. */
class SshjCommandExecutor(
    private val credentialResolver: CredentialResolver,
) {
    fun execute(
        server: SshServer,
        command: String,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
        maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    ): SshCommandResult = execute(server, SshCommandRequest(command, timeoutMillis, maxOutputBytes))

    fun execute(
        server: SshServer,
        request: SshCommandRequest,
    ): SshCommandResult {
        val started = System.nanoTime()

        SSHClient(SshjClientConfigFactory.create()).use { client ->
            // The default client has no verifier. Exactly one strict policy is installed before connect.
            client.addHostKeyVerifier(StrictHostKeyVerifierFactory.create(server.hostKeyPolicy))
            client.connectTimeout = minOf(request.timeoutMillis, MAX_CONNECT_TIMEOUT_MILLIS).toInt()
            client.timeout = request.timeoutMillis.toInt()
            client.connect(server.host, server.port)
            authenticate(client, server)

            client.startSession().use { session ->
                val remoteCommand = session.exec(request.command)
                val executor = Executors.newFixedThreadPool(2) { runnable ->
                    Thread(runnable, "pocketpilot-ssh-output").apply { isDaemon = true }
                }
                val stdoutCollector = BoundedSshStreamCollector(remoteCommand.inputStream, request.maxOutputBytes)
                val stderrCollector = BoundedSshStreamCollector(remoteCommand.errorStream, request.maxOutputBytes)
                val drains = listOf(
                    executor.submit(stdoutCollector),
                    executor.submit(stderrCollector),
                )

                try {
                    val timedOut: Boolean
                    try {
                        remoteCommand.join(request.timeoutMillis, TimeUnit.MILLISECONDS)
                        timedOut = remoteCommand.isOpen
                    } finally {
                        // SSHJ documents that exit status must only be read after closing the command.
                        runCatching { remoteCommand.close() }
                    }

                    val drainFailure = awaitDrains(drains)
                    val stdout = stdoutCollector.snapshot()
                    val stderr = stderrCollector.snapshot()
                    val result = SshCommandResult(
                        stdout = stdout.text,
                        stderr = stderr.text,
                        exitCode = remoteCommand.exitStatus ?: UNKNOWN_EXIT_CODE,
                        stdoutTruncated = stdout.truncated,
                        stderrTruncated = stderr.truncated,
                        durationMillis = elapsedMillis(started),
                    )

                    if (timedOut) {
                        throw SshCommandTimeoutException(request.timeoutMillis, result)
                    }
                    if (drainFailure != null) {
                        throw IOException("Unable to read complete SSH command output", drainFailure)
                    }
                    return result
                } finally {
                    drains.filterNot(Future<Unit>::isDone).forEach { it.cancel(true) }
                    executor.shutdownNow()
                }
            }
        }
    }

    private fun authenticate(client: SSHClient, server: SshServer) {
        credentialResolver.resolve(server.credential).use { resolved ->
            when (server.credential) {
                is SshCredentialRef.Password -> {
                    require(resolved is SshPasswordCredential) { "Credential type does not match password reference" }
                    // SSHJ erases the supplied array in a finally block after authentication.
                    client.authPassword(server.username, resolved.copyPassword())
                }

                is SshCredentialRef.PrivateKey -> {
                    require(resolved is SshPrivateKeyCredential) {
                        "Credential type does not match private-key reference"
                    }
                    authenticatePrivateKey(client, server.username, resolved)
                }
            }
        }
    }

    private fun authenticatePrivateKey(
        client: SSHClient,
        username: String,
        credential: SshPrivateKeyCredential,
    ) {
        val privateKey = credential.copyPrivateKey()
        val publicKey = credential.copyPublicKey()
        val passphrase = credential.copyPassphrase()
        try {
            // SSHJ's in-memory key API requires encoded key strings. They are never persisted or logged.
            val keyProvider = client.loadKeys(
                String(privateKey),
                publicKey?.let(::String),
                PasswordUtils.createOneOff(passphrase),
            )
            client.authPublickey(username, keyProvider)
        } finally {
            Arrays.fill(privateKey, '\u0000')
            publicKey?.let { Arrays.fill(it, '\u0000') }
            passphrase?.let { Arrays.fill(it, '\u0000') }
        }
    }

    private fun awaitDrains(futures: List<Future<Unit>>): Throwable? {
        var firstFailure: Throwable? = null
        futures.forEach { future ->
            try {
                future.get(OUTPUT_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (failure: ExecutionException) {
                if (firstFailure == null) firstFailure = failure.cause ?: failure
            } catch (failure: TimeoutException) {
                future.cancel(true)
                if (firstFailure == null) firstFailure = failure
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                future.cancel(true)
                if (firstFailure == null) firstFailure = failure
            }
        }
        return firstFailure
    }

    private fun elapsedMillis(startedNanos: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 60_000L
        const val DEFAULT_MAX_OUTPUT_BYTES = 512 * 1024
        const val MAX_CONNECT_TIMEOUT_MILLIS = 30_000L
        const val OUTPUT_DRAIN_TIMEOUT_SECONDS = 5L
        const val UNKNOWN_EXIT_CODE = -1
    }
}

/**
 * Builds an SSHJ configuration without changing Android's process-wide JCA providers.
 *
 * Android exposes an old platform provider named `BC`. SSHJ selects that provider for
 * all JCA lookups, but some Android versions do not implement X25519 or EC in it.
 * Advertising those algorithms makes negotiation choose a KEX that cannot start. Probe
 * the same SSHJ lookup path and omit each unsupported family from this client instance.
 */
internal object SshjClientConfigFactory {
    fun create(): DefaultConfig {
        val config = newDefaultJcaConfig()
        return configure(
            config = config,
            x25519Available = isX25519AvailableToSshj(),
            ecdhAvailable = isEcdhAvailableToSshj(),
        )
    }

    internal fun create(
        x25519Available: Boolean,
        ecdhAvailable: Boolean,
    ): DefaultConfig = configure(
        config = newDefaultJcaConfig(),
        x25519Available = x25519Available,
        ecdhAvailable = ecdhAvailable,
    )

    private fun configure(
        config: DefaultConfig,
        x25519Available: Boolean,
        ecdhAvailable: Boolean,
    ): DefaultConfig = config.also {
        config.keyExchangeFactories = secureKeyExchangeFactories(
            candidates = config.keyExchangeFactories,
            x25519Available = x25519Available,
            ecdhAvailable = ecdhAvailable,
        )
    }

    fun isX25519AvailableToSshj(): Boolean {
        useDefaultJcaProviders()
        return runCatching {
            // Curve25519DH needs all three primitives through SSHJ's selected provider.
            SecurityUtils.getKeyAgreement(X25519_JCA_NAME)
            SecurityUtils.getKeyFactory(X25519_JCA_NAME)
            SecurityUtils.getKeyPairGenerator(X25519_JCA_NAME)
        }.isSuccess
    }

    fun isEcdhAvailableToSshj(): Boolean {
        useDefaultJcaProviders()
        return runCatching {
            // ECDH constructs keys with EC and performs agreement with ECDH.
            SecurityUtils.getKeyPairGenerator(EC_JCA_NAME)
            SecurityUtils.getKeyFactory(EC_JCA_NAME)
            SecurityUtils.getKeyAgreement(ECDH_JCA_NAME)
        }.isSuccess
    }

    private fun newDefaultJcaConfig(): DefaultConfig {
        useDefaultJcaProviders()
        return DefaultSecurityProviderConfig()
    }

    /**
     * SSHJ normally registers/selects a provider named `BC`. Android already owns that
     * name with a reduced legacy implementation, so forcing it breaks common primitives.
     * This changes SSHJ's own lookup policy only; it never adds, removes, or reorders JCA
     * providers. Default JCA lookup can then choose an implementation per algorithm.
     */
    private fun useDefaultJcaProviders() {
        SecurityUtils.setSecurityProvider(null)
        SecurityUtils.setRegisterBouncyCastle(false)
    }

    internal fun secureKeyExchangeFactories(
        candidates: List<Factory.Named<KeyExchange>>,
        x25519Available: Boolean,
        ecdhAvailable: Boolean,
    ): List<Factory.Named<KeyExchange>> {
        val enabled = candidates.filter { factory ->
            factory.name in SAFE_KEY_EXCHANGE_NAMES &&
                (x25519Available || factory.name !in CURVE25519_KEY_EXCHANGE_NAMES) &&
                (ecdhAvailable || factory.name !in ECDH_KEY_EXCHANGE_NAMES)
        }
        check(enabled.any { it.name != EXT_INFO_CLIENT }) {
            "No supported secure SSH key exchange algorithm is available"
        }
        return enabled
    }

    private val CURVE25519_KEY_EXCHANGE_NAMES = setOf(
        "curve25519-sha256",
        "curve25519-sha256@libssh.org",
    )

    private val ECDH_KEY_EXCHANGE_NAMES = setOf(
        "ecdh-sha2-nistp521",
        "ecdh-sha2-nistp384",
        "ecdh-sha2-nistp256",
    )

    // SHA-2 KEX only. SHA-1 and the 1024-bit fixed group1 are not advertised.
    private val SAFE_KEY_EXCHANGE_NAMES = CURVE25519_KEY_EXCHANGE_NAMES +
        ECDH_KEY_EXCHANGE_NAMES + setOf(
            "diffie-hellman-group-exchange-sha256",
            "diffie-hellman-group14-sha256",
            "diffie-hellman-group15-sha512",
            "diffie-hellman-group16-sha512",
            "diffie-hellman-group17-sha512",
            "diffie-hellman-group18-sha512",
            "diffie-hellman-group14-sha256@ssh.com",
            "diffie-hellman-group15-sha256",
            "diffie-hellman-group15-sha256@ssh.com",
            "diffie-hellman-group15-sha384@ssh.com",
            "diffie-hellman-group16-sha256",
            "diffie-hellman-group16-sha384@ssh.com",
            "diffie-hellman-group16-sha512@ssh.com",
            "diffie-hellman-group18-sha512@ssh.com",
            EXT_INFO_CLIENT,
        )

    private const val X25519_JCA_NAME = "X25519"
    private const val EC_JCA_NAME = "EC"
    private const val ECDH_JCA_NAME = "ECDH"
    private const val EXT_INFO_CLIENT = "ext-info-c"
}

internal data class SshStreamSnapshot(
    val text: String,
    val truncated: Boolean,
)

internal class BoundedSshStreamCollector(
    private val input: InputStream,
    private val limit: Int,
) : Callable<Unit> {
    private val captured = ByteArrayOutputStream(minOf(limit, 8192))
    private var wasTruncated = false

    init {
        require(limit > 0) { "Output limit must be positive" }
    }

    override fun call() {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            synchronized(this) {
                val writable = minOf(read, limit - captured.size())
                if (writable > 0) captured.write(buffer, 0, writable)
                if (writable < read) wasTruncated = true
            }
        }
    }

    @Synchronized
    fun snapshot(): SshStreamSnapshot = SshStreamSnapshot(
        text = captured.toString(StandardCharsets.UTF_8.name()),
        truncated = wasTruncated,
    )
}
