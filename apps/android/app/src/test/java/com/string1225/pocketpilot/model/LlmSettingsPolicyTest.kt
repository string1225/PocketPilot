package com.string1225.pocketpilot.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmSettingsPolicyTest {
    @Test
    fun `new settings select OpenAI Chat API but require user connection details`() {
        val settings = PocketPilotSettings()

        assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.llmProvider)
        assertEquals("", settings.modelName)
        assertEquals("", settings.llmBaseUrl)
        assertEquals("", settings.openAiModelName)
        assertEquals("", settings.openAiBaseUrl)
        assertEquals("", settings.imageModelName)
        assertEquals("", settings.openAiImageModelName)
        assertEquals(LlmProtocolPreference.CHAT_COMPLETIONS, settings.llmProtocol)
        assertTrue(settings.llmProvider.hasEditableBaseUrl)
    }

    @Test
    fun `legacy BigModel settings migrate to GLM without a provider key`() {
        assertEquals(
            LlmProviderPreference.GLM,
            LlmSettingsPolicy.loadProvider(
                persistedProvider = null,
                persistedBaseUrl = "https://open.bigmodel.cn/api/coding/paas/v4",
            ),
        )
        assertEquals(
            LlmProviderPreference.GLM,
            LlmSettingsPolicy.loadProvider(
                persistedProvider = null,
                persistedBaseUrl = "https://open.bigmodel.cn/api/v1",
            ),
        )
    }

    @Test
    fun `unknown BigModel paths remain OpenAI compatible endpoints`() {
        listOf(
            "https://open.bigmodel.cn/api/coding/paas/v4/custom",
            "https://open.bigmodel.cn/api/v2",
            "https://open.bigmodel.cn/another-compatible-api/v1",
        ).forEach { endpoint ->
            assertEquals(
                LlmProviderPreference.OPENAI_CHAT,
                LlmSettingsPolicy.inferLegacyProvider(endpoint),
                endpoint,
            )
        }
    }

    @Test
    fun `legacy GLM model is preserved while its endpoint becomes built in`() {
        val loaded = LlmSettingsPolicy.loadConnection(
            persistedProvider = null,
            persistedModel = "glm-custom",
            persistedBaseUrl = "https://open.bigmodel.cn/api/v1",
            persistedOpenAiModel = null,
            persistedOpenAiBaseUrl = null,
        )

        assertEquals(LlmProviderPreference.GLM, loaded.provider)
        assertEquals("glm-custom", loaded.modelName)
        assertEquals(GLM_CHAT_BASE_URL, loaded.baseUrl)
        assertEquals("", loaded.openAiModelName)
        assertEquals("", loaded.openAiBaseUrl)
        assertEquals(GLM_IMAGE_MODEL, loaded.imageModelName)
    }

    @Test
    fun `legacy compatible endpoint and model become the saved OpenAI profile`() {
        val loaded = LlmSettingsPolicy.loadConnection(
            persistedProvider = null,
            persistedModel = "custom-model",
            persistedBaseUrl = "https://gateway.example/v1",
            persistedOpenAiModel = null,
            persistedOpenAiBaseUrl = null,
        )

        assertEquals(LlmProviderPreference.OPENAI_CHAT, loaded.provider)
        assertEquals("custom-model", loaded.modelName)
        assertEquals("https://gateway.example/v1", loaded.baseUrl)
        assertEquals("custom-model", loaded.openAiModelName)
        assertEquals("https://gateway.example/v1", loaded.openAiBaseUrl)
    }

    @Test
    fun `GLM load retains a separately saved OpenAI profile`() {
        val loaded = LlmSettingsPolicy.loadConnection(
            persistedProvider = LlmProviderPreference.GLM.value,
            persistedModel = "glm-5.2",
            persistedBaseUrl = GLM_CHAT_BASE_URL,
            persistedOpenAiModel = "my-openai-model",
            persistedOpenAiBaseUrl = "https://gateway.example/v1",
            persistedOpenAiImageModel = "my-vision-model",
        )

        assertEquals(LlmProviderPreference.GLM, loaded.provider)
        assertEquals("glm-5.2", loaded.modelName)
        assertEquals("my-openai-model", loaded.openAiModelName)
        assertEquals("https://gateway.example/v1", loaded.openAiBaseUrl)
        assertEquals(GLM_IMAGE_MODEL, loaded.imageModelName)
        assertEquals("my-vision-model", loaded.openAiImageModelName)
    }

    @Test
    fun `invalid persisted profiles fail closed while GLM keeps a usable default`() {
        val openAi = LlmSettingsPolicy.loadConnection(
            persistedProvider = LlmProviderPreference.OPENAI_CHAT.value,
            persistedModel = "x".repeat(129),
            persistedBaseUrl = "https://gateway.example/v1?secret=legacy",
            persistedOpenAiModel = "x".repeat(129),
            persistedOpenAiBaseUrl = "https://gateway.example/v1?secret=legacy",
        )
        val glm = LlmSettingsPolicy.loadConnection(
            persistedProvider = LlmProviderPreference.GLM.value,
            persistedModel = "x".repeat(129),
            persistedBaseUrl = GLM_CHAT_BASE_URL,
            persistedOpenAiModel = null,
            persistedOpenAiBaseUrl = null,
        )

        assertEquals("", openAi.modelName)
        assertEquals("", openAi.baseUrl)
        assertEquals("", openAi.openAiModelName)
        assertEquals("", openAi.openAiBaseUrl)
        assertEquals("glm-5.3", glm.modelName)
        assertEquals(GLM_IMAGE_MODEL, glm.imageModelName)
        assertEquals(GLM_CHAT_BASE_URL, glm.baseUrl)
    }

    @Test
    fun `persisted provider wins and fresh settings default to OpenAI`() {
        assertEquals(
            LlmProviderPreference.OPENAI_CHAT,
            LlmSettingsPolicy.loadProvider(null, null),
        )
        assertEquals(
            LlmProviderPreference.OPENAI_CHAT,
            LlmSettingsPolicy.loadProvider(
                persistedProvider = LlmProviderPreference.OPENAI_CHAT.value,
                persistedBaseUrl = GLM_CHAT_BASE_URL,
            ),
        )
    }

    @Test
    fun `GLM always resolves to its built in endpoint`() {
        assertEquals(
            GLM_CHAT_BASE_URL,
            LlmSettingsPolicy.resolveBaseUrl(
                provider = LlmProviderPreference.GLM,
                configuredBaseUrl = "https://untrusted.example/v1",
            ),
        )
        assertFalse(LlmProviderPreference.GLM.hasEditableBaseUrl)
    }

    @Test
    fun `GLM supplies its own model default`() {
        assertEquals("glm-5.3", LlmProviderPreference.GLM.defaultModel)
        assertEquals(GLM_IMAGE_MODEL, LlmProviderPreference.GLM.defaultImageModel)
        assertEquals("", LlmProviderPreference.OPENAI_CHAT.defaultModel)
    }

    @Test
    fun `OpenAI endpoint must be a metadata free HTTPS base URL`() {
        assertTrue(LlmSettingsPolicy.isValidOpenAiBaseUrl("https://gateway.example/v1"))
        assertFalse(LlmSettingsPolicy.isValidOpenAiBaseUrl(""))
        assertFalse(LlmSettingsPolicy.isValidOpenAiBaseUrl("http://gateway.example/v1"))
        assertFalse(LlmSettingsPolicy.isValidOpenAiBaseUrl("https://user:key@gateway.example/v1"))
        assertFalse(LlmSettingsPolicy.isValidOpenAiBaseUrl("https://gateway.example/v1?key=secret"))
    }

    @Test
    fun `missing key and destination changes require a new credential`() {
        assertTrue(
            LlmSettingsPolicy.requiresNewCredential(
                previousProvider = LlmProviderPreference.OPENAI_CHAT,
                previousBaseUrl = "https://api.example/v1",
                newProvider = LlmProviderPreference.OPENAI_CHAT,
                newBaseUrl = "https://api.example/v1",
                credentialConfigured = false,
            ),
        )
        assertTrue(
            LlmSettingsPolicy.requiresNewCredential(
                previousProvider = LlmProviderPreference.OPENAI_CHAT,
                previousBaseUrl = "https://api.example/v1",
                newProvider = LlmProviderPreference.GLM,
                newBaseUrl = GLM_CHAT_BASE_URL,
                credentialConfigured = true,
            ),
        )
        assertTrue(
            LlmSettingsPolicy.requiresNewCredential(
                previousProvider = LlmProviderPreference.OPENAI_CHAT,
                previousBaseUrl = "https://api.example/v1",
                newProvider = LlmProviderPreference.OPENAI_CHAT,
                newBaseUrl = "https://gateway.example/v1",
                credentialConfigured = true,
            ),
        )
        assertFalse(
            LlmSettingsPolicy.requiresNewCredential(
                previousProvider = LlmProviderPreference.OPENAI_CHAT,
                previousBaseUrl = "https://api.example/v1/",
                newProvider = LlmProviderPreference.OPENAI_CHAT,
                newBaseUrl = "https://api.example/v1",
                credentialConfigured = true,
            ),
        )
    }
}
