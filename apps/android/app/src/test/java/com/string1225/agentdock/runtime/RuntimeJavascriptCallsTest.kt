package com.string1225.agentdock.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeJavascriptCallsTest {
    @Test
    fun `start passes a quoted json string instead of an object`() {
        assertEquals(
            "window.AgentDockRuntime.start(\"{\\\"runId\\\":\\\"run-1\\\"}\");",
            RuntimeJavascriptCalls.start("\"{\\\"runId\\\":\\\"run-1\\\"}\""),
        )
    }

    @Test
    fun `receive passes a quoted envelope string instead of an object`() {
        assertEquals(
            "window.AgentDockRuntime.receive(\"{\\\"version\\\":1}\");",
            RuntimeJavascriptCalls.receive("\"{\\\"version\\\":1}\""),
        )
    }

    @Test
    fun `cancel passes a quoted run id`() {
        assertEquals(
            "window.AgentDockRuntime.cancel(\"run-1\");",
            RuntimeJavascriptCalls.cancel("\"run-1\""),
        )
    }
}
