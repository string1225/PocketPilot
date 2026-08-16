package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.TokenUsage

enum class ChatMessageStatus {
    QUEUED,
    SENT,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class ChatMessagePresentation(
    val id: String,
    val fromUser: Boolean,
    val body: String,
    val createdAt: Long,
    val status: ChatMessageStatus,
    val tokenUsage: TokenUsage? = null,
    val attachments: List<ChatImageAttachment> = emptyList(),
    val isError: Boolean = false,
)

/** Removes implementation events and projects the conversation into chat rows. */
internal fun presentChatTimeline(
    timeline: List<TimelineItem>,
    agentStatus: AgentRunStatus,
): List<ChatMessagePresentation> {
    val runActive = agentStatus == AgentRunStatus.RUNNING ||
        agentStatus == AgentRunStatus.WAITING_FOR_APPROVAL
    val visible = timeline.filter {
        it.kind == TimelineItemKind.USER ||
            it.kind == TimelineItemKind.ASSISTANT ||
            it.kind == TimelineItemKind.ERROR
    }
    val lastUserIndex = visible.indexOfLast { it.kind == TimelineItemKind.USER }
    val lastAssistantIndex = visible.indexOfLast { it.kind == TimelineItemKind.ASSISTANT }
    val latestResponseId = visible
        .getOrNull(lastAssistantIndex)
        ?.takeIf { lastAssistantIndex > lastUserIndex }
        ?.id
    val activeAssistantId = latestResponseId?.takeIf { runActive }
    return visible.map { item ->
        ChatMessagePresentation(
            id = item.id,
            fromUser = item.kind == TimelineItemKind.USER,
            body = item.body,
            createdAt = item.createdAt,
            status = item.status.toChatMessageStatusOrNull()
                ?.takeUnless { it == ChatMessageStatus.RUNNING && item.id != activeAssistantId }
                ?: when {
                item.kind == TimelineItemKind.ERROR || item.isError -> ChatMessageStatus.FAILED
                item.kind == TimelineItemKind.USER -> ChatMessageStatus.SENT
                item.id == activeAssistantId -> ChatMessageStatus.RUNNING
                agentStatus == AgentRunStatus.FAILED && item.id == latestResponseId ->
                    ChatMessageStatus.FAILED
                agentStatus == AgentRunStatus.CANCELLED && item.id == latestResponseId ->
                    ChatMessageStatus.CANCELLED
                else -> ChatMessageStatus.COMPLETED
            },
            tokenUsage = item.tokenUsage,
            attachments = item.attachments,
            isError = item.isError || item.kind == TimelineItemKind.ERROR,
        )
    }
}

private fun String?.toChatMessageStatusOrNull(): ChatMessageStatus? = when (this?.lowercase()) {
    "queued" -> ChatMessageStatus.QUEUED
    "sent" -> ChatMessageStatus.SENT
    "running", "streaming" -> ChatMessageStatus.RUNNING
    "completed", "complete", "succeeded" -> ChatMessageStatus.COMPLETED
    "failed", "error" -> ChatMessageStatus.FAILED
    "cancelled", "canceled" -> ChatMessageStatus.CANCELLED
    else -> null
}
