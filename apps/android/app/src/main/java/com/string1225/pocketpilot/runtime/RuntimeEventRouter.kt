package com.string1225.pocketpilot.runtime

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class RuntimeEventRouter : RuntimeEventSink, Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messages = Channel<RouterMessage>(Channel.UNLIMITED)
    private val listeners = ConcurrentHashMap<String, Listener>()
    private val ready = CompletableDeferred<Unit>()

    init {
        scope.launch {
            for (message in messages) {
                runCatching {
                    when (message) {
                        is RouterMessage.Event -> routeEvent(message.value)
                        is RouterMessage.Error -> routeError(message.value)
                    }
                }
            }
        }
    }

    override fun onEvent(event: AgentRuntimeEvent) {
        messages.trySend(RouterMessage.Event(event))
    }

    override fun onBridgeError(error: RuntimeBridgeError) {
        messages.trySend(RouterMessage.Error(error))
    }

    suspend fun awaitReady(timeoutMillis: Long = DEFAULT_READY_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { ready.await() }
    }

    fun subscribe(
        runId: String,
        projectId: String,
        onEvent: (AgentRuntimeEvent) -> Unit,
        onError: (RuntimeBridgeError) -> Unit,
    ): Closeable {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        require(projectId.isNotBlank()) { "Project id must not be blank" }
        val listener = Listener(projectId, onEvent, onError)
        check(listeners.putIfAbsent(runId, listener) == null) { "Run already has a runtime listener: $runId" }
        return Closeable { listeners.remove(runId, listener) }
    }

    override fun close() {
        messages.close()
        listeners.clear()
        scope.cancel()
    }

    private fun routeEvent(event: AgentRuntimeEvent) {
        if (event.type == "runtime.ready") {
            val readyPayload = runCatching { JSONObject(event.payloadJson) }.getOrNull()
            val protocolVersion = readyPayload?.optInt("protocolVersion", -1) ?: -1
            val runtimeVersion = readyPayload?.optString("runtimeVersion").orEmpty()
            if (protocolVersion == POCKET_PILOT_RUNTIME_BRIDGE_VERSION && runtimeVersion.isNotBlank()) {
                ready.complete(Unit)
            } else {
                ready.completeExceptionally(
                    IllegalStateException("TypeScript Runtime reported an incompatible ready handshake"),
                )
            }
            return
        }
        val listener = listeners[event.runId] ?: return
        if (event.projectId != listener.projectId) {
            notifyError(
                listener,
                RuntimeBridgeError(
                    code = "context_mismatch",
                    message = "Runtime event project ${event.projectId} does not match subscribed project ${listener.projectId}",
                    requestId = event.id,
                    runId = event.runId,
                    projectId = event.projectId,
                ),
            )
            return
        }
        runCatching { listener.onEvent(event) }.onFailure { error ->
            notifyError(
                listener,
                RuntimeBridgeError(
                    code = "runtime_event_consumer_failed",
                    message = error.message ?: "Runtime event consumer failed",
                    requestId = event.id,
                    runId = event.runId,
                    projectId = event.projectId,
                ),
            )
        }
    }

    private fun routeError(error: RuntimeBridgeError) {
        if (error.runId == null) {
            if (error.code in FATAL_RUNTIME_ERROR_CODES && !ready.isCompleted) {
                ready.completeExceptionally(IllegalStateException(error.message))
            }
            listeners.values.toList().forEach { listener -> notifyError(listener, error) }
            return
        }
        listeners[error.runId]?.let { listener -> notifyError(listener, error) }
    }

    private fun notifyError(listener: Listener, error: RuntimeBridgeError) {
        runCatching { listener.onError(error) }
    }

    private data class Listener(
        val projectId: String,
        val onEvent: (AgentRuntimeEvent) -> Unit,
        val onError: (RuntimeBridgeError) -> Unit,
    )

    private sealed interface RouterMessage {
        data class Event(val value: AgentRuntimeEvent) : RouterMessage
        data class Error(val value: RuntimeBridgeError) : RouterMessage
    }

    companion object {
        private const val DEFAULT_READY_TIMEOUT_MILLIS = 15_000L
        private val FATAL_RUNTIME_ERROR_CODES = setOf(
            "runtime_page_load_failed",
            "runtime_renderer_gone",
            "runtime_ssl_error",
        )
    }
}
