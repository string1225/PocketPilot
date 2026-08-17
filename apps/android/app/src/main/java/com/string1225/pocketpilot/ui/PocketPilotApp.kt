package com.string1225.pocketpilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.Conversation
import com.string1225.pocketpilot.model.Project
import kotlinx.coroutines.launch

private const val LIBRARY_PAGE = 0
private const val CHAT_PAGE = 1
private const val ARTIFACTS_PAGE = 2
internal const val SETTINGS_LOGO_TEST_TAG = "pocketpilot-settings-logo"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PocketPilotApp(
    viewModel: PocketPilotViewModel,
    speechInputController: SpeechInputController,
    onReadyForNotificationPermission: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = state.settings.language
    val snackbarHostState = remember { SnackbarHostState() }
    val pagerState = rememberPagerState(initialPage = CHAT_PAGE, pageCount = { 3 })
    val scope = rememberCoroutineScope()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCreateProject by rememberSaveable { mutableStateOf(false) }
    var deleteProject by remember { mutableStateOf<Project?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        viewModel.consumeNotice(notice.id)
    }

    LaunchedEffect(viewModel) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                PocketPilotNavigationEvent.OPEN_CHAT -> {
                    showSettings = false
                    pagerState.animateScrollToPage(CHAT_PAGE)
                }
            }
        }
    }

    LaunchedEffect(state.selectedConversationId, showSettings, pagerState.currentPage) {
        // A recognition callback is scoped to the draft that started it. Even
        // when the chat page stays visible, switching conversations must revoke
        // the old callback before it can write into the new draft.
        speechInputController.stop()
    }

    LaunchedEffect(state.onboardingCompleted) {
        if (state.onboardingCompleted == true) onReadyForNotificationPermission()
    }

    PocketPilotTheme(preference = state.settings.theme) {
        if (state.onboardingCompleted == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (state.onboardingCompleted == false) {
            OnboardingScreen(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
            )
        } else if (showSettings) {
            BackHandler { showSettings = false }
            SettingsScreen(
                settings = state.settings,
                snackbarHostState = snackbarHostState,
                llmCredentialConfigured = state.llmCredentialConfigured,
                llmConnectionTestInProgress = state.llmConnectionTestInProgress,
                llmConnectionTestSucceeded = state.llmConnectionTestSucceeded,
                llmConnectionTestError = state.llmConnectionTestError,
                gitCredentialConfigured = state.gitCredentialConfigured,
                remoteServers = state.remoteServers,
                plugins = state.plugins,
                appUpdate = state.appUpdate,
                onSettingsChange = viewModel::updateSettings,
                onSaveLlmConnection = viewModel::saveLlmConnection,
                onClearLlmConnectionTestResult = viewModel::clearLlmConnectionTestResult,
                onRemoveLlmCredential = viewModel::removeLlmCredential,
                onSaveGitCredential = viewModel::saveGitCredential,
                onRemoveGitCredential = viewModel::removeGitCredential,
                onSaveRemoteServer = viewModel::saveRemoteServer,
                onDeleteRemoteServer = viewModel::deleteRemoteServer,
                onPreviewPluginBundle = viewModel::previewPluginBundle,
                onInstallPluginBundle = viewModel::installPluginBundle,
                onSetPluginEnabled = viewModel::setPluginEnabled,
                onDeletePlugin = viewModel::deletePlugin,
                onCheckForAppUpdate = viewModel::checkForAppUpdate,
                onDownloadAndInstallAppUpdate = viewModel::downloadAndInstallAppUpdate,
                onInstallAppUpdate = viewModel::installAppUpdate,
                onOpenUnknownSourcesSettings = viewModel::openUnknownSourcesSettings,
                onBack = { showSettings = false },
            )
        } else {
            BackHandler(
                enabled = pagerState.currentPage != CHAT_PAGE || state.selectedFilePath != null,
            ) {
                when {
                    pagerState.currentPage == ARTIFACTS_PAGE && state.selectedFilePath != null && state.editorDirty ->
                        confirmDiscard = true
                    pagerState.currentPage == ARTIFACTS_PAGE && state.selectedFilePath != null ->
                        viewModel.closeEditor()
                    else -> scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                }
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(
                                onClick = { showSettings = true },
                                modifier = Modifier.testTag(SETTINGS_LOGO_TEST_TAG),
                            ) {
                                PocketPilotLogo(
                                    modifier = Modifier.size(36.dp),
                                    contentDescription = ppText(language, "打开设置", "Open settings"),
                                )
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    when (pagerState.currentPage) {
                                        LIBRARY_PAGE -> ppText(language, "项目与会话", "Projects & chats")
                                        ARTIFACTS_PAGE -> ppText(language, "产出物", "Artifacts")
                                        else -> state.selectedConversation?.title ?: "PocketPilot"
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                state.selectedProject?.let { project ->
                                    Text(
                                        project.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                        actions = {
                            if (pagerState.currentPage != LIBRARY_PAGE) {
                                IconButton(
                                    onClick = { scope.launch { pagerState.animateScrollToPage(LIBRARY_PAGE) } },
                                ) {
                                    Icon(
                                        Icons.Default.FolderOpen,
                                        contentDescription = ppText(language, "选择项目和会话", "Choose project and chat"),
                                    )
                                }
                            }
                            if (pagerState.currentPage == CHAT_PAGE || pagerState.currentPage == LIBRARY_PAGE) {
                                IconButton(
                                    onClick = {
                                        viewModel.createConversation()
                                        scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                                    },
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = ppText(language, "新建会话", "New chat"),
                                    )
                                }
                            } else {
                                IconButton(
                                    onClick = { scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) } },
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Chat,
                                        contentDescription = ppText(language, "返回聊天", "Back to chat"),
                                    )
                                }
                            }
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbarHostState) },
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        beyondViewportPageCount = 1,
                    ) { page ->
                        when (page) {
                            LIBRARY_PAGE -> LibraryScreen(
                                state = state,
                                onSelectProject = { projectId ->
                                    viewModel.selectProject(projectId)
                                    scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                                },
                                onSelectConversation = { conversationId ->
                                    viewModel.selectConversation(conversationId)
                                    scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                                },
                                onNewConversation = {
                                    viewModel.createConversation()
                                    scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                                },
                                onDeleteConversation = viewModel::deleteConversation,
                                onCreateProject = { showCreateProject = true },
                                onDeleteProject = { deleteProject = it },
                            )

                            CHAT_PAGE -> AgentScreen(
                                timeline = state.timeline,
                                input = state.agentInput,
                                attachments = state.agentAttachments,
                                queuedCount = state.queuedAgentTaskCount,
                                status = state.agentStatus,
                                offlineDemo = state.offlineDemo,
                                runtimeAvailable = state.runtimeAvailable,
                                language = language,
                                speechInputController = speechInputController,
                                onInputChange = viewModel::updateAgentInput,
                                onImportImages = viewModel::importAgentImages,
                                onRemoveAttachment = viewModel::removeAgentAttachment,
                                onSend = viewModel::sendAgentTask,
                                onCancel = viewModel::cancelAgent,
                            )

                            ARTIFACTS_PAGE -> ArtifactsScreen(
                                state = state,
                                viewModel = viewModel,
                                onCloseEditor = {
                                    if (state.editorDirty) confirmDiscard = true else viewModel.closeEditor()
                                },
                            )
                        }
                    }

                    if (state.loading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }

        state.pendingApproval?.let { approval ->
            AlertDialog(
                onDismissRequest = { viewModel.resolveApproval(false) },
                title = { Text(approval.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            approvalSummary(approval.toolName, language),
                        )
                        Text(
                            approval.detail,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            ppText(
                                language,
                                "仅允许这一次调用；拒绝后 Agent Run 会安全停止。",
                                "Approval applies once; rejecting safely stops the Agent run.",
                            ),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resolveApproval(true) }) {
                        Text(ppText(language, "允许一次", "Allow once"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resolveApproval(false) }) {
                        Text(ppText(language, "拒绝", "Reject"))
                    }
                },
            )
        }

        if (state.confirmDeepLinkDiscard) {
            AlertDialog(
                onDismissRequest = viewModel::cancelPendingConversationNavigation,
                title = {
                    Text(
                        ppText(
                            language,
                            "放弃未保存的修改并打开会话？",
                            "Discard unsaved changes and open the chat?",
                        ),
                    )
                },
                text = {
                    Text(
                        ppText(
                            language,
                            "通知指向另一个会话。当前编辑器内容尚未保存；PocketPilot 不会在未确认时覆盖它。",
                            "The notification targets another chat. The current editor has unsaved content; PocketPilot will not overwrite it without confirmation.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::discardEditorAndOpenPendingConversation) {
                        Text(ppText(language, "放弃并打开", "Discard & open"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::cancelPendingConversationNavigation) {
                        Text(ppText(language, "继续编辑", "Keep editing"))
                    }
                },
            )
        }

        if (showCreateProject) {
            CreateProjectDialog(
                language = language,
                onDismiss = { showCreateProject = false },
                onCreate = {
                    showCreateProject = false
                    viewModel.createProject(it)
                    scope.launch { pagerState.animateScrollToPage(CHAT_PAGE) }
                },
            )
        }

        deleteProject?.let { project ->
            AlertDialog(
                onDismissRequest = { deleteProject = null },
                title = { Text(ppText(language, "删除项目？", "Delete project?")) },
                text = {
                    Text(
                        ppText(
                            language,
                            "“${project.name}”的工作区、会话和检查点将被永久删除。",
                            "The workspace, chats, and checkpoints for “${project.name}” will be permanently deleted.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            deleteProject = null
                            viewModel.deleteProject(project.id)
                        },
                    ) { Text(ppText(language, "删除", "Delete"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { deleteProject = null }) {
                        Text(ppText(language, "取消", "Cancel"))
                    }
                },
            )
        }

        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                title = { Text(ppText(language, "放弃未保存的修改？", "Discard unsaved changes?")) },
                text = {
                    Text(
                        ppText(
                            language,
                            "编辑器中的修改尚未写入 Workspace。",
                            "Editor changes have not been written to the Workspace.",
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDiscard = false
                            viewModel.closeEditor()
                        },
                    ) { Text(ppText(language, "放弃", "Discard")) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) {
                        Text(ppText(language, "继续编辑", "Keep editing"))
                    }
                },
            )
        }
    }
}

private fun approvalSummary(toolName: String, language: AppLanguage): String = when {
    toolName == "ssh.execute" -> ppText(
        language,
        "SSH 将在已配置的远程服务器上执行命令，请核对服务器、主机指纹和命令。",
        "SSH will execute a command on a configured remote server. Verify the server, host key, and command.",
    )
    toolName == "git.push" -> ppText(
        language,
        "Git 将向远端推送提交，请核对远端主机、分支以及是否会发送凭据。",
        "Git will push commits remotely. Verify the host, branch, and whether a credential will be sent.",
    )
    toolName == "git.clone" || toolName == "git.pull" -> ppText(
        language,
        "Git 将访问远端并修改当前项目 Workspace，请核对远端主机以及是否会发送凭据。",
        "Git will contact a remote and modify this project workspace. Verify the host and credential release.",
    )
    toolName.startsWith("git.") -> ppText(
        language,
        "Git 将修改当前项目仓库，请核对操作详情。",
        "Git will modify the current project repository. Verify the operation details.",
    )
    else -> ppText(
        language,
        "${toolName} 请求修改当前项目，请核对操作详情。",
        "${toolName} requests a change to this project. Verify the operation details.",
    )
}

@Composable
private fun LibraryScreen(
    state: PocketPilotUiState,
    onSelectProject: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onCreateProject: () -> Unit,
    onDeleteProject: (Project) -> Unit,
) {
    val language = state.settings.language
    val selectedProjectId = state.selectedProjectId
    val conversations = state.conversations.filter { it.projectId == selectedProjectId }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.offlineDemo) {
            item {
                OfflineDemoBanner(
                    language = language,
                    runtimeAvailable = state.runtimeAvailable,
                )
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    ppText(language, "项目", "Projects"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(onClick = onCreateProject) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(ppText(language, "新建项目", "New project"), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.projects, key = { it.id }) { project ->
                    AssistChip(
                        onClick = { onSelectProject(project.id) },
                        label = { Text(project.name) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (project.id == selectedProjectId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Default.DeleteOutline,
                                contentDescription = ppText(language, "删除项目", "Delete project"),
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onDeleteProject(project) },
                            )
                        },
                    )
                }
            }
        }
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    ppText(language, "会话", "Chats"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                FilledTonalButton(onClick = onNewConversation) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(ppText(language, "新建会话", "New chat"), modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        if (conversations.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Chat,
                    title = ppText(language, "还没有会话", "No chats yet"),
                    body = ppText(language, "新建会话后，从中间聊天页开始工作。", "Create a chat and start from the center page."),
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        FilledTonalButton(onClick = onNewConversation) {
                            Text(ppText(language, "新建会话", "New chat"))
                        }
                    },
                )
            }
        } else {
            items(conversations, key = { it.id }) { conversation ->
                ConversationCard(
                    conversation = conversation,
                    selected = conversation.id == state.selectedConversationId,
                    language = language,
                    onOpen = { onSelectConversation(conversation.id) },
                    onDelete = { onDeleteConversation(conversation.id) },
                )
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: Conversation,
    selected: Boolean,
    language: AppLanguage,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Chat,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(conversation.title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                Text(
                    ppText(
                        language,
                        "更新于 ${formatTimestamp(conversation.updatedAt)}",
                        "Updated ${formatTimestamp(conversation.updatedAt)}",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.DeleteOutline,
                    contentDescription = ppText(language, "删除会话", "Delete chat"),
                )
            }
        }
    }
}

@Composable
private fun ArtifactsScreen(
    state: PocketPilotUiState,
    viewModel: PocketPilotViewModel,
    onCloseEditor: () -> Unit,
) {
    val language = state.settings.language
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.selectedProject == null) {
            EmptyState(
                icon = Icons.Default.Inventory2,
                title = ppText(language, "没有可显示的产出物", "No artifacts to show"),
                body = ppText(language, "先在项目与会话页选择一个项目。", "Choose a project from the projects and chats page."),
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }
        TabRow(selectedTabIndex = if (state.section == ProjectSection.FILES) 0 else 1) {
            Tab(
                selected = state.section == ProjectSection.FILES,
                onClick = { viewModel.selectSection(ProjectSection.FILES) },
                text = { Text(ppText(language, "文件", "Files")) },
            )
            Tab(
                selected = state.section == ProjectSection.CHECKPOINTS,
                onClick = { viewModel.selectSection(ProjectSection.CHECKPOINTS) },
                text = { Text(ppText(language, "检查点", "Checkpoints")) },
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            when (state.section) {
                ProjectSection.FILES -> FilesScreen(
                    state = state,
                    language = language,
                    onOpenFile = viewModel::openFile,
                    onCloseEditor = onCloseEditor,
                    onEditorChange = viewModel::updateEditor,
                    onCreateFile = viewModel::createFile,
                    onSaveFile = viewModel::saveFile,
                    onDeleteFile = viewModel::deleteFile,
                )

                ProjectSection.CHECKPOINTS -> CheckpointsScreen(
                    checkpoints = state.checkpoints,
                    language = language,
                    onRestore = viewModel::restoreCheckpoint,
                )
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    language: AppLanguage,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ppText(language, "新建项目", "New project")) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text(ppText(language, "名称", "Name")) },
                placeholder = { Text(ppText(language, "例如：个人网站", "For example: Personal website")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text(ppText(language, "创建", "Create"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(ppText(language, "取消", "Cancel")) }
        },
    )
}
