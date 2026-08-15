package com.string1225.agentdock.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.string1225.agentdock.model.Project

@Composable
fun AgentDockApp(viewModel: AgentDockViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        viewModel.consumeNotice(notice.id)
    }

    AgentDockTheme {
        Box(Modifier.fillMaxSize()) {
            if (state.selectedProject == null) {
                ProjectListScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onCreateProject = viewModel::createProject,
                    onDeleteProject = viewModel::deleteProject,
                    onOpenProject = viewModel::openProject,
                )
            } else {
                ProjectScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    viewModel = viewModel,
                )
            }

            if (state.loading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false) {},
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }

        state.pendingApproval?.let { approval ->
            AlertDialog(
                onDismissRequest = { viewModel.resolveApproval(false) },
                title = { Text(approval.title) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${approval.toolName} 请求对当前 Project 执行写操作。")
                        Text(
                            approval.detail,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("仅允许这一次调用；拒绝后 Agent Run 会安全停止。")
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.resolveApproval(true) }) { Text("允许一次") }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.resolveApproval(false) }) { Text("拒绝") }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectListScreen(
    state: AgentDockUiState,
    snackbarHostState: SnackbarHostState,
    onCreateProject: (String) -> Unit,
    onDeleteProject: (String) -> Unit,
    onOpenProject: (String) -> Unit,
) {
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("AgentDock", fontWeight = FontWeight.Bold)
                        Text(
                            "你的移动 Agent Workspace",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "新建项目")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "新建项目")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.offlineDemo) {
                item { OfflineDemoBanner(runtimeAvailable = state.runtimeAvailable) }
            }
            item {
                Text(
                    "Projects",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(state.projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    onOpen = { onOpenProject(project.id) },
                    onDelete = { deleteTarget = project },
                )
            }
        }
    }

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = {
                showCreateDialog = false
                onCreateProject(it)
            },
        )
    }

    deleteTarget?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除项目？") },
            text = { Text("“${project.name}”的工作区、Agent 历史和检查点将被永久删除。此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        onDeleteProject(project.id)
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun ProjectCard(
    project: Project,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "更新于 ${formatTimestamp(project.updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "删除 ${project.name}")
            }
        }
    }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建 Project") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("名称") },
                placeholder = { Text("例如：个人网站") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectScreen(
    state: AgentDockUiState,
    snackbarHostState: SnackbarHostState,
    viewModel: AgentDockViewModel,
) {
    val project = state.selectedProject ?: return
    var confirmDiscard by remember { mutableStateOf(false) }
    var closeProjectAfterDiscard by remember { mutableStateOf(false) }

    fun navigateBack() {
        val closesEditor = state.section == ProjectSection.FILES && state.selectedFilePath != null
        if (state.editorDirty) {
            closeProjectAfterDiscard = !closesEditor
            confirmDiscard = true
        } else if (closesEditor) {
            viewModel.closeEditor()
        } else {
            viewModel.closeProject()
        }
    }

    BackHandler { navigateBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(project.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = state.section == ProjectSection.FILES,
                    onClick = { viewModel.selectSection(ProjectSection.FILES) },
                    icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
                    label = { Text("Files") },
                )
                NavigationBarItem(
                    selected = state.section == ProjectSection.AGENT,
                    onClick = { viewModel.selectSection(ProjectSection.AGENT) },
                    icon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
                    label = { Text("Agent") },
                )
                NavigationBarItem(
                    selected = state.section == ProjectSection.CHECKPOINTS,
                    onClick = { viewModel.selectSection(ProjectSection.CHECKPOINTS) },
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("Checkpoints") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.offlineDemo) {
                OfflineDemoBanner(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    compact = true,
                    runtimeAvailable = state.runtimeAvailable,
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                when (state.section) {
                    ProjectSection.FILES -> FilesScreen(
                        state = state,
                        onOpenFile = viewModel::openFile,
                        onCloseEditor = {
                            if (state.editorDirty) {
                                closeProjectAfterDiscard = false
                                confirmDiscard = true
                            } else {
                                viewModel.closeEditor()
                            }
                        },
                        onEditorChange = viewModel::updateEditor,
                        onCreateFile = viewModel::createFile,
                        onSaveFile = viewModel::saveFile,
                        onDeleteFile = viewModel::deleteFile,
                    )

                    ProjectSection.AGENT -> AgentScreen(
                        timeline = state.timeline,
                        input = state.agentInput,
                        status = state.agentStatus,
                        offlineDemo = state.offlineDemo,
                        runtimeAvailable = state.runtimeAvailable,
                        onInputChange = viewModel::updateAgentInput,
                        onSend = viewModel::sendAgentTask,
                        onCancel = viewModel::cancelAgent,
                    )

                    ProjectSection.CHECKPOINTS -> CheckpointsScreen(
                        checkpoints = state.checkpoints,
                        onRestore = viewModel::restoreCheckpoint,
                    )
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("编辑器中的修改尚未写入 Workspace。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDiscard = false
                        if (closeProjectAfterDiscard) viewModel.closeProject() else viewModel.closeEditor()
                    },
                ) { Text("放弃") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("继续编辑") } },
        )
    }
}
