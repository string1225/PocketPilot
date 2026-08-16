package com.string1225.pocketpilot.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.integrations.http.HttpToolExecutor
import com.string1225.pocketpilot.integrations.http.HttpToolRequest
import com.string1225.pocketpilot.integrations.http.HttpToolResponse
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.NativeToolRequest
import com.string1225.pocketpilot.runtime.NativeToolResult
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.runtime.ToolDispatchException
import com.string1225.pocketpilot.runtime.ToolRequestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpToolDispatcherTest {
    @Test
    fun httpsGetRunsWithoutApprovalAndReturnsBoundedShape() = runBlocking {
        var executed: HttpToolRequest? = null
        val fixture = fixture(
            executor = HttpToolExecutor { request ->
                executed = request
                HttpToolResponse(
                    status = 200,
                    headers = mapOf("content-type" to listOf("application/json")),
                    body = "{\"ok\":true}",
                    truncated = false,
                )
            },
        )
        val result = fixture.dispatcher.dispatch(
            request("""{"url":"https://example.com/api"}"""),
        )
        val data = JSONObject(result.payloadJson).getJSONObject("data")
        assertEquals(200, data.getInt("status"))
        assertEquals("{\"ok\":true}", data.getString("body"))
        assertEquals("utf8", data.getString("bodyEncoding"))
        assertFalse(data.getBoolean("truncated"))
        assertEquals("example.com", executed?.host)
        assertTrue(fixture.approvals.requests.value.isEmpty())
    }

    @Test
    fun postWaitsForOneTimeApprovalWithSafeTargetSummary() = runBlocking {
        var executions = 0
        val fixture = fixture(
            executor = HttpToolExecutor {
                executions += 1
                HttpToolResponse(201, emptyMap(), "created", false)
            },
        )
        val pending = async {
            fixture.dispatcher.dispatch(
                request(
                    """{"method":"POST","url":"https://example.com/v1","body":"{}"}""",
                ),
            )
        }
        val approval = fixture.approvals.requests.first { it.isNotEmpty() }.single()
        assertEquals("http.request", approval.toolName)
        assertTrue(approval.detail.contains("Method: POST"))
        assertTrue(approval.detail.contains("Target: https://example.com:443"))
        assertTrue(approval.detail.contains("Target URL: https://example.com/v1"))
        assertTrue(approval.detail.contains("Request body: 2 UTF-8 bytes"))
        assertTrue(approval.detail.contains("Body SHA-256: 44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a"))
        assertTrue(approval.detail.contains("Body preview (escaped): \"{}\""))
        assertEquals(0, executions)
        assertTrue(fixture.approvals.resolve(approval.id, true))
        assertEquals(201, JSONObject(pending.await().payloadJson).getJSONObject("data").getInt("status"))
        assertEquals(1, executions)
    }

    @Test
    fun everyExplicitCleartextRequestStillRequiresApproval() = runBlocking { supervisorScope {
        val fixture = fixture()
        val pending = async {
            fixture.dispatcher.dispatch(
                request("""{"url":"http://example.com/","allowInsecureHttp":true}"""),
            )
        }
        val approval = fixture.approvals.requests.first { it.isNotEmpty() }.single()
        assertTrue(approval.detail.contains("cleartext HTTP"))
        fixture.approvals.resolve(approval.id, false)
        val failure = runCatching { pending.await() }.exceptionOrNull() as ToolDispatchException
        assertEquals("PERMISSION_DENIED", failure.code)
    } }

    @Test
    fun strictArgumentsAndPrivateTargetsFailBeforeExecution() = runBlocking {
        var executed = false
        val fixture = fixture(
            executor = HttpToolExecutor {
                executed = true
                HttpToolResponse(200, emptyMap(), "", false)
            },
        )
        val invalid = listOf(
            """{"url":"https://127.0.0.1/"}""",
            """{"url":"http://example.com/"}""",
            """{"url":"https://example.com/","headers":{"Authorization":"secret"}}""",
            """{"url":"https://example.com/","unknown":true}""",
        )
        invalid.forEach { arguments ->
            val error = assertThrows(ToolDispatchException::class.java) {
                runBlocking { fixture.dispatcher.dispatch(request(arguments)) }
            }
            assertEquals("HTTP_INVALID_ARGUMENTS", error.code)
        }
        assertFalse(executed)
    }

    @Test
    fun cancellationPropagatesIntoNetworkExecutor() = runBlocking {
        var entered = false
        var cancelled = false
        val fixture = fixture(
            executor = HttpToolExecutor {
                entered = true
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
        )
        val job = launch { fixture.dispatcher.dispatch(request("""{"url":"https://example.com/"}""")) }
        while (!entered) kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertTrue(cancelled)
    }

    @Test
    fun unauthorizedRunCannotReachDnsOrApproval() {
        val activeRuns = ActiveRunRegistry()
        val approvals = ToolApprovalCoordinator()
        val dispatcher = HttpToolDispatcher(
            executor = HttpToolExecutor { HttpToolResponse(200, emptyMap(), "", false) },
            activeRuns = activeRuns,
            approvals = approvals,
            fallback = fallback,
        )
        val error = assertThrows(ToolDispatchException::class.java) {
            runBlocking { dispatcher.dispatch(request("""{"url":"https://example.com/"}""")) }
        }
        assertEquals("UNAUTHORIZED_RUN", error.code)
        assertTrue(approvals.requests.value.isEmpty())
    }

    private fun fixture(
        executor: HttpToolExecutor = HttpToolExecutor {
            HttpToolResponse(200, emptyMap(), "ok", false)
        },
    ): Fixture {
        val activeRuns = ActiveRunRegistry().also { it.register(RUN_ID, PROJECT_ID) }
        val approvals = ToolApprovalCoordinator()
        return Fixture(
            dispatcher = HttpToolDispatcher(executor, activeRuns, approvals, fallback),
            approvals = approvals,
        )
    }

    private fun request(argumentsJson: String): NativeToolRequest = NativeToolRequest(
        id = "call-1",
        runId = RUN_ID,
        projectId = PROJECT_ID,
        name = "http.request",
        argumentsJson = argumentsJson,
    )

    private data class Fixture(
        val dispatcher: HttpToolDispatcher,
        val approvals: ToolApprovalCoordinator,
    )

    private companion object {
        const val RUN_ID = "run-http"
        const val PROJECT_ID = "project-http"
        val fallback = object : ToolRequestDispatcher {
            override suspend fun dispatch(request: NativeToolRequest): NativeToolResult =
                NativeToolResult("""{"success":false,"error":{"code":"fallback","message":"fallback"}}""")
        }
    }
}
