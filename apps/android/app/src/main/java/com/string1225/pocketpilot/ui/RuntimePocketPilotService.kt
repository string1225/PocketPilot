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
import com.string1225.pocketpilot.runtime.PocketPilotRuntimeBridge
import com.string1225.pocketpilot.runtime.AgentRuntimeEvent
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.RuntimeBridgeError
import com.string1225.pocketpilot.runtime.RuntimeEventRouter
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/**
 * Production composition for the alpha.1 vertical slice. The Provider is the
 * deterministic offline Provider bundled with the TypeScript runtime, while
 * every Agent Loop and Workspace Tool call crosses the real JS/Native bridge.
 */
class RuntimePocketPilotService(
    private val projects: ProjectRepository,
    private val workspace: WorkspaceRepository,
    private val checkpoints: CheckpointRepository,
    private val agentRuns: AgentRunRepository,
    private val runtime: PocketPilotRuntimeBridge,
    private val events: RuntimeEventRouter,
    private val activeRuns: ActiveRunRegistry,
    private val approvals: ToolApprovalCoordinator,
) : PocketPilotService {
    override val isOfflineDemo: Boolean = true
    override val runtimeAvailable: Boolean = true
    override val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = approvals.requests

    override suspend fun initialize(): List<Project> {
        agentRuns.markInterruptedRuns()
        projects.ensureDefaultProject()
        return projects.list()
    }

    override suspend fun listProjects(): List<Project> = projects.list()

    override suspend fun createProject(name: String): Project = projects.create(name)

    override suspend fun deleteProject(projectId: String) {
        projects.delete(projectId)
        projects.ensureDefaultProject()
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
        val completion = CompletableDeferred<AgentRunStatus>()
        var subscription: Closeable? = null
        var runCreated = false
        var registered = false
        var runtimeStarted = false

        return try {
            agentRuns.create(projectId, task, requestedId = runId)
            runCreated = true
            agentRuns.appendEvent(runId, "user.message", JSONObject().put("content", task).toString())
            activeRuns.register(runId, projectId)
            registered = true
            subscription = events.subscribe(
                runId = runId,
                projectId = projectId,
                onEvent = { event -> handleEvent(runId, projectId, event, emit, completion) },
                onError = { error -> handleBridgeError(runId, error, emit, completion) },
            )
            events.awaitReady()
            runtime.start(
                JSONObject()
                    .put("runId", runId)
                    .put("projectId", projectId)
                    .put("task", task)
                    .put("maxSteps", 8)
                    .toString(),
            )
            runtimeStarted = true
            withTimeout(RUN_TIMEOUT_MILLIS) { completion.await() }
        } catch (timeout: TimeoutCancellationException) {
            val message = if (runtimeStarted) {
                "TypeScript Agent Run exceeded the ${RUN_TIMEOUT_MILLIS / 1_000}-second MVP limit"
            } else {
                "TypeScript Agent Runtime did not become ready in time"
            }
            runtime.cancel(runId)
            approvals.cancelRun(runId)
            if (runCreated) runCatching { agentRuns.finish(runId, AgentRunStatus.FAILED, message) }
            emit(timeline(TimelineItemKind.ERROR, "Runtime 超时", message, isError = true))
            AgentRunStatus.FAILED
        } catch (cancelled: CancellationException) {
            runtime.cancel(runId)
            approvals.cancelRun(runId)
            if (runCreated) runCatching { agentRuns.finish(runId, AgentRunStatus.CANCELLED) }
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "Agent Runtime failed"
            if (runCreated) runCatching { agentRuns.finish(runId, AgentRunStatus.FAILED, message) }
            emit(timeline(TimelineItemKind.ERROR, "Runtime 运行失败", message, isError = true))
            AgentRunStatus.FAILED
        } finally {
            subscription?.close()
            approvals.cancelRun(runId)
            if (registered) activeRuns.revoke(runId, projectId)
        }
    }

    override suspend fun cancelAgent(runId: String) {
        if (activeRuns.revoke(runId) == null) return
        approvals.cancelRun(runId)
        runtime.cancel(runId)
        runCatching {
            agentRuns.appendEvent(runId, "run.cancel_requested", "{}")
            agentRuns.finish(runId, AgentRunStatus.CANCELLED)
        }
    }

    override fun resolveApproval(requestId: String, approved: Boolean): Boolean =
        approvals.resolve(requestId, approved)

    private fun handleEvent(
        runId: String,
        projectId: String,
        event: AgentRuntimeEvent,
        emit: (TimelineItem) -> Unit,
        completion: CompletableDeferred<AgentRunStatus>,
    ) {
        if (event.runId != runId || event.projectId != projectId) return
        agentRuns.appendEvent(runId, event.type, event.payloadJson)
        event.toTimelineItem()?.let(emit)

        val terminal = when (event.type) {
            "run.completed" -> AgentRunStatus.COMPLETED
            // Native approvals suspend the Tool RPC and are resumed in-place.
            // A runtime-level waiting event has no resumable protocol in v1,
            // so fail closed instead of leaving a dead Run behind.
            "run.waiting_for_approval" -> AgentRunStatus.FAILED
            "run.failed" -> AgentRunStatus.FAILED
            "run.cancelled" -> AgentRunStatus.CANCELLED
            else -> null
        } ?: return

        val error = if (terminal == AgentRunStatus.FAILED) {
            runCatching {
                JSONObject(event.payloadJson).optJSONObject("error")?.optString("message")
            }.getOrNull()
        } else {
            null
        }
        agentRuns.finish(runId, terminal, error)
        completion.complete(terminal)
    }

    private fun handleBridgeError(
        runId: String,
        error: RuntimeBridgeError,
        emit: (TimelineItem) -> Unit,
        completion: CompletableDeferred<AgentRunStatus>,
    ) {
        if (completion.isCompleted) return
        val message = "${error.code}: ${error.message}"
        runCatching {
            agentRuns.appendEvent(runId, "runtime.bridge_error", JSONObject().put("message", message).toString())
            agentRuns.finish(runId, AgentRunStatus.FAILED, message)
        }
        emit(timeline(TimelineItemKind.ERROR, "Runtime Bridge 错误", message, isError = true))
        completion.complete(AgentRunStatus.FAILED)
    }

    private fun AgentRuntimeEvent.toTimelineItem(): TimelineItem? {
        val payload = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
        return when (type) {
            "run.started" -> timeline(
                TimelineItemKind.STATUS,
                "Agent Run 已启动",
                payload.optString("task"),
            )

            "assistant.message" -> timeline(
                TimelineItemKind.ASSISTANT,
                "Agent",
                payload.optString("content"),
            )

            "tool.started" -> {
                val call = payload.optJSONObject("call") ?: JSONObject()
                timeline(
                    TimelineItemKind.TOOL,
                    "${call.optString("name", "Tool")} · 执行中",
                    call.opt("arguments")?.let(::prettyJson) ?: "{}",
                )
            }

            "tool.finished" -> {
                val call = payload.optJSONObject("call") ?: JSONObject()
                val result = payload.optJSONObject("result") ?: JSONObject()
                val success = result.optBoolean("success", false)
                timeline(
                    TimelineItemKind.TOOL,
                    "${call.optString("name", "Tool")} · ${if (success) "完成" else "失败"}",
                    prettyJson(result),
                    isError = !success,
                )
            }

            "run.waiting_for_approval" -> timeline(
                TimelineItemKind.STATUS,
                "等待用户确认",
                payload.optString("reason", "该工具调用需要确认"),
            )

            "run.completed" -> timeline(
                TimelineItemKind.STATUS,
                "任务完成",
                "Agent Run 已完成，共 ${payload.optInt("steps")} step。",
            )

            "run.failed" -> {
                val error = payload.optJSONObject("error")
                timeline(
                    TimelineItemKind.ERROR,
                    "任务失败",
                    error?.let(::prettyJson) ?: "Agent Run failed",
                    isError = true,
                )
            }

            "run.cancelled" -> timeline(
                TimelineItemKind.STATUS,
                "任务已取消",
                "Agent Run 已停止。",
            )

            else -> null
        }
    }

    private fun prettyJson(value: Any): String = when (value) {
        is JSONObject -> value.toString(2)
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    private fun timeline(
        kind: TimelineItemKind,
        title: String,
        body: String,
        isError: Boolean = false,
    ): TimelineItem = TimelineItem(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        body = body,
        createdAt = System.currentTimeMillis(),
        isError = isError,
    )

    companion object {
        private const val RUN_TIMEOUT_MILLIS = 120_000L
    }
}
