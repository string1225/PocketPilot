package com.string1225.pocketpilot.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.string1225.pocketpilot.MainActivity
import com.string1225.pocketpilot.R
import com.string1225.pocketpilot.model.AgentRunStatus

object AgentNotifications {
    const val RUNNING_NOTIFICATION_ID = 41001
    private const val RUNNING_CHANNEL_ID = "agent_runs"
    private const val COMPLETION_CHANNEL_ID = "agent_results"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    RUNNING_CHANNEL_ID,
                    "Active Agent runs",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shows when PocketPilot is continuing a conversation in the background"
                },
                NotificationChannel(
                    COMPLETION_CHANNEL_ID,
                    "Agent results",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifies when an Agent run completes or fails"
                },
            ),
        )
    }

    fun runningNotification(
        context: Context,
        activeRuns: List<CoordinatedAgentRun>,
    ): Notification {
        ensureChannels(context)
        val primary = activeRuns
            .filter { it.status == AgentRunStatus.WAITING_FOR_APPROVAL }
            .maxByOrNull { it.startedAt }
            ?: activeRuns.maxBy { it.startedAt }
        val waiting = activeRuns.count { it.status == AgentRunStatus.WAITING_FOR_APPROVAL }
        val text = when {
            waiting > 0 -> "$waiting run(s) need approval"
            activeRuns.size > 1 -> "${activeRuns.size} conversations are running"
            else -> primary.task.take(80)
        }
        val cancelIntent = PendingIntent.getService(
            context,
            primary.runId.hashCode(),
            AgentRunForegroundService.cancelIntent(context, primary.runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocketpilot)
            .setContentTitle(if (waiting > 0) "PocketPilot needs your approval" else "PocketPilot is working")
            .setContentText(text)
            .setContentIntent(openAppIntent(context, primary))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, RUNNING_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pocketpilot)
                    .setContentTitle("PocketPilot is working")
                    .setContentText("Open PocketPilot for details")
                    .build(),
            )
            .addAction(0, "Stop", cancelIntent)
            .build()
    }

    fun showRunning(context: Context, activeRuns: List<CoordinatedAgentRun>) {
        if (activeRuns.isEmpty()) return
        notifyIfPermitted(context, RUNNING_NOTIFICATION_ID, runningNotification(context, activeRuns))
    }

    fun showCompletion(context: Context, run: CoordinatedAgentRun) {
        ensureChannels(context)
        val title = when (run.status) {
            AgentRunStatus.COMPLETED -> "PocketPilot finished"
            AgentRunStatus.CANCELLED -> "PocketPilot stopped"
            else -> "PocketPilot run failed"
        }
        val body = run.timeline.lastOrNull {
            it.kind == com.string1225.pocketpilot.model.TimelineItemKind.ASSISTANT || it.isError
        }?.body?.take(140) ?: run.task.take(140)
        val notification = NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_pocketpilot)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(openAppIntent(context, run))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, COMPLETION_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_pocketpilot)
                    .setContentTitle(title)
                    .setContentText("Open PocketPilot for details")
                    .build(),
            )
            .build()
        notifyIfPermitted(context, run.runId.hashCode(), notification)
    }

    private fun openAppIntent(context: Context, run: CoordinatedAgentRun): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CONVERSATION)
            .putExtra(MainActivity.EXTRA_PROJECT_ID, run.projectId)
            .putExtra(MainActivity.EXTRA_CONVERSATION_ID, run.conversationId)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            run.runId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    @SuppressLint("MissingPermission")
    private fun notifyIfPermitted(context: Context, id: Int, notification: Notification) {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        runCatching { manager.notify(id, notification) }
    }
}
