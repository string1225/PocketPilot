package com.string1225.pocketpilot.integrations.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.Proxy
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class OkHttpToolExecutor(
    private val client: OkHttpClient = secureClient(),
) : HttpToolExecutor {
    override suspend fun execute(request: HttpToolRequest): HttpToolResponse {
        val requestBody = when {
            request.body != null -> request.body.toByteArray(StandardCharsets.UTF_8).toRequestBody()
            request.method.isReadOnly -> null
            else -> ByteArray(0).toRequestBody()
        }
        val nativeRequest = Request.Builder()
            .url(request.url)
            .method(request.method.name, requestBody)
            .header("User-Agent", USER_AGENT)
            .apply { request.headers.forEach(::header) }
            .build()
        val call = client.newCall(nativeRequest)
        call.timeout().timeout(request.timeoutMillis, TimeUnit.MILLISECONDS)
        return try {
            call.awaitResponse(request.maxResponseBytes)
        } catch (error: HttpToolException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw HttpToolException("HTTP_TIMEOUT", "HTTP request timed out", retryable = true, cause = error)
        } catch (error: IOException) {
            if (error.containsCause<HttpTargetBlockedException>()) {
                throw HttpToolException("HTTP_TARGET_BLOCKED", "HTTP target is not a public network endpoint", cause = error)
            }
            if (error.containsCause<UnknownHostException>()) {
                throw HttpToolException("HTTP_DNS_FAILED", "HTTP target could not be resolved", retryable = true, cause = error)
            }
            throw HttpToolException("HTTP_NETWORK_ERROR", "HTTP network request failed", retryable = true, cause = error)
        }
    }

    private suspend fun Call.awaitResponse(maxResponseBytes: Int): HttpToolResponse =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        if (!continuation.isActive) {
                            response.close()
                            return
                        }
                        try {
                            response.use {
                                val bounded = readBounded(response, maxResponseBytes)
                                try {
                                    val decoded = decodeBody(bounded.bytes)
                                    continuation.resume(
                                        HttpToolResponse(
                                            status = response.code,
                                            headers = safeResponseHeaders(response),
                                            body = decoded.body,
                                            bodyEncoding = decoded.encoding,
                                            truncated = bounded.truncated,
                                        ),
                                    )
                                } finally {
                                    bounded.bytes.fill(0)
                                }
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                },
            )
        }

    private fun readBounded(response: Response, limit: Int): BoundedBody {
        val body = response.body
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream(minOf(limit, 16 * 1024))
            val buffer = ByteArray(8 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) return BoundedBody(output.toByteArray(), false)
                output.write(buffer, 0, count)
                remaining -= count
            }
            return BoundedBody(output.toByteArray(), input.read() >= 0)
        }
    }

    private fun safeResponseHeaders(response: Response): Map<String, List<String>> {
        val result = linkedMapOf<String, List<String>>()
        var encodedCharacters = 0
        for (name in SAFE_RESPONSE_HEADERS) {
            val values = response.headers.values(name).map { it.take(MAX_RESPONSE_HEADER_VALUE_CHARS) }
            if (values.isEmpty()) continue
            val additional = name.length + values.sumOf(String::length)
            if (result.size >= MAX_RESPONSE_HEADER_NAMES || encodedCharacters + additional > MAX_RESPONSE_HEADER_CHARS) {
                break
            }
            result[name.lowercase(Locale.ROOT)] = values
            encodedCharacters += additional
        }
        return result
    }

    private fun decodeBody(bytes: ByteArray): DecodedBody {
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            null
        }
        return if (text != null && text.none { character ->
                character.isISOControl() && character != '\r' && character != '\n' && character != '\t'
            }
        ) {
            DecodedBody(text, "utf8")
        } else {
            DecodedBody(Base64.getEncoder().encodeToString(bytes), "base64")
        }
    }

    private inline fun <reified T : Throwable> Throwable.containsCause(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is T) return true
            current = current.cause
        }
        return false
    }

    private data class BoundedBody(val bytes: ByteArray, val truncated: Boolean)

    private data class DecodedBody(val body: String, val encoding: String)

    companion object {
        private const val USER_AGENT = "PocketPilot/0.1"
        private const val MAX_RESPONSE_HEADER_NAMES = 16
        private const val MAX_RESPONSE_HEADER_CHARS = 16 * 1024
        private const val MAX_RESPONSE_HEADER_VALUE_CHARS = 2 * 1024
        private val SAFE_RESPONSE_HEADERS = listOf(
            "Cache-Control",
            "Content-Language",
            "Content-Length",
            "Content-Type",
            "Date",
            "ETag",
            "Expires",
            "Last-Modified",
            "Retry-After",
            "X-Request-Id",
            "X-RateLimit-Limit",
            "X-RateLimit-Remaining",
            "X-RateLimit-Reset",
        )

        internal fun secureClient(): OkHttpClient = OkHttpClient.Builder()
            .dns(PublicOnlyDns())
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .proxy(Proxy.NO_PROXY)
            .build()
    }
}
