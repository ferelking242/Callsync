package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.service.CallUploadService
import com.example.ui.screens.MainScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CallSyncViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CallSyncViewModel by viewModels()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val onboardingCompleted = viewModel.repository.isOnboardingCompleted()

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var showOnboarding by remember { mutableStateOf(!onboardingCompleted) }

                    if (showOnboarding) {
                        OnboardingScreen(
                            viewModel  = viewModel,
                            onComplete = {
                                showOnboarding = false
                            }
                        )
                    } else {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // S'assurer que le service tourne à chaque retour sur l'app
        if (!CallUploadService.isRunning.value) {
            viewModel.startService()
        }
        // Re-demander batterie si pas encore accordée (utilisateur revenant)
    }
}
