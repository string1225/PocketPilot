package com.string1225.pocketpilot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.string1225.pocketpilot.ui.PocketPilotApp
import com.string1225.pocketpilot.ui.PocketPilotViewModel

class MainActivity : ComponentActivity() {
    private lateinit var pocketPilotViewModel: PocketPilotViewModel

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Completion notifications remain optional when the user declines. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val application = application as PocketPilotApplication
        pocketPilotViewModel = ViewModelProvider(
            this,
            PocketPilotViewModel.Factory(application.service, application.agentRunCoordinator),
        )[PocketPilotViewModel::class.java]
        handleOpenConversationIntent(intent)
        setContent {
            PocketPilotApp(pocketPilotViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenConversationIntent(intent)
    }

    private fun handleOpenConversationIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_CONVERSATION || !::pocketPilotViewModel.isInitialized) return
        val projectId = intent.getStringExtra(EXTRA_PROJECT_ID)?.takeIf { it.isNotBlank() } ?: return
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf { it.isNotBlank() } ?: return
        pocketPilotViewModel.openConversation(projectId, conversationId)
    }

    companion object {
        const val ACTION_OPEN_CONVERSATION = "com.string1225.pocketpilot.OPEN_CONVERSATION"
        const val EXTRA_PROJECT_ID = "projectId"
        const val EXTRA_CONVERSATION_ID = "conversationId"
    }
}
