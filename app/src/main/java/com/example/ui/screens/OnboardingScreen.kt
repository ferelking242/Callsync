package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
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
import com.example.ui.viewmodel.CallSyncViewModel

@SuppressLint("BatteryLife")
@Composable
fun OnboardingScreen(
    viewModel: CallSyncViewModel,
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Environment.isExternalStorageManager()
            else
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
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

    // ── Permission launchers ──────────────────────────────────────────────────

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotificationPermission = granted
        if (granted) Toast.makeText(context, "Notifications autorisées !", Toast.LENGTH_SHORT).show()
    }

    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasStoragePermission = granted
        if (granted) Toast.makeText(context, "Accès audio autorisé !", Toast.LENGTH_SHORT).show()
    }

    val manageStorageResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        hasManageStorage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        hasStoragePermission = hasManageStorage
    }

    val batteryResult = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            hasBatteryExemption = pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    // ── Layout ────────────────────────────────────────────────────────────────

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(
                colors = listOf(MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.surface)
            ))
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
                Box(modifier = Modifier.size(12.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary))
                Spacer(modifier = Modifier.width(8.dp))
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
                            onRequestNotification = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else hasNotificationPermission = true
                            },
                            onRequestStorage = {
                                when {
                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                        try {
                                            manageStorageResult.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                            })
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
                                        batteryResult.launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        })
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
                            }
                        )
                        2 -> SlideReady()
                    }
                }
            }

            // Navigation
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                // Page dots
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

                // Next / Start button
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
                        containerColor = if (currentPage == 2) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        contentColor   = MaterialTheme.colorScheme.onPrimary
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
                        contentDescription = null
                    )
                }

                // Skip (only on permissions page)
                if (currentPage == 1) {
                    TextButton(onClick = { currentPage++ }) {
                        Text("Passer pour l'instant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Sync, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Synchronisation Silencieuse & Automatique",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "CallSync surveille votre dossier audio et envoie chaque enregistrement vers le serveur dès qu'il est créé — en tâche de fond, sans aucune action de votre part.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeaturePill(icon = Icons.Default.Bolt, text = "Auto-upload")
            FeaturePill(icon = Icons.Default.Shield, text = "Discret")
            FeaturePill(icon = Icons.Default.Wifi, text = "Temps réel")
        }
    }
}

@Composable
private fun FeaturePill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
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
    onRequestNotification: () -> Unit,
    onRequestStorage: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestManageAll: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Autorisations Requises",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Ces autorisations garantissent un fonctionnement 24h/24 sans interruption par le système.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, bottom = 4.dp)
        )

        PermissionItemCard(
            title       = "Notifications (service permanent)",
            description = "Affiche la notification de fond qui empêche Android de stopper le scanner.",
            isGranted   = hasNotification,
            onClick     = onRequestNotification,
            icon        = Icons.Default.Notifications
        )

        PermissionItemCard(
            title       = "Accès aux fichiers audio",
            description = "Lecture des enregistrements (.m4a, .mp3, .wav…) pour les envoyer au serveur.",
            isGranted   = hasStorage,
            onClick     = onRequestStorage,
            icon        = Icons.Default.FolderOpen
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            PermissionItemCard(
                title       = "Accès à tous les fichiers (Android 11+)",
                description = "Nécessaire pour lire les enregistrements dans des dossiers tiers (MIUI, Samsung, etc.).",
                isGranted   = hasManageAll,
                onClick     = onRequestManageAll,
                icon        = Icons.Default.Storage
            )
        }

        PermissionItemCard(
            title       = "Exempter de l'optimisation batterie",
            description = "Exempte CallSync d'Android Doze pour garantir l'upload même téléphone en veille.",
            isGranted   = hasBattery,
            onClick     = onRequestBattery,
            icon        = Icons.Default.BatteryChargingFull
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
            containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
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
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isGranted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isGranted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                    contentColor   = MaterialTheme.colorScheme.onPrimary
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
            modifier = Modifier.size(100.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Tout est prêt !",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "CallSync va démarrer silencieusement en arrière-plan. Vous pouvez modifier le dossier cible et l'URL du serveur à tout moment dans ⚙ Paramètres.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 24.sp
        )
        // Summary of what will happen
        SummaryCard()
    }
}

@Composable
private fun SummaryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryRow(icon = Icons.Default.FolderOpen, text = "Surveille le dossier configuré automatiquement")
            SummaryRow(icon = Icons.Default.Upload, text = "Envoie 4 fichiers en parallèle vers le serveur")
            SummaryRow(icon = Icons.Default.Download, text = "Télécharge tout automatiquement côté receveur")
            SummaryRow(icon = Icons.Default.CloudOff, text = "\"Vide Remote\" purge le serveur après vérification")
        }
    }
}

@Composable
private fun SummaryRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
