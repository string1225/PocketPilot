package com.string1225.pocketpilot.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ProjectToolExecutionGateTest {
    @Test
    fun `serializes calls for one project`() = runBlocking {
        val gate = ProjectToolExecutionGate()
        val entered = AtomicInteger()
        val maximum = AtomicInteger()

        val jobs = List(6) {
            async {
                gate.withProject("project-a") {
                    val active = entered.incrementAndGet()
                    maximum.updateAndGet { previous -> maxOf(previous, active) }
                    delay(10)
                    entered.decrementAndGet()
                }
            }
        }
        jobs.forEach { it.await() }

        assertEquals(1, maximum.get())
    }

    @Test
    fun `allows independent projects to execute concurrently`() = runBlocking {
        val gate = ProjectToolExecutionGate()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async {
            gate.withProject("project-a") {
                firstEntered.complete(Unit)
                secondEntered.await()
            }
        }
        val second = async {
            firstEntered.await()
            gate.withProject("project-b") {
                secondEntered.complete(Unit)
            }
        }

        first.await()
        second.await()
        assertTrue(firstEntered.isCompleted && secondEntered.isCompleted)
    }
}
