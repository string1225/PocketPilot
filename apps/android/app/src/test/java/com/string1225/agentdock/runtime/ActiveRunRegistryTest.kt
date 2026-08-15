package com.string1225.agentdock.runtime

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ActiveRunRegistryTest {
    @Test
    fun bindsOneRunToExactlyOneProjectUntilRevoked() {
        val registry = ActiveRunRegistry()
        registry.register("run-1", "project-1")

        assertTrue(registry.authorizes("run-1", "project-1"))
        assertFalse(registry.authorizes("run-1", "project-2"))
        assertFailsWith<IllegalStateException> { registry.register("run-1", "project-2") }
        assertTrue(registry.revoke("run-1", "project-1"))
        assertFalse(registry.authorizes("run-1", "project-1"))
    }
}
