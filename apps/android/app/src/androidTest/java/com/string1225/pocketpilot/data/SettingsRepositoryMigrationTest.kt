package com.string1225.pocketpilot.data

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.model.GLM_CHAT_BASE_URL
import com.string1225.pocketpilot.model.LlmProviderPreference
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryMigrationTest {
    @Test
    fun legacyCredentialWithoutConnectionFieldsMigratesToGlm() = withRepository { _, repository ->
        assertTrue(repository.migrateLegacyLlmConnection(credentialConfigured = true))

        val settings = repository.load()
        assertEquals(LlmProviderPreference.GLM, settings.llmProvider)
        assertEquals("glm-5.2", settings.modelName)
        assertEquals(GLM_CHAT_BASE_URL, settings.llmBaseUrl)
        assertEquals(LlmProviderPreference.GLM.value, repository.get(SettingsRepository.KEY_LLM_PROVIDER))
    }

    @Test
    fun freshInstallWithoutCredentialMaterializesBlankOpenAiConnection() =
        withRepository { _, repository ->
            assertTrue(repository.migrateLegacyLlmConnection(credentialConfigured = false))

            val settings = repository.load()
            assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.llmProvider)
            assertEquals("", settings.modelName)
            assertEquals("", settings.llmBaseUrl)
            assertEquals("", settings.openAiModelName)
            assertEquals("", settings.openAiBaseUrl)
        }

    @Test
    fun legacyCustomEndpointMigratesToOpenAiAndIsPreserved() = withRepository { database, repository ->
        put(database, SettingsRepository.KEY_MODEL_NAME, "legacy-custom-model")
        put(database, SettingsRepository.KEY_LLM_BASE_URL, "https://gateway.example/v1")

        assertTrue(repository.migrateLegacyLlmConnection(credentialConfigured = true))

        val settings = repository.load()
        assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.llmProvider)
        assertEquals("legacy-custom-model", settings.modelName)
        assertEquals("https://gateway.example/v1", settings.llmBaseUrl)
        assertEquals("legacy-custom-model", settings.openAiModelName)
        assertEquals("https://gateway.example/v1", settings.openAiBaseUrl)
    }

    @Test
    fun existingProviderPreventsMigrationFromOverwritingConnection() =
        withRepository { database, repository ->
            put(
                database,
                SettingsRepository.KEY_LLM_PROVIDER,
                LlmProviderPreference.OPENAI_CHAT.value,
            )
            put(database, SettingsRepository.KEY_MODEL_NAME, "explicit-model")
            put(database, SettingsRepository.KEY_LLM_BASE_URL, GLM_CHAT_BASE_URL)

            assertFalse(repository.migrateLegacyLlmConnection(credentialConfigured = true))
            assertEquals(
                LlmProviderPreference.OPENAI_CHAT.value,
                repository.get(SettingsRepository.KEY_LLM_PROVIDER),
            )
            assertEquals("explicit-model", repository.get(SettingsRepository.KEY_MODEL_NAME))
            assertEquals(GLM_CHAT_BASE_URL, repository.get(SettingsRepository.KEY_LLM_BASE_URL))

            val settings = repository.load()
            assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.llmProvider)
            assertEquals("explicit-model", settings.modelName)
            assertEquals(GLM_CHAT_BASE_URL, settings.llmBaseUrl)
        }

    @Test
    fun inactiveInvalidLegacyOpenAiProfileDoesNotBlockGlmSettings() =
        withRepository { database, repository ->
            put(database, SettingsRepository.KEY_LLM_PROVIDER, LlmProviderPreference.GLM.value)
            put(database, SettingsRepository.KEY_MODEL_NAME, "glm-5.2")
            put(database, SettingsRepository.KEY_LLM_BASE_URL, GLM_CHAT_BASE_URL)
            put(database, SettingsRepository.KEY_OPENAI_MODEL, "x".repeat(160))
            put(database, SettingsRepository.KEY_OPENAI_BASE_URL, "https://legacy.example/v1?key=old")

            repository.save(repository.load())

            assertEquals("", repository.get(SettingsRepository.KEY_OPENAI_MODEL))
            assertEquals("", repository.get(SettingsRepository.KEY_OPENAI_BASE_URL))
            assertEquals(LlmProviderPreference.GLM, repository.load().llmProvider)
        }

    @Test
    fun oversizedLegacyGlmModelFallsBackToSupportedDefault() =
        withRepository { database, repository ->
            put(database, SettingsRepository.KEY_MODEL_NAME, "x".repeat(160))
            put(database, SettingsRepository.KEY_LLM_BASE_URL, GLM_CHAT_BASE_URL)

            assertTrue(repository.migrateLegacyLlmConnection(credentialConfigured = true))

            assertEquals("glm-5.2", repository.load().modelName)
        }

    private fun withRepository(
        block: (PocketPilotDatabase, SettingsRepository) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "settings-migration-${UUID.randomUUID()}.db"
        val database = PocketPilotDatabase(context, databaseName)
        try {
            block(database, SettingsRepository(database))
        } finally {
            database.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun put(database: PocketPilotDatabase, key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
            put("updated_at", System.currentTimeMillis())
        }
        check(
            database.writableDatabase.insertWithOnConflict(
                "settings",
                null,
                values,
                android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L,
        )
    }
}
