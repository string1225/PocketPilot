package com.string1225.pocketpilot.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeEnvelopeCodecTest {
    @Test
    fun envelopeLimitIsEightMiB() {
        assertEquals(8 * 1_048_576, RuntimeEnvelopeCodec.MAX_ENVELOPE_LENGTH)
    }

    @Test
    fun startScriptPassesCanonicalJsonAsAString() {
        val normalized = RuntimeEnvelopeCodec.normalizeStartRequest(
            """{"runId":"run-1","projectId":"project-1","task":"say \"hello\""}""",
        )
        val script = RuntimeJavascriptCalls.start(JSONObject.quote(normalized))
        val argumentLiteral = script
            .removePrefix("window.PocketPilotRuntime.start(")
            .removeSuffix(");")

        assertEquals(normalized, JSONTokener(argumentLiteral).nextValue())
    }

    @Test
    fun decodesToolContextAndEchoesItInResult() {
        val inbound = RuntimeEnvelopeCodec.decodeInbound(
            """{"version":1,"id":"call-1","type":"tool.request","runId":"run-1","projectId":"project-1","payload":{"name":"workspace.read","arguments":{"path":"README.md"}}}""",
        )
        val request = (inbound as RuntimeInboundMessage.ToolRequest).request

        assertEquals("run-1", request.runId)
        assertEquals("project-1", request.projectId)
        val result = JSONObject(
            RuntimeEnvelopeCodec.encodeToolResult(
                request,
                """{"success":true,"data":{"content":"hello"}}""",
            ),
        )
        assertEquals("call-1", result.getString("id"))
        assertEquals("run-1", result.getString("runId"))
        assertEquals("project-1", result.getString("projectId"))
    }

    @Test
    fun echoesToolContextInError() {
        val error = JSONObject(
            RuntimeEnvelopeCodec.encodeToolError(
                RuntimeRequestContext("call-1", "run-1", "project-1"),
                "NOT_FOUND",
                "File was not found",
            ),
        )

        assertEquals("call-1", error.getString("id"))
        assertEquals("run-1", error.getString("runId"))
        assertEquals("project-1", error.getString("projectId"))
        assertEquals("NOT_FOUND", error.getJSONObject("payload").getString("code"))
    }

    @Test
    fun echoesToolContextInProgress() {
        val progress = JSONObject(
            RuntimeEnvelopeCodec.encodeToolProgress(
                RuntimeRequestContext("call-1", "run-1", "project-1"),
                """{"contentDelta":"hello"}""",
            ),
        )

        assertEquals("tool.progress", progress.getString("type"))
        assertEquals("call-1", progress.getString("id"))
        assertEquals("hello", progress.getJSONObject("payload").getString("contentDelta"))
    }

    @Test
    fun preservesEventContextAndPayload() {
        val inbound = RuntimeEnvelopeCodec.decodeInbound(
            """{"version":1,"id":"event-1","type":"event","runId":"run-1","projectId":"project-1","payload":{"type":"run.started","sequence":1}}""",
        )
        val event = (inbound as RuntimeInboundMessage.Event).event

        assertEquals("event-1", event.id)
        assertEquals("run-1", event.runId)
        assertEquals("project-1", event.projectId)
        assertEquals("run.started", event.type)
        assertEquals(1, JSONObject(event.payloadJson).getInt("sequence"))
    }

    @Test
    fun rejectsToolRequestWithoutRunOrProjectContext() {
        assertThrows(RuntimeProtocolException::class.java) {
            RuntimeEnvelopeCodec.decodeInbound(
                """{"version":1,"id":"call-1","type":"tool.request","payload":{"name":"workspace.read","arguments":{}}}""",
            )
        }
    }
}
