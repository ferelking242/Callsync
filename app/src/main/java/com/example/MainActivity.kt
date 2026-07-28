package com.example

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
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
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: CallSyncViewModel by viewModels()

    // ── Permission launchers ──────────────────────────────────────────────────

    private val multiPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // After any permission result, check if we still need more; start service regardless
        requestBatteryOptimizationIfNeeded()
        requestManageStorageIfNeeded()
        viewModel.startService()
    }

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.startService()
    }

    private val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.startService()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Silently request all permissions in one shot — no user manual press needed
        requestAllPermissionsAuto()

        // Create default sandbox recordings folder
        val sandboxDir = File(getExternalFilesDir(null), "Recordings")
        if (!sandboxDir.exists()) sandboxDir.mkdirs()

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
                            viewModel = viewModel,
                            onComplete = { showOnboarding = false }
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
        // Ensure service is running every time user returns to the app
        if (!CallUploadService.isRunning.value) {
            viewModel.startService()
        }
    }

    // ── Auto permission flow ──────────────────────────────────────────────────

    private fun requestAllPermissionsAuto() {
        val permsToRequest = mutableListOf<String>()

        // Notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) !=
                PackageManager.PERMISSION_GRANTED) {
                permsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permsToRequest.isNotEmpty()) {
            multiPermLauncher.launch(permsToRequest.toTypedArray())
        } else {
            // Already have base permissions — go straight to battery + storage
            requestBatteryOptimizationIfNeeded()
            requestManageStorageIfNeeded()
            viewModel.startService()
        }
    }

    private fun requestManageStorageIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            try {
                manageStorageLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${packageName}")
                    }
                )
            } catch (_: Exception) {
                try {
                    manageStorageLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun requestBatteryOptimizationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    batteryLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${packageName}")
                        }
                    )
                } catch (_: Exception) {}
            }
        }
    }
}
