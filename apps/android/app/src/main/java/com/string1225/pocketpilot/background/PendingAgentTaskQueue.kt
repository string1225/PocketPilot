package com.string1225.pocketpilot.background

import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.TimelineItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingAgentTask(
    val id: String,
    val projectId: String,
    val conversationId: String,
    val task: String,
    val attachments: List<ChatImageAttachment>,
    val userItem: TimelineItem,
    val queuedAt: Long,
)

data class AgentTaskSubmission(
    val id: String,
    val queued: Boolean,
)

/** Thread-safe process-local FIFO; ordering is FIFO independently per project. */
internal class PendingAgentTaskQueue {
    private val mutableTasks = MutableStateFlow<List<PendingAgentTask>>(emptyList())
    val tasks: StateFlow<List<PendingAgentTask>> = mutableTasks.asStateFlow()

    @Synchronized
    fun enqueue(task: PendingAgentTask) {
        check(mutableTasks.value.none { it.id == task.id }) { "Pending task already exists" }
        mutableTasks.value = mutableTasks.value + task
    }

    @Synchronized
    fun dequeue(projectId: String): PendingAgentTask? {
        val current = mutableTasks.value
        val index = current.indexOfFirst { it.projectId == projectId }
        if (index < 0) return null
        val task = current[index]
        mutableTasks.value = current.toMutableList().also { it.removeAt(index) }
        return task
    }

    @Synchronized
    fun prepend(task: PendingAgentTask) {
        check(mutableTasks.value.none { it.id == task.id }) { "Pending task already exists" }
        mutableTasks.value = listOf(task) + mutableTasks.value
    }

    @Synchronized
    fun remove(taskId: String): PendingAgentTask? {
        val current = mutableTasks.value
        val index = current.indexOfFirst { it.id == taskId }
        if (index < 0) return null
        val task = current[index]
        mutableTasks.value = current.toMutableList().also { it.removeAt(index) }
        return task
    }

    @Synchronized
    fun hasProject(projectId: String): Boolean = mutableTasks.value.any { it.projectId == projectId }

    @Synchronized
    fun hasConversation(conversationId: String): Boolean =
        mutableTasks.value.any { it.conversationId == conversationId }

    @Synchronized
    fun clear() {
        mutableTasks.value = emptyList()
    }
}
