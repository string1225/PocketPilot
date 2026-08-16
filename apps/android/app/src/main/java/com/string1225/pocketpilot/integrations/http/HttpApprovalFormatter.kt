package com.string1225.pocketpilot.integrations.http

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Produces a complete, bounded, display-safe one-time HTTP approval summary. */
object HttpApprovalFormatter {
    const val MAX_DETAIL_CHARS: Int = 8 * 1024

    private const val MAX_PREVIEW_CODE_POINTS = 512
    private const val MAX_ESCAPED_PREVIEW_CHARS = 2 * 1024

    fun format(request: HttpToolRequest): String {
        val body = request.body.orEmpty()
        val preview = escapedPreview(body)
        val cleartextWarning = if (request.scheme == "http") {
            "\nWarning: cleartext HTTP can be read or changed in transit."
        } else {
            ""
        }
        return buildString {
            append("Method: ${request.method.name}\n")
            append("Target: ${request.scheme}://${request.host}:${request.port}\n")
            // HttpRequestPolicy already rejected controls, userinfo, and fragments.
            // Never abbreviate this line: path and query determine the approved action.
            append("Target URL: ${request.url}\n")
            append("Request headers:")
            if (request.headers.isEmpty()) {
                append(" (none)\n")
            } else {
                append('\n')
                request.headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                    append("  ")
                    append(name)
                    append(": ")
                    append(value)
                    append('\n')
                }
            }
            append("Request body: ${request.bodyBytes} UTF-8 bytes\n")
            append("Body SHA-256: ${sha256(body)}\n")
            append("Body preview (escaped): \"")
            append(preview.text)
            append('"')
            if (preview.truncated) append(" [preview truncated]")
            append("\nTimeout: ${request.timeoutMillis} ms\n")
            append("Response limit: ${request.maxResponseBytes} bytes")
            append(cleartextWarning)
        }.also { detail ->
            require(detail.length <= MAX_DETAIL_CHARS) { "HTTP approval request is too large" }
        }
    }

    private fun sha256(body: String): String {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
        } finally {
            bytes.fill(0)
        }
    }

    private fun escapedPreview(value: String): SafePreview {
        val escaped = StringBuilder(minOf(value.length, MAX_ESCAPED_PREVIEW_CHARS))
        var offset = 0
        var codePoints = 0
        while (offset < value.length && codePoints < MAX_PREVIEW_CODE_POINTS) {
            val codePoint = Character.codePointAt(value, offset)
            val encoded = escape(codePoint)
            if (escaped.length + encoded.length > MAX_ESCAPED_PREVIEW_CHARS) break
            escaped.append(encoded)
            offset += Character.charCount(codePoint)
            codePoints += 1
        }
        return SafePreview(
            text = escaped.toString(),
            truncated = offset < value.length,
        )
    }

    private fun escape(codePoint: Int): String = when (codePoint) {
        '\\'.code -> "\\\\"
        '"'.code -> "\\\""
        '\r'.code -> "\\r"
        '\n'.code -> "\\n"
        '\t'.code -> "\\t"
        0x2028 -> "\\u2028"
        0x2029 -> "\\u2029"
        else -> if (
            codePoint in 0xd800..0xdfff ||
            Character.isISOControl(codePoint) ||
            Character.getType(codePoint) == Character.FORMAT.toInt()
        ) {
            if (codePoint <= 0xffff) {
                "\\u${codePoint.toString(16).padStart(4, '0')}"
            } else {
                "\\u{${codePoint.toString(16)}}"
            }
        } else {
            String(Character.toChars(codePoint))
        }
    }

    private fun ByteArray.toLowerHex(): String {
        val digits = "0123456789abcdef"
        val result = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            result[index * 2] = digits[value ushr 4]
            result[index * 2 + 1] = digits[value and 0x0f]
        }
        return result.concatToString()
    }

    private data class SafePreview(
        val text: String,
        val truncated: Boolean,
    )
}
