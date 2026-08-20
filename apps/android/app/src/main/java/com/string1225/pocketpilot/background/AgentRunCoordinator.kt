package com.string1225.pocketpilot.background

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AgentRunResume
import com.string1225.pocketpilot.model.ChatImageAttachment
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable

data class CoordinatedAgentRun(
    val runId: String,
    val projectId: String,
    val conversationId: String,
    val task: String,
    val attachments: List<ChatImageAttachment> = emptyList(),
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
    private val finishingRunIds = mutableSetOf<String>()
    private val mutableRuns = MutableStateFlow<Map<String, CoordinatedAgentRun>>(emptyMap())
    private val taskQueue = PendingAgentTaskQueue()

    val runs: StateFlow<Map<String, CoordinatedAgentRun>> = mutableRuns.asStateFlow()
    val pendingTasks: StateFlow<List<PendingAgentTask>> = taskQueue.tasks
    val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = service.pendingApprovals

    init {
        scope.launch {
            service.pendingApprovals.collectLatest { approvals ->
                val waitingRunIds = approvals.mapTo(mutableSetOf()) { it.runId }
                synchronized(runLock) {
                    mutableRuns.value = mutableRuns.value.mapValues { (runId, run) ->
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

    fun start(
        projectId: String,
        conversationId: String,
        task: String,
        attachments: List<ChatImageAttachment> = emptyList(),
    ): String = startPending(createPendingTask(projectId, conversationId, task, attachments))

    /** Resumes only provider-ready boundaries recovered by [AgentRunRepository]. */
    fun resumeRecoverableRuns(): Int {
        var resumed = 0
        service.listRecoverableAgentRuns().forEach { recovery ->
            synchronized(runLock) {
                if (mutableRuns.value.values.any {
                        it.projectId == recovery.projectId && !it.status.isTerminal
                    }
                ) return@forEach
            }
            runCatching { startRecovered(recovery) }
                .onSuccess { resumed += 1 }
                .onFailure { Log.e(TAG, "Unable to resume safe Agent boundary", it) }
        }
        return resumed
    }

    fun enqueueOrStart(
        projectId: String,
        conversationId: String,
        task: String,
        attachments: List<ChatImageAttachment> = emptyList(),
    ): AgentTaskSubmission {
        val submitted = createPendingTask(projectId, conversationId, task, attachments)
        synchronized(runLock) {
            val activeConversationRun = mutableRuns.value.values.firstOrNull {
                it.projectId == projectId &&
                    it.conversationId == conversationId &&
                    !it.status.isTerminal &&
                    it.runId !in finishingRunIds
            }
            if (
                activeConversationRun != null &&
                service.followUpAgent(
                    activeConversationRun.runId,
                    submitted.task,
                    submitted.attachments,
                )
            ) {
                val queuedItem = submitted.userItem.copy(status = "queued")
                val updated = activeConversationRun.copy(
                    timeline = upsertTimelineItem(activeConversationRun.timeline, queuedItem),
                )
                mutableRuns.value = mutableRuns.value + (updated.runId to updated)
                transcriptChannels[updated.runId]?.trySend(
                    TranscriptCommand.Append(updated, queuedItem),
                )
                return AgentTaskSubmission(submitted.id, queued = true)
            }
            taskQueue.enqueue(submitted)
            if (mutableRuns.value.values.any { it.projectId == projectId && !it.status.isTerminal }) {
                return AgentTaskSubmission(submitted.id, queued = true)
            }
            val next = checkNotNull(taskQueue.dequeue(projectId))
            return try {
                startPending(next)
                AgentTaskSubmission(submitted.id, queued = next.id != submitted.id).also {
                    if (next.id != submitted.id) {
                        // The older task was retried first; the new submission stays queued.
                        check(taskQueue.hasProject(projectId))
                    }
                }
            } catch (error: Throwable) {
                if (next.id != submitted.id) {
                    // The caller receives an error and keeps its draft. Remove
                    // this submission as well, otherwise a retry would execute
                    // the same user request twice after the older task recovers.
                    taskQueue.remove(submitted.id)
                    taskQueue.prepend(next)
                }
                throw error
            }
        }
    }

    private fun createPendingTask(
        projectId: String,
        conversationId: String,
        task: String,
        attachments: List<ChatImageAttachment>,
    ): PendingAgentTask {
        require(projectId.isNotBlank()) { "Project id must not be blank" }
        require(conversationId.isNotBlank()) { "Conversation id must not be blank" }
        val normalizedTask = task.trim()
        require(normalizedTask.isNotEmpty()) { "Task must not be empty" }
        require(attachments.all { it.projectId == projectId }) { "Attachment belongs to another project" }
        val now = System.currentTimeMillis()
        val userItem = TimelineItem(
            id = UUID.randomUUID().toString(),
            kind = TimelineItemKind.USER,
            title = "You",
            body = normalizedTask,
            createdAt = now,
            status = "queued",
            attachments = attachments,
        )
        return PendingAgentTask(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            conversationId = conversationId,
            task = normalizedTask,
            attachments = attachments,
            userItem = userItem,
            queuedAt = now,
        )
    }

    private fun startPending(pending: PendingAgentTask): String {
        val runId = UUID.randomUUID().toString()
        val userItem = pending.userItem.copy(status = "sent")
        val run = CoordinatedAgentRun(
            runId = runId,
            projectId = pending.projectId,
            conversationId = pending.conversationId,
            task = pending.task,
            attachments = pending.attachments,
            status = AgentRunStatus.RUNNING,
            timeline = listOf(userItem),
            startedAt = userItem.createdAt,
        )
        synchronized(runLock) {
            check(
                mutableRuns.value.values.none {
                    it.projectId == pending.projectId && !it.status.isTerminal
                },
            ) { "This project already has an active run" }
            mutableRuns.value = mutableRuns.value + (runId to run)
        }
        val foregroundIntent = AgentRunForegroundService.startIntent(
            context = appContext,
            runId = runId,
            projectId = pending.projectId,
            conversationId = pending.conversationId,
            task = pending.task,
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
                service.runAgent(
                    pending.projectId,
                    pending.conversationId,
                    runId,
                    pending.task,
                    pending.attachments,
                ) { item ->
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

    private fun startRecovered(recovery: AgentRunResume): String {
        val run = CoordinatedAgentRun(
            runId = recovery.runId,
            projectId = recovery.projectId,
            conversationId = recovery.conversationId,
            task = recovery.task,
            status = AgentRunStatus.RUNNING,
            timeline = emptyList(),
            startedAt = System.currentTimeMillis(),
        )
        synchronized(runLock) {
            check(mutableRuns.value.values.none {
                it.projectId == recovery.projectId && !it.status.isTerminal
            }) { "This project already has an active run" }
            mutableRuns.value = mutableRuns.value + (recovery.runId to run)
        }
        try {
            ContextCompat.startForegroundService(
                appContext,
                AgentRunForegroundService.startIntent(
                    context = appContext,
                    runId = recovery.runId,
                    projectId = recovery.projectId,
                    conversationId = recovery.conversationId,
                    task = recovery.task,
                ),
            )
        } catch (error: Throwable) {
            synchronized(runLock) { mutableRuns.value = mutableRuns.value - recovery.runId }
            throw error
        }
        startTranscriptWriter(run)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val terminalStatus = try {
                service.runAgent(
                    recovery.projectId,
                    recovery.conversationId,
                    recovery.runId,
                    recovery.task,
                    emptyList(),
                    resume = recovery,
                ) { item -> appendTimeline(recovery.runId, item) }
            } catch (cancelled: CancellationException) {
                AgentRunStatus.CANCELLED
            } catch (error: Throwable) {
                appendTimeline(
                    recovery.runId,
                    TimelineItem(
                        id = UUID.randomUUID().toString(),
                        kind = TimelineItemKind.ERROR,
                        title = "Recovery failed",
                        body = error.message?.takeIf(String::isNotBlank) ?: "Unknown recovery error",
                        createdAt = System.currentTimeMillis(),
                        isError = true,
                    ),
                )
                AgentRunStatus.FAILED
            }
            withContext(NonCancellable) { finish(recovery.runId, terminalStatus) }
        }
        jobs[recovery.runId] = job
        job.invokeOnCompletion { jobs.remove(recovery.runId, job) }
        job.start()
        refreshRunningNotification()
        return recovery.runId
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
        taskQueue.clear()
        mutableRuns.value.values
            .filterNot { it.status.isTerminal }
            .forEach { cancel(it.runId) }
    }

    fun hasActiveProjectRun(projectId: String): Boolean =
        mutableRuns.value.values.any { it.projectId == projectId && !it.status.isTerminal }

    fun hasActiveConversationRun(conversationId: String): Boolean =
        mutableRuns.value.values.any { it.conversationId == conversationId && !it.status.isTerminal }

    fun hasActiveRuns(): Boolean = mutableRuns.value.values.any { !it.status.isTerminal }

    fun hasQueuedProjectTasks(projectId: String): Boolean = taskQueue.hasProject(projectId)

    fun hasQueuedConversationTasks(conversationId: String): Boolean = taskQueue.hasConversation(conversationId)

    fun resolveApproval(requestId: String, approved: Boolean): Boolean =
        service.resolveApproval(requestId, approved)

    fun removeTerminalRun(runId: String) {
        synchronized(runLock) {
            val current = mutableRuns.value
            val run = current[runId]
            if (run != null && run.status.isTerminal) mutableRuns.value = current - runId
        }
    }

    override fun close() {
        taskQueue.clear()
        jobs.values.forEach { it.cancel(CancellationException("Application is closing")) }
        jobs.clear()
        transcriptChannels.values.forEach { it.close() }
        transcriptChannels.clear()
        scope.cancel()
    }

    private fun appendTimeline(runId: String, item: TimelineItem) {
        synchronized(runLock) {
            // RuntimeEventRouter may already have dequeued a callback when its
            // subscription closes. Once finish() takes the terminal snapshot,
            // reject every such late delta atomically.
            if (runId in finishingRunIds) return
            val current = mutableRuns.value
            val run = current[runId] ?: return
            if (run.status.isTerminal) return
            val nextTimeline = upsertTimelineItem(run.timeline, item)
            val normalizedItem = nextTimeline.first { it.id == item.id }
            val next = run.copy(timeline = nextTimeline)
            mutableRuns.value = current + (runId to next)
            // Acceptance and transcript admission are one atomic operation.
            // finish() uses the same lock before removing/closing this channel,
            // so a final assistant/error event cannot be visible in UI but lost
            // from SQLite in the gap between these two writes.
            transcriptChannels[runId]?.trySend(TranscriptCommand.Append(next, normalizedItem))
        }
    }

    private suspend fun finish(runId: String, requestedStatus: AgentRunStatus) {
        val terminalStatus = if (requestedStatus.isTerminal) requestedStatus else AgentRunStatus.FAILED
        val (finishing, finalizedStreamItems) = synchronized(runLock) {
            val current = mutableRuns.value
            val run = mutableRuns.value[runId] ?: return
            if (!finishingRunIds.add(runId)) return
            val finalizedTimeline = finalizeStreamingTimeline(run.timeline, terminalStatus)
            if (finalizedTimeline !== run.timeline) {
                mutableRuns.value = current + (runId to run.copy(timeline = finalizedTimeline))
            }
            run.copy(
                status = terminalStatus,
                timeline = finalizedTimeline,
                completedAt = System.currentTimeMillis(),
            ) to finalizedTimeline.filterIndexed { index, item -> item !== run.timeline[index] }
        }

        // Keep the current Run non-terminal until every transcript command has
        // reached SQLite. A queued Run builds its model history immediately, so
        // starting it before this join would intermittently omit the previous
        // assistant response from the next request.
        val channel = transcriptChannels.remove(runId)
        finalizedStreamItems.forEach { item ->
            channel?.trySend(TranscriptCommand.Append(finishing, item))
        }
        channel?.trySend(TranscriptCommand.Finish(finishing))
        channel?.close()
        transcriptJobs.remove(runId)?.join()

        var finished: CoordinatedAgentRun? = null
        synchronized(runLock) {
            val current = mutableRuns.value
            val run = current[runId] ?: return
            val terminalRun = run.copy(status = finishing.status, completedAt = finishing.completedAt)
            finished = terminalRun
            mutableRuns.value = current + (runId to terminalRun)
            finishingRunIds.remove(runId)

            taskQueue.dequeue(run.projectId)?.let { next ->
                runCatching { startPending(next) }
                    .onFailure { error ->
                        taskQueue.prepend(next)
                        Log.e(TAG, "Unable to start queued Agent task", error)
                    }
            }
        }
        val run = finished ?: return
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
                try {
                    transcriptStore.runStarted(run)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    Log.e(TAG, "Unable to persist Agent transcript start", error)
                }
                for (command in channel) {
                    try {
                        when (command) {
                            is TranscriptCommand.Append ->
                                transcriptStore.timelineAppended(command.run, command.item)
                            is TranscriptCommand.Finish -> transcriptStore.runFinished(command.run)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        // A malformed or temporarily failed event must not make
                        // every later assistant/final message disappear.
                        Log.e(TAG, "Unable to persist one Agent transcript event", error)
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

internal fun upsertTimelineItem(
    timeline: List<TimelineItem>,
    incoming: TimelineItem,
): List<TimelineItem> {
    val index = timeline.indexOfFirst { it.id == incoming.id }
    if (index < 0) return timeline + incoming
    val original = timeline[index]
    return timeline.toMutableList().also {
        it[index] = incoming.copy(createdAt = original.createdAt)
    }
}

internal fun finalizeStreamingTimeline(
    timeline: List<TimelineItem>,
    terminalStatus: AgentRunStatus,
): List<TimelineItem> {
    val messageStatus = when (terminalStatus) {
        AgentRunStatus.COMPLETED -> "completed"
        AgentRunStatus.FAILED -> "failed"
        AgentRunStatus.CANCELLED -> "cancelled"
        else -> return timeline
    }
    var changed = false
    val finalized = timeline.map { item ->
        if (item.kind == TimelineItemKind.ASSISTANT && item.status == "running") {
            changed = true
            item.copy(status = messageStatus)
        } else {
            item
        }
    }
    return if (changed) finalized else timeline
}
