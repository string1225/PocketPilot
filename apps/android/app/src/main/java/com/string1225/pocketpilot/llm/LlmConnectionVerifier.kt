package com.string1225.pocketpilot.llm

import com.string1225.pocketpilot.model.GLM_IMAGE_BASE_URL
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.LlmSettingsPolicy
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.util.Base64

fun interface LlmConnectionVerifier {
    /** Throws a sanitized [LlmProtocolException] unless text and vision both respond. */
    fun verify(settings: PocketPilotSettings, secret: CharArray)
}

class OpenAiCompatibleConnectionVerifier(
    private val textClientFactory: (SecureCredentialStore) -> OpenAiCompatibleLlmClient =
        { OpenAiCompatibleLlmClient(it) },
    private val visionClientFactory: (SecureCredentialStore) -> OpenAiCompatibleVisionClient =
        { OpenAiCompatibleVisionClient(it) },
) : LlmConnectionVerifier {
    override fun verify(settings: PocketPilotSettings, secret: CharArray) {
        require(secret.isNotEmpty()) { "AK / API Key is required" }
        val store = TransientCredentialStore(secret)
        try {
            val textConfig = textConfig(settings)
            try {
                textClientFactory(store).complete(
                    LlmCompletionRequest(
                        config = textConfig,
                        messages = listOf(LlmMessage("user", TEXT_PROBE_PROMPT)),
                        tools = emptyList(),
                        // Agent runs always use SSE, so a successful JSON-only
                        // response is not sufficient proof that the configured
                        // endpoint can power the chat UI.
                        stream = true,
                    ),
                )
            } catch (error: LlmProtocolException) {
                throw LlmProtocolException(
                    "LLM_TEXT_CONNECTION_TEST_FAILED",
                    "Text model connection test failed: ${error.message}",
                    error.retryable,
                    error,
                )
            }
            val visionConfig = visionConfig(settings)
            val image = Base64.getDecoder().decode(PROBE_PNG_BASE64)
            try {
                try {
                    visionClientFactory(store).analyze(
                        LlmVisionRequest(
                            config = visionConfig,
                            prompt = VISION_PROBE_PROMPT,
                            mimeType = "image/png",
                            imageBytes = image,
                        ),
                    )
                } catch (error: LlmProtocolException) {
                    throw LlmProtocolException(
                        "LLM_IMAGE_CONNECTION_TEST_FAILED",
                        "Image model connection test failed: ${error.message}",
                        error.retryable,
                        error,
                    )
                }
            } finally {
                image.fill(0)
            }
        } finally {
            store.close()
        }
    }

    private fun textConfig(settings: PocketPilotSettings): LlmEndpointConfig {
        val baseUrl = LlmSettingsPolicy.resolveBaseUrl(settings.llmProvider, settings.llmBaseUrl)
        val model = settings.modelName.trim().ifBlank { settings.llmProvider.defaultModel }
        return LlmEndpointConfig(
            protocol = LlmProtocol.CHAT_COMPLETIONS,
            baseUrl = baseUrl,
            model = model,
            credentialId = CredentialIds.DEFAULT_LLM,
        ).also { LlmEndpointPolicy.resolve(it) }
    }

    private fun visionConfig(settings: PocketPilotSettings): LlmEndpointConfig {
        val (baseUrl, model) = when (settings.llmProvider) {
            LlmProviderPreference.GLM -> GLM_IMAGE_BASE_URL to settings.llmProvider.defaultImageModel
            LlmProviderPreference.OPENAI_CHAT -> settings.llmBaseUrl to settings.imageModelName.trim()
        }
        return LlmEndpointConfig(
            protocol = LlmProtocol.CHAT_COMPLETIONS,
            baseUrl = baseUrl,
            model = model,
            credentialId = CredentialIds.DEFAULT_LLM,
        ).also { LlmEndpointPolicy.resolve(it) }
    }

    private class TransientCredentialStore(secret: CharArray) : SecureCredentialStore {
        private var stored: CharArray? = secret.copyOf()

        override fun put(credentialId: String, secret: CharArray) {
            throw UnsupportedOperationException("Probe credentials cannot be replaced")
        }

        override fun get(credentialId: String): CharArray? {
            require(credentialId == CredentialIds.DEFAULT_LLM) { "Unexpected credential reference" }
            return stored?.copyOf()
        }

        override fun contains(credentialId: String): Boolean =
            credentialId == CredentialIds.DEFAULT_LLM && stored != null

        override fun remove(credentialId: String) = close()

        fun close() {
            stored?.fill('\u0000')
            stored = null
        }
    }

    companion object {
        private const val TEXT_PROBE_PROMPT = "Reply with OK only."
        private const val VISION_PROBE_PROMPT = "Reply with OK only if you can read this image."
        // A valid 1x1 PNG kept in native code solely for a minimal vision connectivity probe.
        private const val PROBE_PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
