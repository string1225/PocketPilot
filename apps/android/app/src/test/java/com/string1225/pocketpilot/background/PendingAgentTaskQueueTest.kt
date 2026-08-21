package com.string1225.pocketpilot.background

import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingAgentTaskQueueTest {
    @Test
    fun `tasks dequeue FIFO within each project`() {
        val queue = PendingAgentTaskQueue()
        queue.enqueue(task("a1", "a"))
        queue.enqueue(task("b1", "b"))
        queue.enqueue(task("a2", "a"))

        assertEquals("a1", queue.dequeue("a")?.id)
        assertEquals("a2", queue.dequeue("a")?.id)
        assertEquals("b1", queue.dequeue("b")?.id)
    }

    @Test
    fun `concurrent admissions do not lose or duplicate tasks`() {
        val queue = PendingAgentTaskQueue()
        val count = 80
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        repeat(count) { index ->
            pool.submit {
                start.await()
                queue.enqueue(task("task-$index", "project"))
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

        val ids = buildSet {
            repeat(count) { add(assertNotNull(queue.dequeue("project")).id) }
        }
        assertEquals(count, ids.size)
        assertEquals(null, queue.dequeue("project"))
    }

    @Test
    fun `remove rolls back only the requested submission`() {
        val queue = PendingAgentTaskQueue()
        val first = task("first", "project-a")
        val submitted = task("submitted", "project-a")
        val other = task("other", "project-b")
        queue.enqueue(first)
        queue.enqueue(submitted)
        queue.enqueue(other)

        assertEquals(submitted, queue.remove(submitted.id))
        assertNull(queue.remove(submitted.id))
        assertEquals(first, queue.dequeue("project-a"))
        assertEquals(other, queue.dequeue("project-b"))
    }

    @Test
    fun `global dequeue skips a busy conversation without reordering eligible work`() {
        val queue = PendingAgentTaskQueue()
        queue.enqueue(task("busy-1", "project-a", "conversation-a"))
        queue.enqueue(task("ready-1", "project-a", "conversation-b"))
        queue.enqueue(task("ready-2", "project-b", "conversation-c"))

        assertEquals(
            "ready-1",
            queue.dequeueFirst { it.conversationId != "conversation-a" }?.id,
        )
        assertEquals("busy-1", queue.dequeue()?.id)
        assertEquals("ready-2", queue.dequeue()?.id)
        assertNull(queue.dequeue())
    }

    private fun task(
        id: String,
        projectId: String,
        conversationId: String = "conversation",
    ) = PendingAgentTask(
        id = id,
        projectId = projectId,
        conversationId = conversationId,
        task = id,
        attachments = emptyList(),
        userItem = TimelineItem(
            id = "message-$id",
            kind = TimelineItemKind.USER,
            title = "You",
            body = id,
            createdAt = 1L,
        ),
        queuedAt = 1L,
    )
}
