package com.string1225.agentdock.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeEventRouterTest {
    @Test
    fun `global bridge errors reach every listener despite listener failure`() = runBlocking {
        val router = RuntimeEventRouter()
        try {
            val throwingCalls = AtomicInteger()
            val throwingSeen = CompletableDeferred<Unit>()
            val received = CompletableDeferred<RuntimeBridgeError>()
            router.subscribe("run-1", "project-1", {}, {
                throwingCalls.incrementAndGet()
                throwingSeen.complete(Unit)
                error("consumer failed")
            })
            router.subscribe("run-2", "project-2", {}, { received.complete(it) })

            router.onBridgeError(RuntimeBridgeError("runtime_renderer_gone", "renderer gone"))

            assertEquals("runtime_renderer_gone", withTimeout(1_000) { received.await() }.code)
            withTimeout(1_000) { throwingSeen.await() }
            assertEquals(1, throwingCalls.get())
        } finally {
            router.close()
        }
    }

    @Test
    fun `project mismatch is reported and event is ignored`() = runBlocking {
        val router = RuntimeEventRouter()
        try {
            val eventCalls = AtomicInteger()
            val receivedError = CompletableDeferred<RuntimeBridgeError>()
            router.subscribe(
                runId = "run-1",
                projectId = "expected-project",
                onEvent = { eventCalls.incrementAndGet() },
                onError = { receivedError.complete(it) },
            )

            router.onEvent(event(projectId = "other-project"))

            val error = withTimeout(1_000) { receivedError.await() }
            assertEquals("context_mismatch", error.code)
            assertEquals(0, eventCalls.get())
        } finally {
            router.close()
        }
    }

    @Test
    fun `throwing event and error listeners do not stop the router`() = runBlocking {
        val router = RuntimeEventRouter()
        try {
            val laterEvent = CompletableDeferred<AgentRuntimeEvent>()
            router.subscribe("run-broken", "project-1", { error("event consumer failed") }, {
                error("error consumer failed")
            })
            router.subscribe("run-ok", "project-1", { laterEvent.complete(it) }, {})

            router.onEvent(event(runId = "run-broken"))
            router.onEvent(event(runId = "run-ok"))

            assertEquals("run-ok", withTimeout(1_000) { laterEvent.await() }.runId)
        } finally {
            router.close()
        }
    }

    private fun event(
        runId: String = "run-1",
        projectId: String = "project-1",
    ): AgentRuntimeEvent = AgentRuntimeEvent(
        id = "event-$runId",
        runId = runId,
        projectId = projectId,
        type = "run.started",
        payloadJson = "{}",
    )
}
