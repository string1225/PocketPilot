package com.string1225.pocketpilot.runtime

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Strict codec for the only JSON boundary exposed to bundled JavaScript. */
internal object RuntimeEnvelopeCodec {
    const val MAX_ENVELOPE_LENGTH: Int = 8 * 1_048_576

    fun decodeInbound(envelopeJson: String): RuntimeInboundMessage {
        val envelope = parseObject(envelopeJson, "Runtime envelope")
        val requestId = optionalNonBlankString(envelope, "id")
        val runId = optionalString(envelope, "runId")
        val projectId = optionalString(envelope, "projectId")
        // Only Tool requests have a request/response relationship. Events are
        // observational and must never provoke a synthetic Tool reply.
        val responseContext = if (envelope.opt("type") == "tool.request") {
            responseContext(requestId, runId, projectId)
        } else {
            null
        }
        val version = integer(envelope, "version", responseContext)
        try {
            RuntimeBridgeVersion.requireSupported(version)
        } catch (error: UnsupportedRuntimeBridgeVersionException) {
            throw RuntimeProtocolException(
                code = "unsupported_bridge_version",
                message = error.message ?: "Unsupported runtime bridge version",
                context = responseContext,
                cause = error,
            )
        }

        val type = requiredNonBlankString(envelope, "type", responseContext)
        val payload = envelope.opt("payload") as? JSONObject
            ?: throw RuntimeProtocolException(
                code = "invalid_envelope",
                message = "Runtime envelope payload must be a JSON object",
                context = responseContext,
            )

        return when (type) {
            "tool.request" -> decodeToolRequest(responseContext, payload)
            "event" -> RuntimeInboundMessage.Event(
                AgentRuntimeEvent(
                    id = requestId ?: throw RuntimeProtocolException(
                        code = "invalid_event",
                        message = "event envelope requires a non-blank id",
                        context = responseContext,
                    ),
                    // runtime.ready intentionally carries empty context before a run starts.
                    runId = runId ?: throw RuntimeProtocolException(
                        code = "invalid_event",
                        message = "event envelope requires a string runId",
                        context = responseContext,
                    ),
                    projectId = projectId ?: throw RuntimeProtocolException(
                        code = "invalid_event",
                        message = "event envelope requires a string projectId",
                        context = responseContext,
                    ),
                    type = requiredNonBlankString(payload, "type", responseContext),
                    payloadJson = payload.toString(),
                ),
            )
            else -> throw RuntimeProtocolException(
                code = "unsupported_message_type",
                message = "Unsupported runtime message type: $type",
                context = responseContext,
            )
        }
    }

    fun normalizeStartRequest(requestJson: String): String =
        parseObject(requestJson, "Runtime start request").toString()

    fun encodeToolResult(request: NativeToolRequest, resultJson: String): String {
        val result = validateToolResult(parseObject(resultJson, "Native Tool result"))
        return envelope("tool.result", request.context(), result).toString()
    }

    fun encodeToolError(context: RuntimeRequestContext, code: String, message: String): String {
        require(code.isNotBlank()) { "Tool error code must not be blank" }
        require(message.isNotBlank()) { "Tool error message must not be blank" }
        val payload = JSONObject()
            .put("code", code)
            .put("message", message)
        return envelope("tool.error", context, payload).toString()
    }

    fun encodeToolProgress(context: RuntimeRequestContext, progressJson: String): String {
        val progress = parseObject(progressJson, "Native Tool progress")
        val encoded = envelope("tool.progress", context, progress).toString()
        if (encoded.length > MAX_ENVELOPE_LENGTH) {
            throw RuntimeProtocolException(
                code = "message_too_large",
                message = "Native Tool progress exceeds $MAX_ENVELOPE_LENGTH characters",
                context = context,
            )
        }
        return encoded
    }

    private fun decodeToolRequest(
        context: RuntimeRequestContext?,
        payload: JSONObject,
    ): RuntimeInboundMessage.ToolRequest {
        val requestContext = context ?: throw RuntimeProtocolException(
            code = "invalid_tool_request",
            message = "tool.request envelope requires non-blank id, runId, and projectId",
        )
        val name = requiredNonBlankString(payload, "name", requestContext)
        if (!payload.has("arguments")) {
            throw RuntimeProtocolException(
                code = "invalid_tool_request",
                message = "tool.request payload requires arguments",
                context = requestContext,
            )
        }
        return RuntimeInboundMessage.ToolRequest(
            NativeToolRequest(
                id = requestContext.id,
                runId = requestContext.runId,
                projectId = requestContext.projectId,
                name = name,
                argumentsJson = jsonValueToString(payload.get("arguments")),
            ),
        )
    }

    private fun validateToolResult(result: JSONObject): JSONObject {
        val success = result.opt("success") as? Boolean
            ?: throw RuntimeProtocolException(
                code = "invalid_tool_result",
                message = "Native Tool result requires a boolean success field",
            )
        if (success) {
            if (!result.has("data")) {
                throw RuntimeProtocolException(
                    code = "invalid_tool_result",
                    message = "Successful Native Tool result requires data",
                )
            }
            return result
        }

        val error = result.opt("error") as? JSONObject
            ?: throw RuntimeProtocolException(
                code = "invalid_tool_result",
                message = "Failed Native Tool result requires an error object",
            )
        requiredNonBlankString(error, "code", null)
        requiredNonBlankString(error, "message", null)
        return result
    }

    private fun envelope(
        type: String,
        context: RuntimeRequestContext,
        payload: JSONObject,
    ): JSONObject =
        JSONObject()
            .put("version", POCKET_PILOT_RUNTIME_BRIDGE_VERSION)
            .put("type", type)
            .put("id", context.id)
            .put("runId", context.runId)
            .put("projectId", context.projectId)
            .put("payload", payload)

    private fun parseObject(json: String, label: String): JSONObject {
        if (json.length > MAX_ENVELOPE_LENGTH) {
            throw RuntimeProtocolException(
                code = "message_too_large",
                message = "$label exceeds $MAX_ENVELOPE_LENGTH characters",
            )
        }
        try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (value !is JSONObject || tokener.nextClean() != '\u0000') {
                throw RuntimeProtocolException(
                    code = "invalid_json",
                    message = "$label must contain exactly one JSON object",
                )
            }
            return value
        } catch (error: RuntimeProtocolException) {
            throw error
        } catch (error: JSONException) {
            throw RuntimeProtocolException(
                code = "invalid_json",
                message = "$label is not valid JSON",
                cause = error,
            )
        }
    }

    private fun integer(
        objectValue: JSONObject,
        key: String,
        context: RuntimeRequestContext?,
    ): Int {
        val value = objectValue.opt(key)
        return when (value) {
            is Byte, is Short, is Int -> (value as Number).toInt()
            is Long -> if (value in Int.MIN_VALUE..Int.MAX_VALUE) value.toInt() else null
            else -> null
        } ?: throw RuntimeProtocolException(
            code = "invalid_envelope",
            message = "Runtime envelope $key must be an integer",
            context = context,
        )
    }

    private fun optionalNonBlankString(objectValue: JSONObject, key: String): String? {
        if (!objectValue.has(key)) return null
        val value = objectValue.opt(key) as? String ?: return null
        return value.takeIf(String::isNotBlank)
    }

    private fun optionalString(objectValue: JSONObject, key: String): String? =
        objectValue.opt(key) as? String

    private fun requiredNonBlankString(
        objectValue: JSONObject,
        key: String,
        context: RuntimeRequestContext?,
    ): String = (objectValue.opt(key) as? String)
        ?.takeIf(String::isNotBlank)
        ?: throw RuntimeProtocolException(
            code = "invalid_envelope",
            message = "$key must be a non-blank string",
            context = context,
        )

    private fun responseContext(
        id: String?,
        runId: String?,
        projectId: String?,
    ): RuntimeRequestContext? = if (
        id != null && !runId.isNullOrBlank() && !projectId.isNullOrBlank()
    ) {
        RuntimeRequestContext(id, runId, projectId)
    } else {
        null
    }

    private fun NativeToolRequest.context(): RuntimeRequestContext =
        RuntimeRequestContext(id, runId, projectId)

    private fun jsonValueToString(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
