package com.string1225.pocketpilot.integrations.ssh

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoundedSshStreamCollectorTest {
    @Test
    fun `collector retains bounded prefix while draining entire stream`() {
        val collector = BoundedSshStreamCollector(
            ByteArrayInputStream("0123456789".toByteArray()),
            limit = 4,
        )

        collector.call()

        assertEquals("0123", collector.snapshot().text)
        assertTrue(collector.snapshot().truncated)
    }

    @Test
    fun `collector reports complete output below limit`() {
        val collector = BoundedSshStreamCollector(
            ByteArrayInputStream("complete".toByteArray()),
            limit = 64,
        )

        collector.call()

        assertEquals("complete", collector.snapshot().text)
        assertFalse(collector.snapshot().truncated)
    }
}
