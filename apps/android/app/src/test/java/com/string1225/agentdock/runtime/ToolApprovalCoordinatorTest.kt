package com.string1225.agentdock.runtime

import com.string1225.agentdock.model.ToolApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

class ToolApprovalCoordinatorTest {
    @Test
    fun resumesExactlyOnePendingDecision() = runBlocking {
        val coordinator = ToolApprovalCoordinator()
        val request = ToolApprovalRequest(
            id = "call-1",
            runId = "run-1",
            projectId = "project-1",
            toolName = "workspace.delete",
            title = "Approve",
            detail = "notes/a.md",
        )

        val result = async { coordinator.awaitDecision(request) }
        while (coordinator.requests.value.isEmpty()) yield()

        assertEquals(listOf(request), coordinator.requests.value)
        assertTrue(coordinator.resolve(request.id, approved = true))
        assertTrue(result.await())
        assertTrue(coordinator.requests.value.isEmpty())
    }
}
