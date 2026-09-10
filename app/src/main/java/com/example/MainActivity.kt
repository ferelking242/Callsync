package com.example

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import com.example.service.CallDeviceAdminReceiver
import com.example.service.CallUploadService
import com.example.ui.screens.MainScreen
import com.example.ui.screens.OnboardingScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.CallSyncViewModel
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: CallSyncViewModel by viewModels()

    // ── Permission launchers ──────────────────────────────────────────────────

    /** Lanceur multi-permissions — utilisé UNIQUEMENT depuis l'onboarding (page 1) */
    val multiPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Résultat géré dans OnboardingScreen via les states locaux
        viewModel.startService()
    }

    val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.startService()
    }

    val batteryLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.startService()
    }

    val deviceAdminLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Admin activé ou refusé — démarrer le service dans tous les cas
        viewModel.startService()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Créer le dossier sandbox par défaut
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
                            viewModel  = viewModel,
                            activity   = this@MainActivity,
                            onComplete = {
                                showOnboarding = false
                                // Demander admin device après onboarding si pas encore accordé
                                requestDeviceAdminIfNeeded()
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
        requestBatteryOptimizationIfNeeded()
    }

    // ── Device admin ──────────────────────────────────────────────────────────

    fun requestDeviceAdminIfNeeded() {
        val dpm       = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, CallDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComp)) {
            try {
                deviceAdminLauncher.launch(
                    Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Empêche Android et les gestionnaires de batterie OEM " +
                            "de tuer le service d'upload en arrière-plan."
                        )
                    }
                )
            } catch (_: Exception) {}
        }
    }

    fun isDeviceAdminActive(): Boolean {
        val dpm       = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, CallDeviceAdminReceiver::class.java)
        return dpm.isAdminActive(adminComp)
    }

    // ── Battery optimization ──────────────────────────────────────────────────

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

    // ── Manage storage (Android 11+) — appelé depuis OnboardingScreen ─────────

    fun requestManageStorageIfNeeded() {
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

    fun hasMissingPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return true
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return true
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) return true
        }
        return false
    }
}
