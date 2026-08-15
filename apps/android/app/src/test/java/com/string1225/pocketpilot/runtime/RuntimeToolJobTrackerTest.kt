package com.string1225.pocketpilot.runtime

import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeToolJobTrackerTest {
    @Test
    fun `cancel only stops unfinished jobs owned by the requested run`() {
        val tracker = RuntimeToolJobTracker()
        val first = Job()
        val second = Job()
        val otherRun = Job()
        tracker.track("run-1", first)
        tracker.track("run-1", second)
        tracker.track("run-2", otherRun)

        first.complete()
        tracker.cancel("run-1", "cancelled")

        assertFalse(first.isCancelled)
        assertTrue(second.isCancelled)
        assertFalse(otherRun.isCancelled)
        assertEquals(0, tracker.activeCount("run-1"))
        assertEquals(1, tracker.activeCount("run-2"))
    }

    @Test
    fun `cancel all clears every run`() {
        val tracker = RuntimeToolJobTracker()
        val first = Job()
        val second = Job()
        tracker.track("run-1", first)
        tracker.track("run-2", second)

        tracker.cancelAll("renderer gone")

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertEquals(0, tracker.activeCount("run-1"))
        assertEquals(0, tracker.activeCount("run-2"))
    }

    @Test
    fun `late track after cancellation is cancelled before it can start`() {
        val tracker = RuntimeToolJobTracker()
        tracker.beginRun("run-1")
        tracker.cancel("run-1", "cancelled")

        val lateJob = Job()

        assertFalse(tracker.canStart("run-1"))
        assertFalse(tracker.track("run-1", lateJob))
        assertTrue(lateJob.isCancelled)
        assertEquals(0, tracker.activeCount("run-1"))
    }

    @Test
    fun `explicit next lifecycle clears a completed run cancellation tombstone`() {
        val tracker = RuntimeToolJobTracker()
        tracker.beginRun("run-1")
        tracker.cancel("run-1", "cancelled")
        tracker.finishRun("run-1")

        tracker.beginRun("run-1")
        val nextRunJob = Job()

        assertTrue(tracker.canStart("run-1"))
        assertTrue(tracker.track("run-1", nextRunJob))
        assertFalse(nextRunJob.isCancelled)
        nextRunJob.cancel()
    }
}
