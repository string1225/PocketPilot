package com.string1225.pocketpilot.ui

import android.net.Uri

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.string1225.pocketpilot.background.AgentRunCoordinator
import com.string1225.pocketpilot.background.CoordinatedAgentRun
import com.string1225.pocketpilot.background.PendingAgentTask
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AppLanguage
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.Conversation
import com.string1225.pocketpilot.model.ConversationMessage
import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.model.InstalledPlugin
import com.string1225.pocketpilot.model.PluginInstallPreview
import com.string1225.pocketpilot.model.Project
import com.string1225.pocketpilot.model.ProjectGitBinding
import com.string1225.pocketpilot.model.RemoteServerProfile
import com.string1225.pocketpilot.model.SshHostKeyCandidate
import com.string1225.pocketpilot.model.ThemePreference
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.model.WorkspaceEntry
import com.string1225.pocketpilot.update.GitHubReleaseUpdateManager
import com.string1225.pocketpilot.update.UpdatePhase
import com.string1225.pocketpilot.update.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ProjectSection {
    FILES,
    CHECKPOINTS,
}

data class UiNotice(
    val id: Long,
    val message: String,
    val isError: Boolean,
)

data class PocketPilotUiState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val conversations: List<Conversation> = emptyList(),
    val selectedProjectId: String? = null,
    val selectedConversationId: String? = null,
    val section: ProjectSection = ProjectSection.FILES,
    val files: List<WorkspaceEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val editorText: String = "",
    val savedEditorText: String = "",
    val checkpoints: List<Checkpoint> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val agentInput: String = "",
    val agentAttachments: List<ChatImageAttachment> = emptyList(),
    val queuedAgentTaskCount: Int = 0,
    val agentStatus: AgentRunStatus = AgentRunStatus.IDLE,
    val pendingApproval: ToolApprovalRequest? = null,
    val notice: UiNotice? = null,
    val offlineDemo: Boolean = true,
    val runtimeAvailable: Boolean = true,
    val settings: PocketPilotSettings = PocketPilotSettings(),
    val onboardingCompleted: Boolean? = null,
    val onboardingProjectSetupInProgress: Boolean = false,
    val onboardingProjectSetupSucceeded: Boolean = false,
    val onboardingProjectSetupError: String? = null,
    val onboardingCompletionInProgress: Boolean = false,
    val llmCredentialConfigured: Boolean = false,
    val llmConnectionTestInProgress: Boolean = false,
    val llmConnectionTestSucceeded: Boolean? = null,
    val llmConnectionTestError: String? = null,
    val gitCredentialConfigured: Boolean = false,
    val projectGitBinding: ProjectGitBinding? = null,
    val projectGitBindingInProgress: Boolean = false,
    val remoteServers: List<RemoteServerProfile> = emptyList(),
    val sshHostKeyScanInProgress: Boolean = false,
    val sshHostKeyCandidate: SshHostKeyCandidate? = null,
    val sshHostKeyScanError: String? = null,
    val plugins: List<InstalledPlugin> = emptyList(),
    val appUpdate: UpdateState,
    val confirmDeepLinkDiscard: Boolean = false,
) {
    val selectedProject: Project?
        get() = projects.firstOrNull { it.id == selectedProjectId }

    val selectedConversation: Conversation?
        get() = conversations.firstOrNull { it.id == selectedConversationId }

    val editorDirty: Boolean
        get() = selectedFilePath != null && editorText != savedEditorText
}

class PocketPilotViewModel(
    private val service: PocketPilotService,
    private val coordinator: AgentRunCoordinator,
    private val attachmentImporter: ChatImageAttachmentImporter,
    private val updateManager: GitHubReleaseUpdateManager,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        PocketPilotUiState(
            offlineDemo = service.isOfflineDemo,
            runtimeAvailable = service.runtimeAvailable,
            appUpdate = updateManager.state.value,
        ),
    )
    val state: StateFlow<PocketPilotUiState> = mutableState.asStateFlow()
    private val mutableNavigationEvents = Channel<PocketPilotNavigationEvent>(Channel.CONFLATED)
    val navigationEvents = mutableNavigationEvents.receiveAsFlow()

    private var noticeId = 0L
    private var contentLoadJob: Job? = null
    private var fileLoadJob: Job? = null
    private var conversationNavigationJob: Job? = null
    private var requestedFile: Pair<String, String>? = null
    private var initialized = false
    private var pendingConversationTarget: ConversationTarget? = null
    private val observedTerminalRunIds = mutableSetOf<String>()
    private val settingsMutex = Mutex()
    private var draftGeneration = 0L
    private var llmConnectionTestJob: Job? = null
    private var onboardingProjectSetupJob: Job? = null
    private var sshHostKeyScanJob: Job? = null
    private var appUpdateOperationJob: Job? = null
    private var lastObservedUpdatePhase = updateManager.state.value.phase
    private var notifiedAvailableVersion: String? = null

    init {
        viewModelScope.launch {
            updateManager.state.collect { updateState ->
                val previousPhase = lastObservedUpdatePhase
                lastObservedUpdatePhase = updateState.phase
                mutableState.update { it.copy(appUpdate = updateState) }
                val availableVersion = updateState.release?.versionName
                if (shouldNotifyAvailableUpdate(previousPhase, updateState, notifiedAvailableVersion)) {
                    notifiedAvailableVersion = availableVersion
                    postNotice(
                        localized(
                            "PocketPilot $availableVersion 可更新，请在设置的“版本更新”中下载并安装",
                            "PocketPilot $availableVersion is available. Open App update in Settings to install it.",
                        ),
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(
                coordinator.runs,
                coordinator.pendingApprovals,
                coordinator.pendingTasks,
            ) { runs, approvals, pendingTasks -> Triple(runs, approvals, pendingTasks) }
                .collect { (runs, approvals, pendingTasks) ->
                    syncCoordinatorState(runs, approvals, pendingTasks)
                    if (initialized) handleNewTerminalRuns(runs.values)
                }
        }
        viewModelScope.launch { initialize() }
    }

    private suspend fun initialize() {
        var succeeded = false
        runOperation {
            val snapshot = withContext(Dispatchers.IO) {
                val projects = service.initialize()
                val settings = service.loadSettings()
                var conversations = service.listConversations()
                val selected = conversations.firstOrNull()
                    ?: service.createConversation(
                        projects.first().id,
                        defaultConversationTitle(settings.language),
                    ).also { conversations = service.listConversations() }
                InitialSnapshot(
                    projects = projects,
                    conversations = conversations,
                    selected = selected,
                    settings = settings,
                    onboardingCompleted = service.isOnboardingCompleted(),
                    onboardingProjectSetupSucceeded = service.isOnboardingProjectConfigured(),
                    files = service.listFiles(selected.projectId),
                    checkpoints = service.listCheckpoints(selected.projectId),
                    timeline = service.listMessages(selected.id).map { it.toTimelineItem() },
                    llmCredentialConfigured = service.hasLlmCredential(),
                    gitCredentialConfigured = service.hasGitCredential(),
                    projectGitBinding = service.getProjectGitBinding(selected.projectId),
                    remoteServers = service.listRemoteServers(),
                    plugins = service.listPlugins(),
                )
            }
            mutableState.update {
                it.copy(
                    loading = false,
                    projects = snapshot.projects,
                    conversations = snapshot.conversations,
                    selectedProjectId = snapshot.selected.projectId,
                    selectedConversationId = snapshot.selected.id,
                    files = snapshot.files,
                    checkpoints = snapshot.checkpoints,
                    timeline = snapshot.timeline,
                    settings = snapshot.settings,
                    onboardingCompleted = snapshot.onboardingCompleted,
                    onboardingProjectSetupSucceeded = snapshot.onboardingProjectSetupSucceeded,
                    llmCredentialConfigured = snapshot.llmCredentialConfigured,
                    gitCredentialConfigured = snapshot.gitCredentialConfigured,
                    projectGitBinding = snapshot.projectGitBinding,
                    remoteServers = snapshot.remoteServers,
                    plugins = snapshot.plugins,
                    offlineDemo = service.isOfflineDemo,
                    runtimeAvailable = service.runtimeAvailable,
                )
            }
            initialized = true
            val resumedRuns = withContext(Dispatchers.IO) {
                coordinator.resumeRecoverableRuns()
            }
            if (resumedRuns > 0) {
                postNotice(
                    localized(
                        "已从安全边界恢复 $resumedRuns 个任务",
                        "Resumed $resumedRuns task(s) from a safe model boundary",
                    ),
                )
            }
            syncCoordinatorState(
                coordinator.runs.value,
                coordinator.pendingApprovals.value,
                coordinator.pendingTasks.value,
            )
            handleNewTerminalRuns(coordinator.runs.value.values)
            succeeded = true
        }
        if (succeeded) {
            launchUpdateOperation(notifyFailure = false) { updateManager.check(force = false) }
            val target = pendingConversationTarget
            pendingConversationTarget = null
            target?.let(::navigateToConversation)
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            runOperation {
                val result = withContext(Dispatchers.IO) {
                    service.listProjects() to service.listConversations()
                }
                mutableState.update { it.copy(projects = result.first, conversations = result.second) }
            }
        }
    }

    fun createProject(name: String) {
        if (!canChangeContext()) return
        viewModelScope.launch {
            runOperation {
                val settings = mutableState.value.settings
                val result = withContext(Dispatchers.IO) {
                    val project = service.createProject(name)
                    val conversation = service.createConversation(
                        project.id,
                        defaultConversationTitle(settings.language),
                    )
                    ProjectSelection(
                        projects = service.listProjects(),
                        conversations = service.listConversations(),
                        project = project,
                        conversation = conversation,
                    )
                }
                applySelection(result)
                postNotice(localized("已创建 ${result.project.name}", "Created ${result.project.name}"))
            }
        }
    }

    fun configureOnboardingLocalProject(name: String) {
        launchOnboardingProjectSetup {
            service.createProject(name)
        }
    }

    fun configureOnboardingGitProject(
        name: String,
        remoteUrl: String,
        branch: String?,
        username: String,
        useStoredCredential: Boolean,
        rawToken: String?,
    ) {
        if (onboardingProjectSetupJob?.isActive == true) return
        val token = rawToken?.takeIf(String::isNotEmpty)?.toCharArray()
        mutableState.update {
            it.copy(
                onboardingProjectSetupInProgress = true,
                onboardingProjectSetupSucceeded = false,
                onboardingProjectSetupError = null,
            )
        }
        onboardingProjectSetupJob = viewModelScope.launch {
            try {
                val project = runInterruptible(Dispatchers.IO) {
                    service.createProjectFromGit(
                        name = name,
                        remoteUrl = remoteUrl,
                        branch = branch,
                        username = username,
                        useStoredCredential = useStoredCredential,
                        newToken = token,
                    )
                }
                val selection = withContext(Dispatchers.IO) {
                    selectionForNewProject(project).also {
                        service.markOnboardingProjectConfigured(project.id)
                    }
                }
                applySelection(selection)
                val credentialConfigured = withContext(Dispatchers.IO) { service.hasGitCredential() }
                mutableState.update {
                    it.copy(
                        gitCredentialConfigured = credentialConfigured,
                        onboardingProjectSetupInProgress = false,
                        onboardingProjectSetupSucceeded = true,
                        onboardingProjectSetupError = null,
                    )
                }
                postNotice(localized("Git 项目已克隆", "Git project cloned"))
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(onboardingProjectSetupInProgress = false) }
                throw cancelled
            } catch (error: Throwable) {
                val message = error.displayMessage()
                mutableState.update {
                    it.copy(
                        onboardingProjectSetupInProgress = false,
                        onboardingProjectSetupSucceeded = false,
                        onboardingProjectSetupError = message,
                    )
                }
                postNotice(message, isError = true)
            } finally {
                token?.fill('\u0000')
            }
        }
    }

    fun clearOnboardingProjectSetupResult() {
        if (onboardingProjectSetupJob?.isActive == true) return
        mutableState.update {
            it.copy(
                onboardingProjectSetupSucceeded = false,
                onboardingProjectSetupError = null,
            )
        }
    }

    fun completeOnboarding() {
        val snapshot = mutableState.value
        if (snapshot.onboardingCompletionInProgress || snapshot.onboardingCompleted != false) return
        if (onboardingProjectSetupJob?.isActive == true || llmConnectionTestJob?.isActive == true) {
            postNotice(localized("请等待当前配置完成", "Wait for the current setup to finish"), isError = true)
            return
        }
        if (!snapshot.llmCredentialConfigured) {
            postNotice(localized("请先完成模型连接测试", "Complete the model connection test first"), isError = true)
            return
        }
        mutableState.update { it.copy(onboardingCompletionInProgress = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { service.completeOnboarding() }
                mutableState.update {
                    it.copy(
                        onboardingCompleted = true,
                        onboardingCompletionInProgress = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(onboardingCompletionInProgress = false) }
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(onboardingCompletionInProgress = false) }
                postNotice(error.displayMessage(), isError = true)
            }
        }
    }

    private fun launchOnboardingProjectSetup(createProject: suspend () -> Project) {
        if (onboardingProjectSetupJob?.isActive == true) return
        mutableState.update {
            it.copy(
                onboardingProjectSetupInProgress = true,
                onboardingProjectSetupSucceeded = false,
                onboardingProjectSetupError = null,
            )
        }
        onboardingProjectSetupJob = viewModelScope.launch {
            try {
                val selection = withContext(Dispatchers.IO) {
                    val project = createProject()
                    selectionForNewProject(project).also {
                        service.markOnboardingProjectConfigured(project.id)
                    }
                }
                applySelection(selection)
                mutableState.update {
                    it.copy(
                        onboardingProjectSetupInProgress = false,
                        onboardingProjectSetupSucceeded = true,
                        onboardingProjectSetupError = null,
                    )
                }
                postNotice(localized("本地项目已创建", "Local project created"))
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(onboardingProjectSetupInProgress = false) }
                throw cancelled
            } catch (error: Throwable) {
                val message = error.displayMessage()
                mutableState.update {
                    it.copy(
                        onboardingProjectSetupInProgress = false,
                        onboardingProjectSetupSucceeded = false,
                        onboardingProjectSetupError = message,
                    )
                }
                postNotice(message, isError = true)
            }
        }
    }

    private suspend fun selectionForNewProject(project: Project): ProjectSelection {
        val conversation = service.createConversation(
            project.id,
            defaultConversationTitle(mutableState.value.settings.language),
        )
        return ProjectSelection(
            projects = service.listProjects(),
            conversations = service.listConversations(),
            project = project,
            conversation = conversation,
        )
    }

    fun deleteProject(projectId: String) {
        if (!ensureProjectIsIdle(projectId)) return
        if (!canChangeContext()) return
        viewModelScope.launch {
            runOperation {
                val settings = mutableState.value.settings
                val result = withContext(Dispatchers.IO) {
                    service.deleteProject(projectId)
                    attachmentImporter.deleteProject(projectId)
                    val projects = service.listProjects()
                    var conversations = service.listConversations()
                    val conversation = conversations.firstOrNull()
                        ?: service.createConversation(
                            projects.first().id,
                            defaultConversationTitle(settings.language),
                        ).also { conversations = service.listConversations() }
                    ProjectSelection(
                        projects = projects,
                        conversations = conversations,
                        project = projects.first { it.id == conversation.projectId },
                        conversation = conversation,
                    )
                }
                applySelection(result)
                postNotice(localized("项目已删除", "Project deleted"))
            }
        }
    }

    fun saveProjectGitBinding(
        remoteName: String,
        remoteUrl: String,
        branch: String?,
        rawToken: String?,
    ) {
        val projectId = mutableState.value.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId) || mutableState.value.projectGitBindingInProgress) return
        val token = rawToken?.takeIf(String::isNotEmpty)?.toCharArray()
        mutableState.update { it.copy(projectGitBindingInProgress = true) }
        viewModelScope.launch {
            try {
                val binding = runInterruptible(Dispatchers.IO) {
                    service.saveProjectGitBinding(
                        projectId = projectId,
                        remoteName = remoteName,
                        remoteUrl = remoteUrl,
                        branch = branch,
                        newToken = token,
                    )
                }
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId) {
                        current.copy(projectGitBinding = binding, projectGitBindingInProgress = false)
                    } else {
                        current.copy(projectGitBindingInProgress = false)
                    }
                }
                postNotice(localized("Git 仓库已绑定到当前项目", "Git repository bound to this project"))
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(projectGitBindingInProgress = false) }
                throw cancelled
            } catch (error: Throwable) {
                mutableState.update { it.copy(projectGitBindingInProgress = false) }
                postNotice(error.displayMessage(), isError = true)
            } finally {
                token?.fill('\u0000')
            }
        }
    }

    fun removeProjectGitBinding() {
        val projectId = mutableState.value.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId) || mutableState.value.projectGitBindingInProgress) return
        mutableState.update { it.copy(projectGitBindingInProgress = true) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { service.removeProjectGitBinding(projectId) }
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId) {
                        current.copy(projectGitBinding = null, projectGitBindingInProgress = false)
                    } else {
                        current.copy(projectGitBindingInProgress = false)
                    }
                }
                postNotice(localized("已解除 Git 绑定，本地仓库和文件未删除", "Git binding removed; local files were kept"))
            } catch (error: Throwable) {
                mutableState.update { it.copy(projectGitBindingInProgress = false) }
                postNotice(error.displayMessage(), isError = true)
            }
        }
    }

    fun removeProjectGitCredential() {
        val projectId = mutableState.value.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId) || mutableState.value.projectGitBindingInProgress) return
        mutableState.update { it.copy(projectGitBindingInProgress = true) }
        viewModelScope.launch {
            try {
                val binding = withContext(Dispatchers.IO) { service.removeProjectGitCredential(projectId) }
                mutableState.update { current ->
                    current.copy(
                        projectGitBinding = if (current.selectedProjectId == projectId) binding else current.projectGitBinding,
                        projectGitBindingInProgress = false,
                    )
                }
                postNotice(localized("已移除当前项目的 Personal access token", "Personal access token removed"))
            } catch (error: Throwable) {
                mutableState.update { it.copy(projectGitBindingInProgress = false) }
                postNotice(error.displayMessage(), isError = true)
            }
        }
    }

    fun selectProject(projectId: String) {
        if (!canChangeContext()) return
        val project = mutableState.value.projects.firstOrNull { it.id == projectId } ?: return
        viewModelScope.launch {
            runOperation {
                val settings = mutableState.value.settings
                val result = withContext(Dispatchers.IO) {
                    var conversations = service.listConversations()
                    val conversation = conversations.firstOrNull { it.projectId == projectId }
                        ?: service.createConversation(
                            projectId,
                            defaultConversationTitle(settings.language),
                        ).also { conversations = service.listConversations() }
                    ProjectSelection(
                        projects = service.listProjects(),
                        conversations = conversations,
                        project = project,
                        conversation = conversation,
                    )
                }
                applySelection(result)
            }
        }
    }

    fun selectConversation(conversationId: String) {
        if (!canChangeContext()) return
        val snapshot = mutableState.value
        val conversation = snapshot.conversations.firstOrNull { it.id == conversationId } ?: return
        val project = snapshot.projects.firstOrNull { it.id == conversation.projectId } ?: return
        applySelection(
            ProjectSelection(snapshot.projects, snapshot.conversations, project, conversation),
        )
    }

    fun createConversation() {
        if (!canChangeContext()) return
        val snapshot = mutableState.value
        val project = snapshot.selectedProject ?: snapshot.projects.firstOrNull() ?: return
        viewModelScope.launch {
            runOperation {
                val result = withContext(Dispatchers.IO) {
                    val conversation = service.createConversation(
                        project.id,
                        defaultConversationTitle(snapshot.settings.language),
                    )
                    ProjectSelection(
                        projects = service.listProjects(),
                        conversations = service.listConversations(),
                        project = project,
                        conversation = conversation,
                    )
                }
                applySelection(result)
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        val conversation = mutableState.value.conversations.firstOrNull { it.id == conversationId } ?: return
        if (!ensureProjectIsIdle(conversation.projectId)) return
        if (!canChangeContext()) return
        viewModelScope.launch {
            runOperation {
                val snapshot = mutableState.value
                val result = withContext(Dispatchers.IO) {
                    val removedAttachmentIds = service.listMessages(conversationId)
                        .flatMap { it.attachments }
                        .mapTo(mutableSetOf()) { it.id }
                    service.deleteConversation(conversationId)
                    val stillReferencedIds = service.listConversations(conversation.projectId)
                        .flatMap { remaining -> service.listMessages(remaining.id) }
                        .flatMap { it.attachments }
                        .mapTo(mutableSetOf()) { it.id }
                    (removedAttachmentIds - stillReferencedIds).forEach { attachmentId ->
                        attachmentImporter.delete(conversation.projectId, attachmentId)
                    }
                    var conversations = service.listConversations()
                    val project = snapshot.selectedProject ?: service.listProjects().first()
                    val conversation = conversations.firstOrNull { it.projectId == project.id }
                        ?: service.createConversation(
                            project.id,
                            defaultConversationTitle(snapshot.settings.language),
                        ).also { conversations = service.listConversations() }
                    ProjectSelection(service.listProjects(), conversations, project, conversation)
                }
                if (snapshot.selectedConversationId == conversationId) {
                    applySelection(result)
                } else {
                    mutableState.update { it.copy(conversations = result.conversations) }
                }
            }
        }
    }

    fun openConversation(projectId: String, conversationId: String) {
        if (projectId.isBlank() || conversationId.isBlank()) return
        val target = ConversationTarget(projectId, conversationId)
        if (!initialized) {
            pendingConversationTarget = target
            return
        }
        navigateToConversation(target)
    }

    private fun navigateToConversation(target: ConversationTarget) {
        val snapshot = mutableState.value
        if (
            snapshot.selectedProjectId == target.projectId &&
            snapshot.selectedConversationId == target.conversationId
        ) {
            mutableNavigationEvents.trySend(PocketPilotNavigationEvent.OPEN_CHAT)
            return
        }
        if (snapshot.editorDirty) {
            pendingConversationTarget = target
            mutableState.update { it.copy(confirmDeepLinkDiscard = true) }
            return
        }
        conversationNavigationJob?.cancel()
        conversationNavigationJob = viewModelScope.launch {
            runOperation {
                val selection = withContext(Dispatchers.IO) {
                    val projects = service.listProjects()
                    val conversations = service.listConversations()
                    val conversation = conversations.firstOrNull {
                        it.id == target.conversationId && it.projectId == target.projectId
                    } ?: return@withContext null
                    val project = projects.firstOrNull { it.id == target.projectId }
                        ?: return@withContext null
                    ProjectSelection(projects, conversations, project, conversation)
                }
                if (selection == null) {
                    postNotice(
                        localized("通知中的会话已不存在", "The conversation in this notification no longer exists"),
                        isError = true,
                    )
                    return@runOperation
                }
                applySelection(selection)
                mutableNavigationEvents.trySend(PocketPilotNavigationEvent.OPEN_CHAT)
            }
        }
    }

    fun discardEditorAndOpenPendingConversation() {
        val target = pendingConversationTarget ?: return
        pendingConversationTarget = null
        mutableState.update {
            it.copy(
                confirmDeepLinkDiscard = false,
                selectedFilePath = null,
                editorText = "",
                savedEditorText = "",
            )
        }
        navigateToConversation(target)
    }

    fun cancelPendingConversationNavigation() {
        pendingConversationTarget = null
        mutableState.update { it.copy(confirmDeepLinkDiscard = false) }
    }

    private fun applySelection(selection: ProjectSelection) {
        contentLoadJob?.cancel()
        fileLoadJob?.cancel()
        requestedFile = null
        discardDraftAttachments(mutableState.value.agentAttachments)
        draftGeneration += 1
        mutableState.update {
            it.copy(
                loading = true,
                projects = selection.projects,
                conversations = selection.conversations,
                selectedProjectId = selection.project.id,
                selectedConversationId = selection.conversation.id,
                selectedFilePath = null,
                editorText = "",
                savedEditorText = "",
                files = emptyList(),
                checkpoints = emptyList(),
                timeline = emptyList(),
                agentStatus = AgentRunStatus.IDLE,
                pendingApproval = null,
                agentInput = "",
                agentAttachments = emptyList(),
                projectGitBinding = null,
                projectGitBindingInProgress = false,
            )
        }
        syncCoordinatorState(
            coordinator.runs.value,
            coordinator.pendingApprovals.value,
            coordinator.pendingTasks.value,
        )
        contentLoadJob = viewModelScope.launch {
            loadSelectionContent(selection.project.id, selection.conversation.id)
        }
    }

    private suspend fun loadSelectionContent(projectId: String, conversationId: String) {
        runOperation {
            val content = withContext(Dispatchers.IO) {
                SelectionContent(
                    files = service.listFiles(projectId),
                    checkpoints = service.listCheckpoints(projectId),
                    timeline = service.listMessages(conversationId).map { it.toTimelineItem() },
                    gitBinding = service.getProjectGitBinding(projectId),
                )
            }
            mutableState.update { current ->
                if (
                    current.selectedProjectId == projectId &&
                    current.selectedConversationId == conversationId
                ) {
                    current.copy(
                        loading = false,
                        files = content.files,
                        checkpoints = content.checkpoints,
                        timeline = mergeTimeline(
                            content.timeline,
                            coordinatorTimeline(conversationId, coordinator.runs.value.values) +
                                coordinator.pendingTasks.value
                                    .filter { it.conversationId == conversationId }
                                    .map { it.userItem },
                        ),
                        projectGitBinding = content.gitBinding,
                    )
                } else {
                    current
                }
            }
        }
    }

    fun selectSection(section: ProjectSection) {
        mutableState.update { it.copy(section = section) }
        val projectId = mutableState.value.selectedProjectId ?: return
        if (section == ProjectSection.CHECKPOINTS) refreshCheckpoints(projectId)
        if (section == ProjectSection.FILES) refreshFiles(projectId)
    }

    fun openFile(path: String) {
        val projectId = mutableState.value.selectedProjectId ?: return
        fileLoadJob?.cancel()
        val request = projectId to path
        requestedFile = request
        fileLoadJob = viewModelScope.launch {
            try {
                runOperation {
                    val content = withContext(Dispatchers.IO) { service.readFile(projectId, path) }
                    mutableState.update { current ->
                        if (current.selectedProjectId == projectId && requestedFile == request) {
                            current.copy(selectedFilePath = path, editorText = content, savedEditorText = content)
                        } else {
                            current
                        }
                    }
                }
            } finally {
                if (requestedFile == request) requestedFile = null
            }
        }
    }

    fun closeEditor() {
        mutableState.update { it.copy(selectedFilePath = null, editorText = "", savedEditorText = "") }
    }

    fun updateEditor(text: String) {
        mutableState.update { it.copy(editorText = text) }
    }

    fun createFile(path: String) {
        val projectId = mutableState.value.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId)) return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.createFile(projectId, path) }
                refreshFilesAndCheckpoints(projectId)
                if (mutableState.value.selectedProjectId == projectId) openFile(path.trim().replace('\\', '/'))
                postNotice(localized("文件已创建", "File created"))
            }
        }
    }

    fun saveFile() {
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId)) return
        val path = snapshot.selectedFilePath ?: return
        val content = snapshot.editorText
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.saveFile(projectId, path, content) }
                refreshFilesAndCheckpoints(projectId)
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId && current.selectedFilePath == path) {
                        current.copy(savedEditorText = content)
                    } else {
                        current
                    }
                }
                postNotice(localized("已保存 $path", "Saved $path"))
            }
        }
    }

    fun deleteFile(path: String) {
        val projectId = mutableState.value.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId)) return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.deleteFile(projectId, path) }
                refreshFilesAndCheckpoints(projectId)
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId && current.selectedFilePath == path) {
                        current.copy(selectedFilePath = null, editorText = "", savedEditorText = "")
                    } else {
                        current
                    }
                }
                postNotice(localized("已删除 $path", "Deleted $path"))
            }
        }
    }

    fun restoreCheckpoint(checkpointId: String) {
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        if (!ensureProjectIsIdle(projectId)) return
        if (snapshot.editorDirty) {
            postNotice(
                localized(
                    "请先保存或放弃编辑器中的修改，再恢复 Checkpoint",
                    "Save or discard editor changes before restoring a checkpoint",
                ),
                isError = true,
            )
            return
        }
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.restoreCheckpoint(projectId, checkpointId) }
                refreshFilesAndCheckpoints(projectId)
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId) {
                        current.copy(selectedFilePath = null, editorText = "", savedEditorText = "")
                    } else {
                        current
                    }
                }
                postNotice(localized("工作区已恢复", "Workspace restored"))
            }
        }
    }

    fun updateAgentInput(value: String) {
        mutableState.update { it.copy(agentInput = value) }
    }

    fun importAgentImages(uris: List<Uri>) {
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        val conversationId = snapshot.selectedConversationId ?: return
        val importGeneration = draftGeneration
        val availableSlots = MAX_CHAT_ATTACHMENTS - snapshot.agentAttachments.size
        if (availableSlots <= 0) {
            postNotice(
                localized(
                    "每条消息最多添加 $MAX_CHAT_ATTACHMENTS 张图片",
                    "Attach up to $MAX_CHAT_ATTACHMENTS images",
                ),
                isError = true,
            )
            return
        }
        val selected = uris.distinct().take(availableSlots)
        viewModelScope.launch {
            val outcomes = withContext(Dispatchers.IO) {
                selected.map { uri -> runCatching { attachmentImporter.import(projectId, uri) } }
            }
            val imported = outcomes.mapNotNull { it.getOrNull() }
            if (
                mutableState.value.selectedProjectId == projectId &&
                mutableState.value.selectedConversationId == conversationId &&
                draftGeneration == importGeneration
            ) {
                var retainedIds = emptySet<String>()
                mutableState.update { current ->
                    val remaining = (MAX_CHAT_ATTACHMENTS - current.agentAttachments.size).coerceAtLeast(0)
                    val retained = imported.take(remaining)
                    retainedIds = retained.mapTo(mutableSetOf()) { it.id }
                    current.copy(agentAttachments = current.agentAttachments + retained)
                }
                val discarded = imported.filterNot { it.id in retainedIds }
                if (discarded.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        discarded.forEach { runCatching { attachmentImporter.delete(projectId, it.id) } }
                    }
                }
            } else {
                withContext(Dispatchers.IO) {
                    imported.forEach { runCatching { attachmentImporter.delete(projectId, it.id) } }
                }
            }
            val failed = outcomes.count { it.isFailure }
            if (failed > 0) {
                postNotice(
                    localized(
                        "有 $failed 张图片无法导入，请使用 JPEG、PNG 或 WebP（单张不超过 8 MiB）",
                        "$failed image(s) could not be imported. Use JPEG, PNG, or WebP up to 8 MiB",
                    ),
                    isError = true,
                )
            }
            if (uris.distinct().size > availableSlots) {
                postNotice(
                    localized(
                        "每条消息最多添加 $MAX_CHAT_ATTACHMENTS 张图片",
                        "Attach up to $MAX_CHAT_ATTACHMENTS images",
                    ),
                    isError = true,
                )
            }
        }
    }

    fun removeAgentAttachment(attachmentId: String) {
        var removed: ChatImageAttachment? = null
        mutableState.update { current ->
            removed = current.agentAttachments.firstOrNull { it.id == attachmentId }
            if (removed == null) {
                current
            } else {
                current.copy(
                    agentAttachments = current.agentAttachments.filterNot { it.id == attachmentId },
                )
            }
        }
        val attachment = removed ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { attachmentImporter.delete(attachment.projectId, attachment.id) }
        }
    }

    fun sendAgentTask() {
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        val conversationId = snapshot.selectedConversationId ?: return
        val task = snapshot.agentInput.trim().ifEmpty {
            if (snapshot.agentAttachments.isEmpty()) return
            localized("请分析我附上的图片", "Please analyze the attached images")
        }
        if (snapshot.editorDirty) {
            postNotice(
                localized(
                    "请先保存或放弃编辑器中的修改，再启动 Agent",
                    "Save or discard editor changes before starting the agent",
                ),
                isError = true,
            )
            return
        }

        val firstUserMessage = snapshot.timeline.none { it.kind == TimelineItemKind.USER }
        try {
            coordinator.enqueueOrStart(
                projectId = projectId,
                conversationId = conversationId,
                task = task,
                attachments = snapshot.agentAttachments,
            )
        } catch (error: Throwable) {
            postNotice(error.displayMessage(), isError = true)
            return
        }
        draftGeneration += 1
        mutableState.update { it.copy(agentInput = "", agentAttachments = emptyList()) }
        syncCoordinatorState(
            coordinator.runs.value,
            coordinator.pendingApprovals.value,
            coordinator.pendingTasks.value,
        )

        if (firstUserMessage) {
            viewModelScope.launch {
                runOperation {
                    withContext(Dispatchers.IO) {
                        service.renameConversation(conversationId, conversationTitleFrom(task))
                    }
                    refreshConversationIndex()
                }
            }
        }
    }

    fun resolveApproval(approved: Boolean) {
        val request = mutableState.value.pendingApproval ?: return
        if (!coordinator.resolveApproval(request.id, approved)) {
            postNotice(localized("该审批请求已经失效", "This approval request has expired"), isError = true)
        }
    }

    fun cancelAgent() {
        val conversationId = mutableState.value.selectedConversationId ?: return
        val run = coordinator.runs.value.values
            .filter { it.conversationId == conversationId && !it.status.isTerminal }
            .maxByOrNull { it.startedAt }
            ?: return
        coordinator.cancel(run.runId)
    }

    fun updateSettings(settings: PocketPilotSettings) {
        val previous = mutableState.value.settings
        mutableState.update { it.copy(settings = settings) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    settingsMutex.withLock {
                        service.saveSettings(mutableState.value.settings)
                    }
                }
            } catch (error: Throwable) {
                mutableState.update { current ->
                    if (current.settings == settings) current.copy(settings = previous) else current
                }
                postNotice(error.displayMessage(), isError = true)
            }
        }
    }

    fun setTheme(theme: ThemePreference) = updateSettings(mutableState.value.settings.copy(theme = theme))

    fun setLanguage(language: AppLanguage) =
        updateSettings(mutableState.value.settings.copy(language = language))

    fun checkForAppUpdate() {
        launchUpdateOperation { updateManager.check(force = true) }
    }

    fun downloadAndInstallAppUpdate() {
        launchUpdateOperation {
            updateManager.download()
            if (updateManager.state.value.phase == UpdatePhase.READY_TO_INSTALL) {
                updateManager.install()
            }
        }
    }

    fun installAppUpdate() {
        launchUpdateOperation {
            updateManager.install()
        }
    }

    fun openUnknownSourcesSettings() {
        try {
            if (!updateManager.openUnknownSourcesSettings()) {
                postNotice(
                    localized(
                        "无法打开安装权限设置，请在系统设置中允许 PocketPilot 安装未知应用",
                        "Could not open install access. Allow PocketPilot to install unknown apps in system settings.",
                    ),
                    isError = true,
                )
            }
        } catch (error: Throwable) {
            postNotice(error.displayMessage(), isError = true)
        }
    }

    fun saveLlmConnection(updatedSettings: PocketPilotSettings, rawSecret: String?) {
        if (llmConnectionTestJob?.isActive == true) return
        mutableState.update {
            it.copy(
                llmConnectionTestInProgress = true,
                llmConnectionTestSucceeded = null,
                llmConnectionTestError = null,
            )
        }
        llmConnectionTestJob = viewModelScope.launch {
            try {
                val secret = rawSecret?.takeIf(String::isNotBlank)?.toCharArray()
                try {
                    withContext(Dispatchers.IO) {
                        settingsMutex.withLock {
                            val currentSettings = service.loadSettings()
                            service.saveLlmConnection(
                                currentSettings.copyLlmConnectionFrom(updatedSettings),
                                secret,
                            )
                            val persistedSettings = service.loadSettings()
                            mutableState.update {
                                it.copy(
                                    settings = it.settings.copyLlmConnectionFrom(persistedSettings),
                                    llmCredentialConfigured = service.hasLlmCredential(),
                                    offlineDemo = service.isOfflineDemo,
                                    llmConnectionTestInProgress = false,
                                    llmConnectionTestSucceeded = true,
                                    llmConnectionTestError = null,
                                )
                            }
                        }
                    }
                } finally {
                    secret?.fill('\u0000')
                }
                postNotice(
                    localized(
                        "文本与图片模型连接测试通过，配置已安全保存",
                        "Text and image model tests passed; the connection was saved securely",
                    ),
                )
            } catch (cancelled: CancellationException) {
                mutableState.update {
                    it.copy(
                        llmConnectionTestInProgress = false,
                        llmConnectionTestSucceeded = false,
                        llmConnectionTestError = null,
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                val message = error.displayMessage()
                mutableState.update {
                    it.copy(
                        llmConnectionTestInProgress = false,
                        llmConnectionTestSucceeded = false,
                        llmConnectionTestError = message,
                    )
                }
            }
        }
    }

    fun clearLlmConnectionTestResult() {
        mutableState.update {
            if (it.llmConnectionTestInProgress) {
                it
            } else {
                it.copy(
                    llmConnectionTestSucceeded = null,
                    llmConnectionTestError = null,
                )
            }
        }
    }

    fun removeLlmCredential() {
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) {
                    settingsMutex.withLock { service.removeLlmCredential() }
                }
                mutableState.update {
                    it.copy(llmCredentialConfigured = false, offlineDemo = service.isOfflineDemo)
                }
                postNotice(localized("模型密钥已移除", "Model credential removed"))
            }
        }
    }

    fun saveGitCredential(rawSecret: String) {
        saveCredential(
            rawSecret = rawSecret,
            save = service::saveGitCredential,
            onSaved = {
                mutableState.update { it.copy(gitCredentialConfigured = true) }
                postNotice(localized("Git 凭据已安全保存", "Git credential saved securely"))
            },
        )
    }

    fun removeGitCredential() {
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.removeGitCredential() }
                mutableState.update { it.copy(gitCredentialConfigured = false) }
                postNotice(localized("Git 凭据已移除", "Git credential removed"))
            }
        }
    }

    fun saveRemoteServer(profile: RemoteServerProfile, rawSecret: String?) {
        viewModelScope.launch {
            runOperation {
                val chars = rawSecret?.takeIf { it.isNotEmpty() }?.toCharArray()
                try {
                    withContext(Dispatchers.IO) { service.saveRemoteServer(profile, chars) }
                } finally {
                    chars?.fill('\u0000')
                }
                val servers = withContext(Dispatchers.IO) { service.listRemoteServers() }
                mutableState.update { it.copy(remoteServers = servers) }
                postNotice(localized("远程服务器已保存", "Remote server saved"))
            }
        }
    }

    fun scanSshHostKey(host: String, port: Int) {
        if (sshHostKeyScanJob?.isActive == true) return
        mutableState.update {
            it.copy(
                sshHostKeyScanInProgress = true,
                sshHostKeyCandidate = null,
                sshHostKeyScanError = null,
            )
        }
        sshHostKeyScanJob = viewModelScope.launch {
            try {
                val candidate = runInterruptible(Dispatchers.IO) {
                    service.scanSshHostKey(host, port)
                }
                mutableState.update {
                    it.copy(
                        sshHostKeyScanInProgress = false,
                        sshHostKeyCandidate = candidate,
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                mutableState.update {
                    it.copy(
                        sshHostKeyScanInProgress = false,
                        sshHostKeyScanError = error.message ?: "SSH host-key scan failed",
                    )
                }
            }
        }
    }

    fun clearSshHostKeyScan() {
        sshHostKeyScanJob?.cancel()
        sshHostKeyScanJob = null
        mutableState.update {
            it.copy(
                sshHostKeyScanInProgress = false,
                sshHostKeyCandidate = null,
                sshHostKeyScanError = null,
            )
        }
    }

    fun deleteRemoteServer(serverId: String) {
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.deleteRemoteServer(serverId) }
                val servers = withContext(Dispatchers.IO) { service.listRemoteServers() }
                mutableState.update { it.copy(remoteServers = servers) }
                postNotice(localized("远程服务器已删除", "Remote server deleted"))
            }
        }
    }

    fun previewPluginBundle(bundleJson: String): Result<PluginInstallPreview> =
        runCatching { service.previewPluginBundle(bundleJson) }

    fun installPluginBundle(bundleJson: String) {
        if (!ensureNoActiveRunsForPluginChange()) return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.installPluginBundle(bundleJson) }
                val plugins = withContext(Dispatchers.IO) { service.listPlugins() }
                mutableState.update { it.copy(plugins = plugins) }
                postNotice(localized("插件已安装，默认保持停用", "Plugin installed and left disabled by default"))
            }
        }
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        if (!ensureNoActiveRunsForPluginChange()) return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.setPluginEnabled(pluginId, enabled) }
                val plugins = withContext(Dispatchers.IO) { service.listPlugins() }
                mutableState.update { current -> current.copy(plugins = plugins) }
                postNotice(
                    if (enabled) {
                        localized("插件已启用", "Plugin enabled")
                    } else {
                        localized("插件已停用", "Plugin disabled")
                    },
                )
            }
        }
    }

    fun deletePlugin(pluginId: String) {
        if (!ensureNoActiveRunsForPluginChange()) return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.deletePlugin(pluginId) }
                val plugins = withContext(Dispatchers.IO) { service.listPlugins() }
                mutableState.update { it.copy(plugins = plugins) }
                postNotice(localized("插件已卸载", "Plugin uninstalled"))
            }
        }
    }

    fun consumeNotice(id: Long) {
        mutableState.update { current -> if (current.notice?.id == id) current.copy(notice = null) else current }
    }

    private fun syncCoordinatorState(
        runs: Map<String, CoordinatedAgentRun>,
        approvals: List<ToolApprovalRequest>,
        pendingTasks: List<PendingAgentTask>,
    ) {
        mutableState.update { current ->
            val conversationId = current.selectedConversationId
                ?: return@update current.copy(
                    agentStatus = AgentRunStatus.IDLE,
                    pendingApproval = null,
                    queuedAgentTaskCount = 0,
                )
            val conversationRuns = runs.values
                .filter { it.conversationId == conversationId }
                .sortedBy { it.startedAt }
            val activeRun = conversationRuns
                .filterNot { it.status.isTerminal }
                .maxByOrNull { it.startedAt }
            val latestRun = activeRun ?: conversationRuns.maxByOrNull { it.startedAt }
            val activeRunIds = conversationRuns
                .filterNot { it.status.isTerminal }
                .mapTo(mutableSetOf()) { it.runId }
            val approval = approvals.firstOrNull { it.runId in activeRunIds }
            val conversationPending = pendingTasks.filter { it.conversationId == conversationId }
            current.copy(
                timeline = mergeTimeline(
                    current.timeline,
                    conversationRuns.flatMap { it.timeline } + conversationPending.map { it.userItem },
                ),
                agentStatus = when {
                    approval != null -> AgentRunStatus.WAITING_FOR_APPROVAL
                    latestRun != null -> latestRun.status
                    else -> AgentRunStatus.IDLE
                },
                pendingApproval = approval,
                queuedAgentTaskCount = conversationPending.size,
            )
        }
    }

    private fun handleNewTerminalRuns(runs: Collection<CoordinatedAgentRun>) {
        runs.filter { it.status.isTerminal && observedTerminalRunIds.add(it.runId) }
            .forEach { run ->
                viewModelScope.launch {
                    try {
                        refreshConversationIndex()
                        if (mutableState.value.selectedProjectId == run.projectId) {
                            refreshFilesAndCheckpoints(run.projectId)
                            refreshOpenEditorAfterAgent(run.projectId)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (refreshError: Throwable) {
                        postNotice(
                            localized(
                                "Run 已结束，但刷新 Workspace 失败：${refreshError.displayMessage()}",
                                "Run finished, but Workspace refresh failed: ${refreshError.displayMessage()}",
                            ),
                            isError = true,
                        )
                    }
                }
            }
    }

    private fun refreshFiles(projectId: String) {
        viewModelScope.launch {
            runOperation {
                val files = withContext(Dispatchers.IO) { service.listFiles(projectId) }
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId) current.copy(files = files) else current
                }
            }
        }
    }

    private fun refreshCheckpoints(projectId: String) {
        viewModelScope.launch {
            runOperation {
                val checkpoints = withContext(Dispatchers.IO) { service.listCheckpoints(projectId) }
                mutableState.update { current ->
                    if (current.selectedProjectId == projectId) current.copy(checkpoints = checkpoints) else current
                }
            }
        }
    }

    private suspend fun refreshFilesAndCheckpoints(projectId: String) {
        val files = withContext(Dispatchers.IO) { service.listFiles(projectId) }
        val checkpoints = withContext(Dispatchers.IO) { service.listCheckpoints(projectId) }
        mutableState.update { current ->
            if (current.selectedProjectId != projectId) return@update current
            val selectedWasRemoved = current.selectedFilePath != null &&
                files.none { it.path == current.selectedFilePath }
            if (selectedWasRemoved && !current.editorDirty) {
                current.copy(
                    files = files,
                    checkpoints = checkpoints,
                    selectedFilePath = null,
                    editorText = "",
                    savedEditorText = "",
                )
            } else {
                current.copy(files = files, checkpoints = checkpoints)
            }
        }
    }

    private suspend fun refreshOpenEditorAfterAgent(projectId: String) {
        val snapshot = mutableState.value
        val path = snapshot.selectedFilePath ?: return
        if (snapshot.selectedProjectId != projectId || snapshot.editorDirty) return
        val content = withContext(Dispatchers.IO) { service.readFile(projectId, path) }
        mutableState.update { current ->
            if (
                current.selectedProjectId == projectId &&
                current.selectedFilePath == path &&
                !current.editorDirty
            ) {
                current.copy(editorText = content, savedEditorText = content)
            } else {
                current
            }
        }
    }

    private suspend fun refreshConversationIndex() {
        val conversations = withContext(Dispatchers.IO) { service.listConversations() }
        mutableState.update { it.copy(conversations = conversations) }
    }

    private suspend fun runOperation(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.update { it.copy(loading = false) }
            postNotice(error.displayMessage(), isError = true)
        }
    }

    private fun launchUpdateOperation(
        notifyFailure: Boolean = true,
        block: suspend () -> Unit,
    ) {
        if (appUpdateOperationJob?.isActive == true) return
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (notifyFailure) postNotice(error.displayMessage(), isError = true)
            }
        }
        appUpdateOperationJob = job
        job.invokeOnCompletion {
            if (appUpdateOperationJob === job) appUpdateOperationJob = null
        }
        job.start()
    }

    private fun postBusyNotice() {
        postNotice(
            localized("请先停止当前 Agent Run", "Stop the current Agent run first"),
            isError = true,
        )
    }

    private fun saveCredential(
        rawSecret: String,
        save: (CharArray) -> Unit,
        onSaved: () -> Unit,
    ) {
        if (rawSecret.isBlank()) {
            postNotice(localized("凭据不能为空", "Credential must not be empty"), isError = true)
            return
        }
        viewModelScope.launch {
            runOperation {
                val chars = rawSecret.toCharArray()
                try {
                    withContext(Dispatchers.IO) { save(chars) }
                } finally {
                    chars.fill('\u0000')
                }
                onSaved()
            }
        }
    }

    private fun ensureProjectIsIdle(projectId: String): Boolean {
        if (!coordinator.hasActiveProjectRun(projectId) && !coordinator.hasQueuedProjectTasks(projectId)) return true
        postBusyNotice()
        return false
    }

    private fun ensureNoActiveRunsForPluginChange(): Boolean {
        if (
            coordinator.runs.value.values.none { !it.status.isTerminal } &&
            coordinator.pendingTasks.value.isEmpty()
        ) return true
        postNotice(
            localized(
                "请先停止所有 Agent Run，再修改全局插件状态",
                "Stop all Agent runs before changing global plugin state",
            ),
            isError = true,
        )
        return false
    }

    private fun canChangeContext(): Boolean {
        if (!mutableState.value.editorDirty) return true
        postNotice(
            localized(
                "请先保存或放弃编辑器中的修改，再切换项目或会话",
                "Save or discard editor changes before switching projects or chats",
            ),
            isError = true,
        )
        return false
    }

    private fun postNotice(message: String, isError: Boolean = false) {
        noticeId += 1
        mutableState.update { it.copy(notice = UiNotice(noticeId, message, isError)) }
    }

    private fun discardDraftAttachments(attachments: List<ChatImageAttachment>) {
        if (attachments.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            attachments.forEach { attachment ->
                runCatching { attachmentImporter.delete(attachment.projectId, attachment.id) }
            }
        }
    }

    private fun localized(chinese: String, english: String): String =
        if (mutableState.value.settings.language == AppLanguage.ENGLISH) english else chinese

    class Factory(
        private val service: PocketPilotService,
        private val coordinator: AgentRunCoordinator,
        private val attachmentImporter: ChatImageAttachmentImporter,
        private val updateManager: GitHubReleaseUpdateManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PocketPilotViewModel::class.java))
            return PocketPilotViewModel(service, coordinator, attachmentImporter, updateManager) as T
        }
    }
}

enum class PocketPilotNavigationEvent {
    OPEN_CHAT,
}

private data class InitialSnapshot(
    val projects: List<Project>,
    val conversations: List<Conversation>,
    val selected: Conversation,
    val settings: PocketPilotSettings,
    val onboardingCompleted: Boolean,
    val onboardingProjectSetupSucceeded: Boolean,
    val files: List<WorkspaceEntry>,
    val checkpoints: List<Checkpoint>,
    val timeline: List<TimelineItem>,
    val llmCredentialConfigured: Boolean,
    val gitCredentialConfigured: Boolean,
    val projectGitBinding: ProjectGitBinding?,
    val remoteServers: List<RemoteServerProfile>,
    val plugins: List<InstalledPlugin>,
)

private data class ProjectSelection(
    val projects: List<Project>,
    val conversations: List<Conversation>,
    val project: Project,
    val conversation: Conversation,
)

private data class SelectionContent(
    val files: List<WorkspaceEntry>,
    val checkpoints: List<Checkpoint>,
    val timeline: List<TimelineItem>,
    val gitBinding: ProjectGitBinding?,
)

private data class ConversationTarget(
    val projectId: String,
    val conversationId: String,
)

private fun PocketPilotSettings.copyLlmConnectionFrom(
    source: PocketPilotSettings,
): PocketPilotSettings = copy(
    modelName = source.modelName,
    llmProvider = source.llmProvider,
    llmProtocol = source.llmProtocol,
    llmBaseUrl = source.llmBaseUrl,
    openAiModelName = source.openAiModelName,
    openAiBaseUrl = source.openAiBaseUrl,
    imageModelName = source.imageModelName,
    openAiImageModelName = source.openAiImageModelName,
)

private fun defaultConversationTitle(language: AppLanguage): String =
    if (language == AppLanguage.ENGLISH) "New chat" else "新对话"

private fun conversationTitleFrom(task: String): String =
    task.lineSequence().firstOrNull().orEmpty().trim().take(48).ifBlank { "New chat" }

private fun ConversationMessage.toTimelineItem(): TimelineItem = TimelineItem(
    id = id,
    kind = when (role) {
        ConversationMessageRole.USER -> TimelineItemKind.USER
        ConversationMessageRole.ASSISTANT -> TimelineItemKind.ASSISTANT
        ConversationMessageRole.TOOL -> TimelineItemKind.TOOL
        ConversationMessageRole.STATUS -> TimelineItemKind.STATUS
        ConversationMessageRole.ERROR -> TimelineItemKind.ERROR
    },
    title = title,
    body = content,
    createdAt = createdAt,
    isError = isError,
    status = status,
    tokenUsage = tokenUsage,
    attachments = attachments,
)

private fun coordinatorTimeline(
    conversationId: String,
    runs: Collection<CoordinatedAgentRun>,
): List<TimelineItem> = runs
    .filter { it.conversationId == conversationId }
    .sortedBy { it.startedAt }
    .flatMap { it.timeline }

private fun mergeTimeline(
    history: List<TimelineItem>,
    coordinated: List<TimelineItem>,
): List<TimelineItem> {
    val itemsById = LinkedHashMap<String, TimelineItem>(history.size + coordinated.size)
    history.forEach { itemsById.putIfAbsent(it.id, it) }
    coordinated.forEach { itemsById[it.id] = it }
    return itemsById.values.sortedBy { it.createdAt }
}

private val AgentRunStatus.isTerminal: Boolean
    get() = this == AgentRunStatus.COMPLETED ||
        this == AgentRunStatus.FAILED ||
        this == AgentRunStatus.CANCELLED

private fun Throwable.displayMessage(): String = message?.takeIf { it.isNotBlank() } ?: "发生未知错误"

private const val MAX_CHAT_ATTACHMENTS = 6

internal fun shouldNotifyAvailableUpdate(
    previousPhase: UpdatePhase,
    currentState: UpdateState,
    notifiedVersion: String?,
): Boolean {
    val version = currentState.release?.versionName
    return currentState.phase == UpdatePhase.AVAILABLE &&
        (previousPhase == UpdatePhase.IDLE || previousPhase == UpdatePhase.CHECKING) &&
        !version.isNullOrBlank() &&
        notifiedVersion != version
}
