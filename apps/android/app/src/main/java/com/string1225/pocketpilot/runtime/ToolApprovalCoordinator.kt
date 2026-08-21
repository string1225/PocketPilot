package com.string1225.pocketpilot.runtime

import com.string1225.pocketpilot.model.ToolApprovalRequest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Suspends one Native Tool call until the user explicitly allows or rejects it. */
class ToolApprovalCoordinator {
    private val pendingById = ConcurrentHashMap<String, PendingApproval>()
    private val allowedToolsByRun = ConcurrentHashMap<String, MutableSet<String>>()
    private val mutableRequests = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

    val requests: StateFlow<List<ToolApprovalRequest>> = mutableRequests.asStateFlow()

    suspend fun awaitDecision(request: ToolApprovalRequest): Boolean {
        require(request.id.isNotBlank()) { "Approval id must not be blank" }
        if (allowedToolsByRun[request.runId]?.contains(request.toolName) == true) return true
        val pending = PendingApproval(request, CompletableDeferred())
        check(pendingById.putIfAbsent(request.id, pending) == null) {
            "Approval request is already pending: ${request.id}"
        }
        mutableRequests.update { current -> current + request }
        return try {
            pending.decision.await()
        } finally {
            pendingById.remove(request.id, pending)
            mutableRequests.update { current -> current.filterNot { it.id == request.id } }
        }
    }

    fun resolve(requestId: String, approved: Boolean, rememberForRun: Boolean = false): Boolean {
        val selected = pendingById[requestId] ?: return false
        if (approved && rememberForRun) {
            allowedToolsByRun.compute(selected.request.runId) { _, existing ->
                (existing ?: ConcurrentHashMap.newKeySet()).also { it += selected.request.toolName }
            }
            pendingById.values
                .filter {
                    it.request.runId == selected.request.runId &&
                        it.request.toolName == selected.request.toolName
                }
                .forEach { it.decision.complete(true) }
            return true
        }
        return selected.decision.complete(approved)
    }

    fun cancelRun(runId: String) {
        allowedToolsByRun.remove(runId)
        pendingById.values
            .filter { it.request.runId == runId }
            .forEach { pending ->
                pending.decision.cancel(CancellationException("Agent Run was cancelled"))
            }
    }

    private data class PendingApproval(
        val request: ToolApprovalRequest,
        val decision: CompletableDeferred<Boolean>,
    )
}
