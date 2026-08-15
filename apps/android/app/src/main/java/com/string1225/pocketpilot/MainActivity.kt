package com.string1225.pocketpilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.string1225.pocketpilot.ui.PocketPilotApp
import com.string1225.pocketpilot.ui.PocketPilotViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val application = application as PocketPilotApplication
        setContent {
            val viewModel: PocketPilotViewModel = viewModel(
                factory = PocketPilotViewModel.Factory(application.service),
            )
            PocketPilotApp(viewModel)
        }
    }
}
