package com.string1225.pocketpilot.background

import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.TokenUsage
import com.string1225.pocketpilot.model.AgentRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentTimelineUpsertTest {
    @Test
    fun `consecutive deltas and final replace one assistant row`() {
        val first = assistant(body = "Hel", status = "running", createdAt = 10L)
        val second = assistant(body = "Hello", status = "running", createdAt = 20L)
        val usage = TokenUsage(promptTokens = 3, completionTokens = 1, totalTokens = 4)
        val final = assistant(body = "Hello", status = "completed", createdAt = 30L, usage = usage)

        val timeline = upsertTimelineItem(
            upsertTimelineItem(
                upsertTimelineItem(emptyList(), first),
                second,
            ),
            final,
        )

        assertEquals(1, timeline.size)
        assertEquals("Hello", timeline.single().body)
        assertEquals("completed", timeline.single().status)
        assertEquals(usage, timeline.single().tokenUsage)
        assertEquals(10L, timeline.single().createdAt)
    }

    @Test
    fun `cancel finalizes an in-memory streaming response`() {
        val streaming = assistant(body = "Partial answer", status = "running", createdAt = 10L)

        val finalized = finalizeStreamingTimeline(listOf(streaming), AgentRunStatus.CANCELLED)

        assertEquals("Partial answer", finalized.single().body)
        assertEquals("cancelled", finalized.single().status)
    }

    private fun assistant(
        body: String,
        status: String,
        createdAt: Long,
        usage: TokenUsage? = null,
    ) = TimelineItem(
        id = "assistant-message",
        kind = TimelineItemKind.ASSISTANT,
        title = "Agent",
        body = body,
        createdAt = createdAt,
        status = status,
        tokenUsage = usage,
    )
}
