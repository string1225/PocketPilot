package com.string1225.pocketpilot.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginRunCoordinationGateTest {
    @Test
    fun pluginMutationFailsClosedWhileRunIsActive() {
        val gate = PluginRunCoordinationGate()
        gate.beginRun("run-1")

        assertFailsWith<IllegalStateException> { gate.mutate { error("must not execute") } }

        gate.finishRun("run-1")
        assertTrue(gate.mutate { true })
    }

    @Test
    fun concurrentRunCannotCrossAnInProgressMutation() {
        val gate = PluginRunCoordinationGate()
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val runStarted = AtomicBoolean(false)
        val mutation = thread(start = true) {
            gate.mutate {
                mutationEntered.countDown()
                releaseMutation.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))
        val run = thread(start = true) {
            gate.beginRun("run-race")
            runStarted.set(true)
            gate.finishRun("run-race")
        }

        assertFalse(runStarted.get())
        releaseMutation.countDown()
        mutation.join(2_000)
        run.join(2_000)
        assertFalse(mutation.isAlive)
        assertFalse(run.isAlive)
        assertTrue(runStarted.get())
    }
}
