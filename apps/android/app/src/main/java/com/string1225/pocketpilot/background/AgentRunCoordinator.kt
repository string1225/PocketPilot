package com.string1225.pocketpilot.background

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.ui.PocketPilotService
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

data class CoordinatedAgentRun(
    val runId: String,
    val projectId: String,
    val conversationId: String,
    val task: String,
    val status: AgentRunStatus,
    val timeline: List<TimelineItem>,
    val startedAt: Long,
    val completedAt: Long? = null,
)

/**
 * Persistence seam for conversation transcripts. The coordinator owns a Run
 * independently from any Activity or ViewModel, so transcript writes must live
 * at application scope as well.
 */
interface AgentRunTranscriptStore {
    suspend fun runStarted(run: CoordinatedAgentRun)
    suspend fun timelineAppended(run: CoordinatedAgentRun, item: TimelineItem)
    suspend fun runFinished(run: CoordinatedAgentRun)
}

object NoOpAgentRunTranscriptStore : AgentRunTranscriptStore {
    override suspend fun runStarted(run: CoordinatedAgentRun) = Unit
    override suspend fun timelineAppended(run: CoordinatedAgentRun, item: TimelineItem) = Unit
    override suspend fun runFinished(run: CoordinatedAgentRun) = Unit
}

/**
 * Application-scoped owner for Agent runs.
 *
 * A ViewModel may disappear while the user switches apps. This coordinator and
 * the foreground service remain alive, keep the WebView/LLM task running, and
 * publish the same StateFlow when the UI is recreated.
 */
class AgentRunCoordinator(
    context: Context,
    private val service: PocketPilotService,
    private val transcriptStore: AgentRunTranscriptStore = NoOpAgentRunTranscriptStore,
) : Closeable {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val transcriptChannels = ConcurrentHashMap<String, Channel<TranscriptCommand>>()
    private val transcriptJobs = ConcurrentHashMap<String, Job>()
    private val runLock = Any()
    private val mutableRuns = MutableStateFlow<Map<String, CoordinatedAgentRun>>(emptyMap())

    val runs: StateFlow<Map<String, CoordinatedAgentRun>> = mutableRuns.asStateFlow()
    val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = service.pendingApprovals

    init {
        scope.launch {
            service.pendingApprovals.collectLatest { approvals ->
                val waitingRunIds = approvals.mapTo(mutableSetOf()) { it.runId }
                mutableRuns.update { current ->
                    current.mapValues { (runId, run) ->
                        when {
                            runId in waitingRunIds && run.status == AgentRunStatus.RUNNING ->
                                run.copy(status = AgentRunStatus.WAITING_FOR_APPROVAL)

                            runId !in waitingRunIds && run.status == AgentRunStatus.WAITING_FOR_APPROVAL ->
                                run.copy(status = AgentRunStatus.RUNNING)

                            else -> run
                        }
                    }
                }
                refreshRunningNotification()
            }
        }
    }

    fun start(projectId: String, conversationId: String, task: String): String {
        require(projectId.isNotBlank()) { "Project id must not be blank" }
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty()) { "Task must not be empty" }
        val runId = UUID.randomUUID().toString()
        val userItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            kind = TimelineItemKind.USER,
            title = "You",
            body = normalizedTask,
            createdAt = System.currentTimeMillis(),
        )
        val run = CoordinatedAgentRun(
            runId = runId,
            projectId = projectId,
            conversationId = conversationId,
            task = normalizedTask,
            status = AgentRunStatus.RUNNING,
            timeline = listOf(userItem),
            startedAt = userItem.createdAt,
        )
        synchronized(runLock) {
            check(
                mutableRuns.value.values.none {
                    it.projectId == projectId && !it.status.isTerminal
                },
            ) { "This project already has an active run" }
            mutableRuns.value = mutableRuns.value + (runId to run)
        }
        val foregroundIntent = AgentRunForegroundService.startIntent(
            context = appContext,
            runId = runId,
            projectId = projectId,
            conversationId = conversationId,
            task = normalizedTask,
        )
        try {
            ContextCompat.startForegroundService(appContext, foregroundIntent)
        } catch (error: Throwable) {
            synchronized(runLock) { mutableRuns.value = mutableRuns.value - runId }
            throw error
        }

        startTranscriptWriter(run)
        transcriptChannels[runId]?.trySend(TranscriptCommand.Append(run, userItem))
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val terminalStatus = try {
                service.runAgent(projectId, conversationId, runId, normalizedTask) { item ->
                    appendTimeline(runId, item)
                }
            } catch (cancelled: CancellationException) {
                AgentRunStatus.CANCELLED
            } catch (error: Throwable) {
                appendTimeline(
                    runId,
                    TimelineItem(
                        id = UUID.randomUUID().toString(),
                        kind = TimelineItemKind.ERROR,
                        title = "Run failed",
                        body = error.message?.takeIf { it.isNotBlank() } ?: "Unknown Agent error",
                        createdAt = System.currentTimeMillis(),
                        isError = true,
                    ),
                )
                AgentRunStatus.FAILED
            }
            withContext(NonCancellable) { finish(runId, terminalStatus) }
        }
        jobs[runId] = job
        job.invokeOnCompletion { jobs.remove(runId, job) }
        job.start()
        refreshRunningNotification()
        return runId
    }

    fun cancel(runId: String) {
        val run = mutableRuns.value[runId] ?: return
        if (run.status.isTerminal) return
        jobs[runId]?.cancel(CancellationException("Cancelled by user"))
        scope.launch(NonCancellable) {
            runCatching { service.cancelAgent(runId) }
        }
    }

    fun cancelAll() {
        mutableRuns.value.values
            .filterNot { it.status.isTerminal }
            .forEach { cancel(it.runId) }
    }

    fun hasActiveProjectRun(projectId: String): Boolean =
        mutableRuns.value.values.any { it.projectId == projectId && !it.status.isTerminal }

    fun hasActiveConversationRun(conversationId: String): Boolean =
        mutableRuns.value.values.any { it.conversationId == conversationId && !it.status.isTerminal }

    fun hasActiveRuns(): Boolean = mutableRuns.value.values.any { !it.status.isTerminal }

    fun resolveApproval(requestId: String, approved: Boolean): Boolean =
        service.resolveApproval(requestId, approved)

    fun removeTerminalRun(runId: String) {
        mutableRuns.update { current ->
            val run = current[runId]
            if (run == null || !run.status.isTerminal) current else current - runId
        }
    }

    override fun close() {
        jobs.values.forEach { it.cancel(CancellationException("Application is closing")) }
        jobs.clear()
        transcriptChannels.values.forEach { it.close() }
        transcriptChannels.clear()
        scope.cancel()
    }

    private fun appendTimeline(runId: String, item: TimelineItem) {
        var updated: CoordinatedAgentRun? = null
        mutableRuns.update { current ->
            val run = current[runId] ?: return@update current
            run.copy(timeline = run.timeline + item).also { next ->
                updated = next
            }.let { current + (runId to it) }
        }
        updated?.let { run -> transcriptChannels[runId]?.trySend(TranscriptCommand.Append(run, item)) }
    }

    private suspend fun finish(runId: String, requestedStatus: AgentRunStatus) {
        val terminalStatus = if (requestedStatus.isTerminal) requestedStatus else AgentRunStatus.FAILED
        var finished: CoordinatedAgentRun? = null
        mutableRuns.update { current ->
            val run = current[runId] ?: return@update current
            run.copy(
                status = terminalStatus,
                completedAt = System.currentTimeMillis(),
            ).also { next ->
                finished = next
            }.let { current + (runId to it) }
        }
        val run = finished ?: return
        val channel = transcriptChannels.remove(runId)
        channel?.trySend(TranscriptCommand.Finish(run))
        channel?.close()
        transcriptJobs.remove(runId)?.join()
        AgentNotifications.showCompletion(appContext, run)
        if (mutableRuns.value.values.none { !it.status.isTerminal }) {
            // Let the Service stop itself with its startId. A direct
            // stopService() can race a newly registered Run and destroy the
            // new foreground-service generation.
            appContext.startService(AgentRunForegroundService.stopIfIdleIntent(appContext))
        } else {
            refreshRunningNotification()
        }
    }

    private fun refreshRunningNotification() {
        val active = mutableRuns.value.values.filterNot { it.status.isTerminal }
        if (active.isNotEmpty()) AgentNotifications.showRunning(appContext, active)
    }

    private fun startTranscriptWriter(run: CoordinatedAgentRun) {
        val channel = Channel<TranscriptCommand>(Channel.UNLIMITED)
        check(transcriptChannels.putIfAbsent(run.runId, channel) == null)
        val writer = scope.launch {
            try {
                transcriptStore.runStarted(run)
                for (command in channel) {
                    when (command) {
                        is TranscriptCommand.Append ->
                            transcriptStore.timelineAppended(command.run, command.item)
                        is TranscriptCommand.Finish -> transcriptStore.runFinished(command.run)
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                Log.e(TAG, "Unable to persist Agent transcript", error)
            }
        }
        transcriptJobs[run.runId] = writer
    }

    private sealed interface TranscriptCommand {
        data class Append(val run: CoordinatedAgentRun, val item: TimelineItem) : TranscriptCommand
        data class Finish(val run: CoordinatedAgentRun) : TranscriptCommand
    }

    private companion object {
        const val TAG = "AgentRunCoordinator"
    }
}

private val AgentRunStatus.isTerminal: Boolean
    get() = this == AgentRunStatus.COMPLETED ||
        this == AgentRunStatus.FAILED ||
        this == AgentRunStatus.CANCELLED
