package com.string1225.pocketpilot.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.security.CredentialIds
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmProtocolCodecTest {
    private val tool = LlmToolDefinition(
        name = "workspace.read",
        description = "Read a workspace file",
        inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
    )

    @Test
    fun chatCompletionsAliasesToolNamesAndMapsCallsBack() {
        val request = LlmCompletionRequest(
            config = LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
            messages = listOf(LlmMessage("user", "Read README.md")),
            tools = listOf(tool),
        )
        val encoded = JSONObject(String(OpenAiCompatibleProtocolCodec.encodeRequest(request)))
        val apiName = encoded.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONObject("function")
            .getString("name")
        assertFalse(apiName.contains('.'))
        assertTrue(apiName.length <= 64)

        val response = OpenAiCompatibleProtocolCodec.decodeResponse(
            request,
            """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"$apiName","arguments":"{\"path\":\"README.md\"}"}}]}}]}""",
        )
        assertEquals("workspace.read", response.toolCalls.single().name)
        assertEquals("README.md", JSONObject(response.toolCalls.single().argumentsJson).getString("path"))
    }

    @Test
    fun responsesEncodesToolOutputsAndDecodesTextAndCalls() {
        val request = LlmCompletionRequest(
            config = LlmEndpointConfig(
                protocol = LlmProtocol.RESPONSES,
                baseUrl = DEFAULT_RESPONSES_BASE_URL,
                credentialId = CredentialIds.DEFAULT_LLM,
            ),
            messages = listOf(
                LlmMessage("user", "Read README.md"),
                LlmMessage(
                    role = "assistant",
                    content = "I will inspect the file.",
                    toolCalls = listOf(
                        LlmToolCall("call-1", "workspace.read", """{"path":"README.md"}"""),
                    ),
                ),
                LlmMessage("tool", "file contents", toolCallId = "call-1", name = "workspace.read"),
            ),
            tools = listOf(tool),
        )
        val encoded = JSONObject(String(OpenAiCompatibleProtocolCodec.encodeRequest(request)))
        assertFalse(encoded.getBoolean("store"))
        val assistantInput = encoded.getJSONArray("input").let { input ->
            (0 until input.length())
                .map(input::getJSONObject)
                .first { it.optString("role") == "assistant" }
        }
        assertEquals("message", assistantInput.getString("type"))
        assertEquals("I will inspect the file.", assistantInput.getString("content"))
        assertTrue(
            encoded.getJSONArray("input").let { input ->
                (0 until input.length()).any {
                    input.getJSONObject(it).optString("type") == "function_call_output"
                }
            },
        )
        val apiName = encoded.getJSONArray("tools").getJSONObject(0).getString("name")

        val response = OpenAiCompatibleProtocolCodec.decodeResponse(
            request,
            """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Checking another file."}]},{"type":"function_call","call_id":"call-2","name":"$apiName","arguments":"{\"path\":\"LICENSE\"}"}]}""",
        )
        assertEquals("Checking another file.", response.content)
        assertEquals("workspace.read", response.toolCalls.single().name)
    }

    @Test
    fun nativeRequestRejectsKeyMaterialAndMalformedToolArguments() {
        assertThrows(LlmProtocolException::class.java) {
            LlmNativeRequestCodec.decode(
                """{"protocol":"chat_completions","baseUrl":"$DEFAULT_CHAT_COMPLETIONS_BASE_URL","model":"$DEFAULT_LLM_MODEL","credentialId":"${CredentialIds.DEFAULT_LLM}","apiKey":"forbidden","messages":[],"tools":[]}""",
            )
        }
        assertThrows(LlmProtocolException::class.java) {
            LlmNativeRequestCodec.decode(
                """{"protocol":"chat_completions","baseUrl":"$DEFAULT_CHAT_COMPLETIONS_BASE_URL","model":"$DEFAULT_LLM_MODEL","credentialId":"${CredentialIds.DEFAULT_LLM}","Authorization":"Bearer forbidden","messages":[],"tools":[]}""",
            )
        }

        val request = LlmCompletionRequest(
            config = LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
            messages = listOf(LlmMessage("user", "test")),
            tools = listOf(tool),
        )
        val encoded = JSONObject(String(OpenAiCompatibleProtocolCodec.encodeRequest(request)))
        val apiName = encoded.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONObject("function")
            .getString("name")
        assertThrows(LlmProtocolException::class.java) {
            OpenAiCompatibleProtocolCodec.decodeResponse(
                request,
                """{"choices":[{"finish_reason":"tool_calls","message":{"tool_calls":[{"id":"call-1","function":{"name":"$apiName","arguments":"not-json"}}]}}]}""",
            )
        }
    }

    @Test
    fun nativeRequestRejectsNonLlmCredentialReferences() {
        listOf(CredentialIds.DEFAULT_GIT_TOKEN, "ssh.server-1.password").forEach { credentialId ->
            val error = assertThrows(LlmProtocolException::class.java) {
                LlmNativeRequestCodec.decode(
                    """{"protocol":"chat_completions","baseUrl":"$DEFAULT_CHAT_COMPLETIONS_BASE_URL","model":"$DEFAULT_LLM_MODEL","credentialId":"$credentialId","messages":[{"role":"user","content":"test"}],"tools":[]}""",
                )
            }
            assertEquals("LLM_CREDENTIAL_REFERENCE_FORBIDDEN", error.code)
        }
    }

    @Test
    fun normalizedNativeResponseKeepsEnvelopeHeadroom() {
        val accepted = LlmNativeRequestCodec.encode(
            LlmProviderResponse(content = "\u0001".repeat(750_000)),
        )
        assertTrue(JSONObject.quote(accepted.toString()).length < 6 * 1_048_576)

        val error = assertThrows(LlmProtocolException::class.java) {
            LlmNativeRequestCodec.encode(
                LlmProviderResponse(content = "\u0001".repeat(900_000)),
            )
        }
        assertEquals("LLM_RESPONSE_TOO_LARGE", error.code)
    }

    @Test
    fun normalizedToolArgumentsCountTheirSecondEscapingPass() {
        fun response(controlCharacters: Int): LlmProviderResponse = LlmProviderResponse(
            toolCalls = listOf(
                LlmToolCall(
                    id = "call-1",
                    name = "workspace.read",
                    argumentsJson = JSONObject()
                        .put("value", "\u0001".repeat(controlCharacters))
                        .toString(),
                ),
            ),
        )

        val accepted = LlmNativeRequestCodec.encode(response(750_000))
        assertTrue(JSONObject.quote(accepted.toString()).length < 6 * 1_048_576)

        val error = assertThrows(LlmProtocolException::class.java) {
            LlmNativeRequestCodec.encode(response(900_000))
        }
        assertEquals("LLM_RESPONSE_TOO_LARGE", error.code)
    }

    @Test
    fun chatStreamAccumulatesTextToolCallsAndFinalUsage() {
        val request = LlmCompletionRequest(
            config = LlmEndpointConfig(credentialId = CredentialIds.DEFAULT_LLM),
            messages = listOf(LlmMessage("user", "Read README.md")),
            tools = listOf(tool),
            stream = true,
        )
        val encoded = JSONObject(String(OpenAiCompatibleProtocolCodec.encodeRequest(request)))
        assertTrue(encoded.getBoolean("stream"))
        assertTrue(encoded.getBoolean("tool_stream"))
        assertFalse(encoded.has("stream_options"))
        val apiName = encoded.getJSONArray("tools")
            .getJSONObject(0)
            .getJSONObject("function")
            .getString("name")
        val decoder = OpenAiCompatibleProtocolCodec.newChatCompletionsStreamDecoder(request)

        assertEquals(
            "我会",
            decoder.accept(
                """{"choices":[{"index":0,"delta":{"content":"我会"},"finish_reason":null}]}""",
            )?.contentDelta,
        )
        decoder.accept(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"$apiName","arguments":"{\"path\":"}}]},"finish_reason":null}]}""",
        )
        decoder.accept(
            """{"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"\"README.md\"}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":4,"total_tokens":16}}""",
        )
        val response = decoder.finish()

        assertEquals("我会", response.content)
        assertEquals("workspace.read", response.toolCalls.single().name)
        assertEquals("README.md", JSONObject(response.toolCalls.single().argumentsJson).getString("path"))
        assertEquals(LlmTokenUsage(12, 4, 16), response.usage)
    }

    @Test
    fun officialOpenAiStreamRequestsUsageWhileGenericCompatibilityDoesNot() {
        fun encoded(baseUrl: String): JSONObject = JSONObject(
            String(
                OpenAiCompatibleProtocolCodec.encodeRequest(
                    LlmCompletionRequest(
                        config = LlmEndpointConfig(
                            baseUrl = baseUrl,
                            credentialId = CredentialIds.DEFAULT_LLM,
                        ),
                        messages = listOf(LlmMessage("user", "test")),
                        tools = emptyList(),
                        stream = true,
                    ),
                ),
            ),
        )

        assertTrue(encoded("https://api.openai.com/v1").getJSONObject("stream_options").getBoolean("include_usage"))
        assertFalse(encoded("https://compatible.example.test/v1").has("stream_options"))
    }
}
