package com.string1225.pocketpilot.runtime

import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeJavascriptCallsTest {
    @Test
    fun `start passes a quoted json string instead of an object`() {
        assertEquals(
            "window.PocketPilotRuntime.start(\"{\\\"runId\\\":\\\"run-1\\\"}\");",
            RuntimeJavascriptCalls.start("\"{\\\"runId\\\":\\\"run-1\\\"}\""),
        )
    }

    @Test
    fun `receive passes a quoted envelope string instead of an object`() {
        assertEquals(
            "window.PocketPilotRuntime.receive(\"{\\\"version\\\":1}\");",
            RuntimeJavascriptCalls.receive("\"{\\\"version\\\":1}\""),
        )
    }

    @Test
    fun `cancel passes a quoted run id`() {
        assertEquals(
            "window.PocketPilotRuntime.cancel(\"run-1\");",
            RuntimeJavascriptCalls.cancel("\"run-1\""),
        )
    }
}
