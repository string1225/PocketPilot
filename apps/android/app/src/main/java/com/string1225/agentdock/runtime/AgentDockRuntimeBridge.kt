package com.string1225.agentdock.runtime

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Hosts the bundled TypeScript runtime in a locked-down WebView.
 *
 * JavaScript can only enter Native through
 * `AgentDockNativeBridge.postMessage(envelopeJson)`. Native starts and cancels
 * runs through direct runtime calls, while Tool replies return through the
 * versioned `window.AgentDockRuntime.receive(...)` envelope.
 */
class AgentDockRuntimeBridge(
    private val webView: WebView,
    private val toolDispatcher: ToolRequestDispatcher,
    private val eventSink: RuntimeEventSink = NoOpRuntimeEventSink,
) : Closeable {
    companion object {
        const val JAVASCRIPT_BRIDGE_NAME: String = "AgentDockNativeBridge"
    }

    private val closed = AtomicBoolean(false)
    private val webViewFailed = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bridgeJob = SupervisorJob()
    private val bridgeScope = CoroutineScope(bridgeJob + Dispatchers.Main.immediate)
    private val toolJobs = RuntimeToolJobTracker()
    private val javascriptBridge = AgentDockNativeBridge(::onJavascriptMessage)

    init {
        onMainThread {
            configureRuntimeWebView(webView, ::onWebViewBridgeError)
            webView.addJavascriptInterface(javascriptBridge, JAVASCRIPT_BRIDGE_NAME)
        }
    }

    /** Loads one APK asset from the bridge's private local origin. */
    fun loadAsset(assetPath: String) {
        val url = RuntimeAssetUrlPolicy.urlFor(assetPath)
        onMainThread {
            if (!closed.get()) webView.loadUrl(url)
        }
    }

    /** Fire-and-forget start; progress and completion return as `event` messages. */
    fun start(requestJson: String) {
        val normalizedRequest = RuntimeEnvelopeCodec.normalizeStartRequest(requestJson)
        evaluateJavascript(
            RuntimeJavascriptCalls.start(JSONObject.quote(normalizedRequest)),
        )
    }

    /** Asks the TypeScript runtime to cancel its currently active run. */
    fun cancel(runId: String) {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        toolJobs.cancel(runId, "Agent run was cancelled")
        evaluateJavascript(RuntimeJavascriptCalls.cancel(JSONObject.quote(runId)))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        toolJobs.cancelAll("Runtime bridge was closed")
        bridgeScope.cancel()
        onMainThread {
            webView.removeJavascriptInterface(JAVASCRIPT_BRIDGE_NAME)
            webView.stopLoading()
        }
    }

    private fun onJavascriptMessage(envelopeJson: String) {
        if (closed.get()) return
        val message = try {
            RuntimeEnvelopeCodec.decodeInbound(envelopeJson)
        } catch (error: RuntimeProtocolException) {
            reportBridgeError(
                RuntimeBridgeError(
                    code = error.code,
                    message = error.message,
                    requestId = error.context?.id,
                    runId = error.context?.runId,
                    projectId = error.context?.projectId,
                ),
            )
            error.context?.let { context ->
                sendToolError(context, error.code, error.message)
            }
            return
        }

        onMainThread {
            if (closed.get()) return@onMainThread
            when (message) {
                is RuntimeInboundMessage.Event -> eventSink.onEvent(message.event)
                is RuntimeInboundMessage.ToolRequest -> dispatchTool(message.request)
            }
        }
    }

    private fun dispatchTool(request: NativeToolRequest) {
        val job = bridgeScope.launch(start = CoroutineStart.LAZY) {
            try {
                val result = toolDispatcher.dispatch(request)
                currentCoroutineContext().ensureActive()
                val envelope = RuntimeEnvelopeCodec.encodeToolResult(request, result.payloadJson)
                sendEnvelope(envelope)
            } catch (error: ToolDispatchException) {
                sendToolError(request.context(), error.code, error.message)
            } catch (error: RuntimeProtocolException) {
                sendToolError(request.context(), error.code, error.message)
                reportBridgeError(
                    RuntimeBridgeError(
                        error.code,
                        error.message,
                        request.id,
                        request.runId,
                        request.projectId,
                    ),
                )
            } catch (error: CancellationException) {
                if (!closed.get()) {
                    sendToolError(
                        request.context(),
                        "tool_cancelled",
                        "Native Tool execution was cancelled",
                    )
                }
            } catch (error: Exception) {
                val message = error.message?.takeIf(String::isNotBlank)
                    ?: "Native Tool execution failed"
                sendToolError(request.context(), "native_tool_failure", message)
                reportBridgeError(
                    RuntimeBridgeError(
                        "native_tool_failure",
                        message,
                        request.id,
                        request.runId,
                        request.projectId,
                    ),
                )
            }
        }
        toolJobs.track(request.runId, job)
        job.start()
    }

    private fun sendToolError(context: RuntimeRequestContext, code: String, message: String) {
        sendEnvelope(RuntimeEnvelopeCodec.encodeToolError(context, code, message))
    }

    private fun sendEnvelope(envelopeJson: String) {
        evaluateJavascript(
            RuntimeJavascriptCalls.receive(JSONObject.quote(envelopeJson)),
        )
    }

    private fun reportBridgeError(error: RuntimeBridgeError) {
        onMainThread {
            if (!closed.get()) {
                runCatching { eventSink.onBridgeError(error) }
            }
        }
    }

    private fun onWebViewBridgeError(error: RuntimeBridgeError) {
        if (!webViewFailed.compareAndSet(false, true)) return
        toolJobs.cancelAll("Runtime WebView became unavailable")
        reportBridgeError(error)
    }

    private fun evaluateJavascript(script: String) {
        onMainThread {
            if (!closed.get() && !webViewFailed.get()) webView.evaluateJavascript(script, null)
        }
    }

    private inline fun onMainThread(crossinline action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post { action() }
        }
    }

    private fun NativeToolRequest.context(): RuntimeRequestContext =
        RuntimeRequestContext(id, runId, projectId)
}

/** Thread-safe ownership of unfinished native Tool jobs, grouped by Agent run. */
internal class RuntimeToolJobTracker {
    private val lock = Any()
    private val jobsByRunId = mutableMapOf<String, MutableSet<Job>>()

    fun track(runId: String, job: Job) {
        require(runId.isNotBlank()) { "Run id must not be blank" }
        synchronized(lock) {
            jobsByRunId.getOrPut(runId) { mutableSetOf() }.add(job)
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                val jobs = jobsByRunId[runId] ?: return@synchronized
                jobs.remove(job)
                if (jobs.isEmpty()) jobsByRunId.remove(runId)
            }
        }
    }

    fun cancel(runId: String, message: String) {
        val jobs = synchronized(lock) { jobsByRunId.remove(runId)?.toList().orEmpty() }
        jobs.forEach { job -> job.cancel(CancellationException(message)) }
    }

    fun cancelAll(message: String) {
        val jobs = synchronized(lock) {
            jobsByRunId.values.flatMap { it.toList() }.also { jobsByRunId.clear() }
        }
        jobs.forEach { job -> job.cancel(CancellationException(message)) }
    }

    fun activeCount(runId: String): Int = synchronized(lock) {
        jobsByRunId[runId]?.size ?: 0
    }
}

/** Pure script templates; callers pass already-quoted JavaScript string literals. */
internal object RuntimeJavascriptCalls {
    fun start(quotedRequestJson: String): String =
        "window.AgentDockRuntime.start($quotedRequestJson);"

    fun receive(quotedEnvelopeJson: String): String =
        "window.AgentDockRuntime.receive($quotedEnvelopeJson);"

    fun cancel(quotedRunId: String): String =
        "window.AgentDockRuntime.cancel($quotedRunId);"
}
