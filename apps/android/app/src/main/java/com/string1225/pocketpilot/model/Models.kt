package com.string1225.pocketpilot.model

import java.net.URI

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

const val GLM_CHAT_BASE_URL = "https://open.bigmodel.cn/api/coding/paas/v4"
private const val LEGACY_GLM_RESPONSES_BASE_URL = "https://open.bigmodel.cn/api/v1"

enum class LlmProviderPreference(
    val value: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val hasEditableBaseUrl: Boolean,
) {
    OPENAI_CHAT(
        value = "openai_chat",
        defaultBaseUrl = "",
        defaultModel = "",
        hasEditableBaseUrl = true,
    ),
    GLM(
        value = "glm",
        defaultBaseUrl = GLM_CHAT_BASE_URL,
        defaultModel = "glm-5.2",
        hasEditableBaseUrl = false,
    );

    companion object {
        fun fromValueOrNull(value: String?): LlmProviderPreference? =
            entries.firstOrNull { it.value == value }
    }
}

/** Provider-specific defaults and compatibility rules shared by persistence and UI code. */
object LlmSettingsPolicy {
    data class LoadedConnection(
        val provider: LlmProviderPreference,
        val modelName: String,
        val baseUrl: String,
        val openAiModelName: String,
        val openAiBaseUrl: String,
    )

    fun loadConnection(
        persistedProvider: String?,
        persistedModel: String?,
        persistedBaseUrl: String?,
        persistedOpenAiModel: String?,
        persistedOpenAiBaseUrl: String?,
    ): LoadedConnection {
        val provider = loadProvider(persistedProvider, persistedBaseUrl)
        val activeModel = persistedModel?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_LENGTH }
        val activeBaseUrl = persistedBaseUrl?.trim()
            ?.takeIf { it.isNotEmpty() && isValidOpenAiBaseUrl(it) }
        val openAiModel = persistedOpenAiModel?.trim()
            ?.takeIf { it.isNotEmpty() && it.length <= MAX_MODEL_LENGTH }
            ?: activeModel.takeIf { provider == LlmProviderPreference.OPENAI_CHAT }
            ?: ""
        val openAiBaseUrl = persistedOpenAiBaseUrl?.trim()
            ?.takeIf { it.isNotEmpty() && isValidOpenAiBaseUrl(it) }
            ?: activeBaseUrl.takeIf { provider == LlmProviderPreference.OPENAI_CHAT }
            ?: ""
        return LoadedConnection(
            provider = provider,
            modelName = when (provider) {
                LlmProviderPreference.OPENAI_CHAT -> openAiModel
                LlmProviderPreference.GLM -> activeModel ?: provider.defaultModel
            },
            baseUrl = resolveBaseUrl(provider, openAiBaseUrl),
            openAiModelName = openAiModel,
            openAiBaseUrl = openAiBaseUrl,
        )
    }

    fun loadProvider(
        persistedProvider: String?,
        persistedBaseUrl: String?,
    ): LlmProviderPreference = LlmProviderPreference.fromValueOrNull(persistedProvider)
        ?: inferLegacyProvider(persistedBaseUrl)

    fun inferLegacyProvider(persistedBaseUrl: String?): LlmProviderPreference {
        val normalized = persistedBaseUrl?.trim()?.trimEnd('/')?.lowercase().orEmpty()
        return if (normalized == GLM_CHAT_BASE_URL.lowercase() ||
            normalized == LEGACY_GLM_RESPONSES_BASE_URL.lowercase()
        ) {
            LlmProviderPreference.GLM
        } else {
            LlmProviderPreference.OPENAI_CHAT
        }
    }

    fun resolveBaseUrl(
        provider: LlmProviderPreference,
        configuredBaseUrl: String?,
    ): String = when (provider) {
        LlmProviderPreference.OPENAI_CHAT ->
            configuredBaseUrl?.trim().orEmpty()

        LlmProviderPreference.GLM -> GLM_CHAT_BASE_URL
    }

    fun requiresNewCredential(
        previousProvider: LlmProviderPreference,
        previousBaseUrl: String,
        newProvider: LlmProviderPreference,
        newBaseUrl: String,
        credentialConfigured: Boolean,
    ): Boolean {
        if (!credentialConfigured || previousProvider != newProvider) return true
        if (newProvider == LlmProviderPreference.GLM) return false
        return normalizeEndpoint(previousBaseUrl) != normalizeEndpoint(newBaseUrl)
    }

    fun isValidOpenAiBaseUrl(value: String): Boolean {
        if (value.length !in 1..MAX_ENDPOINT_LENGTH) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) &&
            !uri.host.isNullOrBlank() &&
            (uri.port == -1 || uri.port in 1..65_535) &&
            uri.rawUserInfo == null &&
            uri.rawQuery == null &&
            uri.rawFragment == null
    }

    private fun normalizeEndpoint(value: String): String = value.trim().trimEnd('/')

    private const val MAX_MODEL_LENGTH = 128
    private const val MAX_ENDPOINT_LENGTH = 2_048
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
    val modelName: String = LlmProviderPreference.OPENAI_CHAT.defaultModel,
    val llmProvider: LlmProviderPreference = LlmProviderPreference.OPENAI_CHAT,
    val llmProtocol: LlmProtocolPreference = LlmProtocolPreference.CHAT_COMPLETIONS,
    val llmBaseUrl: String = LlmProviderPreference.OPENAI_CHAT.defaultBaseUrl,
    val openAiModelName: String = "",
    val openAiBaseUrl: String = "",
    val remoteServer: String = "",
    val personalization: String = "",
    val memoryEnabled: Boolean = true,
    val toolsEnabled: Boolean = true,
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val language: AppLanguage = AppLanguage.CHINESE,
)

enum class PluginToolRisk(val value: String) {
    READ("read"),
    WRITE("write"),
    NETWORK("network"),
    REMOTE("remote");

    companion object {
        fun fromValue(value: String): PluginToolRisk =
            entries.firstOrNull { it.value == value }
                ?: throw IllegalArgumentException("Unsupported plugin tool risk: $value")
    }
}

data class PluginToolManifest(
    val name: String,
    val description: String,
    val inputSchemaJson: String,
    val risk: PluginToolRisk,
)

data class InstalledPlugin(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val sourceSha256: String,
    val tools: List<PluginToolManifest>,
    val enabled: Boolean,
    val installedAt: Long,
    val updatedAt: Long,
)

data class PluginInstallPreview(
    val plugin: InstalledPlugin,
    val sourceSizeBytes: Int,
)

data class RuntimePluginPackage(
    val plugin: InstalledPlugin,
    val source: String,
)

data class ToolApprovalRequest(
    val id: String,
    val runId: String,
    val projectId: String,
    val toolName: String,
    val title: String,
    val detail: String,
)
