package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject

/**
 * Routes only `llm.complete`; callers may supply the existing Workspace
 * dispatcher as [fallback] when composing the application.
 */
class LlmToolRequestDispatcher(
    private val client: OpenAiCompatibleLlmClient,
    private val activeRuns: ActiveRunRegistry,
    private val fallback: ToolRequestDispatcher? = null,
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult {
        if (request.name != TOOL_NAME) {
            return fallback?.dispatch(request)
                ?: throw ToolDispatchException("UNKNOWN_TOOL", "Unknown Native Tool: ${request.name}")
        }
        if (!activeRuns.authorizes(request.runId, request.projectId)) {
            throw ToolDispatchException(
                "UNAUTHORIZED_RUN",
                "LLM request is not authorized for this active Agent Run",
            )
        }
        return try {
            val completionRequest = LlmNativeRequestCodec.decode(request.argumentsJson)
            val response = runInterruptible(Dispatchers.IO) {
                client.complete(completionRequest) { event ->
                    request.progressSink.emit(LlmNativeRequestCodec.encodeProgress(event).toString())
                }
            }
            NativeToolResult(
                JSONObject()
                    .put("success", true)
                    .put("data", LlmNativeRequestCodec.encode(response))
                    .toString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: LlmProtocolException) {
            failure(error.code, error.message, error.retryable)
        } catch (error: IllegalArgumentException) {
            failure("LLM_INVALID_REQUEST", error.message ?: "LLM request is invalid", false)
        } catch (_: Exception) {
            // Never forward an arbitrary exception which could contain request
            // headers, endpoint internals, or credential material.
            failure("LLM_NATIVE_FAILURE", "Native LLM request failed", true)
        }
    }

    private fun failure(code: String, message: String, retryable: Boolean): NativeToolResult =
        NativeToolResult(
            JSONObject()
                .put("success", false)
                .put(
                    "error",
                    JSONObject()
                        .put("code", code)
                        .put("message", message)
                        .put("retryable", retryable),
                )
                .toString(),
        )

    companion object {
        const val TOOL_NAME: String = "llm.complete"
    }
}
