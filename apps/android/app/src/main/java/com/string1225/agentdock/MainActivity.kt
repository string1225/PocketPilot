package com.string1225.agentdock

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.string1225.agentdock.ui.AgentDockApp
import com.string1225.agentdock.ui.AgentDockViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as AgentDockApplication
        setContent {
            val viewModel: AgentDockViewModel = viewModel(
                factory = AgentDockViewModel.Factory(application.service),
            )
            AgentDockApp(viewModel)
        }
    }
}
