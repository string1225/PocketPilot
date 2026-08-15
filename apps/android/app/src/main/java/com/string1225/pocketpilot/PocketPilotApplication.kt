package com.string1225.pocketpilot

import android.app.Application
import android.util.Log
import android.webkit.WebView
import com.string1225.pocketpilot.data.PocketPilotDatabase
import com.string1225.pocketpilot.data.AgentRunRepository
import com.string1225.pocketpilot.data.CheckpointRepository
import com.string1225.pocketpilot.data.ProjectRepository
import com.string1225.pocketpilot.data.WorkspaceRepository
import com.string1225.pocketpilot.data.WorkspaceToolDispatcher
import com.string1225.pocketpilot.runtime.PocketPilotRuntimeBridge
import com.string1225.pocketpilot.runtime.ActiveRunRegistry
import com.string1225.pocketpilot.runtime.RuntimeEventRouter
import com.string1225.pocketpilot.runtime.ToolApprovalCoordinator
import com.string1225.pocketpilot.ui.PocketPilotService
import com.string1225.pocketpilot.ui.OfflinePocketPilotService
import com.string1225.pocketpilot.ui.RuntimePocketPilotService
import java.io.File

class PocketPilotApplication : Application() {
    lateinit var service: PocketPilotService
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
        val activeRuns = ActiveRunRegistry()
        val approvals = ToolApprovalCoordinator()

        service = try {
            val eventRouter = RuntimeEventRouter()
            runtimeEvents = eventRouter
            val bridge = PocketPilotRuntimeBridge(
                webView = WebView(this),
                toolDispatcher = WorkspaceToolDispatcher(workspace, activeRuns, approvals),
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
            )
        } catch (error: Throwable) {
            runtimeBridge?.close()
            runtimeEvents?.close()
            Log.e(TAG, "TypeScript runtime is unavailable; using the repository-only fallback", error)
            OfflinePocketPilotService(projects, workspace, checkpoints, agentRuns)
        }
    }

    override fun onTerminate() {
        runtimeBridge?.close()
        runtimeEvents?.close()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "PocketPilot"
    }
}
