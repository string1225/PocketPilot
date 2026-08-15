package com.string1225.agentdock.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.string1225.agentdock.model.AgentRunStatus
import com.string1225.agentdock.model.Checkpoint
import com.string1225.agentdock.model.Project
import com.string1225.agentdock.model.TimelineItem
import com.string1225.agentdock.model.TimelineItemKind
import com.string1225.agentdock.model.ToolApprovalRequest
import com.string1225.agentdock.model.WorkspaceEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class ProjectSection {
    FILES,
    AGENT,
    CHECKPOINTS,
}

data class UiNotice(
    val id: Long,
    val message: String,
    val isError: Boolean,
)

data class AgentDockUiState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val selectedProjectId: String? = null,
    val section: ProjectSection = ProjectSection.FILES,
    val files: List<WorkspaceEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val editorText: String = "",
    val savedEditorText: String = "",
    val checkpoints: List<Checkpoint> = emptyList(),
    val timeline: List<TimelineItem> = emptyList(),
    val agentInput: String = "",
    val agentStatus: AgentRunStatus = AgentRunStatus.IDLE,
    val pendingApproval: ToolApprovalRequest? = null,
    val notice: UiNotice? = null,
    val offlineDemo: Boolean = true,
    val runtimeAvailable: Boolean = true,
) {
    val selectedProject: Project?
        get() = projects.firstOrNull { it.id == selectedProjectId }

    val editorDirty: Boolean
        get() = selectedFilePath != null && editorText != savedEditorText
}

class AgentDockViewModel(
    private val service: AgentDockService,
) : ViewModel() {
    private val mutableState = MutableStateFlow(
        AgentDockUiState(
            offlineDemo = service.isOfflineDemo,
            runtimeAvailable = service.runtimeAvailable,
        ),
    )
    val state: StateFlow<AgentDockUiState> = mutableState.asStateFlow()

    private var noticeId = 0L
    private var agentJob: Job? = null
    private var projectLoadJob: Job? = null
    private var fileLoadJob: Job? = null
    private var requestedFile: Pair<String, String>? = null
    private var activeRunId: String? = null

    init {
        viewModelScope.launch {
            service.pendingApprovals.collect { requests ->
                val request = requests.firstOrNull { it.runId == activeRunId }
                mutableState.update { current ->
                    val status = when {
                        request != null -> AgentRunStatus.WAITING_FOR_APPROVAL
                        current.agentStatus == AgentRunStatus.WAITING_FOR_APPROVAL && agentJob?.isActive == true ->
                            AgentRunStatus.RUNNING
                        else -> current.agentStatus
                    }
                    current.copy(pendingApproval = request, agentStatus = status)
                }
            }
        }
        viewModelScope.launch {
            runOperation {
                val projects = withContext(Dispatchers.IO) { service.initialize() }
                mutableState.update { current ->
                    current.copy(
                        loading = false,
                        projects = projects,
                        offlineDemo = service.isOfflineDemo,
                        runtimeAvailable = service.runtimeAvailable,
                    )
                }
            }
        }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            runOperation {
                val projects = withContext(Dispatchers.IO) { service.listProjects() }
                mutableState.update { it.copy(projects = projects) }
            }
        }
    }

    fun createProject(name: String) {
        viewModelScope.launch {
            runOperation {
                val project = withContext(Dispatchers.IO) { service.createProject(name) }
                val projects = withContext(Dispatchers.IO) { service.listProjects() }
                mutableState.update { it.copy(projects = projects) }
                openProject(project.id)
                postNotice("已创建 ${project.name}")
            }
        }
    }

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.deleteProject(projectId) }
                val projects = withContext(Dispatchers.IO) { service.listProjects() }
                mutableState.update { current ->
                    current.copy(
                        projects = projects,
                        selectedProjectId = if (current.selectedProjectId == projectId) null else current.selectedProjectId,
                    )
                }
                postNotice("项目已删除")
            }
        }
    }

    fun openProject(projectId: String) {
        if (agentJob?.isActive == true) cancelAgent()
        projectLoadJob?.cancel()
        fileLoadJob?.cancel()
        requestedFile = null
        mutableState.update {
            it.copy(
                selectedProjectId = projectId,
                section = ProjectSection.FILES,
                selectedFilePath = null,
                editorText = "",
                savedEditorText = "",
                timeline = emptyList(),
                agentStatus = AgentRunStatus.IDLE,
                loading = true,
            )
        }
        projectLoadJob = viewModelScope.launch { loadProjectContent(projectId) }
    }

    fun closeProject() {
        if (agentJob?.isActive == true) cancelAgent()
        projectLoadJob?.cancel()
        fileLoadJob?.cancel()
        requestedFile = null
        mutableState.update {
            it.copy(
                selectedProjectId = null,
                selectedFilePath = null,
                editorText = "",
                savedEditorText = "",
                files = emptyList(),
                checkpoints = emptyList(),
                timeline = emptyList(),
                agentStatus = AgentRunStatus.IDLE,
                pendingApproval = null,
                loading = false,
            )
        }
        refreshProjects()
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
        mutableState.update {
            it.copy(selectedFilePath = null, editorText = "", savedEditorText = "")
        }
    }

    fun updateEditor(text: String) {
        mutableState.update { it.copy(editorText = text) }
    }

    fun createFile(path: String) {
        if (agentJob?.isActive == true) {
            postNotice("Agent 运行期间不能手动修改 Workspace", isError = true)
            return
        }
        val projectId = mutableState.value.selectedProjectId ?: return
        viewModelScope.launch {
            runOperation {
                withContext(Dispatchers.IO) { service.createFile(projectId, path) }
                refreshFilesAndCheckpoints(projectId)
                if (mutableState.value.selectedProjectId == projectId) {
                    openFile(path.trim().replace('\\', '/'))
                }
                postNotice("文件已创建")
            }
        }
    }

    fun saveFile() {
        if (agentJob?.isActive == true) {
            postNotice("Agent 运行期间不能手动修改 Workspace", isError = true)
            return
        }
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
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
                postNotice("已保存 $path")
            }
        }
    }

    fun deleteFile(path: String) {
        if (agentJob?.isActive == true) {
            postNotice("Agent 运行期间不能手动修改 Workspace", isError = true)
            return
        }
        val projectId = mutableState.value.selectedProjectId ?: return
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
                postNotice("已删除 $path")
            }
        }
    }

    fun restoreCheckpoint(checkpointId: String) {
        if (agentJob?.isActive == true) {
            postNotice("请先停止 Agent Run，再恢复 Checkpoint", isError = true)
            return
        }
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        if (snapshot.editorDirty) {
            postNotice("请先保存或放弃编辑器中的修改，再恢复 Checkpoint", isError = true)
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
                postNotice("工作区已恢复，并已记录新的 Checkpoint")
            }
        }
    }

    fun updateAgentInput(value: String) {
        mutableState.update { it.copy(agentInput = value) }
    }

    fun sendAgentTask() {
        val snapshot = mutableState.value
        val projectId = snapshot.selectedProjectId ?: return
        val task = snapshot.agentInput.trim()
        if (task.isEmpty() || agentJob?.isActive == true) return
        if (snapshot.editorDirty) {
            postNotice("请先保存或放弃编辑器中的修改，再启动 Agent", isError = true)
            return
        }

        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        val userItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            kind = TimelineItemKind.USER,
            title = "你",
            body = task,
            createdAt = System.currentTimeMillis(),
        )
        mutableState.update {
            it.copy(
                agentInput = "",
                agentStatus = AgentRunStatus.RUNNING,
                timeline = it.timeline + userItem,
                section = ProjectSection.AGENT,
            )
        }

        agentJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    service.runAgent(projectId, runId, task) { appendTimeline(it) }
                }
                mutableState.update { it.copy(agentStatus = result) }
                try {
                    refreshFilesAndCheckpoints(projectId)
                    refreshOpenEditorAfterAgent(projectId)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refreshError: Throwable) {
                    postNotice("Run 已结束，但刷新 Workspace 失败：${refreshError.displayMessage()}", isError = true)
                }
            } catch (_: CancellationException) {
                appendTimeline(
                    TimelineItem(
                        id = UUID.randomUUID().toString(),
                        kind = TimelineItemKind.STATUS,
                        title = "任务已取消",
                        body = "Agent Run 已由用户停止。",
                        createdAt = System.currentTimeMillis(),
                    ),
                )
                mutableState.update { it.copy(agentStatus = AgentRunStatus.CANCELLED) }
            } catch (error: Throwable) {
                appendTimeline(
                    TimelineItem(
                        id = UUID.randomUUID().toString(),
                        kind = TimelineItemKind.ERROR,
                        title = "运行失败",
                        body = error.displayMessage(),
                        createdAt = System.currentTimeMillis(),
                        isError = true,
                    ),
                )
                mutableState.update { it.copy(agentStatus = AgentRunStatus.FAILED) }
            } finally {
                activeRunId = null
            }
        }
    }

    fun resolveApproval(approved: Boolean) {
        val request = mutableState.value.pendingApproval ?: return
        if (!service.resolveApproval(request.id, approved)) {
            postNotice("该审批请求已经失效", isError = true)
        }
    }

    fun cancelAgent() {
        val runId = activeRunId ?: return
        val runningJob = agentJob
        viewModelScope.launch(Dispatchers.IO) {
            service.cancelAgent(runId)
            if (runningJob?.isActive != true) {
                mutableState.update { it.copy(agentStatus = AgentRunStatus.CANCELLED) }
                activeRunId = null
            }
        }
        runningJob?.cancel()
    }

    fun consumeNotice(id: Long) {
        mutableState.update { current ->
            if (current.notice?.id == id) current.copy(notice = null) else current
        }
    }

    private suspend fun loadProjectContent(projectId: String) {
        runOperation {
            val files = withContext(Dispatchers.IO) { service.listFiles(projectId) }
            val checkpoints = withContext(Dispatchers.IO) { service.listCheckpoints(projectId) }
            mutableState.update { current ->
                if (current.selectedProjectId == projectId) {
                    current.copy(loading = false, files = files, checkpoints = checkpoints)
                } else {
                    current
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

    private fun appendTimeline(item: TimelineItem) {
        mutableState.update { it.copy(timeline = it.timeline + item) }
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

    private fun postNotice(message: String, isError: Boolean = false) {
        noticeId += 1
        mutableState.update { it.copy(notice = UiNotice(noticeId, message, isError)) }
    }

    class Factory(
        private val service: AgentDockService,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AgentDockViewModel::class.java))
            return AgentDockViewModel(service) as T
        }
    }
}

private fun Throwable.displayMessage(): String = message?.takeIf { it.isNotBlank() } ?: "发生未知错误"
