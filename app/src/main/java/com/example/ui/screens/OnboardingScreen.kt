package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.service.CallDeviceAdminReceiver
import com.example.ui.viewmodel.CallSyncViewModel

@SuppressLint("BatteryLife")
@Composable
fun OnboardingScreen(
    viewModel:  CallSyncViewModel,
    activity:   MainActivity,
    onComplete: () -> Unit
) {
    val context = LocalContext.current
    var currentPage by remember { mutableStateOf(0) }

    // ── Permission states ─────────────────────────────────────────────────────

    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }

    var hasStoragePermission by remember {
        mutableStateOf(
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    Environment.isExternalStorageManager()
                else ->
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var hasBatteryExemption by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        )
    }

    var hasManageStorage by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
            else true
        )
    }

    var hasDeviceAdmin by remember {
        mutableStateOf(
            run {
                val dpm  = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val comp = ComponentName(context, CallDeviceAdminReceiver::class.java)
                dpm.isAdminActive(comp)
            }
        )
    }

    // ── Permission launchers ──────────────────────────────────────────────────

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasNotificationPermission = granted }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasStoragePermission = granted }

    val manageStorageResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasManageStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            Environment.isExternalStorageManager() else true
        hasStoragePermission = hasManageStorage
    }

    val batteryResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            hasBatteryExemption = pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    val deviceAdminResult = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val dpm  = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val comp = ComponentName(context, CallDeviceAdminReceiver::class.java)
        hasDeviceAdmin = dpm.isAdminActive(comp)
    }

    // ── Auto-request on page 1 arrival ────────────────────────────────────────
    // Les permissions sont demandées UNIQUEMENT quand l'utilisateur est sur la page dédiée

    LaunchedEffect(currentPage) {
        if (currentPage == 1) {
            // Demander notification en premier — la plus simple
            if (!hasNotificationPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            )
            .padding(24.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Box(modifier = Modifier.size(10.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary))
                Spacer(Modifier.width(8.dp))
                Text(
                    "CALLSYNC",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Slide content
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(
                    targetState = currentPage,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "SlideTransition"
                ) { page ->
                    when (page) {
                        0 -> SlideWelcome()
                        1 -> SlidePermissions(
                            hasNotification = hasNotificationPermission,
                            hasStorage      = hasStoragePermission,
                            hasBattery      = hasBatteryExemption,
                            hasManageAll    = hasManageStorage,
                            hasDeviceAdmin  = hasDeviceAdmin,
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else hasNotificationPermission = true
                            },
                            onRequestStorage = {
                                when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                        try {
                                            manageStorageResult.launch(
                                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                    data = Uri.parse("package:${context.packageName}")
                                                }
                                            )
                                        } catch (_: Exception) {
                                            manageStorageResult.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                        }
                                    }
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        storageLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                                    else ->
                                        storageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            },
                            onRequestBattery = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    try {
                                        batteryResult.launch(
                                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            }
                                        )
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Désactivez l'optimisation batterie manuellement.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            onRequestManageAll = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        manageStorageResult.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Activez 'Accès à tous les fichiers' manuellement.", Toast.LENGTH_LONG).show()
                                    }
                                } else hasManageStorage = true
                            },
                            onRequestDeviceAdmin = {
                                try {
                                    deviceAdminResult.launch(
                                        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                                ComponentName(context, CallDeviceAdminReceiver::class.java))
                                            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                                "Empêche les gestionnaires de batterie OEM (MIUI, One UI, etc.) " +
                                                "de tuer le service d'upload en arrière-plan.")
                                        }
                                    )
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Impossible d'activer l'admin appareil.", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                        2 -> SlideReady()
                        else -> SlideReady()
                    }
                }
            }

            // Navigation
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    repeat(3) { index ->
                        val isSelected = index == currentPage
                        Box(
                            modifier = Modifier
                                .size(if (isSelected) 12.dp else 8.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                        )
                    }
                }

                Button(
                    onClick = {
                        if (currentPage < 2) currentPage++
                        else {
                            viewModel.repository.setOnboardingCompleted(true)
                            viewModel.startService()
                            onComplete()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp).testTag("onboarding_next_button"),
                    shape  = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (currentPage == 2) MaterialTheme.colorScheme.secondary
                                         else MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text(
                        if (currentPage == 2) "Démarrer CallSync" else "Continuer",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (currentPage == 2) Icons.Default.CheckCircle else Icons.Default.ArrowForward,
                        null
                    )
                }

                if (currentPage == 1) {
                    TextButton(onClick = { currentPage++ }) {
                        Text(
                            "Passer pour l'instant",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ── Slide 0: Welcome ──────────────────────────────────────────────────────────

@Composable
fun SlideWelcome() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Sync, null, modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Upload Automatique & Silencieux",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "CallSync surveille votre dossier d'enregistrements et envoie chaque nouveau fichier vers le serveur dès sa création — 100% automatique, en arrière-plan.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeaturePill(Icons.Default.Bolt, "Auto-upload")
            FeaturePill(Icons.Default.Shield, "Discret")
            FeaturePill(Icons.Default.Wifi, "Temps réel")
        }
    }
}

@Composable
private fun FeaturePill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Slide 1: Permissions ──────────────────────────────────────────────────────

@Composable
fun SlidePermissions(
    hasNotification: Boolean,
    hasStorage: Boolean,
    hasBattery: Boolean,
    hasManageAll: Boolean,
    hasDeviceAdmin: Boolean,
    onRequestNotification: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestManageAll: () -> Unit,
    onRequestDeviceAdmin: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
    ) {
        Text(
            "Autorisations Requises",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Ces autorisations garantissent un fonctionnement 24h/24 sans interruption.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        PermissionItemCard(
            title       = "Notifications",
            description = "Notification permanente qui empêche Android de tuer le service.",
            isGranted   = hasNotification,
            onClick     = onRequestNotification,
            icon        = Icons.Default.Notifications
        )

        PermissionItemCard(
            title       = "Accès aux fichiers audio",
            description = "Lecture des enregistrements (.m4a, .mp3, .wav…) pour l'envoi automatique.",
            isGranted   = hasStorage,
            onClick     = onRequestStorage,
            icon        = Icons.Default.FolderOpen
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionItemCard(
                title       = "Accès à tous les fichiers (Android 11+)",
                description = "Nécessaire pour lire les dossiers MIUI, Samsung, Huawei, etc.",
                isGranted   = hasManageAll,
                onClick     = onRequestManageAll,
                icon        = Icons.Default.Storage
            )
        }

        PermissionItemCard(
            title       = "Exempter de l'optimisation batterie",
            description = "Permet à CallSync de fonctionner même quand l'écran est éteint (Doze).",
            isGranted   = hasBattery,
            onClick     = onRequestBattery,
            icon        = Icons.Default.BatteryChargingFull
        )

        PermissionItemCard(
            title       = "Administrateur de l'appareil",
            description = "Empêche les gestionnaires OEM (MIUI, One UI…) de tuer le service. Recommandé.",
            isGranted   = hasDeviceAdmin,
            onClick     = onRequestDeviceAdmin,
            icon        = Icons.Default.AdminPanelSettings
        )
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (isGranted)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isGranted) 0.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(
                        if (isGranted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null,
                    tint = if (isGranted) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(
                onClick = onClick,
                colors  = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) MaterialTheme.colorScheme.secondary
                                     else MaterialTheme.colorScheme.primary
                ),
                contentPadding = PaddingValues(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
                shape    = RoundedCornerShape(10.dp),
                modifier = Modifier.height(36.dp)
            ) {
                if (isGranted) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Actif", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("Activer", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ── Slide 2: Ready ────────────────────────────────────────────────────────────

@Composable
fun SlideReady() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Box(
            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Tout est prêt !",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center
        )
        Text(
            "CallSync démarre en silence. Chaque nouvel enregistrement sera envoyé automatiquement sans que vous ayez à faire quoi que ce soit.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SummaryRow(Icons.Default.FolderOpen, "Surveille le dossier configuré en temps réel")
                SummaryRow(Icons.Default.Upload, "Jusqu'à 16 fichiers envoyés en parallèle")
                SummaryRow(Icons.Default.Block, "Ne recharge jamais deux fois le même fichier")
                SummaryRow(Icons.Default.RestartAlt, "Redémarre automatiquement (boot, crash, swipe)")
                SummaryRow(Icons.Default.Wifi, "Reprend automatiquement à la reconnexion réseau")
            }
        }
    }
}

@Composable
private fun SummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
