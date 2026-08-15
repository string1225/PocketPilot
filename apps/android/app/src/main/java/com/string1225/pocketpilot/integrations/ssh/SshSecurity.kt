package com.string1225.pocketpilot.integrations.ssh

import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts

internal object SshInputPolicy {
    private val identifierPattern = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}")
    private val fingerprintPattern = Regex("SHA256:[A-Za-z0-9+/]{43}=?")

    fun requireIdentifier(name: String, value: String) {
        require(identifierPattern.matches(value)) { "$name is invalid" }
    }

    fun requireDisplayName(value: String) {
        require(value.length in 1..256 && value.none(Char::isUnsafeApprovalCharacter)) {
            "SSH server name is invalid"
        }
    }

    fun requireHost(value: String) {
        require(value.length in 1..253) { "SSH host length must be in 1..253" }
        require(value.all { it.isLetterOrDigit() && it.code < 128 || it in "._-:" }) { "SSH host is invalid" }
        require(value.any { it.isLetterOrDigit() && it.code < 128 }) { "SSH host is invalid" }
    }

    fun requireUsername(value: String) {
        require(value.length in 1..256) { "SSH username length must be in 1..256" }
        require(value.all { it.code in 0x21..0x7e && it !in "@:/\\" }) { "SSH username is invalid" }
    }

    fun requireSha256Fingerprint(value: String) {
        require(fingerprintPattern.matches(value)) { "Only a valid SHA256 SSH host fingerprint is accepted" }
        val encoded = value.removePrefix("SHA256:").let { if (it.length % 4 == 0) it else "$it=" }
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Only a valid SHA256 SSH host fingerprint is accepted")
        }
        require(decoded.size == 32) { "SHA256 SSH host fingerprint must contain a 32-byte digest" }
    }

    fun requireKnownHosts(value: String) {
        require(value.length in 1..MAX_KNOWN_HOSTS_CHARS && '\u0000' !in value) {
            "known_hosts content is empty or too large"
        }
        val entries = value.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .toList()
        require(entries.isNotEmpty()) {
            "known_hosts content must include at least one entry"
        }
        entries.forEach(::requireKnownHostEntry)
    }

    fun requireCommand(command: String) {
        require(command.length in 1..MAX_COMMAND_CHARS && command.any { !it.isWhitespace() }) {
            "SSH command is empty or too large"
        }
        require(command.none {
            it.isUnsafeApprovalCharacter() && it != '\n' && it != '\t'
        }) { "SSH command contains unsafe control characters" }
    }

    fun requireTimeout(timeoutMillis: Long) {
        require(timeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "SSH timeout must be in 1..$MAX_TIMEOUT_MILLIS milliseconds" }
    }

    fun requireOutputLimit(maxOutputBytes: Int) {
        require(maxOutputBytes in 1..MAX_OUTPUT_BYTES) { "SSH output limit must be in 1..$MAX_OUTPUT_BYTES bytes" }
    }

    private fun requireKnownHostEntry(line: String) {
        val fields = line.split(Regex("\\s+"))
        var offset = 0
        if (fields.firstOrNull()?.startsWith('@') == true) {
            require(fields[0] == "@cert-authority" || fields[0] == "@revoked") {
                "known_hosts entry has an unsupported marker"
            }
            offset = 1
        }
        require(fields.size >= offset + 3) { "known_hosts entry is malformed" }
        require(fields[offset].length in 1..4096) { "known_hosts host pattern is invalid" }
        val algorithm = fields[offset + 1]
        require(algorithm.length in 1..128 && algorithm.matches(Regex("[A-Za-z0-9@._+-]+"))) {
            "known_hosts key algorithm is invalid"
        }
        val blob = try {
            Base64.getDecoder().decode(fields[offset + 2])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("known_hosts key is not valid base64")
        }
        require(blob.size in 8..MAX_HOST_KEY_BYTES) { "known_hosts key blob is invalid" }
        val buffer = ByteBuffer.wrap(blob)
        val embeddedNameLength = buffer.int
        require(embeddedNameLength in 1..128 && embeddedNameLength <= buffer.remaining()) {
            "known_hosts key blob is malformed"
        }
        val embeddedName = String(ByteArray(embeddedNameLength).also(buffer::get), StandardCharsets.US_ASCII)
        require(embeddedName == algorithm) { "known_hosts key type does not match its key blob" }
    }

    private const val MAX_KNOWN_HOSTS_CHARS = 1024 * 1024
    private const val MAX_COMMAND_CHARS = 4096
    private const val MAX_TIMEOUT_MILLIS = 60 * 60 * 1000L
    // stdout and stderr each receive this budget. Even worst-case JSON escaping
    // then stays below the 8 MiB Native bridge envelope limit.
    private const val MAX_OUTPUT_BYTES = 512 * 1024
    private const val MAX_HOST_KEY_BYTES = 64 * 1024
}

/** Produces an unambiguous, fully bounded copy for the native approval UI. */
internal object SshApprovalFormatter {
    fun escapeCommand(command: String, maximumCharacters: Int): String {
        require(maximumCharacters >= 0) { "SSH approval size limit must not be negative" }
        val escaped = buildString(command.length) {
            command.forEach { character ->
                when {
                    character == '\\' -> append("\\\\")
                    character == '"' -> append("\\\"")
                    character == '\n' -> append("\\n")
                    character == '\r' -> append("\\r")
                    character == '\t' -> append("\\t")
                    character.isUnsafeApprovalCharacter() ||
                        Character.getType(character) == Character.FORMAT.toInt() ||
                        character == '\u2028' ||
                        character == '\u2029' -> {
                        append("\\u")
                        append(character.code.toString(16).uppercase().padStart(4, '0'))
                    }
                    else -> append(character)
                }
            }
        }
        require(escaped.length <= maximumCharacters) {
            "SSH command is too large to display safely for approval"
        }
        return escaped
    }
}

private fun Char.isUnsafeApprovalCharacter(): Boolean =
    isISOControl() ||
        code == 0x061c ||
        code == 0x200e ||
        code == 0x200f ||
        code in 0x202a..0x202e ||
        code in 0x2066..0x2069

internal object StrictHostKeyVerifierFactory {
    fun create(policy: SshHostKeyPolicy): HostKeyVerifier = when (policy) {
        is SshHostKeyPolicy.Sha256Fingerprint -> {
            SshInputPolicy.requireSha256Fingerprint(policy.value)
            net.schmizz.sshj.transport.verification.FingerprintVerifier.getInstance(policy.value)
        }

        is SshHostKeyPolicy.KnownHosts -> {
            SshInputPolicy.requireKnownHosts(policy.entries)
            StrictOpenSshKnownHosts(StringReader(policy.entries)).also {
                require(it.hasParsedEntries()) { "known_hosts content contains no valid entries" }
            }
        }
    }

    private class StrictOpenSshKnownHosts(reader: StringReader) : OpenSSHKnownHosts(reader) {
        fun hasParsedEntries(): Boolean = entries.isNotEmpty()
    }
}
