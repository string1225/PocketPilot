package com.string1225.pocketpilot.integrations.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpApprovalFormatterTest {
    @Test
    fun includesTheCompleteNormalizedPathAndQueryWithoutTruncation() {
        val prefix = "https://example.com:8443/v1/destructive/action?scope="
        val query = "a".repeat(HttpRequestPolicy.MAX_URL_CHARS - prefix.length)
        val rawUrl = prefix + query
        val request = request(url = rawUrl, body = "\u202e".repeat(512))

        val detail = HttpApprovalFormatter.format(request)
        val displayedUrl = detail.substringAfter("Target URL: ").substringBefore('\n')

        assertEquals(HttpRequestPolicy.MAX_URL_CHARS, rawUrl.length)
        assertEquals(request.url, displayedUrl)
        assertTrue(displayedUrl.endsWith("scope=$query"))
        assertFalse(detail.contains('\u202e'))
        assertTrue(detail.length <= HttpApprovalFormatter.MAX_DETAIL_CHARS)
    }

    @Test
    fun safelyEscapesInvisibleAndDirectionalBodyCharactersAndHashesTheFullBody() {
        val body = "line1\nline2\r\t\"\\\u202e\u2066\u0000tail"
        val request = request(body = body)

        val detail = HttpApprovalFormatter.format(request)
        val preview = detail.substringAfter("Body preview (escaped): ").substringBefore('\n')
        val expectedHash = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(
            "\"line1\\nline2\\r\\t\\\"\\\\\\u202e\\u2066\\u0000tail\"",
            preview,
        )
        assertFalse(preview.contains('\u202e'))
        assertFalse(preview.contains('\u2066'))
        assertFalse(preview.contains('\u0000'))
        assertFalse(preview.contains('\r'))
        assertFalse(preview.contains('\t'))
        assertTrue(detail.contains("Request body: ${request.bodyBytes} UTF-8 bytes"))
        assertTrue(detail.contains("Request headers:\n  Content-Type: application/json"))
        assertTrue(detail.contains("Body SHA-256: $expectedHash"))
        assertTrue(detail.length <= HttpApprovalFormatter.MAX_DETAIL_CHARS)
    }

    @Test
    fun boundsPreviewWhileHashingTheEntireMaximumBody() {
        val body = "a".repeat(HttpRequestPolicy.MAX_REQUEST_BODY_BYTES)
        val request = request(body = body)

        val detail = HttpApprovalFormatter.format(request)
        val preview = detail.substringAfter("Body preview (escaped): ").substringBefore('\n')

        assertTrue(preview.endsWith(" [preview truncated]"))
        assertTrue(preview.length < 1_024)
        assertTrue(detail.contains("Request body: 262144 UTF-8 bytes"))
        assertTrue(detail.length <= HttpApprovalFormatter.MAX_DETAIL_CHARS)
    }

    private fun request(
        url: String = "https://example.com/api?confirmed=true",
        body: String,
    ): HttpToolRequest = HttpRequestPolicy.create(
        methodText = "POST",
        rawUrl = url,
        headers = mapOf("Content-Type" to "application/json"),
        body = body,
        timeoutMillis = HttpRequestPolicy.DEFAULT_TIMEOUT_MILLIS,
        maxResponseBytes = HttpRequestPolicy.DEFAULT_RESPONSE_BYTES,
        allowInsecureHttp = false,
    )
}
