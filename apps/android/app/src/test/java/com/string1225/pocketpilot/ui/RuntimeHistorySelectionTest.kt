package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.model.ConversationMessage
import com.string1225.pocketpilot.model.ConversationMessageRole
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeHistorySelectionTest {
    @Test
    fun `selects newest messages within per-message and total utf8 budgets in original order`() {
        val messages = listOf(
            message("old", "aa"),
            message("middle", "bb"),
            message("oversized", "xxxxxx"),
            message("current", "ignored", runId = "current-run"),
            message("tool", "ignored", role = ConversationMessageRole.TOOL),
            message("new", "cc"),
        )

        val selected = selectRecentRuntimeHistory(
            messages = messages,
            excludedRunId = "current-run",
            maxMessages = 10,
            maxMessageUtf8Bytes = 5,
            maxTotalUtf8Bytes = 4,
        )

        assertEquals(listOf("middle", "new"), selected.map(ConversationMessage::id))
    }

    @Test
    fun `budgets utf8 bytes rather than utf16 characters`() {
        val selected = selectRecentRuntimeHistory(
            messages = listOf(message("unicode", "你"), message("ascii", "a")),
            excludedRunId = "current-run",
            maxMessages = 10,
            maxMessageUtf8Bytes = 2,
            maxTotalUtf8Bytes = 10,
        )

        assertEquals(listOf("ascii"), selected.map(ConversationMessage::id))
    }

    private fun message(
        id: String,
        content: String,
        role: ConversationMessageRole = ConversationMessageRole.USER,
        runId: String? = null,
    ): ConversationMessage = ConversationMessage(
        id = id,
        conversationId = "conversation",
        role = role,
        title = id,
        content = content,
        createdAt = 1L,
        runId = runId,
    )
}
