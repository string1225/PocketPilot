package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.TokenUsage
import com.string1225.pocketpilot.model.AgentRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatPresentationModelsTest {
    @Test
    fun `chat hides tool and run lifecycle cards`() {
        val timeline = listOf(
            item("user", TimelineItemKind.USER, "hello"),
            item("started", TimelineItemKind.STATUS, "Agent Run started"),
            item("tool", TimelineItemKind.TOOL, "workspace read"),
            item("answer", TimelineItemKind.ASSISTANT, "done"),
            item("completed", TimelineItemKind.STATUS, "Run completed"),
        )

        assertEquals(listOf("user", "answer"), presentChatTimeline(timeline, AgentRunStatus.COMPLETED).map { it.id })
    }

    @Test
    fun `latest assistant is running only while stream is active`() {
        val timeline = listOf(
            item("user", TimelineItemKind.USER, "hello"),
            item("answer", TimelineItemKind.ASSISTANT, "par"),
        )

        assertEquals(
            ChatMessageStatus.RUNNING,
            presentChatTimeline(timeline, AgentRunStatus.RUNNING).last().status,
        )
        assertEquals(
            ChatMessageStatus.COMPLETED,
            presentChatTimeline(timeline, AgentRunStatus.COMPLETED).last().status,
        )
    }

    @Test
    fun `explicit queue status and usage survive presentation`() {
        val usage = TokenUsage(promptTokens = 5, completionTokens = 7, totalTokens = 12)
        val timeline = listOf(
            item("queued", TimelineItemKind.USER, "next", status = "queued"),
            item("answer", TimelineItemKind.ASSISTANT, "done", usage = usage),
        )

        val presented = presentChatTimeline(timeline, AgentRunStatus.COMPLETED)

        assertEquals(ChatMessageStatus.QUEUED, presented.first().status)
        assertEquals(usage, presented.last().tokenUsage)
    }

    @Test
    fun `unfinished stream stops showing running after cancellation`() {
        val timeline = listOf(
            item("answer", TimelineItemKind.ASSISTANT, "partial", status = "running"),
        )

        assertEquals(
            ChatMessageStatus.CANCELLED,
            presentChatTimeline(timeline, AgentRunStatus.CANCELLED).single().status,
        )
    }

    @Test
    fun `new queued user message does not mark previous answer running`() {
        val timeline = listOf(
            item("old-user", TimelineItemKind.USER, "first"),
            item("old-answer", TimelineItemKind.ASSISTANT, "done"),
            item("new-user", TimelineItemKind.USER, "second", status = "sent"),
        )

        val presented = presentChatTimeline(timeline, AgentRunStatus.RUNNING)

        assertEquals(ChatMessageStatus.COMPLETED, presented[1].status)
        assertEquals(ChatMessageStatus.SENT, presented[2].status)
    }

    private fun item(
        id: String,
        kind: TimelineItemKind,
        body: String,
        status: String? = null,
        usage: TokenUsage? = null,
    ) = TimelineItem(
        id = id,
        kind = kind,
        title = id,
        body = body,
        createdAt = id.length.toLong(),
        status = status,
        tokenUsage = usage,
    )
}
