package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.LlmProtocolPreference
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.model.ThemePreference

class SettingsRepository(
    private val database: PocketPilotDatabase,
) {
    fun load(): PocketPilotSettings {
        val protocol = LlmProtocolPreference.fromValue(get(KEY_LLM_PROTOCOL).orEmpty())
        val defaultBaseUrl = when (protocol) {
            LlmProtocolPreference.CHAT_COMPLETIONS -> "https://open.bigmodel.cn/api/coding/paas/v4"
            LlmProtocolPreference.RESPONSES -> "https://open.bigmodel.cn/api/v1"
        }
        return PocketPilotSettings(
            modelName = get(KEY_MODEL_NAME)?.takeIf { it.isNotBlank() } ?: "glm-5.2",
            llmProtocol = protocol,
            llmBaseUrl = get(KEY_LLM_BASE_URL)?.takeIf { it.isNotBlank() } ?: defaultBaseUrl,
            remoteServer = get(KEY_REMOTE_SERVER).orEmpty(),
            personalization = get(KEY_PERSONALIZATION).orEmpty(),
            memoryEnabled = getBoolean(KEY_MEMORY_ENABLED, true),
            toolsEnabled = getBoolean(KEY_TOOLS_ENABLED, true),
            theme = ThemePreference.fromValue(get(KEY_THEME).orEmpty()),
            language = AppLanguage.fromValue(get(KEY_LANGUAGE).orEmpty()),
        )
    }

    fun save(settings: PocketPilotSettings) {
        require(settings.modelName.length <= 160) { "Model name is too long" }
        require(settings.llmBaseUrl.length <= 2_048) { "LLM endpoint is too long" }
        require(settings.remoteServer.length <= 240) { "Remote server label is too long" }
        require(settings.personalization.length <= 4_000) { "Personalization is too long" }
        val values = mapOf(
            KEY_MODEL_NAME to settings.modelName.trim(),
            KEY_LLM_PROTOCOL to settings.llmProtocol.value,
            KEY_LLM_BASE_URL to settings.llmBaseUrl.trim(),
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
        const val KEY_MODEL_NAME = "model_name"
        const val KEY_LLM_PROTOCOL = "llm_protocol"
        const val KEY_LLM_BASE_URL = "llm_base_url"
        const val KEY_REMOTE_SERVER = "remote_server"
        const val KEY_PERSONALIZATION = "personalization"
        const val KEY_MEMORY_ENABLED = "memory_enabled"
        const val KEY_TOOLS_ENABLED = "tools_enabled"
        const val KEY_THEME = "theme"
        const val KEY_LANGUAGE = "language"
    }
}
