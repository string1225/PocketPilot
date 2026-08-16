package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.integrations.vision.FileAttachmentImageStore
import com.string1225.pocketpilot.security.CredentialStoreException
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class LlmVisionRequest(
    val config: LlmEndpointConfig,
    val prompt: String,
    val mimeType: String,
    val imageBytes: ByteArray,
)

class OpenAiCompatibleVisionClient(
    private val credentials: SecureCredentialStore,
    private val connectionFactory: LlmHttpConnectionFactory = LlmHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 120_000,
) {
    fun analyze(request: LlmVisionRequest): String {
        require(request.config.protocol == LlmProtocol.CHAT_COMPLETIONS) {
            "Image understanding requires Chat Completions"
        }
        require(request.prompt.isNotBlank() && request.prompt.length <= MAX_PROMPT_CHARS) {
            "Image prompt is invalid"
        }
        require(request.mimeType in ALLOWED_MIME_TYPES) { "Unsupported image MIME type" }
        require(request.imageBytes.size.toLong() in 1..FileAttachmentImageStore.MAX_IMAGE_BYTES) {
            "Image exceeds the size limit"
        }
        val endpoint = LlmEndpointPolicy.resolve(request.config).toURL()
        val secret = try {
            credentials.get(request.config.credentialId)
        } catch (error: CredentialStoreException) {
            throw LlmProtocolException(error.code, error.message, cause = error)
        } ?: throw LlmProtocolException(
            "LLM_CREDENTIAL_NOT_FOUND",
            "No local credential is registered for this LLM endpoint",
        )
        requireValidSecret(secret)
        val secretText = secret.concatToString()
        var body: ByteArray? = null
        var connection: HttpURLConnection? = null
        try {
            body = encodeRequest(request)
            require(body.size <= MAX_REQUEST_BYTES) { "Vision request exceeded the size limit" }
            val active = connectionFactory.open(endpoint).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                useCaches = false
                doInput = true
                doOutput = true
                setRequestProperty("Authorization", "Bearer $secretText")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Encoding", "identity")
                setFixedLengthStreamingMode(body.size)
            }
            connection = active
            active.outputStream.use { it.write(body) }
            val status = active.responseCode
            if (status !in 200..299) {
                val errorBytes = readBody(active.errorStream, MAX_ERROR_BYTES)
                val message = try {
                    OpenAiCompatibleProtocolCodec.extractRemoteErrorMessage(decodeUtf8(errorBytes))
                } finally {
                    errorBytes.fill(0)
                }
                throw httpError(status, redactSecret(message, secretText))
            }
            val responseBytes = readBody(active.inputStream, MAX_RESPONSE_BYTES)
            val responseText = try {
                decodeUtf8(responseBytes)
            } finally {
                responseBytes.fill(0)
            }
            val decoded = OpenAiCompatibleProtocolCodec.decodeResponse(
                LlmCompletionRequest(
                    config = request.config,
                    messages = listOf(LlmMessage("user", request.prompt)),
                    tools = emptyList(),
                ),
                responseText,
            )
            val content = decoded.content?.takeIf(String::isNotBlank)
                ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Vision model returned no text")
            if (content.contains(secretText)) {
                throw LlmProtocolException(
                    "LLM_SECRET_ECHO",
                    "Vision response was blocked because it contained credential material",
                )
            }
            return content
        } catch (error: LlmProtocolException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LlmProtocolException("LLM_TIMEOUT", "Vision request timed out", true, error)
        } catch (error: IOException) {
            throw LlmProtocolException("LLM_NETWORK_ERROR", "Vision network request failed", true, error)
        } finally {
            body?.fill(0)
            secret.fill('\u0000')
            connection?.disconnect()
        }
    }

    private fun encodeRequest(request: LlmVisionRequest): ByteArray {
        val encoded = Base64.getEncoder().encodeToString(request.imageBytes)
        val root = JSONObject()
            .put("model", request.config.model)
            .put("stream", false)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(JSONObject().put("type", "text").put("text", request.prompt))
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject().put(
                                                "url",
                                                "data:${request.mimeType};base64,$encoded",
                                            ),
                                        ),
                                ),
                        ),
                ),
            )
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    private fun requireValidSecret(secret: CharArray) {
        if (secret.isEmpty() || secret.size > MAX_CREDENTIAL_CHARS) {
            secret.fill('\u0000')
            throw LlmProtocolException("LLM_CREDENTIAL_INVALID", "Stored LLM credential is invalid")
        }
        val value = secret.concatToString()
        if (value.isBlank() || value != value.trim() || value.any { it == '\r' || it == '\n' || it == '\u0000' }) {
            secret.fill('\u0000')
            throw LlmProtocolException("LLM_CREDENTIAL_INVALID", "Stored LLM credential is invalid")
        }
    }

    private fun readBody(stream: InputStream?, limit: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(8 * 1024)
            try {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > limit) {
                        throw LlmProtocolException("LLM_RESPONSE_TOO_LARGE", "Vision response exceeded the size limit")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } finally {
                buffer.fill(0)
                output.reset()
            }
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (error: Exception) {
        throw LlmProtocolException("LLM_INVALID_RESPONSE", "Vision response is not valid UTF-8", cause = error)
    }

    private fun httpError(status: Int, remoteMessage: String?): LlmProtocolException {
        val message = remoteMessage?.takeIf(String::isNotBlank) ?: "Vision endpoint returned HTTP $status"
        return when (status) {
            401, 403 -> LlmProtocolException("LLM_AUTHENTICATION_FAILED", message)
            408 -> LlmProtocolException("LLM_TIMEOUT", message, true)
            429 -> LlmProtocolException("LLM_RATE_LIMITED", message, true)
            in 500..599 -> LlmProtocolException("LLM_SERVICE_UNAVAILABLE", message, true)
            else -> LlmProtocolException("LLM_HTTP_ERROR", message)
        }
    }

    private fun redactSecret(message: String?, secret: String): String? = message
        ?.replace(secret, "[REDACTED]")
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.take(512)

    companion object {
        private const val MAX_PROMPT_CHARS = 8_192
        private const val MAX_REQUEST_BYTES = 12 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 256 * 1024
        private const val MAX_CREDENTIAL_CHARS = 16 * 1024
        private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp")
    }
}
