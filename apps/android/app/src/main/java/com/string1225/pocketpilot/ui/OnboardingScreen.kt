package com.string1225.pocketpilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.string1225.pocketpilot.integrations.git.GitInputPolicy
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.RemoteServerProfile
import java.util.UUID

private const val ONBOARDING_STEP_COUNT = 5

private enum class SetupDecision {
    YES,
    NO,
}

private enum class ProjectSetupMode {
    LOCAL,
    GIT,
}

internal object OnboardingFlowPolicy {
    fun canContinueModel(credentialConfigured: Boolean, testInProgress: Boolean): Boolean =
        credentialConfigured && !testInProgress

    fun canContinueOptionalSetup(decision: Boolean?, setupSucceeded: Boolean): Boolean =
        decision == false || (decision == true && setupSucceeded)

    fun restoredProjectDecision(projectConfigured: Boolean): Boolean? =
        if (projectConfigured) true else null
}

@Composable
internal fun OnboardingScreen(
    state: PocketPilotUiState,
    viewModel: PocketPilotViewModel,
    snackbarHostState: SnackbarHostState,
) {
    val language = state.settings.language
    var step by rememberSaveable { mutableIntStateOf(0) }
    var showModelDialog by rememberSaveable { mutableStateOf(false) }
    var projectDecision by rememberSaveable(state.onboardingProjectSetupSucceeded) {
        mutableStateOf(
            OnboardingFlowPolicy.restoredProjectDecision(state.onboardingProjectSetupSucceeded)
                ?.let { SetupDecision.YES },
        )
    }
    var projectMode by rememberSaveable { mutableStateOf(ProjectSetupMode.LOCAL) }
    var localProjectName by rememberSaveable { mutableStateOf("") }
    var gitProjectName by rememberSaveable { mutableStateOf("") }
    var gitRemoteUrl by rememberSaveable { mutableStateOf("") }
    var gitBranch by rememberSaveable { mutableStateOf("") }
    var gitUsername by rememberSaveable { mutableStateOf("git") }
    var useStoredGitCredential by rememberSaveable { mutableStateOf(false) }
    // Credentials deliberately never enter SavedState or the ViewModel state.
    var gitToken by remember { mutableStateOf("") }
    var serverDecision by rememberSaveable { mutableStateOf<SetupDecision?>(null) }
    var editingServer by remember { mutableStateOf<RemoteServerProfile?>(null) }

    val modelReady = OnboardingFlowPolicy.canContinueModel(
        credentialConfigured = state.llmCredentialConfigured,
        testInProgress = state.llmConnectionTestInProgress,
    )
    val projectReady = OnboardingFlowPolicy.canContinueOptionalSetup(
        decision = projectDecision?.let { it == SetupDecision.YES },
        setupSucceeded = state.onboardingProjectSetupSucceeded,
    )
    val serverReady = OnboardingFlowPolicy.canContinueOptionalSetup(
        decision = serverDecision?.let { it == SetupDecision.YES },
        setupSucceeded = state.remoteServers.isNotEmpty(),
    )
    val canContinue = when (step) {
        0 -> true
        1 -> modelReady
        2 -> projectReady && !state.onboardingProjectSetupInProgress
        3 -> serverReady
        else -> !state.onboardingCompletionInProgress
    }
    val operationInProgress = state.llmConnectionTestInProgress ||
        state.onboardingProjectSetupInProgress ||
        state.onboardingCompletionInProgress

    // Keep consuming system Back while an operation owns this screen. Disabling
    // the handler would let Android close the Activity during a clone or model test.
    BackHandler(enabled = step > 0) {
        if (!operationInProgress) step -= 1
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            OnboardingBottomBar(
                language = language,
                step = step,
                canContinue = canContinue,
                inProgress = operationInProgress,
                onBack = { step -= 1 },
                onContinue = {
                    if (step == ONBOARDING_STEP_COUNT - 1) {
                        viewModel.completeOnboarding()
                    } else {
                        step += 1
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinearProgressIndicator(
                progress = { (step + 1f) / ONBOARDING_STEP_COUNT },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    ppText(language, "第 ${step + 1} 步，共 $ONBOARDING_STEP_COUNT 步", "Step ${step + 1} of $ONBOARDING_STEP_COUNT"),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = {
                    viewModel.setLanguage(
                        if (language == AppLanguage.CHINESE) AppLanguage.ENGLISH else AppLanguage.CHINESE,
                    )
                }) {
                    Text(if (language == AppLanguage.CHINESE) "English" else "中文")
                }
            }

            when (step) {
                0 -> WelcomeStep(language)
                1 -> ModelStep(
                    state = state,
                    onConfigure = {
                        viewModel.clearLlmConnectionTestResult()
                        showModelDialog = true
                    },
                )
                2 -> ProjectStep(
                    state = state,
                    decision = projectDecision,
                    mode = projectMode,
                    localProjectName = localProjectName,
                    gitProjectName = gitProjectName,
                    gitRemoteUrl = gitRemoteUrl,
                    gitBranch = gitBranch,
                    gitUsername = gitUsername,
                    gitToken = gitToken,
                    useStoredGitCredential = useStoredGitCredential,
                    onDecisionChange = { choice ->
                        if (
                            !state.onboardingProjectSetupInProgress &&
                            (!state.onboardingProjectSetupSucceeded || projectDecision == null)
                        ) {
                            projectDecision = choice
                        }
                    },
                    onModeChange = { choice ->
                        if (!state.onboardingProjectSetupInProgress && !state.onboardingProjectSetupSucceeded) {
                            projectMode = choice
                            viewModel.clearOnboardingProjectSetupResult()
                        }
                    },
                    onLocalProjectNameChange = { localProjectName = it.take(80) },
                    onGitProjectNameChange = { gitProjectName = it.take(80) },
                    onGitRemoteUrlChange = { gitRemoteUrl = it.take(4_096) },
                    onGitBranchChange = { gitBranch = it.take(256) },
                    onGitUsernameChange = { gitUsername = it.take(256) },
                    onGitTokenChange = { gitToken = it.take(16_384) },
                    onUseStoredGitCredentialChange = { useStoredGitCredential = it },
                    onCreateLocal = {
                        viewModel.configureOnboardingLocalProject(localProjectName.trim())
                    },
                    onCloneGit = {
                        val submittedToken = gitToken.takeIf(String::isNotEmpty)
                        gitToken = ""
                        viewModel.configureOnboardingGitProject(
                            name = gitProjectName.trim(),
                            remoteUrl = gitRemoteUrl.trim(),
                            branch = gitBranch.trim().takeIf(String::isNotEmpty),
                            username = gitUsername.trim(),
                            useStoredCredential = useStoredGitCredential,
                            rawToken = submittedToken,
                        )
                    },
                )
                3 -> ServerStep(
                    language = language,
                    servers = state.remoteServers,
                    decision = serverDecision,
                    onDecisionChange = { serverDecision = it },
                    onAddServer = {
                        val id = UUID.randomUUID().toString()
                        editingServer = RemoteServerProfile(
                            id = id,
                            name = "",
                            host = "",
                            username = "",
                            hostKeyFingerprint = "",
                            credentialId = "ssh.$id",
                        )
                    },
                )
                else -> ReadyStep(state)
            }
        }
    }

    if (showModelDialog) {
        ModelSettingsDialog(
            settings = state.settings,
            credentialConfigured = state.llmCredentialConfigured,
            testInProgress = state.llmConnectionTestInProgress,
            testSucceeded = state.llmConnectionTestSucceeded,
            testError = state.llmConnectionTestError,
            onDismiss = { showModelDialog = false },
            onSave = viewModel::saveLlmConnection,
            onRemoveCredential = {
                showModelDialog = false
                viewModel.removeLlmCredential()
            },
        )
    }

    editingServer?.let { profile ->
        RemoteServerEditorDialog(
            language = language,
            initial = profile,
            scanInProgress = state.sshHostKeyScanInProgress,
            scanCandidate = state.sshHostKeyCandidate,
            scanError = state.sshHostKeyScanError,
            onScan = viewModel::scanSshHostKey,
            onClearScan = viewModel::clearSshHostKeyScan,
            onDismiss = { editingServer = null },
            onSave = { updated, secret ->
                editingServer = null
                viewModel.saveRemoteServer(updated, secret)
            },
        )
    }
}

@Composable
private fun WelcomeStep(language: AppLanguage) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            PocketPilotLogo(
                modifier = Modifier.size(88.dp),
                contentDescription = "PocketPilot",
            )
            Spacer(Modifier.height(16.dp))
            Text(
                ppText(language, "欢迎使用 PocketPilot", "Welcome to PocketPilot"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                ppText(
                    language,
                    "你的 Android Agent 控制中心：模型、项目、工具和服务器都由你掌控。",
                    "Your Android Agent control center—your models, projects, tools, and servers stay under your control.",
                ),
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ArchitectureItem(
                icon = Icons.Default.Memory,
                title = ppText(language, "Agent Kernel", "Agent kernel"),
                body = ppText(language, "在手机中编排对话、任务、上下文和工具调用。", "Orchestrates chats, tasks, context, and tool calls on your phone."),
            )
        }
        item {
            ArchitectureItem(
                icon = Icons.Default.Storage,
                title = ppText(language, "本地 Workspace", "Local workspace"),
                body = ppText(language, "项目文件、会话和 Checkpoint 保存在应用私有空间，更新应用不会清空。", "Projects, chats, and checkpoints stay in private app storage and survive app updates."),
            )
        }
        item {
            ArchitectureItem(
                icon = Icons.Default.CloudQueue,
                title = ppText(language, "你的模型 Endpoint", "Your model endpoint"),
                body = ppText(language, "支持 OpenAI Chat API 兼容服务和内置 Endpoint 的 GLM。", "Connect an OpenAI Chat API-compatible service or GLM through its built-in endpoint."),
            )
        }
        item {
            ArchitectureItem(
                icon = Icons.Default.Terminal,
                title = ppText(language, "真实工具与远程执行", "Real tools and remote execution"),
                body = ppText(language, "Git、HTTP、沙箱 JS/TS 与 SSH 都经过权限边界和明确确认。", "Git, HTTP, sandboxed JS/TS, and SSH all run behind explicit permission boundaries."),
            )
        }
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    ppText(language, "AK、Git Token 和 SSH 凭据只进入 Android Keystore 加密存储。", "API keys, Git tokens, and SSH credentials are stored only through Android Keystore encryption."),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ArchitectureItem(icon: ImageVector, title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ModelStep(state: PocketPilotUiState, onConfigure: () -> Unit) {
    val language = state.settings.language
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            StepTitle(
                icon = Icons.Default.CloudQueue,
                title = ppText(language, "连接你的模型", "Connect your model"),
                body = ppText(language, "模型是必需项。保存时 PocketPilot 会真实测试文本模型和图片模型，只有两项都通过才会启用。", "A model is required. PocketPilot tests both text and vision models before enabling the connection."),
            )
        }
        item {
            StatusCard(
                completed = state.llmCredentialConfigured,
                title = if (state.llmCredentialConfigured) {
                    ppText(language, "模型已连接", "Model connected")
                } else {
                    ppText(language, "等待配置", "Configuration required")
                },
                body = if (state.llmCredentialConfigured) {
                    val provider = when (state.settings.llmProvider) {
                        LlmProviderPreference.OPENAI_CHAT -> "OpenAI Chat API"
                        LlmProviderPreference.GLM -> "GLM"
                    }
                    "$provider · ${state.settings.modelName}"
                } else {
                    ppText(language, "选择 OpenAI Chat API 或 GLM，并填写 AK。", "Choose OpenAI Chat API or GLM and enter your key.")
                },
            )
        }
        state.llmConnectionTestError?.let { error ->
            item { Text(error, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Button(
                onClick = onConfigure,
                enabled = !state.llmConnectionTestInProgress,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.llmConnectionTestInProgress) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(ppText(language, "  测试连接中…", "  Testing connection…"))
                } else {
                    Text(
                        if (state.llmCredentialConfigured) {
                            ppText(language, "重新配置模型", "Reconfigure model")
                        } else {
                            ppText(language, "配置并测试模型", "Configure and test model")
                        },
                    )
                }
            }
        }
        item {
            Text(
                ppText(language, "AK 只会保存到设备加密存储，不会进入项目文件、SQLite 明文字段或日志。", "Your key is stored only in encrypted device storage, never in project files, plaintext SQLite fields, or logs."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectStep(
    state: PocketPilotUiState,
    decision: SetupDecision?,
    mode: ProjectSetupMode,
    localProjectName: String,
    gitProjectName: String,
    gitRemoteUrl: String,
    gitBranch: String,
    gitUsername: String,
    gitToken: String,
    useStoredGitCredential: Boolean,
    onDecisionChange: (SetupDecision) -> Unit,
    onModeChange: (ProjectSetupMode) -> Unit,
    onLocalProjectNameChange: (String) -> Unit,
    onGitProjectNameChange: (String) -> Unit,
    onGitRemoteUrlChange: (String) -> Unit,
    onGitBranchChange: (String) -> Unit,
    onGitUsernameChange: (String) -> Unit,
    onGitTokenChange: (String) -> Unit,
    onUseStoredGitCredentialChange: (Boolean) -> Unit,
    onCreateLocal: () -> Unit,
    onCloneGit: () -> Unit,
) {
    val language = state.settings.language
    val remoteValidation = gitRemoteUrl.trim().takeIf(String::isNotEmpty)?.let { remote ->
        runCatching { GitInputPolicy.requireHttpsRemote(remote) }.exceptionOrNull()?.message
    }
    val branchValidation = gitBranch.trim().takeIf(String::isNotEmpty)?.let { branch ->
        runCatching { GitInputPolicy.requireBranch(branch) }.exceptionOrNull()?.message
    }
    val gitFormValid = gitProjectName.isNotBlank() && gitRemoteUrl.isNotBlank() &&
        remoteValidation == null && branchValidation == null &&
        (!useStoredGitCredential || state.gitCredentialConfigured || gitToken.isNotEmpty())

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StepTitle(
                icon = Icons.Default.FolderOpen,
                title = ppText(language, "现在配置项目吗？", "Set up a project now?"),
                body = ppText(language, "可以创建一个本地 Workspace，也可以通过 HTTPS 克隆 Git 仓库；稍后仍可在项目页继续添加。", "Create a local workspace or clone a Git repository over HTTPS. You can always add more projects later."),
            )
        }
        item {
            DecisionChoices(language, decision, onDecisionChange)
        }
        if (decision == SetupDecision.YES && state.onboardingProjectSetupSucceeded) {
            item {
                StatusCard(
                    completed = true,
                    title = ppText(language, "项目已配置", "Project configured"),
                    body = state.selectedProject?.name.orEmpty(),
                )
            }
        } else if (decision == SetupDecision.YES) {
            item {
                Text(ppText(language, "项目来源", "Project source"), fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth()) {
                    ProjectSetupMode.entries.forEach { choice ->
                        val selected = mode == choice
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onModeChange(choice) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = { onModeChange(choice) })
                            Text(
                                when (choice) {
                                    ProjectSetupMode.LOCAL -> ppText(language, "本地项目", "Local project")
                                    ProjectSetupMode.GIT -> ppText(language, "Git HTTPS 克隆", "Git HTTPS clone")
                                },
                            )
                        }
                    }
                }
            }
            if (mode == ProjectSetupMode.LOCAL) {
                item {
                    OutlinedTextField(
                        value = localProjectName,
                        onValueChange = onLocalProjectNameChange,
                        label = { Text(ppText(language, "项目名称", "Project name")) },
                        placeholder = { Text(ppText(language, "例如：个人网站", "For example: Personal website")) },
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Button(
                        onClick = onCreateLocal,
                        enabled = localProjectName.isNotBlank() && !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ProjectOperationLabel(state, ppText(language, "创建本地项目", "Create local project"))
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = gitProjectName,
                        onValueChange = onGitProjectNameChange,
                        label = { Text(ppText(language, "项目名称", "Project name")) },
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = gitRemoteUrl,
                        onValueChange = onGitRemoteUrlChange,
                        label = { Text("Git HTTPS URL") },
                        placeholder = { Text("https://github.com/owner/repository.git") },
                        supportingText = {
                            Text(
                                remoteValidation ?: ppText(
                                    language,
                                    "仅支持 HTTPS；不要把用户名或 Token 写进 URL。",
                                    "HTTPS only; never put a username or token in the URL.",
                                ),
                            )
                        },
                        isError = remoteValidation != null,
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = gitBranch,
                        onValueChange = onGitBranchChange,
                        label = { Text(ppText(language, "分支（可选）", "Branch (optional)")) },
                        placeholder = { Text("main") },
                        supportingText = branchValidation?.let { message -> ({ Text(message) }) },
                        isError = branchValidation != null,
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = gitUsername,
                        onValueChange = onGitUsernameChange,
                        label = { Text(ppText(language, "Git 用户名", "Git username")) },
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    OutlinedTextField(
                        value = gitToken,
                        onValueChange = onGitTokenChange,
                        label = { Text(ppText(language, "新 Personal Access Token（可选）", "New personal access token (optional)")) },
                        supportingText = {
                            Text(ppText(language, "公开仓库可留空；Token 只保存到 Android Keystore。", "Leave blank for a public repository. Tokens are stored only through Android Keystore."))
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        enabled = !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (state.gitCredentialConfigured && gitToken.isEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onUseStoredGitCredentialChange(!useStoredGitCredential) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = useStoredGitCredential,
                                onCheckedChange = onUseStoredGitCredentialChange,
                            )
                            Text(ppText(language, "使用已保存的 Git Token", "Use the saved Git token"))
                        }
                    }
                }
                item {
                    Button(
                        onClick = onCloneGit,
                        enabled = gitFormValid && !state.onboardingProjectSetupInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ProjectOperationLabel(state, ppText(language, "安全克隆并选择项目", "Clone securely and select project"))
                    }
                }
            }
            state.onboardingProjectSetupError?.let { error ->
                item { Text(error, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun ProjectOperationLabel(state: PocketPilotUiState, idleText: String) {
    if (state.onboardingProjectSetupInProgress) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Text("  ${ppText(state.settings.language, "处理中…", "Working…")}")
    } else {
        Text(idleText)
    }
}

@Composable
private fun ServerStep(
    language: AppLanguage,
    servers: List<RemoteServerProfile>,
    decision: SetupDecision?,
    onDecisionChange: (SetupDecision) -> Unit,
    onAddServer: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            StepTitle(
                icon = Icons.Default.Terminal,
                title = ppText(language, "现在配置远程服务器吗？", "Set up a remote server now?"),
                body = ppText(language, "SSH 服务器承担构建、测试和长任务。密码或私钥会进入设备加密存储，主机指纹用于阻止中间人攻击。", "An SSH server can handle builds, tests, and long-running jobs. Passwords or keys are encrypted on-device, and host fingerprints protect against interception."),
            )
        }
        item { DecisionChoices(language, decision, onDecisionChange) }
        if (decision == SetupDecision.YES) {
            if (servers.isEmpty()) {
                item {
                    StatusCard(
                        completed = false,
                        title = ppText(language, "还没有服务器", "No server configured"),
                        body = ppText(language, "准备好 Host、用户名和认证信息。PocketPilot 会先扫描主机公钥，再引导你通过服务器控制台或管理员核对。", "Have the host, username, and credential ready. PocketPilot scans the host key first, then asks you to verify it through the server console or an administrator."),
                    )
                }
            } else {
                item {
                    StatusCard(
                        completed = true,
                        title = ppText(language, "服务器已配置", "Server configured"),
                        body = ppText(language, "已保存 ${servers.size} 台服务器", "${servers.size} server(s) saved"),
                    )
                }
                items(servers, key = { it.id }) { server ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(server.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${server.username}@${server.host}:${server.port}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Button(onClick = onAddServer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (servers.isEmpty()) {
                            ppText(language, "配置 SSH 服务器", "Configure SSH server")
                        } else {
                            ppText(language, "再添加一台服务器", "Add another server")
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadyStep(state: PocketPilotUiState) {
    val language = state.settings.language
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            PocketPilotLogo(Modifier.size(80.dp), contentDescription = null)
            Spacer(Modifier.height(16.dp))
            Text(
                ppText(language, "一切就绪", "You're ready"),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                ppText(language, "点击下方按钮进入聊天。之后可点左上角 Logo 随时调整设置。", "Start chatting below. You can change these settings anytime from the logo in the top-left corner."),
                modifier = Modifier.padding(top = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            StatusCard(
                completed = state.llmCredentialConfigured,
                title = ppText(language, "模型", "Model"),
                body = state.settings.modelName,
            )
        }
        item {
            StatusCard(
                completed = true,
                title = ppText(language, "当前项目", "Current project"),
                body = state.selectedProject?.name ?: ppText(language, "个人项目", "Personal project"),
            )
        }
        item {
            StatusCard(
                completed = state.remoteServers.isNotEmpty(),
                title = ppText(language, "远程服务器", "Remote servers"),
                body = if (state.remoteServers.isEmpty()) {
                    ppText(language, "暂不配置，可稍后添加", "Skipped for now; add one later")
                } else {
                    ppText(language, "已配置 ${state.remoteServers.size} 台", "${state.remoteServers.size} configured")
                },
            )
        }
    }
}

@Composable
private fun DecisionChoices(
    language: AppLanguage,
    selected: SetupDecision?,
    onSelect: (SetupDecision) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SetupDecision.entries.forEach { choice ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(choice) },
                colors = CardDefaults.cardColors(
                    containerColor = if (choice == selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLow
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = choice == selected, onClick = { onSelect(choice) })
                    Column {
                        Text(
                            when (choice) {
                                SetupDecision.YES -> ppText(language, "是，现在配置", "Yes, configure now")
                                SetupDecision.NO -> ppText(language, "暂时不要", "Not now")
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (choice == SetupDecision.NO) {
                            Text(
                                ppText(language, "稍后可在设置或项目页完成。", "You can finish this later from Settings or Projects."),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepTitle(icon: ImageVector, title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusCard(completed: Boolean, title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (completed) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (completed) Icons.Default.CheckCircle else Icons.Default.Code,
                contentDescription = null,
                tint = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OnboardingBottomBar(
    language: AppLanguage,
    step: Int,
    canContinue: Boolean,
    inProgress: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    Surface(modifier = Modifier.navigationBarsPadding(), tonalElevation = 3.dp) {
        Column {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(onClick = onBack, enabled = !inProgress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        Text(ppText(language, "上一步", "Back"), modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onContinue, enabled = canContinue) {
                    if (inProgress) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(ppText(language, "  正在开始…", "  Starting…"))
                    } else {
                        Icon(
                            if (step == ONBOARDING_STEP_COUNT - 1) Icons.Default.RocketLaunch else Icons.Default.CheckCircle,
                            contentDescription = null,
                        )
                        Text(
                            if (step == ONBOARDING_STEP_COUNT - 1) {
                                ppText(language, "开始使用", "Start using PocketPilot")
                            } else {
                                ppText(language, "下一步", "Next")
                            },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
