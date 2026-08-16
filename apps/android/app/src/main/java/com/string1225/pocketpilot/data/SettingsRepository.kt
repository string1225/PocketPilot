package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.GLM_CHAT_BASE_URL
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.LlmProtocolPreference
import com.string1225.pocketpilot.model.LlmSettingsPolicy
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.model.ThemePreference

class SettingsRepository(
    private val database: PocketPilotDatabase,
) {
    /**
     * Materializes the provider introduced after the original GLM-only alpha.
     * A legacy credential without persisted connection fields belongs to GLM;
     * treating it as a new OpenAI-compatible connection could otherwise scope
     * that credential to the wrong provider on a future configuration change.
     */
    @Synchronized
    fun migrateLegacyLlmConnection(credentialConfigured: Boolean): Boolean {
        if (LlmProviderPreference.fromValueOrNull(get(KEY_LLM_PROVIDER)) != null) return false

        val legacyBaseUrl = get(KEY_LLM_BASE_URL)?.trim()?.takeIf(String::isNotEmpty)
        val legacyModel = get(KEY_MODEL_NAME)?.trim()?.takeIf(String::isNotEmpty)
        val provider = if (legacyBaseUrl == null && credentialConfigured) {
            LlmProviderPreference.GLM
        } else {
            LlmSettingsPolicy.inferLegacyProvider(legacyBaseUrl)
        }
        val activeModel = when (provider) {
            LlmProviderPreference.OPENAI_CHAT ->
                legacyModel?.takeIf { it.length <= MAX_MODEL_LENGTH }.orEmpty()

            LlmProviderPreference.GLM ->
                legacyModel?.takeIf { it.length <= MAX_MODEL_LENGTH } ?: provider.defaultModel
        }
        val activeBaseUrl = when (provider) {
            LlmProviderPreference.OPENAI_CHAT ->
                legacyBaseUrl?.takeIf(LlmSettingsPolicy::isValidOpenAiBaseUrl).orEmpty()

            LlmProviderPreference.GLM -> GLM_CHAT_BASE_URL
        }
        val values = buildMap {
            put(KEY_LLM_PROVIDER, provider.value)
            put(KEY_LLM_PROTOCOL, LlmProtocolPreference.CHAT_COMPLETIONS.value)
            put(KEY_MODEL_NAME, activeModel)
            put(KEY_LLM_BASE_URL, activeBaseUrl)
            put(KEY_IMAGE_MODEL, provider.defaultImageModel)
            if (provider == LlmProviderPreference.OPENAI_CHAT) {
                put(KEY_OPENAI_MODEL, activeModel)
                put(KEY_OPENAI_BASE_URL, activeBaseUrl)
                put(KEY_OPENAI_IMAGE_MODEL, "")
            }
        }
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) -> put(db, key, value) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return true
    }

    @Synchronized
    fun load(): PocketPilotSettings {
        val persistedBaseUrl = get(KEY_LLM_BASE_URL)?.takeIf { it.isNotBlank() }
        val persistedModel = get(KEY_MODEL_NAME)?.takeIf { it.isNotBlank() }
        val connection = LlmSettingsPolicy.loadConnection(
            persistedProvider = get(KEY_LLM_PROVIDER),
            persistedModel = persistedModel,
            persistedBaseUrl = persistedBaseUrl,
            persistedOpenAiModel = get(KEY_OPENAI_MODEL),
            persistedOpenAiBaseUrl = get(KEY_OPENAI_BASE_URL),
            persistedImageModel = get(KEY_IMAGE_MODEL),
            persistedOpenAiImageModel = get(KEY_OPENAI_IMAGE_MODEL),
        )
        val protocol = LlmProtocolPreference.fromValue(get(KEY_LLM_PROTOCOL).orEmpty())
        return PocketPilotSettings(
            modelName = connection.modelName,
            llmProvider = connection.provider,
            llmProtocol = protocol,
            llmBaseUrl = connection.baseUrl,
            openAiModelName = connection.openAiModelName,
            openAiBaseUrl = connection.openAiBaseUrl,
            imageModelName = connection.imageModelName,
            openAiImageModelName = connection.openAiImageModelName,
            remoteServer = get(KEY_REMOTE_SERVER).orEmpty(),
            personalization = get(KEY_PERSONALIZATION).orEmpty(),
            memoryEnabled = getBoolean(KEY_MEMORY_ENABLED, true),
            toolsEnabled = getBoolean(KEY_TOOLS_ENABLED, true),
            theme = ThemePreference.fromValue(get(KEY_THEME).orEmpty()),
            language = AppLanguage.fromValue(get(KEY_LANGUAGE).orEmpty()),
        )
    }

    @Synchronized
    fun save(settings: PocketPilotSettings) {
        require(settings.modelName.length <= MAX_MODEL_LENGTH) { "Model name is too long" }
        require(settings.imageModelName.length <= MAX_MODEL_LENGTH) { "Image model name is too long" }
        if (settings.llmProvider == LlmProviderPreference.OPENAI_CHAT) {
            require(settings.llmBaseUrl.length <= MAX_ENDPOINT_LENGTH) { "LLM endpoint is too long" }
            require(settings.openAiModelName.length <= MAX_MODEL_LENGTH) {
                "OpenAI model name is too long"
            }
            require(settings.openAiBaseUrl.length <= MAX_ENDPOINT_LENGTH) {
                "OpenAI endpoint is too long"
            }
            require(settings.openAiImageModelName.length <= MAX_MODEL_LENGTH) {
                "OpenAI image model name is too long"
            }
            require(
                settings.llmBaseUrl.isBlank() ||
                    LlmSettingsPolicy.isValidOpenAiBaseUrl(settings.llmBaseUrl),
            ) { "OpenAI endpoint is invalid" }
        }
        val resolvedBaseUrl = LlmSettingsPolicy.resolveBaseUrl(
            settings.llmProvider,
            settings.llmBaseUrl,
        )
        val previousBaseUrl = get(KEY_LLM_BASE_URL)?.takeIf { it.isNotBlank() }
        val previousProvider = LlmSettingsPolicy.loadProvider(
            persistedProvider = get(KEY_LLM_PROVIDER),
            persistedBaseUrl = previousBaseUrl,
        )
        val candidateOpenAiModel = when (settings.llmProvider) {
            LlmProviderPreference.OPENAI_CHAT -> settings.modelName.trim()
            LlmProviderPreference.GLM -> settings.openAiModelName.trim().ifBlank {
                get(KEY_OPENAI_MODEL)?.trim().orEmpty().ifBlank {
                    get(KEY_MODEL_NAME)?.trim().orEmpty().takeIf {
                        previousProvider == LlmProviderPreference.OPENAI_CHAT
                    }.orEmpty()
                }
            }
        }
        val openAiModel = candidateOpenAiModel.takeIf { it.length <= MAX_MODEL_LENGTH }.orEmpty()
        val candidateOpenAiBaseUrl = when (settings.llmProvider) {
            LlmProviderPreference.OPENAI_CHAT -> settings.llmBaseUrl.trim()
            LlmProviderPreference.GLM -> settings.openAiBaseUrl.trim().ifBlank {
                get(KEY_OPENAI_BASE_URL)?.trim().orEmpty().ifBlank {
                    previousBaseUrl.takeIf {
                        previousProvider == LlmProviderPreference.OPENAI_CHAT
                    }.orEmpty()
                }
            }
        }
        val openAiBaseUrl = candidateOpenAiBaseUrl
            .takeIf(LlmSettingsPolicy::isValidOpenAiBaseUrl)
            .orEmpty()
        val candidateOpenAiImageModel = when (settings.llmProvider) {
            LlmProviderPreference.OPENAI_CHAT -> settings.imageModelName.trim()
            LlmProviderPreference.GLM -> settings.openAiImageModelName.trim().ifBlank {
                get(KEY_OPENAI_IMAGE_MODEL)?.trim().orEmpty().ifBlank {
                    get(KEY_IMAGE_MODEL)?.trim().orEmpty().takeIf {
                        previousProvider == LlmProviderPreference.OPENAI_CHAT
                    }.orEmpty()
                }
            }
        }
        val openAiImageModel = candidateOpenAiImageModel
            .takeIf { it.length <= MAX_MODEL_LENGTH }
            .orEmpty()
        val imageModel = when (settings.llmProvider) {
            LlmProviderPreference.OPENAI_CHAT -> openAiImageModel
            LlmProviderPreference.GLM -> settings.llmProvider.defaultImageModel
        }
        require(settings.remoteServer.length <= 240) { "Remote server label is too long" }
        require(settings.personalization.length <= 4_000) { "Personalization is too long" }
        val values = mapOf(
            KEY_MODEL_NAME to settings.modelName.trim(),
            KEY_LLM_PROVIDER to settings.llmProvider.value,
            KEY_LLM_PROTOCOL to settings.llmProtocol.value,
            KEY_LLM_BASE_URL to resolvedBaseUrl,
            KEY_OPENAI_MODEL to openAiModel,
            KEY_OPENAI_BASE_URL to openAiBaseUrl,
            KEY_IMAGE_MODEL to imageModel,
            KEY_OPENAI_IMAGE_MODEL to openAiImageModel,
            KEY_REMOTE_SERVER to settings.remoteServer.trim(),
            KEY_PERSONALIZATION to settings.personalization.trim(),
            KEY_MEMORY_ENABLED to settings.memoryEnabled.toString(),
            KEY_TOOLS_ENABLED to settings.toolsEnabled.toString(),
            KEY_THEME to settings.theme.value,
            KEY_LANGUAGE to settings.language.value,
        )
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            values.forEach { (key, value) -> put(db, key, value) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun get(key: String): String? = database.readableDatabase.query(
        "settings",
        arrayOf("value"),
        "key = ?",
        arrayOf(key),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun getBoolean(key: String, fallback: Boolean): Boolean = when (get(key)) {
        "true" -> true
        "false" -> false
        else -> fallback
    }

    private fun put(database: android.database.sqlite.SQLiteDatabase, key: String, value: String) {
        val row = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        check(
            database.insertWithOnConflict(
                "settings",
                null,
                row,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L,
        ) { "Unable to persist setting $key" }
    }

    companion object {
        private const val MAX_MODEL_LENGTH = 128
        private const val MAX_ENDPOINT_LENGTH = 2_048
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_LLM_PROVIDER = "llm_provider"
        const val KEY_LLM_PROTOCOL = "llm_protocol"
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_OPENAI_MODEL = "openai_model"
        const val KEY_OPENAI_BASE_URL = "openai_base_url"
        const val KEY_IMAGE_MODEL = "image_model"
        const val KEY_OPENAI_IMAGE_MODEL = "openai_image_model"
        const val KEY_REMOTE_SERVER = "remote_server"
        const val KEY_PERSONALIZATION = "personalization"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_TOOLS_ENABLED = "tools_enabled"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
    }
}
