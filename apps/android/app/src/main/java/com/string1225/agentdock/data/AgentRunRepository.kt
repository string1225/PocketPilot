package com.string1225.agentdock.data

import android.content.ContentValues
import com.string1225.agentdock.model.AgentRunStatus
import java.util.UUID

class AgentRunRepository(
    private val database: AgentDockDatabase,
    private val projectExists: (String) -> Boolean,
) {
    fun create(projectId: String, task: String, requestedId: String? = null): String {
        require(projectExists(projectId)) { "Project does not exist" }
        require(task.isNotBlank()) { "Task must not be empty" }
        val runId = requestedId ?: UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("id", runId)
            put("project_id", projectId)
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
        }
        database.writableDatabase.update("agent_runs", values, "id = ?", arrayOf(runId))
    }

    fun markInterruptedRuns() {
        val values = ContentValues().apply {
            put("status", AgentRunStatus.FAILED.value)
            put("completed_at", System.currentTimeMillis())
            put("error", "App process stopped before the run completed")
        }
        database.writableDatabase.update(
            "agent_runs",
            values,
            "status = ?",
            arrayOf(AgentRunStatus.RUNNING.value),
        )
    }
}
