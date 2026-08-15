package com.string1225.pocketpilot.llm

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
import org.json.JSONArray
import org.json.JSONObject

fun interface LlmHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class OpenAiCompatibleLlmClient(
    private val credentials: SecureCredentialStore,
    private val connectionFactory: LlmHttpConnectionFactory = LlmHttpConnectionFactory { url ->
        url.openConnection() as HttpURLConnection
    },
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val maxRequestBytes: Int = DEFAULT_MAX_REQUEST_BYTES,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        require(connectTimeoutMs in 1_000..MAX_TIMEOUT_MS) { "Invalid LLM connect timeout" }
        require(readTimeoutMs in 1_000..MAX_TIMEOUT_MS) { "Invalid LLM read timeout" }
        require(maxRequestBytes in 1..MAX_BODY_LIMIT_BYTES) { "Invalid LLM request size limit" }
        require(maxResponseBytes in 1..MAX_BODY_LIMIT_BYTES) { "Invalid LLM response size limit" }
    }

    fun complete(request: LlmCompletionRequest): LlmProviderResponse {
        val endpoint = LlmEndpointPolicy.resolve(request.config).toURL()
        val secret = try {
            credentials.get(request.config.credentialId)
        } catch (error: CredentialStoreException) {
            throw LlmProtocolException(error.code, error.message, cause = error)
        } ?: throw LlmProtocolException(
            "LLM_CREDENTIAL_NOT_FOUND",
            "No local credential is registered for this LLM endpoint",
        )
        if (secret.isEmpty() || secret.size > MAX_CREDENTIAL_CHARACTERS) {
            secret.fill('\u0000')
            throw LlmProtocolException("LLM_CREDENTIAL_INVALID", "Stored LLM credential is invalid")
        }
        val secretText = secret.concatToString()
        if (
            secretText.isBlank() ||
            secretText != secretText.trim() ||
            secretText.any { it == '\r' || it == '\n' || it == '\u0000' }
        ) {
            secret.fill('\u0000')
            throw LlmProtocolException("LLM_CREDENTIAL_INVALID", "Stored LLM credential is invalid")
        }

        var requestBody: ByteArray? = null
        var connection: HttpURLConnection? = null
        try {
            val requestBytes = OpenAiCompatibleProtocolCodec.encodeRequest(request)
            requestBody = requestBytes
            if (requestBytes.size > maxRequestBytes) {
                throw LlmProtocolException("LLM_REQUEST_TOO_LARGE", "LLM request exceeded the size limit")
            }
            val activeConnection = connectionFactory.open(endpoint).apply {
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
                setFixedLengthStreamingMode(requestBytes.size)
            }
            connection = activeConnection
            activeConnection.outputStream.use { it.write(requestBytes) }
            val status = activeConnection.responseCode
            if (status !in 200..299) {
                val errorBytes = readBody(
                    activeConnection.errorStream,
                    minOf(maxResponseBytes, MAX_ERROR_BODY_BYTES),
                )
                val message = try {
                    OpenAiCompatibleProtocolCodec.extractRemoteErrorMessage(decodeUtf8(errorBytes))
                } finally {
                    errorBytes.fill(0)
                }
                throw httpError(status, redactSecret(message, secretText))
            }

            val responseBytes = readBody(activeConnection.inputStream, maxResponseBytes)
            val responseText = try {
                decodeUtf8(responseBytes)
            } finally {
                responseBytes.fill(0)
            }
            return try {
                OpenAiCompatibleProtocolCodec.decodeResponse(request, responseText).also { response ->
                    requireNoCredentialEcho(response, secretText)
                }
            } catch (error: LlmProtocolException) {
                throw LlmProtocolException(
                    error.code,
                    redactSecret(error.message, secretText) ?: "LLM response could not be processed",
                    error.retryable,
                    error,
                )
            }
        } catch (error: LlmProtocolException) {
            throw error
        } catch (error: SocketTimeoutException) {
            throw LlmProtocolException(
                "LLM_TIMEOUT",
                "LLM request timed out",
                retryable = true,
                cause = error,
            )
        } catch (error: IOException) {
            throw LlmProtocolException(
                "LLM_NETWORK_ERROR",
                "LLM network request failed",
                retryable = true,
                cause = error,
            )
        } finally {
            requestBody?.fill(0)
            secret.fill('\u0000')
            connection?.disconnect()
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
                        throw LlmProtocolException(
                            "LLM_RESPONSE_TOO_LARGE",
                            "LLM response exceeded the size limit",
                        )
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
        throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM response is not valid UTF-8", cause = error)
    }

    private fun httpError(status: Int, remoteMessage: String?): LlmProtocolException {
        val message = remoteMessage?.takeIf(String::isNotBlank)
            ?: "LLM endpoint returned HTTP $status"
        return when (status) {
            401, 403 -> LlmProtocolException("LLM_AUTHENTICATION_FAILED", message)
            408 -> LlmProtocolException("LLM_TIMEOUT", message, retryable = true)
            409 -> LlmProtocolException("LLM_CONFLICT", message, retryable = true)
            429 -> LlmProtocolException("LLM_RATE_LIMITED", message, retryable = true)
            in 500..599 -> LlmProtocolException("LLM_SERVICE_UNAVAILABLE", message, retryable = true)
            else -> LlmProtocolException("LLM_HTTP_ERROR", message)
        }
    }

    private fun redactSecret(message: String?, secret: String): String? = message
        ?.replace(secret, "[REDACTED]")
        ?.replace(Regex("[\\r\\n\\t]+"), " ")
        ?.trim()
        ?.take(512)

    private fun requireNoCredentialEcho(response: LlmProviderResponse, secret: String) {
        val echoed = response.content?.contains(secret) == true || response.toolCalls.any { call ->
            call.id.contains(secret) ||
                call.name.contains(secret) ||
                jsonValueContainsSecret(JSONObject(call.argumentsJson), secret)
        }
        if (echoed) {
            throw LlmProtocolException(
                "LLM_SECRET_ECHO",
                "LLM response was blocked because it contained credential material",
            )
        }
    }

    private fun jsonValueContainsSecret(value: Any?, secret: String): Boolean = when (value) {
        is String -> value.contains(secret)
        is JSONObject -> {
            val keys = value.keys()
            var found = false
            while (!found && keys.hasNext()) {
                val key = keys.next()
                found = key.contains(secret) || jsonValueContainsSecret(value.opt(key), secret)
            }
            found
        }
        is JSONArray -> (0 until value.length()).any { index ->
            jsonValueContainsSecret(value.opt(index), secret)
        }
        is Number, is Boolean -> value.toString().contains(secret)
        else -> false
    }

    companion object {
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MS = 120_000
        private const val MAX_TIMEOUT_MS = 300_000
        private const val DEFAULT_MAX_REQUEST_BYTES = 4 * 1024 * 1024
        private const val DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024
        private const val MAX_ERROR_BODY_BYTES = 256 * 1024
        private const val MAX_BODY_LIMIT_BYTES = 16 * 1024 * 1024
        private const val MAX_CREDENTIAL_CHARACTERS = 16 * 1024
    }
}
