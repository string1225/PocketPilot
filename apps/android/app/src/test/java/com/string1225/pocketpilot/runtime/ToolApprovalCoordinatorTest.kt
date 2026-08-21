package com.string1225.pocketpilot.runtime

import com.string1225.pocketpilot.model.ToolApprovalRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
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

    @Test
    fun remembersOneToolOnlyForTheCurrentRun() = runBlocking {
        val coordinator = ToolApprovalCoordinator()
        val first = request("call-1", "run-1", "ssh.execute")
        val decision = async { coordinator.awaitDecision(first) }
        while (coordinator.requests.value.isEmpty()) yield()
        assertTrue(coordinator.resolve(first.id, approved = true, rememberForRun = true))
        assertTrue(decision.await())

        assertTrue(coordinator.awaitDecision(request("call-2", "run-1", "ssh.execute")))

        val otherTool = async { coordinator.awaitDecision(request("call-3", "run-1", "git.push")) }
        while (coordinator.requests.value.isEmpty()) yield()
        assertTrue(coordinator.resolve("call-3", approved = false))
        assertFalse(otherTool.await())

        coordinator.cancelRun("run-1")
        val nextRun = async { coordinator.awaitDecision(request("call-4", "run-2", "ssh.execute")) }
        while (coordinator.requests.value.isEmpty()) yield()
        assertTrue(coordinator.resolve("call-4", approved = false))
        assertFalse(nextRun.await())
    }

    private fun request(id: String, runId: String, toolName: String) = ToolApprovalRequest(
        id = id,
        runId = runId,
        projectId = "project-1",
        toolName = toolName,
        title = "Approve",
        detail = "details",
    )
}
