package com.string1225.agentdock

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.string1225.agentdock.data.AgentDockDatabase
import com.string1225.agentdock.data.AgentRunRepository
import com.string1225.agentdock.data.CheckpointRepository
import com.string1225.agentdock.data.ProjectRepository
import com.string1225.agentdock.data.WorkspaceRepository
import com.string1225.agentdock.data.WorkspaceToolDispatcher
import com.string1225.agentdock.runtime.AgentDockRuntimeBridge
import com.string1225.agentdock.runtime.ActiveRunRegistry
import com.string1225.agentdock.runtime.RuntimeEventRouter
import com.string1225.agentdock.runtime.ToolApprovalCoordinator
import com.string1225.agentdock.ui.AgentDockService
import com.string1225.agentdock.ui.OfflineAgentDockService
import com.string1225.agentdock.ui.RuntimeAgentDockService
import java.io.File

class AgentDockApplication : Application() {
    lateinit var service: AgentDockService
        private set

    private var runtimeBridge: AgentDockRuntimeBridge? = null
    private var runtimeEvents: RuntimeEventRouter? = null

    override fun onCreate() {
        super.onCreate()

        val database = AgentDockDatabase(this)
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
        val activeRuns = ActiveRunRegistry()
        val approvals = ToolApprovalCoordinator()

        service = try {
            val eventRouter = RuntimeEventRouter()
            runtimeEvents = eventRouter
            val bridge = AgentDockRuntimeBridge(
                webView = WebView(this),
                toolDispatcher = WorkspaceToolDispatcher(workspace, activeRuns, approvals),
                eventSink = eventRouter,
            ).also { it.loadAsset("agentdock-runtime.html") }
            runtimeBridge = bridge
            RuntimeAgentDockService(
                projects = projects,
                workspace = workspace,
                checkpoints = checkpoints,
                agentRuns = agentRuns,
                runtime = bridge,
                events = eventRouter,
                activeRuns = activeRuns,
                approvals = approvals,
            )
        } catch (error: Throwable) {
            runtimeBridge?.close()
            runtimeEvents?.close()
            Log.e(TAG, "TypeScript runtime is unavailable; using the repository-only fallback", error)
            OfflineAgentDockService(projects, workspace, checkpoints, agentRuns)
        }
    }

    override fun onTerminate() {
        runtimeBridge?.close()
        runtimeEvents?.close()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "AgentDock"
    }
}
