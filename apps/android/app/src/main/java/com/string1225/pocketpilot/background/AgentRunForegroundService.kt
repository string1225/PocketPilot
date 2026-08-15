package com.string1225.pocketpilot.background

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.string1225.pocketpilot.PocketPilotApplication
import com.string1225.pocketpilot.model.AgentRunStatus

class AgentRunForegroundService : Service() {
    private var intentionalIdleStop = false

    override fun onCreate() {
        super.onCreate()
        AgentNotifications.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                intent.getStringExtra(EXTRA_RUN_ID)?.let {
                    (application as PocketPilotApplication).agentRunCoordinator.cancel(it)
                }
                if (!(application as PocketPilotApplication).agentRunCoordinator.hasActiveRuns()) {
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_START -> {
                intentionalIdleStop = false
                val runId = intent.getStringExtra(EXTRA_RUN_ID).orEmpty()
                val task = intent.getStringExtra(EXTRA_TASK).orEmpty()
                val placeholder = CoordinatedAgentRun(
                    runId = runId,
                    projectId = intent.getStringExtra(EXTRA_PROJECT_ID).orEmpty(),
                    conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID).orEmpty(),
                    task = task,
                    status = AgentRunStatus.RUNNING,
                    timeline = emptyList(),
                    startedAt = System.currentTimeMillis(),
                )
                ServiceCompat.startForeground(
                    this,
                    AgentNotifications.RUNNING_NOTIFICATION_ID,
                    AgentNotifications.runningNotification(this, listOf(placeholder)),
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                    } else {
                        0
                    },
                )
                if (!(application as PocketPilotApplication).agentRunCoordinator.hasActiveRuns()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                }
                return START_NOT_STICKY
            }

            ACTION_STOP_IF_IDLE -> {
                val coordinator = (application as PocketPilotApplication).agentRunCoordinator
                if (!coordinator.hasActiveRuns()) {
                    // stopSelfResult prevents an older idle-stop request from
                    // stopping a newer ACTION_START already delivered here.
                    intentionalIdleStop = stopSelfResult(startId)
                    if (intentionalIdleStop) stopForeground(STOP_FOREGROUND_REMOVE)
                }
                return START_NOT_STICKY
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int, fgsType: Int) {
        intentionalIdleStop = false
        (application as PocketPilotApplication).agentRunCoordinator.cancelAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        val coordinator = (application as PocketPilotApplication).agentRunCoordinator
        // An idle generation may finish while a newer Run is being registered.
        // The newer ACTION_START will establish its own Service generation; do
        // not cancel it from this older instance's onDestroy callback.
        if (!intentionalIdleStop && coordinator.hasActiveRuns()) coordinator.cancelAll()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_START = "com.string1225.pocketpilot.action.START_AGENT_RUN"
        private const val ACTION_CANCEL = "com.string1225.pocketpilot.action.CANCEL_AGENT_RUN"
        private const val ACTION_STOP_IF_IDLE = "com.string1225.pocketpilot.action.STOP_IF_IDLE"
        private const val EXTRA_RUN_ID = "runId"
        private const val EXTRA_TASK = "task"
        private const val EXTRA_PROJECT_ID = "projectId"
        private const val EXTRA_CONVERSATION_ID = "conversationId"

        fun baseIntent(context: Context): Intent = Intent(context, AgentRunForegroundService::class.java)

        fun startIntent(
            context: Context,
            runId: String,
            projectId: String,
            conversationId: String,
            task: String,
        ): Intent =
            baseIntent(context)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RUN_ID, runId)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_CONVERSATION_ID, conversationId)
                .putExtra(EXTRA_TASK, task)

        fun cancelIntent(context: Context, runId: String): Intent =
            baseIntent(context)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_RUN_ID, runId)

        fun stopIfIdleIntent(context: Context): Intent =
            baseIntent(context).setAction(ACTION_STOP_IF_IDLE)
    }
}
