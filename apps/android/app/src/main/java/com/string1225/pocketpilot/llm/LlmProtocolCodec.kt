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
            "stream",
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
        val stream = when (val rawStream = root.opt("stream")) {
            null -> false
            is Boolean -> rawStream
            else -> throw LlmProtocolException("LLM_INVALID_REQUEST", "stream must be a boolean")
        }
        return LlmCompletionRequest(config, messages, tools, stream)
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
            response.usage?.let { usage -> put("usage", encodeUsage(usage)) }
            put("finishReason", response.finishReason)
        }
        if (JSONObject.quote(encoded.toString()).length > MAX_BRIDGE_ENCODED_RESPONSE_CHARS) {
            throw LlmProtocolException(
                "LLM_RESPONSE_TOO_LARGE",
                "LLM response exceeded the serialized bridge size limit",
            )
        }
        return encoded
    }

    fun encodeProgress(event: LlmStreamEvent): JSONObject {
        if (event.contentDelta == null && event.usage == null) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM stream event contains neither text nor usage",
            )
        }
        return JSONObject().apply {
            event.contentDelta?.let { put("contentDelta", it) }
            event.usage?.let { put("usage", encodeUsage(it)) }
        }.also { encoded ->
            if (JSONObject.quote(encoded.toString()).length > MAX_BRIDGE_ENCODED_RESPONSE_CHARS) {
                throw LlmProtocolException(
                    "LLM_RESPONSE_TOO_LARGE",
                    "LLM stream event exceeded the serialized bridge size limit",
                )
            }
        }
    }

    private fun encodeUsage(usage: LlmTokenUsage): JSONObject = JSONObject().apply {
        usage.inputTokens?.let { put("inputTokens", it) }
        usage.outputTokens?.let { put("outputTokens", it) }
        usage.totalTokens?.let { put("totalTokens", it) }
        usage.cachedInputTokens?.let { put("cachedInputTokens", it) }
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

    internal fun newChatCompletionsStreamDecoder(
        request: LlmCompletionRequest,
    ): ChatCompletionsStreamDecoder {
        if (request.config.protocol != LlmProtocol.CHAT_COMPLETIONS) {
            throw LlmProtocolException(
                "LLM_UNSUPPORTED_PROTOCOL",
                "SSE streaming is only supported for Chat Completions",
            )
        }
        return ChatCompletionsStreamDecoder(request)
    }

    private fun encodeChatCompletionsRequest(
        request: LlmCompletionRequest,
        aliases: ToolNameAliases,
    ): JSONObject = JSONObject()
        .put("model", request.config.model)
        .put("stream", request.stream)
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
            if (request.stream && isOfficialOpenAiEndpoint(request.config.baseUrl)) {
                put("stream_options", JSONObject().put("include_usage", true))
            }
            if (request.stream && isGlmEndpoint(request.config.baseUrl) && request.tools.isNotEmpty()) {
                // GLM requires this vendor extension to emit incremental tool-call arguments.
                put("tool_stream", true)
            }
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
        val finishReason = normalizeFinishReason(choice.optString("finish_reason"))
        val message = choice.optJSONObject("message")
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Chat completion choice has no message")
        val content = decodeChatContent(message.opt("content"))
            ?: message.optString("refusal").takeIf(String::isNotBlank)
        val calls = decodeChatToolCalls(message.optJSONArray("tool_calls"), aliases)
        return requireUsableResponse(content, calls, decodeUsage(root.optJSONObject("usage")), finishReason)
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
        return requireUsableResponse(
            textParts.takeIf { it.isNotEmpty() }?.joinToString(""),
            calls,
            decodeResponsesUsage(root.optJSONObject("usage")),
            if (calls.isEmpty()) "stop" else "tool_calls",
        )
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

    private fun requireUsableResponse(
        content: String?,
        calls: List<LlmToolCall>,
        usage: LlmTokenUsage? = null,
        finishReason: String = "unknown",
    ): LlmProviderResponse {
        requireUniqueCallIds(calls)
        if (content == null && calls.isEmpty()) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM response contains neither text nor tool calls",
            )
        }
        return LlmProviderResponse(
            content = content,
            toolCalls = calls,
            usage = usage,
            finishReason = finishReason,
        )
    }

    internal fun decodeUsage(usage: JSONObject?): LlmTokenUsage? {
        if (usage == null) return null
        val inputTokens = usage.optionalNonNegativeLong("prompt_tokens")
        val outputTokens = usage.optionalNonNegativeLong("completion_tokens")
        val totalTokens = usage.optionalNonNegativeLong("total_tokens")
        val cachedInputTokens = usage.optJSONObject("prompt_tokens_details")
            ?.optionalNonNegativeLong("cached_tokens")
        if (inputTokens == null && outputTokens == null && totalTokens == null && cachedInputTokens == null) return null
        return LlmTokenUsage(inputTokens, outputTokens, totalTokens, cachedInputTokens)
    }

    private fun decodeResponsesUsage(usage: JSONObject?): LlmTokenUsage? {
        if (usage == null) return null
        val inputTokens = usage.optionalNonNegativeLong("input_tokens")
        val outputTokens = usage.optionalNonNegativeLong("output_tokens")
        val totalTokens = usage.optionalNonNegativeLong("total_tokens")
        val cachedInputTokens = usage.optJSONObject("input_tokens_details")
            ?.optionalNonNegativeLong("cached_tokens")
        if (inputTokens == null && outputTokens == null && totalTokens == null && cachedInputTokens == null) return null
        return LlmTokenUsage(inputTokens, outputTokens, totalTokens, cachedInputTokens)
    }

    private fun isOfficialOpenAiEndpoint(baseUrl: String): Boolean = runCatching {
        java.net.URI(baseUrl).host.equals("api.openai.com", ignoreCase = true)
    }.getOrDefault(false)

    private fun isGlmEndpoint(baseUrl: String): Boolean = runCatching {
        java.net.URI(baseUrl).host.equals("open.bigmodel.cn", ignoreCase = true)
    }.getOrDefault(false)

    private fun requireUniqueCallIds(calls: List<LlmToolCall>) {
        val ids = mutableSetOf<String>()
        if (calls.any { !ids.add(it.id) }) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM returned duplicate tool call ids")
        }
    }

    internal fun remoteError(error: JSONObject): LlmProtocolException {
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

internal class ChatCompletionsStreamDecoder(
    request: LlmCompletionRequest,
) {
    private val aliases = ToolNameAliases(request.tools)
    private val content = StringBuilder()
    private val calls = sortedMapOf<Int, StreamingToolCall>()
    private var usage: LlmTokenUsage? = null
    private var sawChunk = false
    private var finished = false
    private var finishReason = "unknown"

    fun accept(data: String): LlmStreamEvent? {
        check(!finished) { "LLM stream decoder is already finished" }
        val root = try {
            JSONObject(data)
        } catch (error: JSONException) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM stream event is not valid JSON",
                cause = error,
            )
        }
        root.optJSONObject("error")?.let { throw OpenAiCompatibleProtocolCodec.remoteError(it) }
        sawChunk = true
        val eventUsage = OpenAiCompatibleProtocolCodec.decodeUsage(root.optJSONObject("usage"))
        if (eventUsage != null) usage = eventUsage

        val choices = root.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return eventUsage?.let { LlmStreamEvent(usage = it) }
        }
        val choice = choices.optJSONObject(0)
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM stream choice must be an object")
        choice.optString("finish_reason").takeIf(String::isNotBlank)?.let { rawReason ->
            finishReason = normalizeFinishReason(rawReason)
        }
        val delta = choice.optJSONObject("delta")
            ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM stream choice has no delta")
        val contentDelta = decodeContentDelta(delta)
        if (contentDelta != null) content.append(contentDelta)
        decodeToolCallDeltas(delta.optJSONArray("tool_calls"))
        return when {
            contentDelta != null || eventUsage != null -> LlmStreamEvent(contentDelta, eventUsage)
            else -> null
        }
    }

    fun finish(): LlmProviderResponse {
        check(!finished) { "LLM stream decoder is already finished" }
        finished = true
        if (!sawChunk) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM stream did not contain any events")
        }
        val callIds = mutableSetOf<String>()
        val decodedCalls = calls.values.map { call ->
            if (call.id.isEmpty() || call.name.isEmpty()) {
                throw LlmProtocolException("LLM_INVALID_RESPONSE", "Streamed tool call is incomplete")
            }
            if (!callIds.add(call.id.toString())) {
                throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM returned duplicate tool call ids")
            }
            val arguments = try {
                JSONObject(call.arguments.toString())
            } catch (error: JSONException) {
                throw LlmProtocolException(
                    "LLM_INVALID_TOOL_ARGUMENTS",
                    "LLM returned malformed streamed tool arguments",
                    cause = error,
                )
            }
            LlmToolCall(
                id = call.id.toString(),
                name = aliases.toOriginalName(call.name.toString()),
                argumentsJson = arguments.toString(),
            )
        }
        val decodedContent = content.toString().takeIf { it.isNotEmpty() }
        if (decodedContent == null && decodedCalls.isEmpty()) {
            throw LlmProtocolException(
                "LLM_INVALID_RESPONSE",
                "LLM response contains neither text nor tool calls",
            )
        }
        val normalizedReason = if (finishReason == "unknown" && decodedCalls.isNotEmpty()) {
            "tool_calls"
        } else {
            finishReason
        }
        return LlmProviderResponse(decodedContent, decodedCalls, usage, normalizedReason)
    }

    private fun decodeContentDelta(delta: JSONObject): String? {
        val value = when {
            delta.has("content") && !delta.isNull("content") -> delta.opt("content")
            delta.has("refusal") && !delta.isNull("refusal") -> delta.opt("refusal")
            else -> return null
        }
        if (value !is String) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "LLM stream content delta must be text")
        }
        return value.takeIf { it.isNotEmpty() }
    }

    private fun decodeToolCallDeltas(rawCalls: JSONArray?) {
        if (rawCalls == null) return
        if (rawCalls.length() > MAX_TOOL_CALLS_PER_CHUNK) {
            throw LlmProtocolException("LLM_INVALID_RESPONSE", "Too many streamed tool calls")
        }
        for (position in 0 until rawCalls.length()) {
            val raw = rawCalls.optJSONObject(position)
                ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Streamed tool call must be an object")
            val index = when (val value = raw.opt("index")) {
                is Int -> value
                is Long -> value.takeIf { it in 0 until MAX_TOOL_CALLS_PER_CHUNK.toLong() }?.toInt()
                null -> position
                else -> null
            } ?: throw LlmProtocolException("LLM_INVALID_RESPONSE", "Streamed tool call index is invalid")
            if (index !in 0 until MAX_TOOL_CALLS_PER_CHUNK) {
                throw LlmProtocolException("LLM_INVALID_RESPONSE", "Streamed tool call index is out of range")
            }
            val accumulator = calls.getOrPut(index) { StreamingToolCall() }
            raw.optString("id").takeIf(String::isNotEmpty)?.let { fragment ->
                accumulator.id.appendFragment(fragment)
            }
            val function = raw.optJSONObject("function")
            if (function != null) {
                function.optString("name").takeIf(String::isNotEmpty)?.let { fragment ->
                    accumulator.name.appendFragment(fragment)
                }
                if (function.has("arguments") && !function.isNull("arguments")) {
                    val arguments = function.opt("arguments")
                    if (arguments !is String) {
                        throw LlmProtocolException(
                            "LLM_INVALID_RESPONSE",
                            "Streamed tool arguments must be text fragments",
                        )
                    }
                    accumulator.arguments.append(arguments)
                }
            }
        }
    }

    private class StreamingToolCall {
        val id = StringBuilder()
        val name = StringBuilder()
        val arguments = StringBuilder()
    }

    private fun StringBuilder.appendFragment(fragment: String) {
        // Most servers send fragments. Some repeat or send a cumulative value;
        // tolerate both without duplicating id/name text.
        val current = toString()
        when {
            current.isEmpty() -> append(fragment)
            fragment == current -> Unit
            fragment.startsWith(current) -> {
                setLength(0)
                append(fragment)
            }
            else -> append(fragment)
        }
    }

    private companion object {
        const val MAX_TOOL_CALLS_PER_CHUNK = 128
    }
}

private fun normalizeFinishReason(reason: String): String = when (reason) {
    "stop" -> "stop"
    "tool_calls", "function_call" -> "tool_calls"
    "length" -> throw LlmProtocolException(
        "LLM_OUTPUT_TRUNCATED",
        "LLM output reached its token limit; incomplete tool calls were blocked",
    )
    "content_filter", "sensitive" -> throw LlmProtocolException(
        "LLM_CONTENT_FILTERED",
        "LLM output was blocked by content filtering",
    )
    "network_error" -> throw LlmProtocolException(
        "LLM_REMOTE_NETWORK_ERROR",
        "LLM service reported a network error",
        retryable = true,
    )
    else -> "unknown"
}

internal class ToolNameAliases(tools: List<LlmToolDefinition>) {
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

private fun JSONObject.optionalNonNegativeLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    val value = when (val raw = opt(key)) {
        is Byte -> raw.toLong()
        is Short -> raw.toLong()
        is Int -> raw.toLong()
        is Long -> raw
        else -> throw LlmProtocolException("LLM_INVALID_RESPONSE", "$key must be an integer")
    }
    if (value < 0) {
        throw LlmProtocolException("LLM_INVALID_RESPONSE", "$key must not be negative")
    }
    return value
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
