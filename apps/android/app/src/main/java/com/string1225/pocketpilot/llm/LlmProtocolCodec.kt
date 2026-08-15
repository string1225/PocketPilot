package com.string1225.pocketpilot.llm

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object LlmNativeRequestCodec {
    private const val MAX_REQUEST_BYTES = 4 * 1024 * 1024
    // Tool results are embedded in JSON and then quoted once more as a
    // JavaScript string for evaluateJavascript. Measure that exact second
    // serialization and keep two MiB of headroom below the 8 MiB bridge cap.
    private const val MAX_BRIDGE_ENCODED_RESPONSE_CHARS = 6 * 1024 * 1024
    private const val MAX_MESSAGES = 1_024
    private const val MAX_TOOLS = 128
    private const val MAX_TOOL_CALLS_PER_MESSAGE = 128

    fun decode(argumentsJson: String): LlmCompletionRequest {
        require(argumentsJson.toByteArray(StandardCharsets.UTF_8).size <= MAX_REQUEST_BYTES) {
            "LLM request is too large"
        }
        val root = try {
            JSONObject(argumentsJson)
        } catch (error: JSONException) {
            throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM request must be a JSON object", cause = error)
        }
        if (root.keys().asSequence().any(::isSecretFieldName)) {
            throw LlmProtocolException(
                "LLM_SECRET_IN_REQUEST",
                "LLM request must contain a credential reference, never key material",
            )
        }
        root.requireOnlyKeys(
            "protocol",
            "baseUrl",
            "model",
            "credentialId",
            "messages",
            "tools",
        )
        val protocol = LlmProtocol.fromWireValue(root.requiredString("protocol"))
        val config = LlmEndpointConfig(
            protocol = protocol,
            baseUrl = root.requiredString("baseUrl"),
            model = root.requiredString("model"),
            credentialId = root.requiredString("credentialId"),
        )
        // Resolve now so malformed endpoints never reach credential lookup.
        LlmEndpointPolicy.resolve(config)

        val messagesJson = root.requiredArray("messages")
        require(messagesJson.length() in 1..MAX_MESSAGES) { "LLM request must contain messages" }
        val messages = buildList(messagesJson.length()) {
            for (index in 0 until messagesJson.length()) {
                val item = messagesJson.optJSONObject(index)
                    ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM message must be an object")
                val role = item.requiredString("role")
                require(role in setOf("system", "user", "assistant", "tool")) {
                    "Unsupported LLM message role"
                }
                val content = item.requiredString("content", allowEmpty = true)
                val calls = if (item.has("toolCalls") && !item.isNull("toolCalls")) {
                    decodeToolCalls(item.getJSONArray("toolCalls"))
                } else {
                    emptyList()
                }
                val toolCallId = item.optionalString("toolCallId")
                if (role == "tool") require(!toolCallId.isNullOrBlank()) { "Tool message requires toolCallId" }
                if (role != "assistant") require(calls.isEmpty()) { "Only assistant messages may contain toolCalls" }
                add(
                    LlmMessage(
                        role = role,
                        content = content,
                        toolCalls = calls,
                        toolCallId = toolCallId,
                        name = item.optionalString("name"),
                    ),
                )
            }
        }

        val toolsJson = root.requiredArray("tools")
        require(toolsJson.length() <= MAX_TOOLS) { "Too many LLM tools" }
        val names = mutableSetOf<String>()
        val tools = buildList(toolsJson.length()) {
            for (index in 0 until toolsJson.length()) {
                val item = toolsJson.optJSONObject(index)
                    ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM tool must be an object")
                val name = item.requiredString("name")
                require(names.add(name)) { "Duplicate LLM tool name" }
                val schema = item.optJSONObject("inputSchema")
                    ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM tool schema must be an object")
                add(
                    LlmToolDefinition(
                        name = name,
                        description = item.requiredString("description", allowEmpty = true),
                        inputSchemaJson = schema.toString(),
                    ),
                )
            }
        }
        return LlmCompletionRequest(config, messages, tools)
    }

    fun encode(response: LlmProviderResponse): JSONObject {
        if (response.content == null && response.toolCalls.isEmpty()) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM response contains neither content nor tool calls",
            )
        }
        val encoded = JSONObject().apply {
            response.content?.let { put("content", it) }
            if (response.toolCalls.isNotEmpty()) {
                put(
                    "toolCalls",
                    JSONArray().apply {
                        response.toolCalls.forEach { call ->
                            put(
                                JSONObject()
                                    .put("id", call.id)
                                    .put("name", call.name)
                                    .put("arguments", JSONObject(call.argumentsJson)),
                            )
                        }
                    },
                )
            }
        }
        if (JSONObject.quote(encoded.toString()).length > MAX_BRIDGE_ENCODED_RESPONSE_CHARS) {
            throw LlmProtocolException(
                "LLM_RESPONSE_TOO_LARGE",
                "LLM response exceeded the serialized bridge size limit",
            )
        }
        return encoded
    }

    private fun decodeToolCalls(calls: JSONArray): List<LlmToolCall> {
        require(calls.length() <= MAX_TOOL_CALLS_PER_MESSAGE) { "Too many LLM tool calls" }
        return buildList(calls.length()) {
            for (index in 0 until calls.length()) {
                val item = calls.optJSONObject(index)
                    ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM tool call must be an object")
                val arguments = item.optJSONObject("arguments")
                    ?: throw LlmProtocolException(
                        "LLM_INVALID_REQUEST",
                        "LLM tool call arguments must be an object",
                    )
                add(
                    LlmToolCall(
                        id = item.requiredString("id"),
                        name = item.requiredString("name"),
                        argumentsJson = arguments.toString(),
                    ),
                )
            }
        }
    }
}

object OpenAiCompatibleProtocolCodec {
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024

    fun encodeRequest(request: LlmCompletionRequest): ByteArray {
        val aliases = ToolNameAliases(request.tools)
        val root = when (request.config.protocol) {
            LlmProtocol.CHAT_COMPLETIONS -> encodeChatCompletionsRequest(request, aliases)
            LlmProtocol.RESPONSES -> encodeResponsesRequest(request, aliases)
        }
        return root.toString().toByteArray(StandardCharsets.UTF_8)
    }

    fun decodeResponse(request: LlmCompletionRequest, responseJson: String): LlmProviderResponse {
        if (responseJson.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE_BYTES) {
            throw LlmProtocolException("LLM_RESPONSE_TOO_LARGE", "LLM response exceeded the size limit")
        }
        val root = try {
            JSONObject(responseJson)
        } catch (error: JSONException) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM response is not valid JSON", cause = error)
        }
        root.optJSONObject("error")?.let { throw remoteError(it) }
        val aliases = ToolNameAliases(request.tools)
        return when (request.config.protocol) {
            LlmProtocol.CHAT_COMPLETIONS -> decodeChatCompletionsResponse(root, aliases)
            LlmProtocol.RESPONSES -> decodeResponsesResponse(root, aliases)
        }
    }

    fun extractRemoteErrorMessage(responseJson: String): String? {
        val root = runCatching { JSONObject(responseJson) }.getOrNull() ?: return null
        val error = root.optJSONObject("error")
        return when {
            error != null -> error.optString("message").takeIf(String::isNotBlank)
            else -> root.optString("message").takeIf(String::isNotBlank)
        }?.let(::sanitizeRemoteMessage)
    }

    private fun encodeChatCompletionsRequest(
        request: LlmCompletionRequest,
        aliases: ToolNameAliases,
    ): JSONObject = JSONObject()
        .put("model", request.config.model)
        .put("stream", false)
        .put(
            "messages",
            JSONArray().apply {
                request.messages.forEach { message ->
                    put(
                        JSONObject()
                            .put("role", message.role)
                            .put(
                                "content",
                                if (message.role == "assistant" && message.content.isEmpty() && message.toolCalls.isNotEmpty()) {
                                    JSONObject.NULL
                                } else {
                                    message.content
                                },
                            )
                            .apply {
                                if (message.role == "tool") put("tool_call_id", message.toolCallId)
                                if (message.toolCalls.isNotEmpty()) {
                                    put(
                                        "tool_calls",
                                        JSONArray().apply {
                                            message.toolCalls.forEach { call ->
                                                put(
                                                    JSONObject()
                                                        .put("id", call.id)
                                                        .put("type", "function")
                                                        .put(
                                                            "function",
                                                            JSONObject()
                                                                .put("name", aliases.toApiName(call.name))
                                                                .put("arguments", call.argumentsJson),
                                                        ),
                                                )
                                            }
                                        },
                                    )
                                }
                            },
                    )
                }
            },
        )
        .apply {
            if (request.tools.isNotEmpty()) {
                put("tool_choice", "auto")
                put(
                    "tools",
                    JSONArray().apply {
                        request.tools.forEach { tool ->
                            put(
                                JSONObject()
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", aliases.toApiName(tool.name))
                                            .put("description", tool.description)
                                            .put("parameters", JSONObject(tool.inputSchemaJson)),
                                    ),
                            )
                        }
                    },
                )
            }
        }

    private fun encodeResponsesRequest(
        request: LlmCompletionRequest,
        aliases: ToolNameAliases,
    ): JSONObject = JSONObject()
        .put("model", request.config.model)
        .put("stream", false)
        .put("store", false)
        .put(
            "input",
            JSONArray().apply {
                request.messages.forEach { message ->
                    when (message.role) {
                        "tool" -> put(
                            JSONObject()
                                .put("type", "function_call_output")
                                .put("call_id", message.toolCallId)
                                .put("output", message.content),
                        )
                        "assistant" -> {
                            if (message.content.isNotEmpty()) {
                                put(
                                    JSONObject()
                                        .put("type", "message")
                                        .put("role", "assistant")
                                        .put("content", message.content),
                                )
                            }
                            message.toolCalls.forEach { call ->
                                put(
                                    JSONObject()
                                        .put("type", "function_call")
                                        .put("call_id", call.id)
                                        .put("name", aliases.toApiName(call.name))
                                        .put("arguments", call.argumentsJson),
                                )
                            }
                        }
                        else -> put(
                            JSONObject()
                                .put("type", "message")
                                .put("role", message.role)
                                .put(
                                    "content",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "input_text")
                                            .put("text", message.content),
                                    ),
                                ),
                        )
                    }
                }
            },
        )
        .apply {
            if (request.tools.isNotEmpty()) {
                put("tool_choice", "auto")
                put(
                    "tools",
                    JSONArray().apply {
                        request.tools.forEach { tool ->
                            put(
                                JSONObject()
                                    .put("type", "function")
                                    .put("name", aliases.toApiName(tool.name))
                                    .put("description", tool.description)
                                    .put("parameters", JSONObject(tool.inputSchemaJson)),
                            )
                        }
                    },
                )
            }
        }

    private fun decodeChatCompletionsResponse(
        root: JSONObject,
        aliases: ToolNameAliases,
    ): LlmProviderResponse {
        val choices = root.optJSONArray("choices")
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Chat completion response has no choices")
        val choice = choices.optJSONObject(0)
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Chat completion response has no first choice")
        val finishReason = choice.optString("finish_reason")
        when (finishReason) {
            "length" -> throw LlmProtocolException("LLM_OUTPUT_TRUNCATED", "LLM output reached its token limit")
            "content_filter", "sensitive" -> throw LlmProtocolException(
                "LLM_CONTENT_FILTERED",
                "LLM output was blocked by content filtering",
            )
            "network_error" -> throw LlmProtocolException(
                "LLM_REMOTE_NETWORK_ERROR",
                "LLM service reported a network error",
                retryable = true,
            )
        }
        val message = choice.optJSONObject("message")
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Chat completion choice has no message")
        val content = decodeChatContent(message.opt("content"))
            ?: message.optString("refusal").takeIf(String::isNotBlank)
        val calls = decodeChatToolCalls(message.optJSONArray("tool_calls"), aliases)
        return requireUsableResponse(content, calls)
    }

    private fun decodeResponsesResponse(
        root: JSONObject,
        aliases: ToolNameAliases,
    ): LlmProviderResponse {
        when (root.optString("status")) {
            "failed" -> throw LlmProtocolException("LLM_RESPONSE_FAILED", "Responses API request failed")
            "incomplete" -> throw LlmProtocolException("LLM_OUTPUT_TRUNCATED", "Responses API output is incomplete")
        }
        val textParts = mutableListOf<String>()
        val calls = mutableListOf<LlmToolCall>()
        val output = root.optJSONArray("output")
        if (output != null) {
            for (index in 0 until output.length()) {
                val item = output.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "message" -> decodeResponsesMessageText(item).let(textParts::addAll)
                    "function_call" -> calls += decodeFunctionCall(item, aliases)
                }
            }
        }
        if (textParts.isEmpty()) {
            root.optString("output_text").takeIf(String::isNotBlank)?.let(textParts::add)
        }
        return requireUsableResponse(textParts.takeIf { it.isNotEmpty() }?.joinToString(""), calls)
    }

    private fun decodeChatContent(value: Any?): String? = when (value) {
        null, JSONObject.NULL -> null
        is String -> value
        is JSONArray -> buildList {
            for (index in 0 until value.length()) {
                when (val part = value.opt(index)) {
                    is String -> add(part)
                    is JSONObject -> part.optString("text").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }.takeIf { it.isNotEmpty() }?.joinToString("")
        else -> throw LlmProtocolException("LLM_INVALID_RESPONSE", "Chat completion content is invalid")
    }

    private fun decodeResponsesMessageText(message: JSONObject): List<String> {
        val content = message.optJSONArray("content") ?: return emptyList()
        return buildList {
            for (index in 0 until content.length()) {
                val part = content.optJSONObject(index) ?: continue
                when (part.optString("type")) {
                    "output_text" -> part.optString("text").takeIf(String::isNotBlank)?.let(::add)
                    "refusal" -> part.optString("refusal").takeIf(String::isNotBlank)?.let(::add)
                }
            }
        }
    }

    private fun decodeChatToolCalls(
        rawCalls: JSONArray?,
        aliases: ToolNameAliases,
    ): List<LlmToolCall> {
        if (rawCalls == null) return emptyList()
        return buildList(rawCalls.length()) {
            for (index in 0 until rawCalls.length()) {
                val call = rawCalls.optJSONObject(index)
                    ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Tool call must be an object")
                val function = call.optJSONObject("function")
                    ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Tool call has no function")
                add(
                    decodeFunctionCall(
                        JSONObject()
                            .put("call_id", call.requiredString("id"))
                            .put("name", function.requiredString("name"))
                            .put("arguments", function.requiredString("arguments")),
                        aliases,
                    ),
                )
            }
        }.also(::requireUniqueCallIds)
    }

    private fun decodeFunctionCall(call: JSONObject, aliases: ToolNameAliases): LlmToolCall {
        val id = call.optionalString("call_id") ?: call.optionalString("id")
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Tool call has no id")
        val apiName = call.requiredString("name")
        val argumentsText = when (val arguments = call.opt("arguments")) {
            is String -> arguments
            is JSONObject -> arguments.toString()
            else -> throw LlmProtocolException("LLM_INVALID_RESPONSE", "Tool arguments must be JSON text")
        }
        val arguments = try {
            JSONObject(argumentsText)
        } catch (error: JSONException) {
            throw LlmProtocolException(
                "LLM_INVALID_TOOL_ARGUMENTS",
                "LLM returned malformed tool arguments",
                cause = error,
            )
        }
        return LlmToolCall(id, aliases.toOriginalName(apiName), arguments.toString())
    }

    private fun requireUsableResponse(content: String?, calls: List<LlmToolCall>): LlmProviderResponse {
        requireUniqueCallIds(calls)
        if (content == null && calls.isEmpty()) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM response contains neither text nor tool calls",
            )
        }
        return LlmProviderResponse(content = content, toolCalls = calls)
    }

    private fun requireUniqueCallIds(calls: List<LlmToolCall>) {
        val ids = mutableSetOf<String>()
        if (calls.any { !ids.add(it.id) }) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM returned duplicate tool call ids")
        }
    }

    private fun remoteError(error: JSONObject): LlmProtocolException {
        val remoteCode = error.optString("code").takeIf(String::isNotBlank)
        val message = error.optString("message").takeIf(String::isNotBlank)
            ?.let(::sanitizeRemoteMessage)
            ?: "LLM service returned an error"
        val retryable = remoteCode?.contains("rate", ignoreCase = true) == true ||
            remoteCode?.contains("timeout", ignoreCase = true) == true ||
            remoteCode?.contains("server", ignoreCase = true) == true
        return LlmProtocolException("LLM_REMOTE_ERROR", message, retryable)
    }

    private fun sanitizeRemoteMessage(message: String): String = message
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(
            Regex("(?i)(authorization|api[_ -]?key|bearer)\\s*[:=]?\\s*[^\\s,;]+"),
            "$1 [REDACTED]",
        )
        .trim()
        .take(512)
}

private class ToolNameAliases(tools: List<LlmToolDefinition>) {
    private val originalToApi: Map<String, String>
    private val apiToOriginal: Map<String, String>

    init {
        val forward = linkedMapOf<String, String>()
        val reverse = linkedMapOf<String, String>()
        tools.forEach { tool ->
            val apiName = if (VALID_FUNCTION_NAME.matches(tool.name) && tool.name.length <= 64) {
                tool.name
            } else {
                encodedToolName(tool.name)
            }
            if (reverse.put(apiName, tool.name) != null) {
                throw LlmProtocolException("LLM_INVALID_REQUEST", "LLM tool names are ambiguous")
            }
            forward[tool.name] = apiName
        }
        originalToApi = forward
        apiToOriginal = reverse
    }

    fun toApiName(original: String): String = originalToApi[original]
        ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "Message references an unknown LLM tool")

    fun toOriginalName(apiName: String): String = apiToOriginal[apiName]
        ?: throw LlmProtocolException("LLM_UNKNOWN_TOOL", "LLM requested an unknown tool")

    private fun encodedToolName(original: String): String {
        val readable = original.replace(Regex("[^A-Za-z0-9_-]"), "_")
            .trim('_')
            .ifEmpty { "tool" }
            .take(40)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(original.toByteArray(StandardCharsets.UTF_8))
        val suffix = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        digest.fill(0)
        return "pp_${readable}_$suffix".take(64)
    }

    companion object {
        private val VALID_FUNCTION_NAME = Regex("[A-Za-z0-9_-]+")
    }
}

private fun JSONObject.requiredArray(key: String): JSONArray = optJSONArray(key)
    ?: throw LlmProtocolException("LLM_INVALID_REQUEST", "$key must be an array")

private fun JSONObject.requiredString(key: String, allowEmpty: Boolean = false): String {
    if (!has(key) || isNull(key) || opt(key) !is String) {
        throw LlmProtocolException("LLM_INVALID_REQUEST", "$key must be a string")
    }
    val value = getString(key)
    if (!allowEmpty && value.isBlank()) {
        throw LlmProtocolException("LLM_INVALID_REQUEST", "$key must not be empty")
    }
    return value
}

private fun JSONObject.optionalString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    if (opt(key) !is String) {
        throw LlmProtocolException("LLM_INVALID_REQUEST", "$key must be a string")
    }
    return getString(key)
}

private fun JSONObject.requireOnlyKeys(vararg allowedKeys: String) {
    val allowed = allowedKeys.toHashSet()
    val unexpected = keys().asSequence().firstOrNull { it !in allowed }
    if (unexpected != null) {
        throw LlmProtocolException(
            "LLM_INVALID_REQUEST",
            "LLM request contains an unsupported field",
        )
    }
}

private fun isSecretFieldName(key: String): Boolean = when (
    key.replace("_", "").replace("-", "").replace(" ", "").lowercase()
) {
    "apikey", "authorization", "bearertoken" -> true
    else -> false
}
