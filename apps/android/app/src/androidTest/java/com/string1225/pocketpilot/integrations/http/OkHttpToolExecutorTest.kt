package com.string1225.pocketpilot.integrations.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.net.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OkHttpToolExecutorTest {
    @Test
    fun productionClientDisablesRedirectsRetriesCookiesAndProxies() {
        val client = OkHttpToolExecutor.secureClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.dns is PublicOnlyDns)
    }

    @Test
    fun boundsBodyAndReturnsOnlySafeHeaders() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "text/plain")
                    .header("Set-Cookie", "secret=session")
                    .body("abcdef".toResponseBody("text/plain".toMediaType()))
                    .build()
            }
            .build()
        val response = OkHttpToolExecutor(client).execute(
            HttpRequestPolicy.create(
                methodText = "GET",
                rawUrl = "https://example.com/",
                headers = emptyMap(),
                body = null,
                timeoutMillis = HttpRequestPolicy.DEFAULT_TIMEOUT_MILLIS,
                maxResponseBytes = 4,
                allowInsecureHttp = false,
            ),
        )
        assertEquals(200, response.status)
        assertEquals("abcd", response.body)
        assertEquals("utf8", response.bodyEncoding)
        assertTrue(response.truncated)
        assertEquals(listOf("text/plain"), response.headers["content-type"])
        assertFalse(response.headers.containsKey("set-cookie"))
    }

    @Test
    fun encodesBinaryAndInvalidUtf8BodiesWithoutLoss() = runBlocking {
        val bytes = byteArrayOf(0xff.toByte(), 0x00)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()
        val response = OkHttpToolExecutor(client).execute(request())
        assertEquals("/wA=", response.body)
        assertEquals("base64", response.bodyEncoding)
        assertFalse(response.truncated)
    }

    @Test
    fun enforcesTotalCallTimeout() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Thread.sleep(1_250)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("late".toResponseBody())
                    .build()
            }
            .build()
        val failure = runCatching {
            OkHttpToolExecutor(client).execute(request(timeoutMillis = 1_000))
        }.exceptionOrNull()
        assertTrue(failure is HttpToolException)
        assertEquals("HTTP_TIMEOUT", (failure as HttpToolException).code)
        assertTrue(failure.retryable)
    }

    @Test
    fun coroutineCancellationCancelsTheOkHttpCall() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val call = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                call.set(chain.call())
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                throw java.io.IOException("cancelled test call")
            }
            .build()
        val running = async(start = CoroutineStart.UNDISPATCHED) {
            OkHttpToolExecutor(client).execute(request())
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        running.cancelAndJoin()
        withTimeout(1_000) {
            while (call.get()?.isCanceled() != true) kotlinx.coroutines.yield()
        }
        release.countDown()
        assertTrue(call.get().isCanceled())
    }

    private fun request(
        timeoutMillis: Long = HttpRequestPolicy.DEFAULT_TIMEOUT_MILLIS,
        maxResponseBytes: Int = HttpRequestPolicy.DEFAULT_RESPONSE_BYTES,
    ): HttpToolRequest = HttpRequestPolicy.create(
        methodText = "GET",
        rawUrl = "https://example.com/",
        headers = emptyMap(),
        body = null,
        timeoutMillis = timeoutMillis,
        maxResponseBytes = maxResponseBytes,
        allowInsecureHttp = false,
    )
}
