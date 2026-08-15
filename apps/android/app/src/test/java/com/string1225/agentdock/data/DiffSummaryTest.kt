package com.string1225.agentdock.data

import kotlin.test.Test
import kotlin.test.assertEquals

class DiffSummaryTest {
    @Test
    fun countsAddedModifiedAndDeletedFiles() {
        val previous = mapOf("same.txt" to "a", "changed.txt" to "old", "gone.txt" to "x")
        val current = mapOf("same.txt" to "a", "changed.txt" to "new", "added.txt" to "y")

        assertEquals(SnapshotDiff(added = 1, modified = 1, deleted = 1), DiffSummary.between(previous, current))
    }
}
