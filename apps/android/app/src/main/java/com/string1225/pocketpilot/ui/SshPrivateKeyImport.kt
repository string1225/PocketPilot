package com.string1225.pocketpilot.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.string1225.pocketpilot.model.AppLanguage
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SshPrivateKeyImportButton(
    language: AppLanguage,
    onImported: (String) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { "Unable to open the selected private key" }
                        SshPrivateKeyImport.decode(input)
                    }
                }
            }
            result.onSuccess(onImported).onFailure { error ->
                onError(
                    error.message ?: ppText(
                        language,
                        "无法读取所选私钥",
                        "Unable to read the selected private key",
                    ),
                )
            }
        }
    }

    OutlinedButton(
        onClick = {
            launcher.launch(
                arrayOf(
                    "application/octet-stream",
                    "application/x-pem-file",
                    "text/plain",
                ),
            )
        },
    ) {
        Text(ppText(language, "从文件导入私钥", "Import private key file"))
    }
}

internal object SshPrivateKeyImport {
    // The credential store accepts at most 16 KiB characters. Private keys are
    // wrapped in a versioned Base64 envelope before encryption, so leave the
    // required expansion room and fail at import instead of failing at Save.
    const val MAX_PRIVATE_KEY_BYTES = 12_000
    private val supportedHeaders = listOf(
        "-----BEGIN OPENSSH PRIVATE KEY-----",
        "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN RSA PRIVATE KEY-----",
        "-----BEGIN EC PRIVATE KEY-----",
    )

    fun decode(input: InputStream): String {
        val bytes = readBounded(input)
        try {
            val value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
            require(value.length <= MAX_PRIVATE_KEY_BYTES) { "SSH private key is too large" }
            require(supportedHeaders.any(value::startsWith)) {
                "Select an unencrypted PEM/OpenSSH private key, not a .pub public key"
            }
            require(value.none { it == '\u0000' }) { "SSH private key contains invalid data" }
            requireUnencrypted(value)
            return value
        } finally {
            bytes.fill(0)
        }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_PRIVATE_KEY_BYTES + 1)
        var total = 0
        try {
            while (true) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                if (count == 0) continue
                total += count
                require(total <= MAX_PRIVATE_KEY_BYTES) { "SSH private key is too large" }
            }
            return buffer.copyOf(total)
        } finally {
            buffer.fill(0)
        }
    }

    private fun requireUnencrypted(value: String) {
        require(!value.lineSequence().any { line ->
            line.startsWith("Proc-Type:", ignoreCase = true) ||
                line.startsWith("DEK-Info:", ignoreCase = true)
        }) { "Encrypted SSH private keys are not supported yet" }
        if (!value.startsWith("-----BEGIN OPENSSH PRIVATE KEY-----")) return

        val encoded = value
            .substringAfter("-----BEGIN OPENSSH PRIVATE KEY-----")
            .substringBefore("-----END OPENSSH PRIVATE KEY-----")
        val decoded = try {
            Base64.getMimeDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("OpenSSH private key is not valid Base64", error)
        }
        try {
            val buffer = ByteBuffer.wrap(decoded)
            val magic = ByteArray(OPENSSH_MAGIC.size).also(buffer::get)
            require(magic.contentEquals(OPENSSH_MAGIC)) { "OpenSSH private key has an invalid header" }
            val cipherName = buffer.readSshString("cipher name")
            val kdfName = buffer.readSshString("KDF name")
            require(cipherName == "none" && kdfName == "none") {
                "Encrypted OpenSSH private keys are not supported yet"
            }
        } catch (error: java.nio.BufferUnderflowException) {
            throw IllegalArgumentException("OpenSSH private key is truncated", error)
        } finally {
            decoded.fill(0)
        }
    }

    private fun ByteBuffer.readSshString(label: String): String {
        val length = int
        require(length in 0..remaining()) { "OpenSSH private key has an invalid $label" }
        val bytes = ByteArray(length)
        return try {
            get(bytes)
            String(bytes, StandardCharsets.US_ASCII)
        } finally {
            bytes.fill(0)
        }
    }

    private val OPENSSH_MAGIC = "openssh-key-v1\u0000".toByteArray(StandardCharsets.US_ASCII)
}
