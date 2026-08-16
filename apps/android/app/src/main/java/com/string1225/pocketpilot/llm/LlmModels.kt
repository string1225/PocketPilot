package com.string1225.pocketpilot.llm

const val DEFAULT_LLM_MODEL: String = "glm-5.3"
const val DEFAULT_CHAT_COMPLETIONS_BASE_URL: String = "https://open.bigmodel.cn/api/coding/paas/v4"
const val DEFAULT_RESPONSES_BASE_URL: String = "https://open.bigmodel.cn/api/v1"

enum class LlmProtocol(
    val wireValue: String,
    val endpointPath: String,
    val defaultBaseUrl: String,
) {
    CHAT_COMPLETIONS(
        wireValue = "chat_completions",
        endpointPath = "chat/completions",
        defaultBaseUrl = DEFAULT_CHAT_COMPLETIONS_BASE_URL,
    ),
    RESPONSES(
        wireValue = "responses",
        endpointPath = "responses",
        defaultBaseUrl = DEFAULT_RESPONSES_BASE_URL,
    );

    companion object {
        fun fromWireValue(value: String): LlmProtocol = entries.firstOrNull { it.wireValue == value }
            ?: throw LlmProtocolException("LLM_UNSUPPORTED_PROTOCOL", "Unsupported LLM protocol")
    }
}

data class LlmEndpointConfig(
    val protocol: LlmProtocol = LlmProtocol.CHAT_COMPLETIONS,
    val baseUrl: String = protocol.defaultBaseUrl,
    val model: String = DEFAULT_LLM_MODEL,
    /** Reference into [com.string1225.pocketpilot.security.SecureCredentialStore]. */
    val credentialId: String = "",
)

data class LlmToolCall(
    val id: String,
    val name: String,
    /** Canonical JSON object encoded as text. */
    val argumentsJson: String,
)

data class LlmMessage(
    val role: String,
    val content: String,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val toolCallId: String? = null,
    val name: String? = null,
)

data class LlmToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema object encoded as text. */
    val inputSchemaJson: String,
)

data class LlmCompletionRequest(
    val config: LlmEndpointConfig,
    val messages: List<LlmMessage>,
    val tools: List<LlmToolDefinition>,
    /** Chat Completions only; Responses remains on its existing JSON path. */
    val stream: Boolean = false,
)

data class LlmTokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
) {
    init {
        require(inputTokens != null || outputTokens != null || totalTokens != null) {
            "Token usage must contain at least one value"
        }
        require(listOfNotNull(inputTokens, outputTokens, totalTokens).all { it >= 0 }) {
            "Token usage values must not be negative"
        }
    }
}

data class LlmStreamEvent(
    val contentDelta: String? = null,
    val usage: LlmTokenUsage? = null,
)

data class LlmProviderResponse(
    val content: String? = null,
    val toolCalls: List<LlmToolCall> = emptyList(),
    val usage: LlmTokenUsage? = null,
)

class LlmProtocolException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause) {
    init {
        require(code.isNotBlank()) { "LLM error code must not be blank" }
        require(message.isNotBlank()) { "LLM error message must not be blank" }
    }
}
