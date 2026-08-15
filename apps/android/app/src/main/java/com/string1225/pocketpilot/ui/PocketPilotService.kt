package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.data.AgentRunRepository
import com.string1225.pocketpilot.data.CheckpointRepository
import com.string1225.pocketpilot.data.ProjectRepository
import com.string1225.pocketpilot.data.WorkspaceRepository
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.Project
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.model.WorkspaceEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-facing application boundary. A runtime-backed implementation can replace
 * the offline implementation without changing the Compose screens.
 */
interface PocketPilotService {
    val isOfflineDemo: Boolean
    val runtimeAvailable: Boolean
    val pendingApprovals: StateFlow<List<ToolApprovalRequest>>

    suspend fun initialize(): List<Project>
    suspend fun listProjects(): List<Project>
    suspend fun createProject(name: String): Project
    suspend fun deleteProject(projectId: String)

    suspend fun listFiles(projectId: String): List<WorkspaceEntry>
    suspend fun readFile(projectId: String, path: String): String
    suspend fun createFile(projectId: String, path: String)
    suspend fun saveFile(projectId: String, path: String, content: String)
    suspend fun deleteFile(projectId: String, path: String)

    suspend fun listCheckpoints(projectId: String): List<Checkpoint>
    suspend fun restoreCheckpoint(projectId: String, checkpointId: String)

    suspend fun runAgent(
        projectId: String,
        runId: String,
        task: String,
        emit: (TimelineItem) -> Unit,
    ): AgentRunStatus

    suspend fun cancelAgent(runId: String)
    fun resolveApproval(requestId: String, approved: Boolean): Boolean
}

/**
 * Repository-backed MVP service. Workspace and checkpoint operations are real;
 * only the model/runtime response is a clearly labelled offline demonstration.
 */
class OfflinePocketPilotService(
    private val projects: ProjectRepository,
    private val workspace: WorkspaceRepository,
    private val checkpoints: CheckpointRepository,
    private val agentRuns: AgentRunRepository,
) : PocketPilotService {
    override val isOfflineDemo: Boolean = true
    override val runtimeAvailable: Boolean = false
    private val mutableApprovals = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    override val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = mutableApprovals.asStateFlow()

    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    override suspend fun initialize(): List<Project> {
        agentRuns.markInterruptedRuns()
        val existing = projects.list()
        if (existing.isNotEmpty()) return existing
        projects.create("个人项目")
        return projects.list()
    }

    override suspend fun listProjects(): List<Project> = projects.list()

    override suspend fun createProject(name: String): Project = projects.create(name)

    override suspend fun deleteProject(projectId: String) {
        projects.delete(projectId)
        if (projects.list().isEmpty()) projects.create("个人项目")
    }

    override suspend fun listFiles(projectId: String): List<WorkspaceEntry> = workspace.list(projectId)

    override suspend fun readFile(projectId: String, path: String): String = workspace.read(projectId, path)

    override suspend fun createFile(projectId: String, path: String) {
        workspace.create(projectId, path)
    }

    override suspend fun saveFile(projectId: String, path: String, content: String) {
        workspace.write(projectId, path, content)
    }

    override suspend fun deleteFile(projectId: String, path: String) {
        workspace.delete(projectId, path)
    }

    override suspend fun listCheckpoints(projectId: String): List<Checkpoint> = checkpoints.list(projectId)

    override suspend fun restoreCheckpoint(projectId: String, checkpointId: String) {
        checkpoints.restore(projectId, checkpointId)
    }

    override suspend fun runAgent(
        projectId: String,
        runId: String,
        task: String,
        emit: (TimelineItem) -> Unit,
    ): AgentRunStatus {
        activeRuns += runId
        return try {
            agentRuns.create(projectId, task, requestedId = runId)
            agentRuns.appendEvent(runId, "user.message", task)
            emit(
                timeline(
                    TimelineItemKind.STATUS,
                    "离线演示已启动",
                    "当前未连接 LLM，下面展示 Agent 与工具执行的交互流程。",
                ),
            )
            delay(450)

            val files = workspace.list(projectId)
            val toolBody = if (files.isEmpty()) {
                "workspace.list → 当前工作区为空"
            } else {
                "workspace.list → 找到 ${files.size} 个文件\n${files.take(5).joinToString("\n") { it.path }}"
            }
            agentRuns.appendEvent(runId, "tool.result", toolBody)
            emit(timeline(TimelineItemKind.TOOL, "Workspace Tool", toolBody))
            delay(650)

            val response = buildString {
                append("这是离线演示结果，任务没有发送给模型，也没有由 Agent 修改文件。")
                if (files.isEmpty()) {
                    append(" 你可以先在 Files 页面新建文件。")
                } else {
                    append(" 已安全读取文件列表；接入模型后会在同一时间线显示真实 tool call。")
                }
            }
            agentRuns.appendEvent(runId, "assistant.message", response)
            emit(timeline(TimelineItemKind.ASSISTANT, "Agent", response))
            agentRuns.finish(runId, AgentRunStatus.COMPLETED)
            AgentRunStatus.COMPLETED
        } catch (cancelled: CancellationException) {
            runCatching {
                agentRuns.appendEvent(runId, "run.cancelled", "Cancelled by user")
                agentRuns.finish(runId, AgentRunStatus.CANCELLED)
            }
            AgentRunStatus.CANCELLED
        } catch (error: Throwable) {
            runCatching { agentRuns.finish(runId, AgentRunStatus.FAILED, error.message) }
            emit(
                timeline(
                    TimelineItemKind.ERROR,
                    "运行失败",
                    error.message?.takeIf { it.isNotBlank() } ?: "离线演示运行失败",
                ),
            )
            AgentRunStatus.FAILED
        } finally {
            activeRuns -= runId
        }
    }

    override suspend fun cancelAgent(runId: String) {
        if (runId !in activeRuns) return
        runCatching {
            agentRuns.appendEvent(runId, "run.cancel_requested", "Cancelled by user")
            agentRuns.finish(runId, AgentRunStatus.CANCELLED)
        }
    }

    override fun resolveApproval(requestId: String, approved: Boolean): Boolean = false

    private fun timeline(kind: TimelineItemKind, title: String, body: String): TimelineItem = TimelineItem(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        body = body,
        createdAt = System.currentTimeMillis(),
        isError = kind == TimelineItemKind.ERROR,
    )
}
