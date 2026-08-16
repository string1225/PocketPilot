package com.string1225.pocketpilot.integrations.http

import java.net.InetAddress
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSecurityTest {
    @Test
    fun acceptsBoundedHttpsAndExplicitCleartextRequests() {
        val https = request(url = "https://example.com/v1?q=ok")
        assertFalse(https.requiresApproval)
        assertEquals("example.com", https.host)
        assertEquals(443, https.port)

        val http = request(url = "http://example.com/api", allowInsecureHttp = true)
        assertTrue(http.requiresApproval)
        assertEquals(80, http.port)
    }

    @Test
    fun rejectsUnsupportedOrAmbiguousUrlsBeforeDns() {
        listOf(
            "file:///etc/passwd",
            "https://user:secret@example.com/",
            "https://example.com/#fragment",
            "https://127.0.0.1/",
            "https://[::1]/",
            "https://2130706433/",
            "https://metadata.google.internal/",
            "https://service.local/",
            "https://example.com\\@127.0.0.1/",
        ).forEach { url ->
            assertThrows(url, IllegalArgumentException::class.java) { request(url = url) }
        }
        assertThrows(IllegalArgumentException::class.java) { request(url = "http://example.com/") }
    }

    @Test
    fun permitsOnlyNonsensitiveFixedHeadersAndBoundsBodies() {
        val accepted = request(
            headers = mapOf("Accept" to "application/json", "If-None-Match" to "etag"),
            method = "POST",
            body = "{}",
        )
        assertEquals(2, accepted.bodyBytes)

        listOf("Authorization", "Cookie", "Proxy-Authorization", "Host", "X-Api-Key").forEach { name ->
            assertThrows(name, IllegalArgumentException::class.java) {
                request(headers = mapOf(name to "secret"))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(headers = mapOf("Accept" to "text/plain\r\nHost: internal"))
        }
        listOf("text/plain\tspoof", "text/plain\u202ejson", "a".repeat(513)).forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                request(headers = mapOf("Accept" to value))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(method = "GET", body = "not allowed")
        }
        assertThrows(IllegalArgumentException::class.java) {
            request(method = "POST", body = "界".repeat(HttpRequestPolicy.MAX_REQUEST_BODY_BYTES))
        }
    }

    @Test
    fun rejectsEveryNonpublicIpv4Class() {
        val denied = listOf(
            "0.0.0.0",
            "10.0.0.1",
            "100.64.0.1",
            "127.0.0.1",
            "169.254.169.254",
            "172.16.0.1",
            "192.0.0.9",
            "192.0.2.1",
            "192.168.1.1",
            "198.18.0.1",
            "198.51.100.1",
            "203.0.113.1",
            "224.0.0.1",
            "255.255.255.255",
        )
        denied.forEach { literal ->
            assertFalse(literal, PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName(literal)))
        }
        assertTrue(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("8.8.8.8")))
    }

    @Test
    fun rejectsPrivateMappedAndReservedIpv6() {
        val privateMapped = ByteArray(16).also { bytes ->
            bytes[10] = 0xff.toByte()
            bytes[11] = 0xff.toByte()
            bytes[12] = 10
            bytes[15] = 1
        }
        val publicMapped = privateMapped.copyOf().also { bytes ->
            bytes[12] = 8
            bytes[13] = 8
            bytes[14] = 8
            bytes[15] = 8
        }
        assertFalse(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByAddress(privateMapped)))
        assertTrue(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByAddress(publicMapped)))
        assertFalse(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("fc00::1")))
        assertFalse(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("fe80::1")))
        assertFalse(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("2001:db8::1")))
        assertFalse(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("2002:0808:0808::1")))
        assertTrue(PublicNetworkAddressPolicy.isAllowed(InetAddress.getByName("2606:4700:4700::1111")))
    }

    @Test
    fun publicDnsPinsFinalAnswersAndRejectsMixedSets() {
        val public = InetAddress.getByName("8.8.8.8")
        val private = InetAddress.getByName("10.0.0.1")
        val pinned = PublicOnlyDns(Dns { listOf(public) }).lookup("example.com")
        assertEquals(1, pinned.size)
        assertSame(public, pinned.single())

        assertThrows(HttpTargetBlockedException::class.java) {
            PublicOnlyDns(Dns { listOf(public, private) }).lookup("example.com")
        }
    }

    private fun request(
        method: String = "GET",
        url: String = "https://example.com/",
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        timeoutMillis: Long = HttpRequestPolicy.DEFAULT_TIMEOUT_MILLIS,
        maxResponseBytes: Int = HttpRequestPolicy.DEFAULT_RESPONSE_BYTES,
        allowInsecureHttp: Boolean = false,
    ): HttpToolRequest = HttpRequestPolicy.create(
        methodText = method,
        rawUrl = url,
        headers = headers,
        body = body,
        timeoutMillis = timeoutMillis,
        maxResponseBytes = maxResponseBytes,
        allowInsecureHttp = allowInsecureHttp,
    )
}
