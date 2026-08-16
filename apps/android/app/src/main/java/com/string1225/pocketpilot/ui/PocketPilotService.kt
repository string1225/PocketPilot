package com.string1225.pocketpilot.ui

import com.string1225.pocketpilot.data.AgentRunRepository
import com.string1225.pocketpilot.data.CheckpointRepository
import com.string1225.pocketpilot.data.ConversationRepository
import com.string1225.pocketpilot.data.ProjectRepository
import com.string1225.pocketpilot.data.PluginRepository
import com.string1225.pocketpilot.data.SettingsRepository
import com.string1225.pocketpilot.data.SshServerRepository
import com.string1225.pocketpilot.data.WorkspaceRepository
import com.string1225.pocketpilot.model.AgentRunStatus
import com.string1225.pocketpilot.model.Checkpoint
import com.string1225.pocketpilot.model.ChatImageAttachment
import com.string1225.pocketpilot.model.Conversation
import com.string1225.pocketpilot.model.ConversationMessage
import com.string1225.pocketpilot.model.ConversationMessageRole
import com.string1225.pocketpilot.model.PocketPilotSettings
import com.string1225.pocketpilot.model.Project
import com.string1225.pocketpilot.model.InstalledPlugin
import com.string1225.pocketpilot.model.LlmProviderPreference
import com.string1225.pocketpilot.model.LlmProtocolPreference
import com.string1225.pocketpilot.model.LlmSettingsPolicy
import com.string1225.pocketpilot.model.PluginInstallPreview
import com.string1225.pocketpilot.model.RemoteServerProfile
import com.string1225.pocketpilot.model.TimelineItem
import com.string1225.pocketpilot.model.TimelineItemKind
import com.string1225.pocketpilot.model.TokenUsage
import com.string1225.pocketpilot.model.ToolApprovalRequest
import com.string1225.pocketpilot.model.WorkspaceEntry
import com.string1225.pocketpilot.security.CredentialIds
import com.string1225.pocketpilot.security.SecureCredentialStore
import com.string1225.pocketpilot.llm.LlmEndpointConfig
import com.string1225.pocketpilot.llm.LlmEndpointPolicy
import com.string1225.pocketpilot.llm.LlmProtocol
import com.string1225.pocketpilot.llm.LlmConnectionVerifier
import com.string1225.pocketpilot.runtime.PluginRunCoordinationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * UI-facing application boundary. A runtime-backed implementation can replace
 * the offline implementation without changing the Compose screens.
 */
interface PocketPilotService {
    val isOfflineDemo: Boolean
    val runtimeAvailable: Boolean
    val pendingApprovals: StateFlow<List<ToolApprovalRequest>>

    suspend fun initialize(): List<Project>
    suspend fun listProjects(): List<Project>
    suspend fun createProject(name: String): Project
    suspend fun deleteProject(projectId: String)

    fun listConversations(projectId: String? = null): List<Conversation>
    fun createConversation(projectId: String, title: String): Conversation
    fun renameConversation(conversationId: String, title: String)
    fun deleteConversation(conversationId: String)
    fun listMessages(conversationId: String): List<ConversationMessage>
    fun appendMessage(
        conversationId: String,
        role: ConversationMessageRole,
        title: String,
        content: String,
        runId: String? = null,
        isError: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        messageId: String? = null,
        status: String? = null,
        tokenUsage: TokenUsage? = null,
        attachments: List<ChatImageAttachment> = emptyList(),
    ): ConversationMessage

    fun loadSettings(): PocketPilotSettings
    fun saveSettings(settings: PocketPilotSettings)
    fun hasLlmCredential(): Boolean
    fun saveLlmConnection(settings: PocketPilotSettings, newSecret: CharArray?)
    fun removeLlmCredential()
    fun hasGitCredential(): Boolean
    fun saveGitCredential(secret: CharArray)
    fun removeGitCredential()
    fun listRemoteServers(): List<RemoteServerProfile>
    fun saveRemoteServer(profile: RemoteServerProfile, secret: CharArray?): RemoteServerProfile
    fun deleteRemoteServer(serverId: String)
    fun listPlugins(): List<InstalledPlugin>
    fun previewPluginBundle(bundleJson: String): PluginInstallPreview
    fun installPluginBundle(bundleJson: String): InstalledPlugin
    fun setPluginEnabled(pluginId: String, enabled: Boolean): InstalledPlugin
    fun deletePlugin(pluginId: String)

    suspend fun listFiles(projectId: String): List<WorkspaceEntry>
    suspend fun readFile(projectId: String, path: String): String
    suspend fun createFile(projectId: String, path: String)
    suspend fun saveFile(projectId: String, path: String, content: String)
    suspend fun deleteFile(projectId: String, path: String)

    suspend fun listCheckpoints(projectId: String): List<Checkpoint>
    suspend fun restoreCheckpoint(projectId: String, checkpointId: String)

    suspend fun runAgent(
        projectId: String,
        conversationId: String,
        runId: String,
        task: String,
        attachments: List<ChatImageAttachment>,
        emit: (TimelineItem) -> Unit,
    ): AgentRunStatus

    suspend fun cancelAgent(runId: String)
    fun resolveApproval(requestId: String, approved: Boolean): Boolean
}

/**
 * Repository-backed MVP service. Workspace and checkpoint operations are real;
 * only the model/runtime response is a clearly labelled offline demonstration.
 */
class OfflinePocketPilotService(
    private val projects: ProjectRepository,
    private val workspace: WorkspaceRepository,
    private val checkpoints: CheckpointRepository,
    private val agentRuns: AgentRunRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
    private val credentials: SecureCredentialStore,
    private val sshServers: SshServerRepository,
    private val plugins: PluginRepository,
    private val pluginRuns: PluginRunCoordinationGate,
    private val llmConnectionVerifier: LlmConnectionVerifier? = null,
) : PocketPilotService {
    override val isOfflineDemo: Boolean = true
    override val runtimeAvailable: Boolean = false
    private val mutableApprovals = MutableStateFlow<List<ToolApprovalRequest>>(emptyList())
    override val pendingApprovals: StateFlow<List<ToolApprovalRequest>> = mutableApprovals.asStateFlow()

    private val activeRuns = ConcurrentHashMap.newKeySet<String>()

    override suspend fun initialize(): List<Project> {
        val existing = projects.list()
        if (existing.isNotEmpty()) return existing
        projects.create("个人项目")
        return projects.list()
    }

    override suspend fun listProjects(): List<Project> = projects.list()

    override suspend fun createProject(name: String): Project = projects.create(name)

    override suspend fun deleteProject(projectId: String) {
        projects.delete(projectId)
        if (projects.list().isEmpty()) projects.create("个人项目")
    }

    override fun listConversations(projectId: String?): List<Conversation> = conversations.list(projectId)

    override fun createConversation(projectId: String, title: String): Conversation =
        conversations.create(projectId, title)

    override fun renameConversation(conversationId: String, title: String) {
        conversations.rename(conversationId, title)
    }

    override fun deleteConversation(conversationId: String) {
        conversations.delete(conversationId)
    }

    override fun listMessages(conversationId: String): List<ConversationMessage> =
        conversations.listMessages(conversationId)

    override fun appendMessage(
        conversationId: String,
        role: ConversationMessageRole,
        title: String,
        content: String,
        runId: String?,
        isError: Boolean,
        createdAt: Long,
        messageId: String?,
        status: String?,
        tokenUsage: TokenUsage?,
        attachments: List<ChatImageAttachment>,
    ): ConversationMessage = conversations.appendMessage(
        conversationId = conversationId,
        role = role,
        title = title,
        content = content,
        runId = runId,
        isError = isError,
        createdAt = createdAt,
        messageId = messageId,
        status = status,
        tokenUsage = tokenUsage,
        attachments = attachments,
    )

    override fun loadSettings(): PocketPilotSettings = settings.load()

    override fun saveSettings(settings: PocketPilotSettings) {
        persistNonLlmSettings(settings, this.settings)
    }

    override fun hasLlmCredential(): Boolean = credentials.contains(CredentialIds.DEFAULT_LLM)

    override fun saveLlmConnection(settings: PocketPilotSettings, newSecret: CharArray?) =
        persistLlmConnection(
            settings,
            newSecret,
            this.settings,
            credentials,
            pluginRuns,
            llmConnectionVerifier,
        )

    override fun removeLlmCredential() = pluginRuns.mutate {
        credentials.remove(CredentialIds.DEFAULT_LLM)
    }

    override fun hasGitCredential(): Boolean = credentials.contains(CredentialIds.DEFAULT_GIT_TOKEN)

    override fun saveGitCredential(secret: CharArray) {
        credentials.put(CredentialIds.DEFAULT_GIT_TOKEN, secret)
    }

    override fun removeGitCredential() = credentials.remove(CredentialIds.DEFAULT_GIT_TOKEN)

    override fun listRemoteServers(): List<RemoteServerProfile> = sshServers.list()

    override fun saveRemoteServer(
        profile: RemoteServerProfile,
        secret: CharArray?,
    ): RemoteServerProfile = sshServers.save(profile, secret)

    override fun deleteRemoteServer(serverId: String) = sshServers.delete(serverId)

    override fun listPlugins(): List<InstalledPlugin> = plugins.list()

    override fun previewPluginBundle(bundleJson: String): PluginInstallPreview =
        plugins.previewBundle(bundleJson)

    override fun installPluginBundle(bundleJson: String): InstalledPlugin =
        pluginRuns.mutate { plugins.installBundle(bundleJson) }

    override fun setPluginEnabled(pluginId: String, enabled: Boolean): InstalledPlugin =
        pluginRuns.mutate { plugins.setEnabled(pluginId, enabled) }

    override fun deletePlugin(pluginId: String) = pluginRuns.mutate { plugins.delete(pluginId) }

    override suspend fun listFiles(projectId: String): List<WorkspaceEntry> = workspace.list(projectId)

    override suspend fun readFile(projectId: String, path: String): String = workspace.read(projectId, path)

    override suspend fun createFile(projectId: String, path: String) {
        workspace.create(projectId, path)
    }

    override suspend fun saveFile(projectId: String, path: String, content: String) {
        workspace.write(projectId, path, content)
    }

    override suspend fun deleteFile(projectId: String, path: String) {
        workspace.delete(projectId, path)
    }

    override suspend fun listCheckpoints(projectId: String): List<Checkpoint> = checkpoints.list(projectId)

    override suspend fun restoreCheckpoint(projectId: String, checkpointId: String) {
        checkpoints.restore(projectId, checkpointId)
    }

    override suspend fun runAgent(
        projectId: String,
        conversationId: String,
        runId: String,
        task: String,
        attachments: List<ChatImageAttachment>,
        emit: (TimelineItem) -> Unit,
    ): AgentRunStatus {
        pluginRuns.beginRun(runId)
        return try {
            activeRuns += runId
            agentRuns.create(projectId, task, requestedId = runId)
            agentRuns.appendEvent(runId, "user.message", task)
            emit(
                timeline(
                    TimelineItemKind.STATUS,
                    "离线演示已启动",
                    "当前未连接 LLM，下面展示 Agent 与工具执行的交互流程。",
                ),
            )
            delay(450)

            val files = workspace.list(projectId)
            val toolBody = if (files.isEmpty()) {
                "workspace.list → 当前工作区为空"
            } else {
                "workspace.list → 找到 ${files.size} 个文件\n${files.take(5).joinToString("\n") { it.path }}"
            }
            agentRuns.appendEvent(runId, "tool.result", toolBody)
            emit(timeline(TimelineItemKind.TOOL, "Workspace Tool", toolBody))
            delay(650)

            val response = buildString {
                append("这是离线演示结果，任务没有发送给模型，也没有由 Agent 修改文件。")
                if (files.isEmpty()) {
                    append(" 你可以先在 Files 页面新建文件。")
                } else {
                    append(" 已安全读取文件列表；接入模型后会在同一时间线显示真实 tool call。")
                }
            }
            agentRuns.appendEvent(runId, "assistant.message", response)
            emit(timeline(TimelineItemKind.ASSISTANT, "Agent", response))
            agentRuns.finish(runId, AgentRunStatus.COMPLETED)
            AgentRunStatus.COMPLETED
        } catch (cancelled: CancellationException) {
            runCatching {
                agentRuns.appendEvent(runId, "run.cancelled", "Cancelled by user")
                agentRuns.finish(runId, AgentRunStatus.CANCELLED)
            }
            AgentRunStatus.CANCELLED
        } catch (error: Throwable) {
            runCatching { agentRuns.finish(runId, AgentRunStatus.FAILED, error.message) }
            emit(
                timeline(
                    TimelineItemKind.ERROR,
                    "运行失败",
                    error.message?.takeIf { it.isNotBlank() } ?: "离线演示运行失败",
                ),
            )
            AgentRunStatus.FAILED
        } finally {
            activeRuns -= runId
            pluginRuns.finishRun(runId)
        }
    }

    override suspend fun cancelAgent(runId: String) {
        if (runId !in activeRuns) return
        runCatching {
            agentRuns.appendEvent(runId, "run.cancel_requested", "Cancelled by user")
            agentRuns.finish(runId, AgentRunStatus.CANCELLED)
        }
    }

    override fun resolveApproval(requestId: String, approved: Boolean): Boolean = false

    private fun timeline(kind: TimelineItemKind, title: String, body: String): TimelineItem = TimelineItem(
        id = UUID.randomUUID().toString(),
        kind = kind,
        title = title,
        body = body,
        createdAt = System.currentTimeMillis(),
        isError = kind == TimelineItemKind.ERROR,
    )
}

internal fun persistLlmConnection(
    requestedSettings: PocketPilotSettings,
    newSecret: CharArray?,
    settingsRepository: SettingsRepository,
    credentials: SecureCredentialStore,
    runGate: PluginRunCoordinationGate,
    connectionVerifier: LlmConnectionVerifier? = null,
) = runGate.mutate {
    synchronized(settingsRepository) {
        val normalizedSettings = normalizeLlmConnection(requestedSettings)
        val previousSettings = settingsRepository.load()
        val previousSecret = credentials.get(CredentialIds.DEFAULT_LLM)
        try {
            val requiresNewSecret = LlmSettingsPolicy.requiresNewCredential(
                previousProvider = previousSettings.llmProvider,
                previousBaseUrl = previousSettings.llmBaseUrl,
                newProvider = normalizedSettings.llmProvider,
                newBaseUrl = normalizedSettings.llmBaseUrl,
                credentialConfigured = previousSecret != null,
            )
            require(!requiresNewSecret || (newSecret != null && newSecret.isNotEmpty())) {
                "An AK is required after changing the model provider or endpoint."
            }
            connectionVerifier?.verify(
                normalizedSettings,
                newSecret ?: previousSecret
                    ?: throw IllegalArgumentException("AK / API Key is required"),
            )

            if (newSecret == null) {
                settingsRepository.save(normalizedSettings)
                return@synchronized
            }

            try {
                removeCredentialIfPresent(credentials, CredentialIds.DEFAULT_LLM)
                settingsRepository.save(normalizedSettings)
                credentials.put(CredentialIds.DEFAULT_LLM, newSecret)
            } catch (error: Throwable) {
                val settingsRollback = runCatching { settingsRepository.save(previousSettings) }
                settingsRollback.exceptionOrNull()?.let(error::addSuppressed)
                if (settingsRollback.isSuccess) {
                    val credentialRollback = runCatching {
                        if (previousSecret == null) {
                            removeCredentialIfPresent(credentials, CredentialIds.DEFAULT_LLM)
                        } else {
                            credentials.put(CredentialIds.DEFAULT_LLM, previousSecret)
                        }
                    }
                    credentialRollback.exceptionOrNull()?.let { rollbackError ->
                        error.addSuppressed(rollbackError)
                        // The store may have changed before reporting failure. Make
                        // the connection un-runnable first, then erase any remaining
                        // credential so no unknown AK can be paired with an endpoint.
                        runCatching {
                            settingsRepository.save(disabledLlmSettings(previousSettings))
                        }.exceptionOrNull()?.let(error::addSuppressed)
                        runCatching {
                            removeCredentialIfPresent(credentials, CredentialIds.DEFAULT_LLM)
                        }.exceptionOrNull()?.let(error::addSuppressed)
                    }
                } else {
                    // If the old destination cannot be restored, fail closed with no
                    // credential instead of ever pairing its AK with the new endpoint.
                    runCatching {
                        removeCredentialIfPresent(credentials, CredentialIds.DEFAULT_LLM)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        } finally {
            previousSecret?.fill('\u0000')
        }
    }
}

private fun removeCredentialIfPresent(
    credentials: SecureCredentialStore,
    credentialId: String,
) {
    if (credentials.contains(credentialId)) credentials.remove(credentialId)
}

private fun disabledLlmSettings(
    settings: PocketPilotSettings,
): PocketPilotSettings = settings.copy(
    modelName = "",
    llmProvider = LlmProviderPreference.OPENAI_CHAT,
    llmProtocol = LlmProtocolPreference.CHAT_COMPLETIONS,
    llmBaseUrl = "",
    openAiModelName = "",
    openAiBaseUrl = "",
    imageModelName = "",
    openAiImageModelName = "",
)

internal fun persistNonLlmSettings(
    requestedSettings: PocketPilotSettings,
    settingsRepository: SettingsRepository,
) {
    synchronized(settingsRepository) {
        val currentSettings = settingsRepository.load()
        check(currentSettings.hasSameLlmConnectionAs(requestedSettings)) {
            "Model connections must be changed through the protected connection flow."
        }
        settingsRepository.save(requestedSettings)
    }
}

private fun PocketPilotSettings.hasSameLlmConnectionAs(
    other: PocketPilotSettings,
): Boolean = modelName == other.modelName &&
    llmProvider == other.llmProvider &&
    llmProtocol == other.llmProtocol &&
    llmBaseUrl == other.llmBaseUrl &&
    openAiModelName == other.openAiModelName &&
    openAiBaseUrl == other.openAiBaseUrl &&
    imageModelName == other.imageModelName &&
    openAiImageModelName == other.openAiImageModelName

internal fun normalizeLlmConnection(
    requestedSettings: PocketPilotSettings,
): PocketPilotSettings {
    val resolvedBaseUrl = LlmSettingsPolicy.resolveBaseUrl(
        requestedSettings.llmProvider,
        requestedSettings.llmBaseUrl,
    )
    val normalizedSettings = requestedSettings.copy(
        modelName = requestedSettings.modelName.trim(),
        llmProtocol = LlmProtocolPreference.CHAT_COMPLETIONS,
        llmBaseUrl = resolvedBaseUrl,
        imageModelName = when (requestedSettings.llmProvider) {
            LlmProviderPreference.OPENAI_CHAT -> requestedSettings.imageModelName.trim()
            LlmProviderPreference.GLM -> requestedSettings.llmProvider.defaultImageModel
        },
    )
    require(normalizedSettings.imageModelName.isNotBlank()) {
        "An image model is required for this model connection."
    }
    LlmEndpointPolicy.resolve(
        LlmEndpointConfig(
            protocol = LlmProtocol.CHAT_COMPLETIONS,
            baseUrl = normalizedSettings.llmBaseUrl,
            model = normalizedSettings.modelName.trim(),
            credentialId = CredentialIds.DEFAULT_LLM,
        ),
    )
    return normalizedSettings
}
