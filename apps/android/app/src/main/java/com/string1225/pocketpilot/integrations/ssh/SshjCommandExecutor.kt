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
import net.schmizz.sshj.SSHClient
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

        SSHClient().use { client ->
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
