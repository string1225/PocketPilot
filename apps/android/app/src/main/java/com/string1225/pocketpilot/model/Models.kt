package com.string1225.pocketpilot.model

data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class WorkspaceEntry(
    val path: String,
    val size: Long,
    val modifiedAt: Long,
)

enum class CheckpointSource(val value: String) {
    AGENT("agent"),
    USER("user"),
    GIT("git"),
    IMPORT("import");

    companion object {
        fun fromValue(value: String): CheckpointSource = entries.firstOrNull { it.value == value } ?: USER
    }
}

data class Checkpoint(
    val id: String,
    val projectId: String,
    val parentId: String?,
    val source: CheckpointSource,
    val description: String,
    val createdAt: Long,
    val addedFiles: Int,
    val modifiedFiles: Int,
    val deletedFiles: Int,
    val totalFiles: Int,
)

enum class AgentRunStatus(val value: String) {
    IDLE("idle"),
    RUNNING("running"),
    WAITING_FOR_APPROVAL("waiting_for_approval"),
    COMPLETED("completed"),
    FAILED("failed"),
    CANCELLED("cancelled");
}

enum class TimelineItemKind {
    USER,
    ASSISTANT,
    TOOL,
    STATUS,
    ERROR,
}

data class TimelineItem(
    val id: String,
    val kind: TimelineItemKind,
    val title: String,
    val body: String,
    val createdAt: Long,
    val isError: Boolean = false,
)

data class ToolApprovalRequest(
    val id: String,
    val runId: String,
    val projectId: String,
    val toolName: String,
    val title: String,
    val detail: String,
)
