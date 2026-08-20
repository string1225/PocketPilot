package com.string1225.pocketpilot.ui

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.material3.SnackbarHostState
import androidx.test.platform.app.InstrumentationRegistry
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.update.UpdateState
import org.junit.Rule
import org.junit.Test

class ModelConnectionLoadingUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun testInProgressKeepsDialogOpenAndSuccessClosesIt() {
        val inProgress = mutableStateOf(true)
        val succeeded = mutableStateOf<Boolean?>(null)
        val error = mutableStateOf<String?>(null)
        renderSettings {
            Triple(inProgress.value, succeeded.value, error.value)
        }

        composeRule.onNodeWithText("Model").performClick()
        composeRule.onNodeWithText("Testing…").assertIsDisplayed().assertIsNotEnabled()
        composeRule.onNodeWithText("Default text model").assertIsNotEnabled()
        composeRule.onNodeWithText("Cancel").assertIsNotEnabled()

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
        automation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Model connection").assertIsDisplayed()

        composeRule.runOnIdle {
            succeeded.value = true
            inProgress.value = false
        }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Model connection").assertCountEquals(0)
    }

    @Test
    fun failedTestKeepsDialogEditableAndShowsTheError() {
        val inProgress = mutableStateOf(true)
        val succeeded = mutableStateOf<Boolean?>(null)
        val error = mutableStateOf<String?>(null)
        renderSettings {
            Triple(inProgress.value, succeeded.value, error.value)
        }

        composeRule.onNodeWithText("Model").performClick()
        composeRule.runOnIdle {
            error.value = "Connection rejected"
            succeeded.value = false
            inProgress.value = false
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Model connection").assertIsDisplayed()
        composeRule.onNodeWithText("Connection rejected").assertIsDisplayed()
        composeRule.onNodeWithText("Default text model").assertIsEnabled()
        composeRule.onNodeWithText("Cancel").assertIsEnabled()

        // Editing hides the previous failure. A same-message failure returned
        // immediately by retry must still become visible again.
        composeRule.onNodeWithText("AK / API Key").performTextInput("retry-ak")
        composeRule.onAllNodesWithText("Connection rejected").assertCountEquals(0)
        composeRule.onNodeWithText("Save").performClick()
        composeRule.onNodeWithText("Connection rejected").assertIsDisplayed()
    }

    private fun renderSettings(state: () -> Triple<Boolean, Boolean?, String?>) {
        val snackbarHostState = SnackbarHostState()
        composeRule.setContent {
            val current = state()
            SettingsScreen(
                settings = PocketPilotSettings(
                    language = AppLanguage.ENGLISH,
                    llmProvider = LlmProviderPreference.GLM,
                    modelName = LlmProviderPreference.GLM.defaultModel,
                ),
                snackbarHostState = snackbarHostState,
                llmCredentialConfigured = false,
                llmConnectionTestInProgress = current.first,
                llmConnectionTestSucceeded = current.second,
                llmConnectionTestError = current.third,
                gitCredentialConfigured = false,
                remoteServers = emptyList(),
                plugins = emptyList(),
                appUpdate = UpdateState(
                    currentVersionName = "0.1.0",
                    currentVersionCode = 1L,
                ),
                onSettingsChange = {},
                onSaveLlmConnection = { _, _ -> },
                onClearLlmConnectionTestResult = {},
                onRemoveLlmCredential = {},
                onSaveGitCredential = {},
                onRemoveGitCredential = {},
                onSaveRemoteServer = { _, _ -> },
                onDeleteRemoteServer = {},
                onPreviewPluginBundle = { Result.failure(IllegalStateException("not used")) },
                onInstallPluginBundle = {},
                onSetPluginEnabled = { _, _ -> },
                onDeletePlugin = {},
                onCheckForAppUpdate = {},
                onDownloadAndInstallAppUpdate = {},
                onInstallAppUpdate = {},
                onOpenUnknownSourcesSettings = {},
                onBack = {},
            )
        }
    }
}
