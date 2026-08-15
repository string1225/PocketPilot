package com.string1225.pocketpilot.background

import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.ui.PocketPilotService

class ConversationTranscriptStore(
    private val service: PocketPilotService,
) : AgentRunTranscriptStore {
    override suspend fun runStarted(run: CoordinatedAgentRun) = Unit

    override suspend fun timelineAppended(
        run: CoordinatedAgentRun,
        item: com.string1225.pocketpilot.model.TimelineItem,
    ) {
        service.appendMessage(
            conversationId = run.conversationId,
            role = when (item.kind) {
                TimelineItemKind.USER -> ConversationMessageRole.USER
                TimelineItemKind.ASSISTANT -> ConversationMessageRole.ASSISTANT
                TimelineItemKind.TOOL -> ConversationMessageRole.TOOL
                TimelineItemKind.STATUS -> ConversationMessageRole.STATUS
                TimelineItemKind.ERROR -> ConversationMessageRole.ERROR
            },
            title = item.title,
            content = item.body,
            runId = run.runId,
            isError = item.isError,
            createdAt = item.createdAt,
            messageId = item.id,
        )
    }

    override suspend fun runFinished(run: CoordinatedAgentRun) = Unit
}
