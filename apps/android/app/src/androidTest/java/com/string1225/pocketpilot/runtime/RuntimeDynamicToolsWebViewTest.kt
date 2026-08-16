package com.string1225.pocketpilot.runtime

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises dynamic tools inside the actual bundled Runtime and Android WebView. */
@RunWith(AndroidJUnit4::class)
class RuntimeDynamicToolsWebViewTest {
    @Test
    fun executeTypeScriptAndInstalledPluginCompleteAgentRuns() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sink = CollectingEventSink()
        val dispatcher = ScriptedLlmDispatcher(
            mapOf(
                TS_RUN_ID to ToolScenario(
                    toolName = "execute_ts",
                    arguments = JSONObject()
                        .put(
                            "source",
                            "const values: number[] = input.values; " +
                                "return { answer: values.reduce((sum, value) => sum + value, 0) };",
                        )
                        .put("input", JSONObject().put("values", JSONArray(listOf(10, 20, 12)))),
                    expectedToolResultFragments = listOf("\"answer\":42"),
                    finalAnswer = "typescript-worker-ok",
                ),
                ESCAPE_RUN_ID to ToolScenario(
                    toolName = "execute_js",
                    arguments = JSONObject().put(
                        "source",
                        "const recover = (name) => { for (let scope = globalThis; scope; " +
                            "scope = Object.getPrototypeOf(scope)) { const descriptor = " +
                            "Object.getOwnPropertyDescriptor(scope, name); if (descriptor) return " +
                            "descriptor.value ?? descriptor.get?.call(globalThis); } }; " +
                            "return { fetch: typeof recover('fetch'), postMessage: typeof " +
                            "recover('postMessage'), worker: typeof recover('Worker'), " +
                            "indexedDB: typeof recover('indexedDB'), navigator: typeof " +
                            "recover('navigator'), bridge: typeof recover('PocketPilotNativeBridge') };",
                    ),
                    expectedToolResultFragments = listOf(
                        "\"fetch\":\"undefined\"",
                        "\"postMessage\":\"undefined\"",
                        "\"worker\":\"undefined\"",
                        "\"indexedDB\":\"undefined\"",
                        "\"navigator\":\"undefined\"",
                        "\"bridge\":\"undefined\"",
                    ),
                    finalAnswer = "sandbox-escape-blocked",
                ),
                PLUGIN_RUN_ID to ToolScenario(
                    toolName = "plugin.com.pocketpilot.text.text_stats",
                    arguments = JSONObject().put("text", "one two\nthree"),
                    expectedToolResultFragments = listOf("\"words\":3"),
                    finalAnswer = "plugin-worker-ok",
                ),
            ),
        )
        val webView = AtomicReference<WebView>()
        val bridge = AtomicReference<PocketPilotRuntimeBridge>()
        instrumentation.runOnMainSync {
            val view = WebView(instrumentation.targetContext)
            webView.set(view)
            bridge.set(PocketPilotRuntimeBridge(view, dispatcher, sink))
            bridge.get().loadAsset("pocketpilot-runtime.html")
        }

        try {
            assertTrue("Bundled Runtime did not become ready", sink.ready.await(30, TimeUnit.SECONDS))
            assertEquals(emptyList<RuntimeBridgeError>(), sink.errors.toList())

            runScenario(
                bridge = bridge.get(),
                sink = sink,
                request = baseStartRequest(TS_RUN_ID, "Run the TypeScript tool."),
                expectedAnswer = "typescript-worker-ok",
            )
            runScenario(
                bridge = bridge.get(),
                sink = sink,
                request = baseStartRequest(ESCAPE_RUN_ID, "Verify sandbox capabilities are unavailable."),
                expectedAnswer = "sandbox-escape-blocked",
            )
            runScenario(
                bridge = bridge.get(),
                sink = sink,
                request = baseStartRequest(PLUGIN_RUN_ID, "Run the installed plugin.")
                    .put("plugins", JSONArray().put(examplePlugin())),
                expectedAnswer = "plugin-worker-ok",
            )

            assertEquals(emptyList<RuntimeBridgeError>(), sink.errors.toList())
        } finally {
            instrumentation.runOnMainSync {
                bridge.get().close()
                webView.get().destroy()
            }
        }
    }

    private fun runScenario(
        bridge: PocketPilotRuntimeBridge,
        sink: CollectingEventSink,
        request: JSONObject,
        expectedAnswer: String,
    ) {
        val runId = request.getString("runId")
        val terminal = sink.expectTerminal(runId)
        bridge.start(request.toString())
        assertTrue("Agent run $runId did not finish", terminal.await(30, TimeUnit.SECONDS))
        val event = checkNotNull(sink.terminalEvents[runId]) { "Missing terminal event for $runId" }
        assertEquals("run.completed", event.type)
        assertEquals(expectedAnswer, JSONObject(event.payloadJson).getString("output"))
        bridge.finishRun(runId)
    }

    private fun baseStartRequest(runId: String, task: String): JSONObject = JSONObject()
        .put("runId", runId)
        .put("projectId", PROJECT_ID)
        .put("task", task)
        .put("maxSteps", 4)
        .put("toolsEnabled", true)
        .put(
            "provider",
            JSONObject()
                .put("type", "openai_compatible")
                .put("protocol", "chat_completions")
                .put("baseUrl", "https://example.com/v1")
                .put("model", "instrumented-fake")
                .put("credentialId", "llm.default"),
        )

    private fun examplePlugin(): JSONObject = JSONObject()
        .put("id", "com.pocketpilot.text")
        .put("name", "PocketPilot Text Utilities")
        .put("version", "1.0.0")
        .put("description", "Instrumented pure-computation plugin.")
        .put("sourceSha256", "55e66d67b8196a0a0e26857cd0f44001a5df7c4b50bdc349aad55f97dbf8ba73")
        .put(
            "source",
            "async (toolName, input) => { if (toolName !== \"text_stats\") " +
                "throw new Error(\"Unknown tool\"); const text = input.text; " +
                "const trimmed = text.trim(); return { codePoints: Array.from(text).length, " +
                "lines: text.length === 0 ? 0 : text.split(/\\r?\\n/u).length, " +
                "words: trimmed.length === 0 ? 0 : trimmed.split(/\\s+/u).length }; }",
        )
        .put(
            "tools",
            JSONArray().put(
                JSONObject()
                    .put("name", "text_stats")
                    .put("description", "Count text statistics.")
                    .put("risk", "read")
                    .put(
                        "inputSchema",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "text",
                                    JSONObject().put("type", "string").put("maxLength", 20_000),
                                ),
                            )
                            .put("required", JSONArray().put("text"))
                            .put("additionalProperties", false),
                    ),
            ),
        )

    private data class ToolScenario(
        val toolName: String,
        val arguments: JSONObject,
        val expectedToolResultFragments: List<String>,
        val finalAnswer: String,
    )

    private class ScriptedLlmDispatcher(
        private val scenarios: Map<String, ToolScenario>,
    ) : ToolRequestDispatcher {
        private val calls = ConcurrentHashMap<String, AtomicInteger>()

        override suspend fun dispatch(request: NativeToolRequest): NativeToolResult {
            if (request.name != "llm.complete") {
                throw ToolDispatchException("unexpected_native_tool", "Unexpected native tool: ${request.name}")
            }
            val scenario = scenarios[request.runId]
                ?: throw ToolDispatchException("unexpected_run", "Unexpected run: ${request.runId}")
            val call = calls.computeIfAbsent(request.runId) { AtomicInteger() }.incrementAndGet()
            val llmRequest = JSONObject(request.argumentsJson)
            val data = when (call) {
                1 -> {
                    val advertised = llmRequest.getJSONArray("tools")
                    val toolWasAdvertised = (0 until advertised.length()).any { index ->
                        advertised.getJSONObject(index).getString("name") == scenario.toolName
                    }
                    if (!toolWasAdvertised) {
                        throw ToolDispatchException(
                            "tool_not_registered",
                            "Dynamic tool was not advertised to the provider: ${scenario.toolName}",
                        )
                    }
                    JSONObject().put(
                        "toolCalls",
                        JSONArray().put(
                            JSONObject()
                                .put("id", "${request.runId}-call")
                                .put("name", scenario.toolName)
                                .put("arguments", scenario.arguments),
                        ),
                    )
                }

                2 -> {
                    val messages = llmRequest.getJSONArray("messages")
                    val toolContent = (0 until messages.length())
                        .map(messages::getJSONObject)
                        .lastOrNull { it.optString("role") == "tool" }
                        ?.getString("content")
                        ?: throw ToolDispatchException("tool_result_missing", "Tool result was not returned to provider")
                    if (scenario.expectedToolResultFragments.any { !toolContent.contains(it) }) {
                        throw ToolDispatchException(
                            "tool_result_invalid",
                            "Tool result did not contain all expected data",
                        )
                    }
                    JSONObject().put("content", scenario.finalAnswer)
                }

                else -> throw ToolDispatchException("too_many_provider_calls", "Provider was called too many times")
            }
            return NativeToolResult(JSONObject().put("success", true).put("data", data).toString())
        }
    }

    private class CollectingEventSink : RuntimeEventSink {
        val ready = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<RuntimeBridgeError>()
        val terminalEvents = ConcurrentHashMap<String, AgentRuntimeEvent>()
        private val terminalLatches = ConcurrentHashMap<String, CountDownLatch>()

        fun expectTerminal(runId: String): CountDownLatch = CountDownLatch(1).also {
            check(terminalLatches.putIfAbsent(runId, it) == null)
        }

        override fun onEvent(event: AgentRuntimeEvent) {
            if (event.type == "runtime.ready") ready.countDown()
            if (event.type in TERMINAL_EVENTS) {
                terminalEvents[event.runId] = event
                terminalLatches[event.runId]?.countDown()
            }
        }

        override fun onBridgeError(error: RuntimeBridgeError) {
            errors += error
        }
    }

    private companion object {
        const val PROJECT_ID = "runtime-dynamic-tools-project"
        const val TS_RUN_ID = "runtime-execute-ts"
        const val ESCAPE_RUN_ID = "runtime-sandbox-escape"
        const val PLUGIN_RUN_ID = "runtime-plugin"
        val TERMINAL_EVENTS = setOf("run.completed", "run.failed", "run.cancelled")
    }
}
