package com.string1225.pocketpilot.integrations.http

enum class HttpToolMethod {
    GET,
    HEAD,
    POST,
    PUT,
    PATCH,
    DELETE;

    val isReadOnly: Boolean
        get() = this == GET || this == HEAD

    companion object {
        fun parse(value: String): HttpToolMethod = entries.firstOrNull { it.name == value }
            ?: throw IllegalArgumentException("Unsupported HTTP method")
    }
}

data class HttpToolRequest(
    val method: HttpToolMethod,
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val headers: Map<String, String>,
    val body: String?,
    val bodyBytes: Int,
    val timeoutMillis: Long,
    val maxResponseBytes: Int,
) {
    val requiresApproval: Boolean
        get() = !method.isReadOnly || scheme == "http"
}

data class HttpToolResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val truncated: Boolean,
    val bodyEncoding: String = "utf8",
)

class HttpToolException(
    val code: String,
    override val message: String,
    val retryable: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

fun interface HttpToolExecutor {
    suspend fun execute(request: HttpToolRequest): HttpToolResponse
}
