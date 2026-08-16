package com.string1225.pocketpilot.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.LlmProtocolPreference
import com.string1225.pocketpilot.model.LlmSettingsPolicy
import com.string1225.pocketpilot.model.InstalledPlugin
import com.string1225.pocketpilot.model.PluginInstallPreview
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.model.RemoteServerProfile
import com.string1225.pocketpilot.model.SshAuthType
import com.string1225.pocketpilot.model.ThemePreference
import com.string1225.pocketpilot.update.UpdatePhase
import com.string1225.pocketpilot.update.UpdateState
import java.util.Locale
import java.util.UUID

private enum class EditableSetting {
    PERSONALIZATION,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: PocketPilotSettings,
    snackbarHostState: SnackbarHostState,
    llmCredentialConfigured: Boolean,
    llmConnectionTestInProgress: Boolean,
    llmConnectionTestSucceeded: Boolean?,
    llmConnectionTestError: String?,
    gitCredentialConfigured: Boolean,
    remoteServers: List<RemoteServerProfile>,
    plugins: List<InstalledPlugin>,
    appUpdate: UpdateState,
    onSettingsChange: (PocketPilotSettings) -> Unit,
    onSaveLlmConnection: (PocketPilotSettings, String?) -> Unit,
    onClearLlmConnectionTestResult: () -> Unit,
    onRemoveLlmCredential: () -> Unit,
    onSaveGitCredential: (String) -> Unit,
    onRemoveGitCredential: () -> Unit,
    onSaveRemoteServer: (RemoteServerProfile, String?) -> Unit,
    onDeleteRemoteServer: (String) -> Unit,
    onPreviewPluginBundle: (String) -> Result<PluginInstallPreview>,
    onInstallPluginBundle: (String) -> Unit,
    onSetPluginEnabled: (String, Boolean) -> Unit,
    onDeletePlugin: (String) -> Unit,
    onCheckForAppUpdate: () -> Unit,
    onDownloadAndInstallAppUpdate: () -> Unit,
    onInstallAppUpdate: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val language = settings.language
    var editableSetting by remember { mutableStateOf<EditableSetting?>(null) }
    var showTheme by rememberSaveable { mutableStateOf(false) }
    var showLanguage by rememberSaveable { mutableStateOf(false) }
    var showModelSettings by rememberSaveable { mutableStateOf(false) }
    var showRemoteServers by rememberSaveable { mutableStateOf(false) }
    var showGitCredential by rememberSaveable { mutableStateOf(false) }
    var showTools by rememberSaveable { mutableStateOf(false) }
    var showPlugins by rememberSaveable { mutableStateOf(false) }
    var showAppUpdate by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(ppText(language, "设置", "Settings")) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = ppText(language, "返回", "Back"),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag(SETTINGS_LIST_TEST_TAG),
        ) {
            item {
                SettingsHeader(
                    title = ppText(language, "智能与连接", "Intelligence & connections"),
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.SmartToy,
                    title = ppText(language, "模型", "Model"),
                    value = "${settings.modelName.ifBlank { ppText(language, "未配置模型", "No model") }} · " +
                        "${settings.llmProvider.label()} · " +
                        if (llmCredentialConfigured) ppText(language, "已配置密钥", "Key configured")
                        else ppText(language, "未配置密钥", "No key"),
                    onClick = {
                        onClearLlmConnectionTestResult()
                        showModelSettings = true
                    },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Cloud,
                    title = ppText(language, "远程服务器", "Remote server"),
                    value = if (remoteServers.isEmpty()) {
                        ppText(language, "未配置", "Not configured")
                    } else {
                        ppText(language, "${remoteServers.size} 台服务器", "${remoteServers.size} server(s)")
                    },
                    onClick = { showRemoteServers = true },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Tune,
                    title = ppText(language, "个性化", "Personalization"),
                    value = settings.personalization.ifBlank { ppText(language, "使用默认行为", "Default behavior") },
                    onClick = { editableSetting = EditableSetting.PERSONALIZATION },
                )
            }
            item { HorizontalDivider() }
            item { SettingsHeader(ppText(language, "能力", "Capabilities")) }
            item {
                SettingsToggleRow(
                    icon = Icons.Default.History,
                    title = ppText(language, "记忆", "Memory"),
                    description = ppText(language, "允许模型使用当前会话的早期消息", "Let the model use earlier messages in this chat"),
                    checked = settings.memoryEnabled,
                    onCheckedChange = { onSettingsChange(settings.copy(memoryEnabled = it)) },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Build,
                    title = ppText(language, "工具", "Tools"),
                    value = if (settings.toolsEnabled) {
                        val pluginTools = plugins.filter { it.enabled }.sumOf { it.tools.size }
                        ppText(
                            language,
                            "内置工具 + $pluginTools 个插件工具",
                            "Built-in tools + $pluginTools plugin tool(s)",
                        )
                    } else {
                        ppText(language, "已停用", "Disabled")
                    },
                    onClick = { showTools = true },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Key,
                    title = ppText(language, "Git HTTPS 凭据", "Git HTTPS credential"),
                    value = if (gitCredentialConfigured) {
                        ppText(language, "已安全保存", "Stored securely")
                    } else {
                        ppText(language, "仅支持公开仓库", "Public repositories only")
                    },
                    onClick = { showGitCredential = true },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Extension,
                    title = ppText(language, "插件", "Plugins"),
                    value = if (plugins.isEmpty()) {
                        ppText(language, "当前未安装插件", "No plugins installed")
                    } else {
                        ppText(
                            language,
                            "${plugins.count { it.enabled }} / ${plugins.size} 个已启用",
                            "${plugins.count { it.enabled }} / ${plugins.size} enabled",
                        )
                    },
                    onClick = { showPlugins = true },
                )
            }
            item { HorizontalDivider() }
            item { SettingsHeader(ppText(language, "应用", "App")) }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Palette,
                    title = ppText(language, "外观", "Appearance"),
                    value = settings.theme.label(language),
                    onClick = { showTheme = true },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.Language,
                    title = ppText(language, "语言", "Language"),
                    value = settings.language.label(),
                    onClick = { showLanguage = true },
                )
            }
            item {
                SettingsValueRow(
                    icon = Icons.Default.SystemUpdate,
                    title = ppText(language, "版本更新", "App update"),
                    value = appUpdate.summary(language),
                    onClick = { showAppUpdate = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("PocketPilot") },
                    supportingContent = {
                        Text(
                            ppText(
                                language,
                                "密钥由 Android Keystore 加密；不会进入项目、日志或 Git。",
                                "Secrets are encrypted by Android Keystore and never enter projects, logs, or Git.",
                            ),
                        )
                    },
                    leadingContent = { Icon(Icons.Default.Settings, contentDescription = null) },
                )
            }
        }
    }

    editableSetting?.let { field ->
        EditableSettingDialog(
            field = field,
            settings = settings,
            onDismiss = { editableSetting = null },
            onSave = { value ->
                editableSetting = null
                onSettingsChange(
                    when (field) {
                        EditableSetting.PERSONALIZATION -> settings.copy(personalization = value)
                    },
                )
            },
        )
    }

    if (showModelSettings) {
        ModelSettingsDialog(
            settings = settings,
            credentialConfigured = llmCredentialConfigured,
            testInProgress = llmConnectionTestInProgress,
            testSucceeded = llmConnectionTestSucceeded,
            testError = llmConnectionTestError,
            onDismiss = {
                showModelSettings = false
                onClearLlmConnectionTestResult()
            },
            onSave = { updated, secret ->
                onSaveLlmConnection(updated, secret?.takeIf { it.isNotBlank() })
            },
            onRemoveCredential = {
                showModelSettings = false
                onClearLlmConnectionTestResult()
                onRemoveLlmCredential()
            },
        )
    }

    if (showGitCredential) {
        CredentialDialog(
            language = language,
            title = ppText(language, "Git HTTPS Token", "Git HTTPS token"),
            configured = gitCredentialConfigured,
            onDismiss = { showGitCredential = false },
            onSave = {
                showGitCredential = false
                onSaveGitCredential(it)
            },
            onRemove = {
                showGitCredential = false
                onRemoveGitCredential()
            },
        )
    }

    if (showRemoteServers) {
        RemoteServersDialog(
            language = language,
            servers = remoteServers,
            onDismiss = { showRemoteServers = false },
            onSave = onSaveRemoteServer,
            onDelete = onDeleteRemoteServer,
        )
    }

    if (showTools) {
        ToolSettingsDialog(
            language = language,
            enabled = settings.toolsEnabled,
            plugins = plugins,
            onEnabledChange = { onSettingsChange(settings.copy(toolsEnabled = it)) },
            onDismiss = { showTools = false },
        )
    }

    if (showPlugins) {
        PluginManagerDialog(
            language = language,
            plugins = plugins,
            onPreviewBundle = onPreviewPluginBundle,
            onInstallBundle = onInstallPluginBundle,
            onSetEnabled = onSetPluginEnabled,
            onDelete = onDeletePlugin,
            onDismiss = { showPlugins = false },
        )
    }

    if (showTheme) {
        ChoiceDialog(
            title = ppText(language, "外观", "Appearance"),
            choices = ThemePreference.entries,
            selected = settings.theme,
            label = { it.label(language) },
            onSelect = {
                showTheme = false
                onSettingsChange(settings.copy(theme = it))
            },
            onDismiss = { showTheme = false },
        )
    }

    if (showLanguage) {
        ChoiceDialog(
            title = ppText(language, "语言", "Language"),
            choices = AppLanguage.entries,
            selected = settings.language,
            label = { it.label() },
            onSelect = {
                showLanguage = false
                onSettingsChange(settings.copy(language = it))
            },
            onDismiss = { showLanguage = false },
        )
    }

    if (showAppUpdate) {
        AppUpdateDialog(
            state = appUpdate,
            language = language,
            onCheck = onCheckForAppUpdate,
            onDownloadAndInstall = onDownloadAndInstallAppUpdate,
            onInstall = onInstallAppUpdate,
            onOpenUnknownSourcesSettings = onOpenUnknownSourcesSettings,
            onDismiss = { showAppUpdate = false },
        )
    }
}

@Composable
private fun SettingsHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingsValueRow(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(title) },
        supportingContent = { Text(value, maxLines = 2) },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = { Icon(icon, contentDescription = null) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun AppUpdateDialog(
    state: UpdateState,
    language: AppLanguage,
    onCheck: () -> Unit,
    onDownloadAndInstall: () -> Unit,
    onInstall: () -> Unit,
    onOpenUnknownSourcesSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val release = state.release
    val totalBytes = state.totalBytes?.takeIf { it > 0L }
    val progress = totalBytes?.let {
        (state.bytesDownloaded.toFloat() / it.toFloat()).coerceIn(0f, 1f)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "版本更新", "App update")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    ppText(
                        language,
                        "当前版本 ${state.currentVersionName}（${state.currentVersionCode}）",
                        "Current version ${state.currentVersionName} (${state.currentVersionCode})",
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                release?.let {
                    Text(
                        ppText(
                            language,
                            "最新版本 ${it.versionName}",
                            "Latest version ${it.versionName}",
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (it.title.isNotBlank() && it.title != it.versionName && it.title != it.tagName) {
                        Text(it.title, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (it.body.isNotBlank()) {
                        Text(
                            it.body.take(MAX_VISIBLE_RELEASE_NOTES_CHARS),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                        )
                    }
                }

                when (state.phase) {
                    UpdatePhase.CHECKING -> UpdateBusyRow(
                        ppText(language, "正在检查 GitHub Release…", "Checking GitHub Releases…"),
                    )

                    UpdatePhase.DOWNLOADING -> {
                        Text(
                            ppText(
                                language,
                                "正在下载 ${formatByteCount(state.bytesDownloaded)}" +
                                    (totalBytes?.let { " / ${formatByteCount(it)}" } ?: ""),
                                "Downloading ${formatByteCount(state.bytesDownloaded)}" +
                                    (totalBytes?.let { " / ${formatByteCount(it)}" } ?: ""),
                            ),
                        )
                        if (progress == null) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    UpdatePhase.UP_TO_DATE -> Text(
                        ppText(language, "当前已是最新版本。", "PocketPilot is up to date."),
                    )

                    UpdatePhase.AVAILABLE -> Text(
                        ppText(
                            language,
                            "发现新版本。点击“下载并安装”后，PocketPilot 会先校验安装包，再打开 Android 系统安装确认。",
                            "A new version is available. Download & install verifies the APK before opening Android's installer.",
                        ),
                    )

                    UpdatePhase.READY_TO_INSTALL -> Text(
                        ppText(
                            language,
                            "安装包已下载并通过校验，可以打开系统安装器。",
                            "The APK is downloaded and verified. It is ready for Android's installer.",
                        ),
                    )

                    UpdatePhase.INSTALL_PERMISSION_REQUIRED -> {
                        Text(
                            ppText(
                                language,
                                "Android 需要你允许 PocketPilot 安装未知应用。打开授权页并允许后，返回这里继续安装。",
                                "Android needs permission for PocketPilot to install unknown apps. Enable it, return here, then continue installation.",
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = onInstall) {
                            Text(ppText(language, "已授权，继续安装", "Permission granted, continue"))
                        }
                    }

                    UpdatePhase.INSTALL_LAUNCHED -> Text(
                        ppText(
                            language,
                            "系统安装器已打开；确认安装即可完成更新。",
                            "Android's installer is open. Confirm there to finish the update.",
                        ),
                    )

                    UpdatePhase.ERROR -> Text(
                        state.errorMessage?.takeIf { it.isNotBlank() }
                            ?: ppText(language, "更新失败，请重试。", "Update failed. Try again."),
                        color = MaterialTheme.colorScheme.error,
                    )

                    UpdatePhase.IDLE,
                    UpdatePhase.THROTTLED,
                    -> Text(
                        ppText(
                            language,
                            "PocketPilot 会在启动后自动检查，也可以立即手动检查。",
                            "PocketPilot checks automatically after startup, or you can check now.",
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    ppText(
                        language,
                        "更新采用覆盖安装，不会清除模型 AK、项目、会话、工作区或其他应用私有文件。",
                        "Updates install in place and do not clear model keys, projects, chats, workspaces, or other private app files.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            when (state.phase) {
                UpdatePhase.CHECKING,
                UpdatePhase.DOWNLOADING,
                -> TextButton(onClick = {}, enabled = false) {
                    Text(ppText(language, "请稍候", "Please wait"))
                }

                UpdatePhase.AVAILABLE -> TextButton(onClick = onDownloadAndInstall) {
                    Text(ppText(language, "下载并安装", "Download & install"))
                }

                UpdatePhase.READY_TO_INSTALL,
                UpdatePhase.INSTALL_LAUNCHED,
                -> TextButton(onClick = onInstall) {
                    Text(ppText(language, "安装更新", "Install update"))
                }

                UpdatePhase.INSTALL_PERMISSION_REQUIRED -> TextButton(onClick = onOpenUnknownSourcesSettings) {
                    Text(ppText(language, "打开授权设置", "Open install access"))
                }

                UpdatePhase.ERROR -> TextButton(
                    onClick = if (release == null) onCheck else onDownloadAndInstall,
                ) {
                    Text(ppText(language, "重试", "Retry"))
                }

                UpdatePhase.IDLE,
                UpdatePhase.THROTTLED,
                UpdatePhase.UP_TO_DATE,
                -> TextButton(onClick = onCheck) {
                    Text(ppText(language, "检查更新", "Check for updates"))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(ppText(language, "关闭", "Close"))
            }
        },
    )
}

@Composable
private fun UpdateBusyRow(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label)
    }
}

private fun UpdateState.summary(language: AppLanguage): String = when (phase) {
    UpdatePhase.CHECKING -> ppText(language, "正在检查…", "Checking…")
    UpdatePhase.THROTTLED -> ppText(
        language,
        "已开启启动自动检查 · 当前 ${currentVersionName}",
        "Startup checks enabled · Current ${currentVersionName}",
    )
    UpdatePhase.UP_TO_DATE -> ppText(
        language,
        "已是最新版本 · ${currentVersionName}",
        "Up to date · ${currentVersionName}",
    )
    UpdatePhase.AVAILABLE -> ppText(
        language,
        "新版本 ${release?.versionName.orEmpty()} 可用",
        "Version ${release?.versionName.orEmpty()} available",
    )
    UpdatePhase.DOWNLOADING -> {
        val percentage = totalBytes
            ?.takeIf { it > 0L }
            ?.let { ((bytesDownloaded * 100L) / it).coerceIn(0L, 100L) }
        if (percentage == null) {
            ppText(language, "正在下载更新…", "Downloading update…")
        } else {
            ppText(language, "正在下载更新 · $percentage%", "Downloading update · $percentage%")
        }
    }
    UpdatePhase.READY_TO_INSTALL -> ppText(
        language,
        "新版本 ${release?.versionName.orEmpty()} 已就绪",
        "Version ${release?.versionName.orEmpty()} is ready",
    )
    UpdatePhase.INSTALL_PERMISSION_REQUIRED -> ppText(
        language,
        "需要安装权限 · 点击继续",
        "Install access required · Tap to continue",
    )
    UpdatePhase.INSTALL_LAUNCHED -> ppText(
        language,
        "等待系统安装确认",
        "Waiting for Android installer",
    )
    UpdatePhase.ERROR -> ppText(language, "更新失败 · 点击查看", "Update failed · Tap for details")
    UpdatePhase.IDLE -> ppText(
        language,
        "当前版本 ${currentVersionName} · 启动时自动检查",
        "Current ${currentVersionName} · Checks at startup",
    )
}

private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024L * 1024L))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024L)
    else -> "$bytes B"
}

private const val MAX_VISIBLE_RELEASE_NOTES_CHARS = 1_500
internal const val SETTINGS_LIST_TEST_TAG = "settings-list"

@Composable
private fun EditableSettingDialog(
    field: EditableSetting,
    settings: PocketPilotSettings,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val language = settings.language
    val initial = when (field) {
        EditableSetting.PERSONALIZATION -> settings.personalization
    }
    var value by rememberSaveable(field) { mutableStateOf(initial) }
    val title = when (field) {
        EditableSetting.PERSONALIZATION -> ppText(
            language,
            "个性化",
            "Personalization",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.take(if (field == EditableSetting.PERSONALIZATION) 4_000 else 240)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = if (field == EditableSetting.PERSONALIZATION) 4 else 1,
                    maxLines = if (field == EditableSetting.PERSONALIZATION) 8 else 2,
                    label = { Text(title) },
                    placeholder = {
                        Text(
                            ppText(
                                language,
                                "例如：回答简洁直接；先给结论，再说明关键依据。",
                                "For example: Be concise; lead with the conclusion, then explain the key reasons.",
                            ),
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.trim()) }) {
                Text(ppText(language, "保存", "Save"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}

@Composable
private fun <T> ChoiceDialog(
    title: String,
    choices: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                choices.forEach { choice ->
                    ListItem(
                        modifier = Modifier.clickable { onSelect(choice) },
                        headlineContent = { Text(label(choice)) },
                        leadingContent = {
                            RadioButton(selected = choice == selected, onClick = { onSelect(choice) })
                        },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ModelSettingsDialog(
    settings: PocketPilotSettings,
    credentialConfigured: Boolean,
    testInProgress: Boolean,
    testSucceeded: Boolean?,
    testError: String?,
    onDismiss: () -> Unit,
    onSave: (PocketPilotSettings, String?) -> Unit,
    onRemoveCredential: () -> Unit,
) {
    val language = settings.language
    var provider by rememberSaveable { mutableStateOf(settings.llmProvider) }
    var openAiModel by rememberSaveable {
        mutableStateOf(
            settings.openAiModelName.ifBlank {
                settings.modelName.takeIf {
                    settings.llmProvider == LlmProviderPreference.OPENAI_CHAT
                }.orEmpty()
            },
        )
    }
    var openAiImageModel by rememberSaveable {
        mutableStateOf(
            settings.openAiImageModelName.ifBlank {
                settings.imageModelName.takeIf {
                    settings.llmProvider == LlmProviderPreference.OPENAI_CHAT
                }.orEmpty()
            },
        )
    }
    var glmModel by rememberSaveable {
        mutableStateOf(
            settings.modelName.takeIf { settings.llmProvider == LlmProviderPreference.GLM }
                ?: LlmProviderPreference.GLM.defaultModel,
        )
    }
    var openAiBaseUrl by rememberSaveable {
        mutableStateOf(
            settings.openAiBaseUrl.ifBlank {
                settings.llmBaseUrl.takeIf {
                    settings.llmProvider == LlmProviderPreference.OPENAI_CHAT
                }.orEmpty()
            },
        )
    }
    // Never persist credentials through SavedState/Bundle. This state exists
    // only while the dialog is composed and is cleared before every exit.
    var secret by remember { mutableStateOf("") }
    var submittedTestAttempt by rememberSaveable { mutableStateOf(testInProgress) }
    var testErrorDismissedByEdit by remember { mutableStateOf(false) }
    val model = when (provider) {
        LlmProviderPreference.OPENAI_CHAT -> openAiModel
        LlmProviderPreference.GLM -> glmModel
    }
    val resolvedBaseUrl = LlmSettingsPolicy.resolveBaseUrl(provider, openAiBaseUrl)
    val requiresNewCredential = LlmSettingsPolicy.requiresNewCredential(
        previousProvider = settings.llmProvider,
        previousBaseUrl = settings.llmBaseUrl,
        newProvider = provider,
        newBaseUrl = resolvedBaseUrl,
        credentialConfigured = credentialConfigured,
    )
    val selectProvider: (LlmProviderPreference) -> Unit = { choice ->
        if (provider != choice) {
            testErrorDismissedByEdit = true
            provider = choice
            // A credential is scoped to its provider and endpoint. Clearing the draft
            // prevents accidentally submitting a key typed for the previous host.
            secret = ""
        }
    }
    val clearAndDismiss = {
        secret = ""
        onDismiss()
    }
    LaunchedEffect(testInProgress, testSucceeded) {
        if (testInProgress) {
            submittedTestAttempt = true
        } else if (submittedTestAttempt && testSucceeded == true) {
            clearAndDismiss()
        }
    }
    LaunchedEffect(testError) {
        testErrorDismissedByEdit = false
    }
    AlertDialog(
        onDismissRequest = {
            if (!testInProgress) clearAndDismiss()
        },
        title = { Text(ppText(language, "模型连接", "Model connection")) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!testInProgress && !testErrorDismissedByEdit && !testError.isNullOrBlank()) {
                    Text(
                        text = testError,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(ppText(language, "模型服务", "Model provider"), style = MaterialTheme.typography.labelLarge)
                LlmProviderPreference.entries.forEach { choice ->
                    ListItem(
                        modifier = Modifier.clickable(enabled = !testInProgress) { selectProvider(choice) },
                        headlineContent = { Text(choice.label()) },
                        leadingContent = {
                            RadioButton(
                                selected = provider == choice,
                                enabled = !testInProgress,
                                onClick = { selectProvider(choice) },
                            )
                        },
                    )
                }
                if (provider.hasEditableBaseUrl) {
                    OutlinedTextField(
                        value = openAiBaseUrl,
                        onValueChange = {
                            testErrorDismissedByEdit = true
                            openAiBaseUrl = it.take(2_048)
                            secret = ""
                        },
                        label = { Text(ppText(language, "兼容 API Endpoint", "Compatible API endpoint")) },
                        singleLine = true,
                        enabled = !testInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        ppText(
                            language,
                            "GLM 使用内置安全 Endpoint，只需填写模型编码和 AK。",
                            "GLM uses its built-in secure endpoint; enter only the model code and AK.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = model,
                    onValueChange = { value ->
                        testErrorDismissedByEdit = true
                        when (provider) {
                            LlmProviderPreference.OPENAI_CHAT -> openAiModel = value.take(128)
                            LlmProviderPreference.GLM -> glmModel = value.take(128)
                        }
                    },
                    label = { Text(ppText(language, "默认文本模型", "Default text model")) },
                    singleLine = true,
                    enabled = !testInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (provider == LlmProviderPreference.OPENAI_CHAT) {
                    OutlinedTextField(
                        value = openAiImageModel,
                        onValueChange = {
                            testErrorDismissedByEdit = true
                            openAiImageModel = it.take(128)
                        },
                        label = { Text(ppText(language, "图片模型", "Image model")) },
                        supportingText = {
                            Text(
                                ppText(
                                    language,
                                    "用于图片理解，必须支持 Chat Completions 的 image_url 输入。",
                                    "Used for image understanding; it must accept image_url input through Chat Completions.",
                                ),
                            )
                        },
                        singleLine = true,
                        enabled = !testInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        ppText(
                            language,
                            "图片识别固定使用 GLM-5V-Turbo（官方视觉 Endpoint）。",
                            "Image understanding always uses GLM-5V-Turbo through its official vision endpoint.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        testErrorDismissedByEdit = true
                        secret = it.take(16_384)
                    },
                    label = {
                        Text(
                            if (credentialConfigured && !requiresNewCredential) {
                                ppText(language, "新 AK（留空则不更改）", "New AK (blank keeps current)")
                            } else {
                                "AK / API Key"
                            },
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    enabled = !testInProgress,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (requiresNewCredential && credentialConfigured) {
                    Text(
                        ppText(
                            language,
                            "切换模型服务或 Endpoint 后必须重新填写 AK，避免把旧密钥发送到新地址。",
                            "Re-enter the AK after changing provider or endpoint so an existing key is never sent to a new host.",
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    ppText(
                        language,
                        "保存时会分别测试默认文本模型和图片模型；测试未通过则不会启用这组连接。",
                        "Saving tests both the default text model and image model; the connection is not enabled unless both tests pass.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !testInProgress &&
                    model.isNotBlank() &&
                    (provider == LlmProviderPreference.GLM || openAiImageModel.isNotBlank()) &&
                    (provider == LlmProviderPreference.GLM ||
                        LlmSettingsPolicy.isValidOpenAiBaseUrl(resolvedBaseUrl)) &&
                    (!requiresNewCredential || secret.isNotBlank()),
                onClick = {
                    val submittedSecret = secret.takeIf { it.isNotBlank() }
                    submittedTestAttempt = true
                    testErrorDismissedByEdit = false
                    onSave(
                        settings.copy(
                            modelName = model.trim(),
                            llmProvider = provider,
                            llmProtocol = LlmProtocolPreference.CHAT_COMPLETIONS,
                            llmBaseUrl = resolvedBaseUrl,
                            openAiModelName = openAiModel.trim(),
                            openAiBaseUrl = openAiBaseUrl.trim(),
                            imageModelName = if (provider == LlmProviderPreference.GLM) {
                                provider.defaultImageModel
                            } else {
                                openAiImageModel.trim()
                            },
                            openAiImageModelName = openAiImageModel.trim(),
                        ),
                        submittedSecret,
                    )
                },
            ) {
                if (testInProgress) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(ppText(language, "测试中…", "Testing…"))
                    }
                } else {
                    Text(ppText(language, "保存", "Save"))
                }
            }
        },
        dismissButton = {
            Row {
                if (credentialConfigured) {
                    TextButton(
                        enabled = !testInProgress,
                        onClick = {
                            secret = ""
                            onRemoveCredential()
                        },
                    ) {
                        Text(ppText(language, "移除密钥", "Remove key"))
                    }
                }
                TextButton(
                    enabled = !testInProgress,
                    onClick = clearAndDismiss,
                ) { Text(ppText(language, "取消", "Cancel")) }
            }
        },
    )
}

@Composable
private fun CredentialDialog(
    language: AppLanguage,
    title: String,
    configured: Boolean,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    val clearAndDismiss = {
        secret = ""
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = clearAndDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (configured) {
                        ppText(language, "凭据已配置。输入新值可替换。", "A credential is configured. Enter a new value to replace it.")
                    } else {
                        ppText(language, "只保存在本机加密存储中。", "Stored only in encrypted device storage.")
                    },
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it.take(16_384) },
                    label = { Text(title) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = secret.isNotBlank(),
                onClick = {
                    val submittedSecret = secret
                    secret = ""
                    onSave(submittedSecret)
                },
            ) { Text(ppText(language, "保存", "Save")) }
        },
        dismissButton = {
            Row {
                if (configured) {
                    TextButton(onClick = {
                        secret = ""
                        onRemove()
                    }) {
                        Text(ppText(language, "移除", "Remove"))
                    }
                }
                TextButton(onClick = clearAndDismiss) { Text(ppText(language, "取消", "Cancel")) }
            }
        },
    )
}

@Composable
private fun RemoteServersDialog(
    language: AppLanguage,
    servers: List<RemoteServerProfile>,
    onDismiss: () -> Unit,
    onSave: (RemoteServerProfile, String?) -> Unit,
    onDelete: (String) -> Unit,
) {
    var editing by remember { mutableStateOf<RemoteServerProfile?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "远程服务器", "Remote servers")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (servers.isEmpty()) {
                    Text(ppText(language, "尚未配置服务器。", "No servers configured."))
                }
                servers.forEach { server ->
                    ListItem(
                        modifier = Modifier.clickable { editing = server },
                        headlineContent = { Text(server.name) },
                        supportingContent = { Text("${server.username}@${server.host}:${server.port}") },
                        trailingContent = {
                            IconButton(onClick = { onDelete(server.id) }) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = ppText(language, "删除", "Delete"),
                                )
                            }
                        },
                    )
                }
                Button(
                    onClick = {
                        val id = UUID.randomUUID().toString()
                        editing = RemoteServerProfile(
                            id = id,
                            name = "",
                            host = "",
                            username = "",
                            hostKeyFingerprint = "",
                            credentialId = "ssh.$id",
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(ppText(language, "添加服务器", "Add server"))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "完成", "Done")) }
        },
    )

    editing?.let { server ->
        RemoteServerEditorDialog(
            language = language,
            initial = server,
            onDismiss = { editing = null },
            onSave = { updated, secret ->
                editing = null
                onSave(updated, secret)
            },
        )
    }
}

@Composable
private fun RemoteServerEditorDialog(
    language: AppLanguage,
    initial: RemoteServerProfile,
    onDismiss: () -> Unit,
    onSave: (RemoteServerProfile, String?) -> Unit,
) {
    var name by rememberSaveable(initial.id) { mutableStateOf(initial.name) }
    var host by rememberSaveable(initial.id) { mutableStateOf(initial.host) }
    var port by rememberSaveable(initial.id) { mutableStateOf(initial.port.toString()) }
    var username by rememberSaveable(initial.id) { mutableStateOf(initial.username) }
    var authType by rememberSaveable(initial.id) { mutableStateOf(initial.authType) }
    var fingerprint by rememberSaveable(initial.id) { mutableStateOf(initial.hostKeyFingerprint) }
    var description by rememberSaveable(initial.id) { mutableStateOf(initial.description) }
    var secret by remember(initial.id) { mutableStateOf("") }
    var credentialImportError by remember(initial.id) { mutableStateOf<String?>(null) }
    val clearAndDismiss = {
        secret = ""
        onDismiss()
    }
    val parsedPort = port.toIntOrNull()
    val needsNewCredential = !initial.hasCredential || authType != initial.authType
    val valid = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() &&
        parsedPort != null && parsedPort in 1..65535 && fingerprint.startsWith("SHA256:") &&
        (!needsNewCredential || secret.isNotBlank())

    AlertDialog(
        onDismissRequest = clearAndDismiss,
        title = { Text(ppText(language, "SSH 服务器", "SSH server")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(120) },
                        label = { Text(ppText(language, "名称", "Name")) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.take(253) },
                        label = { Text("Host") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit).take(5) },
                            label = { Text("Port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(0.35f),
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it.take(128) },
                            label = { Text(ppText(language, "用户名", "Username")) },
                            modifier = Modifier.weight(0.65f),
                        )
                    }
                }
                item {
                    Row {
                        SshAuthType.entries.forEach { choice ->
                            val selectChoice = {
                                if (authType != choice) {
                                    secret = ""
                                    credentialImportError = null
                                    authType = choice
                                }
                            }
                            TextButton(onClick = selectChoice) {
                                RadioButton(selected = authType == choice, onClick = selectChoice)
                                Text(choice.label(language))
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = fingerprint,
                        onValueChange = { fingerprint = it.trim().take(80) },
                        label = { Text("Host key SHA256 fingerprint") },
                        supportingText = { Text("SHA256:…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        ppText(
                            language,
                            "请从服务器管理员或服务器控制台核对主机指纹。可在服务器执行：ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub。不要仅信任首次连接弹出的指纹。",
                            "Verify the host fingerprint with the server administrator or console. On the server, run: ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub. Do not trust a fingerprint shown only by the first connection attempt.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (authType == SshAuthType.PASSWORD) {
                    item {
                        OutlinedTextField(
                            value = secret,
                            onValueChange = { secret = it.take(16_384) },
                            label = {
                                Text(
                                    if (initial.hasCredential) {
                                        ppText(
                                            language,
                                            "新密码（留空不更改）",
                                            "New password (blank keeps current)",
                                        )
                                    } else {
                                        ppText(language, "密码", "Password")
                                    },
                                )
                            },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                if (authType == SshAuthType.PRIVATE_KEY) {
                    item {
                        SshPrivateKeyImportButton(
                            language = language,
                            onImported = { imported ->
                                secret = imported
                                credentialImportError = null
                            },
                            onError = { credentialImportError = it },
                        )
                    }
                    item {
                        Text(
                            ppText(
                                language,
                                "请选择私钥文件（例如 id_ed25519），不要选择 .pub 公钥。当前支持未加密的 OpenSSH/PEM 私钥；导入后文件内容只会进入 Android Keystore 加密存储。",
                                "Choose the private key file (for example, id_ed25519), not the .pub public key. This version supports unencrypted OpenSSH/PEM private keys; imported content is stored only through Android Keystore encryption.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (secret.isNotEmpty()) {
                        item {
                            Text(
                                ppText(
                                    language,
                                    "已选择一份通过格式与未加密检查的私钥。保存后仍需实际连接验证。",
                                    "A private key passed the format and unencrypted-key checks. Verify it with a real connection after saving.",
                                ),
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    item {
                        Text(
                            ppText(
                                language,
                                "输入服务器 IP/域名、端口、用户名和密码。密码不会写入项目或 SQLite 明文字段。",
                                "Enter the server IP/hostname, port, username, and password. The password is never written to the project or plaintext SQLite fields.",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                credentialImportError?.let { message ->
                    item {
                        Text(message, color = MaterialTheme.colorScheme.error)
                    }
                }
                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it.take(4_096) },
                        label = { Text(ppText(language, "用途说明", "Description")) },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val submittedSecret = secret.takeIf { it.isNotBlank() }
                    secret = ""
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            host = host.trim(),
                            port = checkNotNull(parsedPort),
                            username = username.trim(),
                            authType = authType,
                            hostKeyFingerprint = fingerprint.trim(),
                            description = description.trim(),
                        ),
                        submittedSecret,
                    )
                },
            ) { Text(ppText(language, "保存", "Save")) }
        },
        dismissButton = {
            TextButton(onClick = clearAndDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}

@Composable
private fun ToolSettingsDialog(
    language: AppLanguage,
    enabled: Boolean,
    plugins: List<InstalledPlugin>,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val groups = buildList {
        add(
            "Workspace" to listOf(
                "workspace.list", "workspace.read", "workspace.write", "workspace.create",
                "workspace.delete", "workspace.move", "workspace.search", "workspace.patch",
            ),
        )
        add(
            "Git" to listOf(
                "git.init", "git.clone", "git.status", "git.diff", "git.commit", "git.pull", "git.push",
            ),
        )
        add("Network" to listOf("http.request"))
        add("Vision" to listOf("image.analyze"))
        add("Sandbox" to listOf("execute_js", "execute_ts"))
        add("SSH" to listOf("ssh.execute"))
        val pluginTools = plugins.filter(InstalledPlugin::enabled)
            .flatMap { plugin -> plugin.tools.map { tool -> "plugin.${plugin.id}.${tool.name}" } }
        if (pluginTools.isNotEmpty()) add("Plugins" to pluginTools)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "工具列表", "Tools")) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    ListItem(
                        headlineContent = { Text(ppText(language, "允许 Agent 使用工具", "Allow Agent tools")) },
                        supportingContent = {
                            Text(
                                ppText(
                                    language,
                                    "停用后 Agent 只能对话；远程和危险操作始终还要逐次确认。",
                                    "When disabled the Agent can only chat. Remote and risky calls always need one-time approval.",
                                ),
                            )
                        },
                        trailingContent = {
                            Switch(checked = enabled, onCheckedChange = onEnabledChange)
                        },
                    )
                }
                groups.forEach { (group, tools) ->
                    item {
                        Text(
                            group,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    items(tools.size) { index ->
                        Text(
                            tools[index],
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "完成", "Done")) }
        },
    )
}

@Composable
private fun PluginInfoDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "插件", "Plugins")) },
        text = {
            Text(
                ppText(
                    language,
                    "当前版本未安装插件。后续插件会在这里显示来源、权限和启用状态；未安装的插件不能执行。",
                    "No plugins are installed. Future plugins will show their source, permissions, and enabled state here; uninstalled plugins cannot run.",
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "完成", "Done")) }
        },
    )
}

private fun ThemePreference.label(language: AppLanguage): String = when (this) {
    ThemePreference.SYSTEM -> ppText(language, "跟随系统", "System")
    ThemePreference.LIGHT -> ppText(language, "浅色", "Light")
    ThemePreference.DARK -> ppText(language, "深色", "Dark")
}

private fun AppLanguage.label(): String = when (this) {
    AppLanguage.CHINESE -> "中文"
    AppLanguage.ENGLISH -> "English"
}

private fun LlmProviderPreference.label(): String = when (this) {
    LlmProviderPreference.OPENAI_CHAT -> "OpenAI Chat API"
    LlmProviderPreference.GLM -> "GLM"
}

private fun SshAuthType.label(language: AppLanguage): String = when (this) {
    SshAuthType.PASSWORD -> ppText(language, "密码", "Password")
    SshAuthType.PRIVATE_KEY -> ppText(language, "私钥", "Private key")
}
