package com.string1225.pocketpilot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.update.UpdatePhase
import com.string1225.pocketpilot.update.UpdateRelease
import com.string1225.pocketpilot.update.UpdateState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AppUpdateSettingsUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun availableReleaseExplainsDataPreservationAndStartsDownload() {
        var downloadRequested = false
        renderSettings(
            state = UpdateState(
                phase = UpdatePhase.AVAILABLE,
                currentVersionName = "0.1.0",
                currentVersionCode = 1L,
                release = release(),
            ),
            onDownloadAndInstall = { downloadRequested = true },
        )

        openUpdateDialog("App update")
        composeRule.onNodeWithText("Latest version 0.2.0").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Updates install in place and do not clear model keys, projects, chats, workspaces, or other private app files.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Download & install").performClick()

        composeRule.runOnIdle { assertTrue(downloadRequested) }
    }

    @Test
    fun unknownSourcesStateOffersPermissionAndResumeActions() {
        var settingsRequested = false
        var installRequested = false
        renderSettings(
            state = UpdateState(
                phase = UpdatePhase.INSTALL_PERMISSION_REQUIRED,
                currentVersionName = "0.1.0",
                currentVersionCode = 1L,
                release = release(),
            ),
            onInstall = { installRequested = true },
            onOpenUnknownSourcesSettings = { settingsRequested = true },
        )

        openUpdateDialog("App update")
        composeRule.onNodeWithText("Open install access").performClick()
        composeRule.onNodeWithText("Permission granted, continue").performClick()

        composeRule.runOnIdle {
            assertTrue(settingsRequested)
            assertTrue(installRequested)
        }
    }

    @Test
    fun chineseDownloadingStateShowsProgress() {
        renderSettings(
            state = UpdateState(
                phase = UpdatePhase.DOWNLOADING,
                currentVersionName = "0.1.0",
                currentVersionCode = 1L,
                release = release(),
                bytesDownloaded = 5L * 1024L * 1024L,
                totalBytes = 10L * 1024L * 1024L,
            ),
            language = AppLanguage.CHINESE,
        )

        openUpdateDialog("版本更新")
        composeRule.onNodeWithText("正在下载 5.0 MB / 10.0 MB").assertIsDisplayed()
        composeRule.onNodeWithText("请稍候").assertIsDisplayed()
    }

    private fun renderSettings(
        state: UpdateState,
        language: AppLanguage = AppLanguage.ENGLISH,
        onDownloadAndInstall: () -> Unit = {},
        onInstall: () -> Unit = {},
        onOpenUnknownSourcesSettings: () -> Unit = {},
    ) {
        composeRule.setContent {
            SettingsScreen(
                settings = PocketPilotSettings(language = language),
                snackbarHostState = SnackbarHostState(),
                llmCredentialConfigured = false,
                llmConnectionTestInProgress = false,
                llmConnectionTestSucceeded = null,
                llmConnectionTestError = null,
                gitCredentialConfigured = false,
                remoteServers = emptyList(),
                plugins = emptyList(),
                appUpdate = state,
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
                onDownloadAndInstallAppUpdate = onDownloadAndInstall,
                onInstallAppUpdate = onInstall,
                onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
                onBack = {},
            )
        }
    }

    private fun openUpdateDialog(rowTitle: String) {
        composeRule.onNodeWithTag(SETTINGS_LIST_TEST_TAG).performScrollToNode(hasText(rowTitle))
        composeRule.onNodeWithText(rowTitle).performClick()
    }

    private fun release(): UpdateRelease = UpdateRelease(
        tagName = "v0.2.0",
        versionName = "0.2.0",
        title = "PocketPilot 0.2.0",
        body = "Secure in-app updates.",
        publishedAt = "2026-08-16T00:00:00Z",
        apkSizeBytes = 10L * 1024L * 1024L,
    )
}
