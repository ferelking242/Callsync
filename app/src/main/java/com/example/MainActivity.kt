package com.example

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.service.CallUploadService
import com.example.ui.screens.MainScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CallSyncViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: CallSyncViewModel by viewModels()
    private val phonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

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

        if (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_PHONE_STATE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            window.decorView.post {
                phonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // S'assurer que le service tourne à chaque retour sur l'app
        if (!CallUploadService.isRunning.value) {
            viewModel.startService()
        }
        viewModel.syncNow()
        // Re-demander batterie si pas encore accordée (utilisateur revenant)
    }
}
