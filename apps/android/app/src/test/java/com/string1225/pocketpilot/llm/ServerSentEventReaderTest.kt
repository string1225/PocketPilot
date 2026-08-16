package com.string1225.pocketpilot.llm

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServerSentEventReaderTest {
    @Test
    fun `frames comments multiline data and done sentinel`() {
        val body = """
            : keepalive
            event: message
            data: {"first":
            data: true}

            data: [DONE]

            data: {"late":true}

        """.trimIndent()
        val events = mutableListOf<String>()

        val done = ServerSentEventReader(
            ByteArrayInputStream(body.toByteArray()),
            maximumBytes = 1024,
        ).read(events::add)

        assertTrue(done)
        assertEquals(listOf("{\"first\":\ntrue}"), events)
    }

    @Test
    fun `accepts a clean eof without done`() {
        val events = mutableListOf<String>()
        val done = ServerSentEventReader(
            ByteArrayInputStream("data: {\"ok\":true}".toByteArray()),
            maximumBytes = 1024,
        ).read(events::add)

        assertEquals(false, done)
        assertEquals(listOf("{\"ok\":true}"), events)
    }

    @Test
    fun `fails closed on invalid utf8 and oversized streams`() {
        val invalidUtf8 = byteArrayOf(
            'd'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte(),
            ':'.code.toByte(), ' '.code.toByte(), 0xC3.toByte(), 0x28,
        )
        assertEquals(
            "LLM_INVALID_RESPONSE",
            assertFailsWith<LlmProtocolException> {
                ServerSentEventReader(ByteArrayInputStream(invalidUtf8), 1024).read {}
            }.code,
        )

        assertEquals(
            "LLM_RESPONSE_TOO_LARGE",
            assertFailsWith<LlmProtocolException> {
                ServerSentEventReader(
                    ByteArrayInputStream("data: 123456789\n\n".toByteArray()),
                    maximumBytes = 8,
                ).read {}
            }.code,
        )
    }
}
