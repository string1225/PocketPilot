package com.string1225.agentdock.runtime

import com.string1225.agentdock.model.ToolApprovalRequest
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
    private val mutableRequests = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())

    val requests: StateFlow<List<ToolApprovalRequest>> = mutableRequests.asStateFlow()

    suspend fun awaitDecision(request: ToolApprovalRequest): Boolean {
        require(request.id.isNotBlank()) { "Approval id must not be blank" }
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

    fun resolve(requestId: String, approved: Boolean): Boolean =
        pendingById[requestId]?.decision?.complete(approved) == true

    fun cancelRun(runId: String) {
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
