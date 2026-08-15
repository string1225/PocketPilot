package com.string1225.agentdock.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RuntimeBridgeVersionTest {
    @Test
    fun `accepts protocol version one`() {
        assertEquals(1, RuntimeBridgeVersion.requireSupported(1))
    }

    @Test
    fun `rejects older protocol versions`() {
        val error = assertFailsWith<UnsupportedRuntimeBridgeVersionException> {
            RuntimeBridgeVersion.requireSupported(0)
        }

        assertEquals(0, error.actualVersion)
    }

    @Test
    fun `rejects newer protocol versions`() {
        val error = assertFailsWith<UnsupportedRuntimeBridgeVersionException> {
            RuntimeBridgeVersion.requireSupported(2)
        }

        assertEquals(2, error.actualVersion)
    }
}
