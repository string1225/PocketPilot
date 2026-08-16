package com.string1225.pocketpilot.runtime

/** Version shared by the native host and the bundled TypeScript runtime. */
const val POCKET_PILOT_RUNTIME_BRIDGE_VERSION: Int = 1

/** Raised when a message targets a bridge protocol the app does not implement. */
class UnsupportedRuntimeBridgeVersionException(
    val actualVersion: Int,
) : IllegalArgumentException(
    "Unsupported PocketPilot runtime bridge version $actualVersion; expected $POCKET_PILOT_RUNTIME_BRIDGE_VERSION",
)

/** Pure version guard kept separate from Android APIs so it can be unit tested. */
object RuntimeBridgeVersion {
    fun requireSupported(version: Int): Int {
        if (version != POCKET_PILOT_RUNTIME_BRIDGE_VERSION) {
            throw UnsupportedRuntimeBridgeVersionException(version)
        }
        return version
    }
}

/** A validated request emitted by the TypeScript Tool Runtime. */
data class NativeToolRequest(
    val id: String,
    val runId: String,
    val projectId: String,
    val name: String,
    /** Canonical JSON for the protocol's unconstrained `arguments` value. */
    val argumentsJson: String,
    /** Native-only observer; it is never decoded from untrusted JavaScript. */
    val progressSink: NativeToolProgressSink = NoOpNativeToolProgressSink,
)

fun interface NativeToolProgressSink {
    fun emit(payloadJson: String)
}

private object NoOpNativeToolProgressSink : NativeToolProgressSink {
    override fun emit(payloadJson: String) = Unit
}

/** A structured Agent event plus its complete, lossless JSON payload. */
data class AgentRuntimeEvent(
    val id: String,
    val runId: String,
    val projectId: String,
    val type: String,
    val payloadJson: String,
)

/** JSON representation of the TypeScript ToolResult union. */
data class NativeToolResult(
    val payloadJson: String,
)

/** Error deliberately returned by a native Tool implementation. */
class ToolDispatchException(
    val code: String,
    override val message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init {
        require(code.isNotBlank()) { "Tool error code must not be blank" }
        require(message.isNotBlank()) { "Tool error message must not be blank" }
    }
}

/** A protocol or dispatch problem suitable for the Android event timeline. */
data class RuntimeBridgeError(
    val code: String,
    val message: String,
    val requestId: String? = null,
    val runId: String? = null,
    val projectId: String? = null,
)

internal data class RuntimeRequestContext(
    val id: String,
    val runId: String,
    val projectId: String,
)

/**
 * Native Tool seam. Android repositories are intentionally not referenced by
 * the bridge; the composition root supplies an implementation for the active
 * Project.
 */
interface ToolRequestDispatcher {
    suspend fun dispatch(request: NativeToolRequest): NativeToolResult
}

/** Receives structured runtime events and non-fatal bridge diagnostics. */
interface RuntimeEventSink {
    fun onEvent(event: AgentRuntimeEvent)

    fun onBridgeError(error: RuntimeBridgeError) = Unit
}

internal object NoOpRuntimeEventSink : RuntimeEventSink {
    override fun onEvent(event: AgentRuntimeEvent) = Unit
}

internal sealed interface RuntimeInboundMessage {
    data class ToolRequest(val request: NativeToolRequest) : RuntimeInboundMessage

    data class Event(val event: AgentRuntimeEvent) : RuntimeInboundMessage
}

internal class RuntimeProtocolException(
    val code: String,
    override val message: String,
    val context: RuntimeRequestContext? = null,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
