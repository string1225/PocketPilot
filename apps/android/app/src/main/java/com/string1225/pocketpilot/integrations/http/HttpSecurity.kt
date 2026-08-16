package com.string1225.pocketpilot.integrations.http

import java.net.InetAddress
import java.net.UnknownHostException
import java.nio.charset.StandardCharsets
import java.util.Locale
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Fail-closed validation performed before any DNS or network activity. */
object HttpRequestPolicy {
    const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000L
    const val MAX_TIMEOUT_MILLIS: Long = 120_000L
    const val DEFAULT_RESPONSE_BYTES: Int = 256 * 1024
    const val MAX_RESPONSE_BYTES: Int = 512 * 1024
    const val MAX_REQUEST_BODY_BYTES: Int = 256 * 1024
    const val MAX_URL_CHARS: Int = 4_096

    // Keep every approved header fully visible inside the bounded approval dialog.
    private const val MAX_HEADER_VALUE_CHARS = 512
    private const val MAX_HEADER_BYTES = 1_024
    private val allowedHeaders = setOf("Accept", "Content-Type", "If-Match", "If-None-Match")
    private val blockedHostSuffixes = listOf(
        ".localhost",
        ".localdomain",
        ".local",
        ".internal",
        ".home",
        ".lan",
    )
    private val blockedHosts = setOf(
        "localhost",
        "localhost.localdomain",
        "metadata",
        "metadata.google.internal",
        "metadata.google.com",
        "instance-data",
        "kubernetes.default.svc",
    )

    fun create(
        methodText: String,
        rawUrl: String,
        headers: Map<String, String>,
        body: String?,
        timeoutMillis: Long,
        maxResponseBytes: Int,
        allowInsecureHttp: Boolean,
    ): HttpToolRequest {
        val method = HttpToolMethod.parse(methodText)
        require(rawUrl.isNotBlank() && rawUrl == rawUrl.trim()) { "url must not be blank or padded" }
        require(rawUrl.length <= MAX_URL_CHARS) { "url is too long" }
        require(rawUrl.none { it == '\\' || it.isISOControl() }) { "url contains unsafe characters" }
        val url = rawUrl.toHttpUrlOrNull() ?: throw IllegalArgumentException("url must be absolute")
        require(url.scheme == "https" || url.scheme == "http") { "url must use http or https" }
        require(url.username.isEmpty() && url.password.isEmpty()) { "url must not contain userinfo" }
        require(url.fragment == null) { "url must not contain a fragment" }
        require(url.host.isNotBlank()) { "url must contain a host" }
        require(url.toString().length <= MAX_URL_CHARS) { "normalized url is too long" }
        require(!isIpLiteral(url.host)) { "IP-literal HTTP targets are forbidden" }
        requireHostNameAllowed(url.host)
        require(url.scheme != "http" || allowInsecureHttp) {
            "cleartext HTTP requires allowInsecureHttp=true"
        }
        require(body == null || !method.isReadOnly) { "GET and HEAD requests cannot include a body" }
        val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)?.size ?: 0
        require(bodyBytes <= MAX_REQUEST_BODY_BYTES) { "request body exceeds 256 KiB" }
        require(timeoutMillis in 1_000L..MAX_TIMEOUT_MILLIS) {
            "timeoutMillis must be in 1000..120000"
        }
        require(maxResponseBytes in 1..MAX_RESPONSE_BYTES) {
            "maxResponseBytes must be in 1..524288"
        }
        validateHeaders(headers)
        return HttpToolRequest(
            method = method,
            url = url.toString(),
            scheme = url.scheme,
            host = url.host,
            port = url.port,
            headers = headers.toMap(),
            body = body,
            bodyBytes = bodyBytes,
            timeoutMillis = timeoutMillis,
            maxResponseBytes = maxResponseBytes,
        )
    }

    fun requireHostNameAllowed(host: String) {
        val canonical = host.lowercase(Locale.ROOT).trimEnd('.')
        require(canonical.isNotBlank() && canonical.length <= 253) { "HTTP host is invalid" }
        require(canonical !in blockedHosts && blockedHostSuffixes.none(canonical::endsWith)) {
            "local and metadata hostnames are forbidden"
        }
    }

    private fun validateHeaders(headers: Map<String, String>) {
        require(headers.keys.all(allowedHeaders::contains)) {
            "Only Accept, Content-Type, If-Match, and If-None-Match headers are allowed"
        }
        var encodedBytes = 0L
        for ((name, value) in headers) {
            require(value.isNotEmpty() && value.length <= MAX_HEADER_VALUE_CHARS) {
                "$name header value is invalid"
            }
            require(value.none { character ->
                character.isISOControl() ||
                    character.isSurrogate() ||
                    Character.getType(character) == Character.FORMAT.toInt()
            }) {
                "$name header value contains unsafe display characters"
            }
            encodedBytes += name.toByteArray(StandardCharsets.UTF_8).size
            encodedBytes += value.toByteArray(StandardCharsets.UTF_8).size
        }
        require(encodedBytes <= MAX_HEADER_BYTES) { "HTTP headers exceed 1024 UTF-8 bytes" }
    }

    private fun isIpLiteral(host: String): Boolean {
        if (host.indexOf(':') >= 0) return true
        if (host.all { it.isDigit() || it == '.' }) return true
        val lower = host.lowercase(Locale.ROOT)
        if (lower.matches(Regex("^0x[0-9a-f]+$"))) return true
        return lower.split('.').all { segment ->
            segment.matches(Regex("^(?:0x[0-9a-f]+|[0-9]+)$"))
        }
    }
}

/** Only globally routable unicast addresses are eligible HTTP destinations. */
object PublicNetworkAddressPolicy {
    fun isAllowed(address: InetAddress): Boolean {
        if (
            address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return false
        }
        val bytes = address.address
        return when (bytes.size) {
            4 -> isAllowedIpv4(bytes)
            16 -> isAllowedIpv6(bytes)
            else -> false
        }
    }

    private fun isAllowedIpv4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        val c = bytes[2].toInt() and 0xff
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false // RFC 6598 carrier-grade NAT
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 0 && c == 0 -> false
            a == 192 && b == 0 && c == 2 -> false
            a == 192 && b == 88 && c == 99 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false // benchmark networks
            a == 198 && b == 51 && c == 100 -> false
            a == 203 && b == 0 && c == 113 -> false
            a >= 224 -> false
            else -> true
        }
    }

    private fun isAllowedIpv6(bytes: ByteArray): Boolean {
        // IPv4-compatible and IPv4-mapped forms must be classified as IPv4.
        if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
            return isAllowedIpv4(bytes.copyOfRange(12, 16))
        }
        if (bytes.take(12).all { it == 0.toByte() }) {
            return isAllowedIpv4(bytes.copyOfRange(12, 16))
        }

        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        // Global unicast is 2000::/3. Reject all transition/reserved ranges by default.
        if (first !in 0x20..0x3f) return false
        if (first == 0x20 && second == 0x01) {
            val third = bytes[2].toInt() and 0xff
            val fourth = bytes[3].toInt() and 0xff
            if (third == 0x00 && fourth == 0x00) return false // Teredo
            if (third == 0x00 && fourth == 0x02) return false // benchmarking
            if (third == 0x0d && fourth == 0xb8) return false // documentation
            if (third == 0x00 && ((fourth and 0xf0) == 0x10 || (fourth and 0xf0) == 0x20)) {
                return false // ORCHIDv1/v2
            }
        }
        if (first == 0x20 && second == 0x02) return false // 6to4 embeds another address
        return true
    }
}

internal class HttpTargetBlockedException(message: String) : UnknownHostException(message)

/**
 * OkHttp connects only to the exact addresses returned here. Validating every
 * final answer (including CNAME targets) closes the resolve-check-connect
 * rebinding gap and rejects mixed public/private answer sets as a whole.
 */
class PublicOnlyDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        try {
            HttpRequestPolicy.requireHostNameAllowed(hostname)
        } catch (_: IllegalArgumentException) {
            throw HttpTargetBlockedException("HTTP target hostname is forbidden")
        }
        val addresses = delegate.lookup(hostname)
        if (addresses.isEmpty() || addresses.any { !PublicNetworkAddressPolicy.isAllowed(it) }) {
            throw HttpTargetBlockedException("HTTP target did not resolve exclusively to public addresses")
        }
        return addresses.distinctBy { it.address.toList() }
    }
}
