package com.string1225.pocketpilot.data

import com.string1225.pocketpilot.integrations.http.HttpRequestPolicy
import com.string1225.pocketpilot.integrations.http.HttpApprovalFormatter
import com.string1225.pocketpilot.integrations.http.HttpToolException
import com.string1225.pocketpilot.integrations.http.HttpToolExecutor
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Native policy, approval, and execution boundary for the generic HTTP Tool. */
class HttpToolDispatcher(
    private val executor: HttpToolExecutor,
    private val activeRuns: ActiveRunRegistry,
    private val approvals: ToolApprovalCoordinator,
    private val fallback: ToolRequestDispatcher,
) : ToolRequestDispatcher {
    override suspend fun dispatch(request: NativeToolRequest): NativeToolResult {
        if (request.name != TOOL_NAME) return fallback.dispatch(request)
        requireAuthorized(request)
        if (request.argumentsJson.length > MAX_ARGUMENT_JSON_CHARS) {
            throw ToolDispatchException("HTTP_INVALID_ARGUMENTS", "HTTP arguments are too large")
        }
        try {
            val arguments = JSONObject(request.argumentsJson)
            arguments.requireOnlyKeys(
                "method",
                "url",
                "headers",
                "body",
                "timeoutMillis",
                "maxResponseBytes",
                "allowInsecureHttp",
            )
            val toolRequest = HttpRequestPolicy.create(
                methodText = arguments.stringOrDefault("method", "GET"),
                rawUrl = arguments.requiredString("url"),
                headers = arguments.headers(),
                body = arguments.optionalString("body"),
                timeoutMillis = arguments.longOrDefault(
                    "timeoutMillis",
                    HttpRequestPolicy.DEFAULT_TIMEOUT_MILLIS,
                ),
                maxResponseBytes = arguments.intOrDefault(
                    "maxResponseBytes",
                    HttpRequestPolicy.DEFAULT_RESPONSE_BYTES,
                ),
                allowInsecureHttp = arguments.booleanOrDefault("allowInsecureHttp", false),
            )
            if (toolRequest.requiresApproval) {
                approve(
                    request = request,
                    detail = HttpApprovalFormatter.format(toolRequest),
                )
            }
            currentCoroutineContext().ensureActive()
            requireAuthorized(request)
            val result = executor.execute(toolRequest)
            currentCoroutineContext().ensureActive()
            val headers = JSONObject()
            result.headers.forEach { (name, values) ->
                headers.put(name.lowercase(Locale.ROOT), JSONArray(values))
            }
            return boundedNativeToolSuccess(
                JSONObject()
                    .put("status", result.status)
                    .put("headers", headers)
                    .put("body", result.body)
                    .put("bodyEncoding", result.bodyEncoding)
                    .put("truncated", result.truncated),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: ToolDispatchException) {
            throw error
        } catch (error: HttpToolException) {
            throw ToolDispatchException(error.code, error.message, error)
        } catch (error: JSONException) {
            throw ToolDispatchException("HTTP_INVALID_ARGUMENTS", "HTTP arguments must be a JSON object", error)
        } catch (error: IllegalArgumentException) {
            throw ToolDispatchException(
                "HTTP_INVALID_ARGUMENTS",
                error.message ?: "HTTP arguments are invalid",
                error,
            )
        }
    }

    private suspend fun approve(request: NativeToolRequest, detail: String) {
        require(detail.length <= HttpApprovalFormatter.MAX_DETAIL_CHARS) {
            "HTTP approval request is too large"
        }
        val approved = approvals.awaitDecision(
            ToolApprovalRequest(
                id = request.id,
                runId = request.runId,
                projectId = request.projectId,
                toolName = request.name,
                title = "Allow Agent to send this HTTP request?",
                detail = detail,
            ),
        )
        if (!approved) throw ToolDispatchException("PERMISSION_DENIED", "User rejected http.request")
        requireAuthorized(request)
    }

    private fun requireAuthorized(request: NativeToolRequest) {
        if (!activeRuns.authorizes(request.runId, request.projectId)) {
            throw ToolDispatchException(
                "UNAUTHORIZED_RUN",
                "Tool request is not authorized for this active Agent Run",
            )
        }
    }

    private fun JSONObject.headers(): Map<String, String> {
        if (!has("headers")) return emptyMap()
        val objectValue = opt("headers") as? JSONObject
            ?: throw IllegalArgumentException("headers must be an object")
        val result = linkedMapOf<String, String>()
        val iterator = objectValue.keys()
        while (iterator.hasNext()) {
            val name = iterator.next()
            val value = objectValue.opt(name) as? String
                ?: throw IllegalArgumentException("$name header must be a string")
            result[name] = value
        }
        return result
    }

    private fun JSONObject.requireOnlyKeys(vararg allowed: String) {
        val allowedSet = allowed.toSet()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            require(key in allowedSet) { "Unknown argument: $key" }
        }
    }

    private fun JSONObject.requiredString(key: String): String {
        require(has(key) && !isNull(key) && opt(key) is String) { "$key must be a string" }
        return getString(key).also { require(it.isNotBlank()) { "$key must not be blank" } }
    }

    private fun JSONObject.optionalString(key: String): String? {
        if (!has(key)) return null
        require(!isNull(key) && opt(key) is String) { "$key must be a string" }
        return getString(key)
    }

    private fun JSONObject.stringOrDefault(key: String, default: String): String =
        if (has(key)) requiredString(key) else default

    private fun JSONObject.booleanOrDefault(key: String, default: Boolean): Boolean {
        if (!has(key)) return default
        require(!isNull(key) && opt(key) is Boolean) { "$key must be a boolean" }
        return getBoolean(key)
    }

    private fun JSONObject.longOrDefault(key: String, default: Long): Long {
        if (!has(key)) return default
        return when (val value = opt(key)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$key must be an integer")
        }
    }

    private fun JSONObject.intOrDefault(key: String, default: Int): Int {
        val value = longOrDefault(key, default.toLong())
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "$key is outside integer range" }
        return value.toInt()
    }

    private companion object {
        const val TOOL_NAME = "http.request"
        const val MAX_ARGUMENT_JSON_CHARS = 2 * 1_048_576
    }
}
