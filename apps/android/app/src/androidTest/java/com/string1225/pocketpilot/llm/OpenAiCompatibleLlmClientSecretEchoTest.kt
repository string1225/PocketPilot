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
