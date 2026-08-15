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

data class Conversation(
    val id: String,
    val projectId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class ConversationMessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
    STATUS("status"),
    ERROR("error");

    companion object {
        fun fromValue(value: String): ConversationMessageRole =
            entries.firstOrNull { it.value == value } ?: STATUS
    }
}

data class ConversationMessage(
    val id: String,
    val conversationId: String,
    val role: ConversationMessageRole,
    val title: String,
    val content: String,
    val createdAt: Long,
    val runId: String? = null,
    val isError: Boolean = false,
)

enum class ThemePreference(val value: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromValue(value: String): ThemePreference =
            entries.firstOrNull { it.value == value } ?: SYSTEM
    }
}

enum class AppLanguage(val value: String) {
    CHINESE("zh"),
    ENGLISH("en");

    companion object {
        fun fromValue(value: String): AppLanguage =
            entries.firstOrNull { it.value == value } ?: CHINESE
    }
}

enum class LlmProtocolPreference(val value: String) {
    CHAT_COMPLETIONS("chat_completions"),
    RESPONSES("responses");

    companion object {
        fun fromValue(value: String): LlmProtocolPreference =
            entries.firstOrNull { it.value == value } ?: CHAT_COMPLETIONS
    }
}

enum class SshAuthType(val value: String) {
    PASSWORD("password"),
    PRIVATE_KEY("private_key");

    companion object {
        fun fromValue(value: String): SshAuthType =
            entries.firstOrNull { it.value == value } ?: PASSWORD
    }
}

data class RemoteServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: SshAuthType = SshAuthType.PASSWORD,
    val hostKeyFingerprint: String,
    val credentialId: String,
    val description: String = "",
    val hasCredential: Boolean = false,
)

data class PocketPilotSettings(
    val modelName: String = "glm-5.2",
    val llmProtocol: LlmProtocolPreference = LlmProtocolPreference.CHAT_COMPLETIONS,
    val llmBaseUrl: String = "https://open.bigmodel.cn/api/coding/paas/v4",
    val remoteServer: String = "",
    val personalization: String = "",
    val memoryEnabled: Boolean = true,
    val toolsEnabled: Boolean = true,
    val pluginsEnabled: Boolean = false,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val language: AppLanguage = AppLanguage.CHINESE,
)

data class ToolApprovalRequest(
    val id: String,
    val runId: String,
    val projectId: String,
    val toolName: String,
    val title: String,
    val detail: String,
)
