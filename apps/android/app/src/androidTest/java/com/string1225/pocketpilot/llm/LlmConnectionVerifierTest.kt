package com.string1225.pocketpilot.llm

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.model.GLM_CHAT_BASE_URL
import com.string1225.pocketpilot.model.GLM_IMAGE_BASE_URL
import com.string1225.pocketpilot.model.GLM_IMAGE_MODEL
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmConnectionVerifierTest {
    @Test
    fun glmProbeForcesSeparateCodingAndOfficialVisionEndpoints() {
        val calls = mutableListOf<FakeSuccessConnection>()
        val factory = LlmHttpConnectionFactory { url ->
            FakeSuccessConnection(url).also(calls::add)
        }
        val verifier = OpenAiCompatibleConnectionVerifier(
            textClientFactory = { OpenAiCompatibleLlmClient(it, factory) },
            visionClientFactory = { OpenAiCompatibleVisionClient(it, factory) },
        )

        verifier.verify(
            PocketPilotSettings(
                llmProvider = LlmProviderPreference.GLM,
                llmBaseUrl = "https://attacker.invalid/v1",
                modelName = "glm-5.3",
                imageModelName = "attacker-model",
            ),
            "test-ak".toCharArray(),
        )

        assertEquals(2, calls.size)
        assertEquals("$GLM_CHAT_BASE_URL/chat/completions", calls[0].url.toString())
        assertEquals("$GLM_IMAGE_BASE_URL/chat/completions", calls[1].url.toString())
        assertEquals("glm-5.3", JSONObject(calls[0].requestText()).getString("model"))
        assertEquals(true, JSONObject(calls[0].requestText()).getBoolean("stream"))
        assertEquals(GLM_IMAGE_MODEL, JSONObject(calls[1].requestText()).getString("model"))
        assertFalse(calls.any { it.requestText().contains("test-ak") })
    }

    @Test
    fun openAiProbeUsesConfiguredTextAndImageModels() {
        val calls = mutableListOf<FakeSuccessConnection>()
        val factory = LlmHttpConnectionFactory { url ->
            FakeSuccessConnection(url).also(calls::add)
        }
        OpenAiCompatibleConnectionVerifier(
            textClientFactory = { OpenAiCompatibleLlmClient(it, factory) },
            visionClientFactory = { OpenAiCompatibleVisionClient(it, factory) },
        ).verify(
            PocketPilotSettings(
                llmProvider = LlmProviderPreference.OPENAI_CHAT,
                llmBaseUrl = "https://gateway.example/v1",
                modelName = "text-model",
                imageModelName = "vision-model",
            ),
            "test-key".toCharArray(),
        )

        assertEquals(2, calls.size)
        assertEquals("https://gateway.example/v1/chat/completions", calls[0].url.toString())
        assertEquals("https://gateway.example/v1/chat/completions", calls[1].url.toString())
        assertEquals("text-model", JSONObject(calls[0].requestText()).getString("model"))
        val visionBody = JSONObject(calls[1].requestText())
        assertEquals("vision-model", visionBody.getString("model"))
        val content = visionBody.getJSONArray("messages").getJSONObject(0).getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            true,
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
                .startsWith("data:image/png;base64,"),
        )
    }

    private class FakeSuccessConnection(url: URL) : HttpURLConnection(url) {
        private val request = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getOutputStream(): OutputStream = request
        override fun getInputStream(): InputStream {
            val body = JSONObject(requestText())
            val response = if (body.optBoolean("stream", false)) {
                "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n" +
                    "data: [DONE]\n\n"
            } else {
                """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"OK"}}]}"""
            }
            return ByteArrayInputStream(response.toByteArray(StandardCharsets.UTF_8))
        }
        override fun getResponseCode(): Int = HTTP_OK

        fun requestText(): String = request.toString(StandardCharsets.UTF_8.name())
    }
}
