package com.string1225.pocketpilot

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.string1225.pocketpilot.background.AgentNotifications
import com.string1225.pocketpilot.background.AgentRunCoordinator
import com.string1225.pocketpilot.background.ConversationTranscriptStore
import com.string1225.pocketpilot.data.PocketPilotDatabase
import com.string1225.pocketpilot.data.AgentRunRepository
import com.string1225.pocketpilot.data.CheckpointRepository
import com.string1225.pocketpilot.data.ConversationRepository
import com.string1225.pocketpilot.data.IntegrationToolDispatcher
import com.string1225.pocketpilot.data.HttpToolDispatcher
import com.string1225.pocketpilot.data.ProjectRepository
import com.string1225.pocketpilot.data.PluginRepository
import com.string1225.pocketpilot.data.SettingsRepository
import com.string1225.pocketpilot.data.SshServerRepository
import com.string1225.pocketpilot.data.WorkspaceRepository
import com.string1225.pocketpilot.data.WorkspaceToolDispatcher
import com.string1225.pocketpilot.runtime.PocketPilotRuntimeBridge
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.RuntimeEventRouter
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.runtime.PluginRunCoordinationGate
import com.string1225.pocketpilot.llm.LlmToolRequestDispatcher
import com.string1225.pocketpilot.llm.OpenAiCompatibleLlmClient
import com.string1225.pocketpilot.integrations.http.OkHttpToolExecutor
import com.string1225.pocketpilot.security.AndroidKeystoreCredentialStore
import com.string1225.pocketpilot.security.SecureCredentialStore
import com.string1225.pocketpilot.ui.PocketPilotService
import com.string1225.pocketpilot.ui.OfflinePocketPilotService
import com.string1225.pocketpilot.ui.RuntimePocketPilotService
import java.io.File

class PocketPilotApplication : Application() {
    lateinit var service: PocketPilotService
        private set
    lateinit var agentRunCoordinator: AgentRunCoordinator
        private set
    lateinit var credentialStore: SecureCredentialStore
        private set

    private var runtimeBridge: PocketPilotRuntimeBridge? = null
    private var runtimeEvents: RuntimeEventRouter? = null

    override fun onCreate() {
        super.onCreate()

        val database = PocketPilotDatabase(this)
        val projectsRoot = File(filesDir, "projects")
        val projects = ProjectRepository(database, projectsRoot)
        val checkpoints = CheckpointRepository(database, projectsRoot, projects::exists)
        projects.checkpoints = checkpoints
        val workspace = WorkspaceRepository(
            projectsRoot = projectsRoot,
            projectExists = projects::exists,
            checkpoints = checkpoints,
            touchProject = { projectId -> projects.touch(projectId) },
        )
        val agentRuns = AgentRunRepository(database, projects::exists)
        // This is process recovery, not screen initialization. A ViewModel can
        // be recreated while an Application-scoped foreground Run is alive.
        agentRuns.markInterruptedRuns()
        val conversations = ConversationRepository(database)
        val settings = SettingsRepository(database)
        val activeRuns = ActiveRunRegistry()
        val approvals = ToolApprovalCoordinator()
        credentialStore = AndroidKeystoreCredentialStore(this)
        val sshServers = SshServerRepository(database, credentialStore)
        val plugins = PluginRepository(database, File(filesDir, "plugins"))
        val pluginRuns = PluginRunCoordinationGate()

        service = try {
            val eventRouter = RuntimeEventRouter()
            runtimeEvents = eventRouter
            val workspaceDispatcher = WorkspaceToolDispatcher(workspace, activeRuns, approvals)
            val integrationDispatcher = IntegrationToolDispatcher(
                projectsRoot = projectsRoot,
                checkpoints = checkpoints,
                sshServers = sshServers,
                credentials = credentialStore,
                activeRuns = activeRuns,
                approvals = approvals,
                fallback = workspaceDispatcher,
            )
            val httpDispatcher = HttpToolDispatcher(
                executor = OkHttpToolExecutor(),
                activeRuns = activeRuns,
                approvals = approvals,
                fallback = integrationDispatcher,
            )
            val toolDispatcher = LlmToolRequestDispatcher(
                client = OpenAiCompatibleLlmClient(credentialStore),
                activeRuns = activeRuns,
                fallback = httpDispatcher,
            )
            val bridge = PocketPilotRuntimeBridge(
                webView = WebView(this),
                toolDispatcher = toolDispatcher,
                eventSink = eventRouter,
            ).also { it.loadAsset("pocketpilot-runtime.html") }
            runtimeBridge = bridge
            RuntimePocketPilotService(
                projects = projects,
                workspace = workspace,
                checkpoints = checkpoints,
                agentRuns = agentRuns,
                runtime = bridge,
                events = eventRouter,
                activeRuns = activeRuns,
                approvals = approvals,
                conversations = conversations,
                settings = settings,
                credentials = credentialStore,
                sshServers = sshServers,
                plugins = plugins,
                pluginRuns = pluginRuns,
            )
        } catch (error: Throwable) {
            runtimeBridge?.close()
            runtimeEvents?.close()
            Log.e(TAG, "TypeScript runtime is unavailable; using the repository-only fallback", error)
            OfflinePocketPilotService(
                projects = projects,
                workspace = workspace,
                checkpoints = checkpoints,
                agentRuns = agentRuns,
                conversations = conversations,
                settings = settings,
                credentials = credentialStore,
                sshServers = sshServers,
                plugins = plugins,
                pluginRuns = pluginRuns,
            )
        }
        agentRunCoordinator = AgentRunCoordinator(
            context = this,
            service = service,
            transcriptStore = ConversationTranscriptStore(service),
        )
        AgentNotifications.ensureChannels(this)
    }

    override fun onTerminate() {
        agentRunCoordinator.close()
        runtimeBridge?.close()
        runtimeEvents?.close()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "PocketPilot"
    }
}
