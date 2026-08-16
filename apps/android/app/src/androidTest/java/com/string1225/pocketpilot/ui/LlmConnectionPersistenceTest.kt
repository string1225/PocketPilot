package com.string1225.pocketpilot.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.data.PocketPilotDatabase
import com.string1225.pocketpilot.data.SettingsRepository
import com.string1225.pocketpilot.model.GLM_CHAT_BASE_URL
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.runtime.PluginRunCoordinationGate
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlmConnectionPersistenceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: PocketPilotDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var credentials: FakeCredentialStore
    private lateinit var gate: PluginRunCoordinationGate

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "llm-connection-${UUID.randomUUID()}.db"
        database = PocketPilotDatabase(context, databaseName)
        settings = SettingsRepository(database)
        credentials = FakeCredentialStore()
        gate = PluginRunCoordinationGate()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun providerSwitchRequiresANewAkAndIsBlockedByAnActiveRun() {
        val originalSecret = "openai-ak".toCharArray()
        persistLlmConnection(openAiSettings(), originalSecret, settings, credentials, gate)
        originalSecret.fill('\u0000')

        gate.beginRun("active-run")
        val blockedSecret = "glm-ak".toCharArray()
        try {
            assertThrows(IllegalStateException::class.java) {
                persistLlmConnection(glmSettings(), blockedSecret, settings, credentials, gate)
            }
        } finally {
            blockedSecret.fill('\u0000')
        }
        gate.finishRun("active-run")
        assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.load().llmProvider)
        assertArrayEquals("openai-ak".toCharArray(), credentials.get(CredentialIds.DEFAULT_LLM))

        assertThrows(IllegalArgumentException::class.java) {
            persistLlmConnection(glmSettings(), null, settings, credentials, gate)
        }
        assertTrue(credentials.lastReturnedSecret?.all { it == '\u0000' } == true)
        assertEquals(LlmProviderPreference.OPENAI_CHAT, settings.load().llmProvider)

        val replacement = "glm-ak".toCharArray()
        persistLlmConnection(glmSettings(), replacement, settings, credentials, gate)
        replacement.fill('\u0000')
        val loaded = settings.load()
        assertEquals(LlmProviderPreference.GLM, loaded.llmProvider)
        assertEquals(GLM_CHAT_BASE_URL, loaded.llmBaseUrl)
        assertArrayEquals("glm-ak".toCharArray(), credentials.get(CredentialIds.DEFAULT_LLM))
    }

    @Test
    fun failedCredentialWriteRestoresThePreviousConnectionAndAk() {
        val originalSecret = "openai-ak".toCharArray()
        persistLlmConnection(openAiSettings(), originalSecret, settings, credentials, gate)
        originalSecret.fill('\u0000')
        credentials.failPutCount = 1

        val replacement = "replacement-ak".toCharArray()
        try {
            assertThrows(IllegalStateException::class.java) {
                persistLlmConnection(glmSettings(), replacement, settings, credentials, gate)
            }
        } finally {
            replacement.fill('\u0000')
        }

        val loaded = settings.load()
        assertEquals(LlmProviderPreference.OPENAI_CHAT, loaded.llmProvider)
        assertEquals("https://gateway.example/v1", loaded.llmBaseUrl)
        assertEquals("custom-model", loaded.modelName)
        assertArrayEquals("openai-ak".toCharArray(), credentials.get(CredentialIds.DEFAULT_LLM))
    }

    @Test
    fun uncertainCredentialRollbackDisablesTheConnectionAndErasesTheKey() {
        val originalSecret = "openai-ak".toCharArray()
        persistLlmConnection(openAiSettings(), originalSecret, settings, credentials, gate)
        originalSecret.fill('\u0000')
        credentials.failPutCount = 2
        credentials.persistBeforeNextPutFailure = true

        val replacement = "glm-ak".toCharArray()
        try {
            assertThrows(IllegalStateException::class.java) {
                persistLlmConnection(glmSettings(), replacement, settings, credentials, gate)
            }
        } finally {
            replacement.fill('\u0000')
        }

        val disabled = settings.load()
        assertEquals(LlmProviderPreference.OPENAI_CHAT, disabled.llmProvider)
        assertEquals("", disabled.modelName)
        assertEquals("", disabled.llmBaseUrl)
        assertFalse(credentials.contains(CredentialIds.DEFAULT_LLM))
    }

    private fun openAiSettings(): PocketPilotSettings = PocketPilotSettings(
        llmProvider = LlmProviderPreference.OPENAI_CHAT,
        modelName = "custom-model",
        llmBaseUrl = "https://gateway.example/v1",
        openAiModelName = "custom-model",
        openAiBaseUrl = "https://gateway.example/v1",
    )

    private fun glmSettings(): PocketPilotSettings = PocketPilotSettings(
        llmProvider = LlmProviderPreference.GLM,
        modelName = "glm-5.2",
        llmBaseUrl = "https://must-not-be-used.example/v1",
    )

    private class FakeCredentialStore : SecureCredentialStore {
        private val values = mutableMapOf<String, CharArray>()
        var failPutCount: Int = 0
        var persistBeforeNextPutFailure: Boolean = false
        var lastReturnedSecret: CharArray? = null
            private set

        override fun put(credentialId: String, secret: CharArray) {
            if (failPutCount > 0) {
                failPutCount -= 1
                if (persistBeforeNextPutFailure) {
                    persistBeforeNextPutFailure = false
                    values.put(credentialId, secret.copyOf())?.fill('\u0000')
                }
                throw IllegalStateException("simulated credential write failure")
            }
            values.put(credentialId, secret.copyOf())?.fill('\u0000')
        }

        override fun get(credentialId: String): CharArray? = values[credentialId]?.copyOf()?.also {
            lastReturnedSecret = it
        }

        override fun contains(credentialId: String): Boolean = values.containsKey(credentialId)

        override fun remove(credentialId: String) {
            values.remove(credentialId)?.fill('\u0000')
        }
    }
}
