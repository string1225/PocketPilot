package com.string1225.pocketpilot.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiCompatibleLlmClientSecretEchoTest {
    private val secret = "llm-test-secret-7f93"

    @Test
    fun blocksCredentialEchoInSuccessfulContent() {
        assertBlocked(
            """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"prefix $secret suffix"}}]}""",
        )
    }

    @Test
    fun blocksCredentialEchoInSuccessfulToolArguments() {
        assertBlocked(
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"safe_tool","arguments":"{\"value\":\"prefix $secret suffix\"}"}}]}}]}""",
            tools = listOf(
                LlmToolDefinition(
                    name = "safe_tool",
                    description = "A test tool",
                    inputSchemaJson = """{"type":"object","additionalProperties":false}""",
                ),
            ),
        )
    }

    @Test
    fun streamsSafeTextAndReturnsFinalUsage() {
        val events = mutableListOf<LlmStreamEvent>()
        val client = streamingClient(
            sse(
                """{"choices":[{"delta":{"content":"PocketPilot streams a sufficiently long answer. "},"finish_reason":null}]}""",
                """{"choices":[{"delta":{"content":"Done."},"finish_reason":"stop"}],"usage":{"prompt_tokens":7,"completion_tokens":9,"total_tokens":16}}""",
            ),
        )

        val response = client.complete(streamingRequest(), events::add)

        assertEquals("PocketPilot streams a sufficiently long answer. Done.", response.content)
        assertEquals(LlmTokenUsage(7, 9, 16), response.usage)
        assertEquals(response.content, events.mapNotNull(LlmStreamEvent::contentDelta).joinToString(""))
        assertNotNull(events.lastOrNull { it.usage != null })
    }

    @Test
    fun blocksCredentialEchoInOneSseChunkBeforeItCrossesBridge() {
        val events = mutableListOf<LlmStreamEvent>()
        val error = assertThrows(LlmProtocolException::class.java) {
            streamingClient(
                sse(
                    """{"choices":[{"delta":{"content":"prefix $secret suffix"},"finish_reason":"stop"}]}""",
                ),
            ).complete(streamingRequest(), events::add)
        }

        assertEquals("LLM_SECRET_ECHO", error.code)
        assertFalse(events.mapNotNull(LlmStreamEvent::contentDelta).joinToString("").contains(secret))
    }

    @Test
    fun blocksCredentialEchoSplitAcrossSseChunksBeforeItCrossesBridge() {
        val split = secret.length / 2
        val events = mutableListOf<LlmStreamEvent>()
        val error = assertThrows(LlmProtocolException::class.java) {
            streamingClient(
                sse(
                    """{"choices":[{"delta":{"content":"harmless preface ${secret.take(split)}"},"finish_reason":null}]}""",
                    """{"choices":[{"delta":{"content":"${secret.drop(split)} tail"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}""",
                ),
            ).complete(streamingRequest(), events::add)
        }

        assertEquals("LLM_SECRET_ECHO", error.code)
        val emitted = events.mapNotNull(LlmStreamEvent::contentDelta).joinToString("")
        assertFalse(emitted.contains(secret))
        assertFalse(emitted.contains(secret.take(split)))
    }

    private fun assertBlocked(responseJson: String, tools: List<LlmToolDefinition> = emptyList()) {
        val client = OpenAiCompatibleLlmClient(
            credentials = FixedCredentialStore(secret),
            connectionFactory = LlmHttpConnectionFactory { url -> FakeSuccessConnection(url, responseJson) },
        )
        val error = assertThrows(LlmProtocolException::class.java) {
            client.complete(
                LlmCompletionRequest(
                    config = LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
                    messages = listOf(LlmMessage("user", "test")),
                    tools = tools,
                ),
            )
        }

        assertEquals("LLM_SECRET_ECHO", error.code)
        assertFalse(error.message.contains(secret))
        assertFalse(error.cause?.message.orEmpty().contains(secret))
    }

    private fun streamingClient(response: String): OpenAiCompatibleLlmClient = OpenAiCompatibleLlmClient(
        credentials = FixedCredentialStore(secret),
        connectionFactory = LlmHttpConnectionFactory { url -> FakeSuccessConnection(url, response) },
    )

    private fun streamingRequest(): LlmCompletionRequest = LlmCompletionRequest(
        config = LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
        messages = listOf(LlmMessage("user", "test")),
        tools = emptyList(),
        stream = true,
    )

    private fun sse(vararg events: String): String = buildString {
        events.forEach { append("data: ").append(it).append("\n\n") }
        append("data: [DONE]\n\n")
    }

    private class FixedCredentialStore(private val value: String) : SecureCredentialStore {
        override fun put(credentialId: String, secret: CharArray) = Unit
        override fun get(credentialId: String): CharArray = value.toCharArray()
        override fun contains(credentialId: String): Boolean = true
        override fun remove(credentialId: String) = Unit
    }

    private class FakeSuccessConnection(
        url: URL,
        responseJson: String,
    ) : HttpURLConnection(url) {
        private val response = responseJson.toByteArray(StandardCharsets.UTF_8)
        private val request = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = request
        override fun getInputStream(): InputStream = ByteArrayInputStream(response)
        override fun getResponseCode(): Int = HTTP_OK
    }
}
