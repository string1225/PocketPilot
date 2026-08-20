package com.string1225.pocketpilot.data

import android.content.ContentValues
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.string1225.pocketpilot.model.AgentRunStatus
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentRunRecoveryTest {
    @Test
    fun providerReadyBoundaryIsRecoverableButToolInflightIsNot() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "agent-recovery-${UUID.randomUUID()}.db"
        val database = PocketPilotDatabase(context, name)
        try {
            val projectId = UUID.randomUUID().toString()
            val conversationId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            database.writableDatabase.insertOrThrow(
                "projects",
                null,
                ContentValues().apply {
                    put("id", projectId)
                    put("name", "Recovery")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            database.writableDatabase.insertOrThrow(
                "conversations",
                null,
                ContentValues().apply {
                    put("id", conversationId)
                    put("project_id", projectId)
                    put("title", "Recovery")
                    put("created_at", now)
                    put("updated_at", now)
                },
            )
            val runs = AgentRunRepository(database) { it == projectId }
            val safeId = runs.create(projectId, conversationId, "safe")
            val unsafeId = runs.create(projectId, conversationId, "unsafe")
            val boundary = JSONObject()
                .put("phase", "provider_ready")
                .put("nextStep", 2)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "continue")),
                )
                .toString()
            runs.saveBoundary(safeId, "provider_ready", boundary)
            runs.saveBoundary(unsafeId, "tool_in_flight", null)

            runs.recoverInterruptedRuns()

            assertEquals(listOf(safeId), runs.listRecoverable().map { it.runId })
            val statuses = mutableMapOf<String, String>()
            database.readableDatabase.query(
                "agent_runs",
                arrayOf("id", "status"),
                null,
                null,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) statuses[cursor.getString(0)] = cursor.getString(1)
            }
            assertEquals(AgentRunStatus.RECOVERABLE.value, statuses[safeId])
            assertEquals(AgentRunStatus.FAILED.value, statuses[unsafeId])
            assertTrue(runs.listRecoverable().single().payloadJson.contains("provider_ready"))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
