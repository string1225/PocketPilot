package com.string1225.pocketpilot.data

import android.content.ContentValues
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.AgentRunResume
import java.util.UUID

class AgentRunRepository(
    private val database: PocketPilotDatabase,
    private val projectExists: (String) -> Boolean,
) {
    fun create(
        projectId: String,
        conversationId: String,
        task: String,
        requestedId: String? = null,
    ): String {
        require(projectExists(projectId)) { "Project does not exist" }
        require(task.isNotBlank()) { "Task must not be empty" }
        require(conversationId.isNotBlank()) { "Conversation id must not be empty" }
        val runId = requestedId ?: UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", runId)
            put("project_id", projectId)
            put("conversation_id", conversationId)
            put("task", task)
            put("status", AgentRunStatus.RUNNING.value)
            put("started_at", System.currentTimeMillis())
        }
        database.writableDatabase.insertOrThrow("agent_runs", null, values)
        return runId
    }

    fun appendEvent(runId: String, type: String, payload: String) {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val nextSequence = db.rawQuery(
                "SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_events WHERE run_id = ?",
                arrayOf(runId),
            ).use { cursor ->
                check(cursor.moveToFirst())
                cursor.getInt(0)
            }
            val values = ContentValues().apply {
                put("run_id", runId)
                put("sequence", nextSequence)
                put("type", type)
                put("payload", payload)
                put("created_at", System.currentTimeMillis())
            }
            db.insertOrThrow("agent_events", null, values)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun finish(runId: String, status: AgentRunStatus, error: String? = null) {
        require(
            status == AgentRunStatus.COMPLETED ||
                status == AgentRunStatus.FAILED ||
                status == AgentRunStatus.CANCELLED,
        ) { "Run is not terminal" }
        val values = ContentValues().apply {
            put("status", status.value)
            put("completed_at", System.currentTimeMillis())
            put("error", error)
            putNull("recovery_phase")
            putNull("resume_payload")
        }
        database.writableDatabase.update("agent_runs", values, "id = ?", arrayOf(runId))
    }

    fun saveBoundary(runId: String, phase: String, payloadJson: String?) {
        require(phase == "provider_ready" || phase == "tool_in_flight") { "Invalid recovery phase" }
        require(phase != "provider_ready" || !payloadJson.isNullOrBlank()) {
            "Provider-ready boundary requires a resume payload"
        }
        val values = ContentValues().apply {
            put("recovery_phase", phase)
            if (phase == "provider_ready") put("resume_payload", payloadJson) else putNull("resume_payload")
        }
        check(database.writableDatabase.update("agent_runs", values, "id = ?", arrayOf(runId)) == 1) {
            "Agent run does not exist"
        }
    }

    fun recoverInterruptedRuns() {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            val rows = mutableListOf<Pair<String, Boolean>>()
            db.query(
                "agent_runs",
                arrayOf("id", "recovery_phase", "resume_payload", "conversation_id"),
                "status = ?",
                arrayOf(AgentRunStatus.RUNNING.value),
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val recoverable = cursor.getString(1) == "provider_ready" &&
                        !cursor.isNull(2) &&
                        !cursor.isNull(3) &&
                        cursor.getString(3).isNotBlank()
                    rows += cursor.getString(0) to recoverable
                }
            }
            rows.forEach { (runId, recoverable) ->
                val values = ContentValues().apply {
                    if (recoverable) {
                        put("status", AgentRunStatus.RECOVERABLE.value)
                        putNull("completed_at")
                        put("error", "PocketPilot will resume from the last safe model boundary")
                    } else {
                        put("status", AgentRunStatus.FAILED.value)
                        put("completed_at", System.currentTimeMillis())
                        put("error", "App stopped while a tool could have been in flight; inspect project state before retrying")
                        putNull("resume_payload")
                    }
                }
                db.update("agent_runs", values, "id = ?", arrayOf(runId))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun listRecoverable(): List<AgentRunResume> {
        val result = mutableListOf<AgentRunResume>()
        database.readableDatabase.query(
            "agent_runs",
            arrayOf("id", "project_id", "conversation_id", "task", "resume_payload"),
            "status = ? AND resume_payload IS NOT NULL",
            arrayOf(AgentRunStatus.RECOVERABLE.value),
            null,
            null,
            "started_at ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val conversationId = cursor.getString(2) ?: continue
                result += AgentRunResume(
                    runId = cursor.getString(0),
                    projectId = cursor.getString(1),
                    conversationId = conversationId,
                    task = cursor.getString(3),
                    payloadJson = cursor.getString(4),
                )
            }
        }
        return result
    }

    fun resume(runId: String) {
        val values = ContentValues().apply {
            put("status", AgentRunStatus.RUNNING.value)
            putNull("completed_at")
            putNull("error")
        }
        check(
            database.writableDatabase.update(
                "agent_runs",
                values,
                "id = ? AND status = ?",
                arrayOf(runId, AgentRunStatus.RECOVERABLE.value),
            ) == 1,
        ) { "Agent run is not recoverable" }
    }
}
