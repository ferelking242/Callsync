package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.Upload
import com.example.ui.viewmodel.CallSyncViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UploaderScreen(viewModel: CallSyncViewModel) {
    val uploads         by viewModel.uploads.collectAsState()
    val isServiceActive by viewModel.isServiceActive.collectAsState()
    val lastUploadTime  by viewModel.lastUploadTime.collectAsState()
    val isConnecting    by viewModel.isConnecting.collectAsState()
    val connOk          by viewModel.isConnectionSuccessful.collectAsState()
    val connError       by viewModel.connectionError.collectAsState()
    val sandboxStatus   by viewModel.sandboxStatus.collectAsState()

    val completed = remember(uploads) { uploads.count { it.status == "COMPLETED" } }
    val pending   = remember(uploads) { uploads.count { it.status == "PENDING" } }
    val failed    = remember(uploads) { uploads.count { it.status == "FAILED" } }
    val uploading = remember(uploads) { uploads.count { it.status == "UPLOADING" } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Status row ──────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ServiceStatusCard(
                    modifier = Modifier.weight(1f),
                    isActive = isServiceActive
                )
                ConnectionStatusCard(
                    modifier = Modifier.weight(1f),
                    isConnecting = isConnecting,
                    connOk = connOk,
                    onTest = { viewModel.testConnection() }
                )
            }
        }

        // ── Connection error ─────────────────────────────────────────────────
        if (connError.isNotEmpty()) {
            item {
                ErrorBanner(message = connError)
            }
        }

        // ── Stats row ────────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(modifier = Modifier.weight(1f), label = "Envoyés",  value = "$completed", color = MaterialTheme.colorScheme.secondary)
                StatChip(modifier = Modifier.weight(1f), label = "En attente", value = "$pending",   color = MaterialTheme.colorScheme.primary)
                StatChip(modifier = Modifier.weight(1f), label = "Erreurs",  value = "$failed",    color = MaterialTheme.colorScheme.error)
            }
        }

        // ── Quick actions ─────────────────────────────────────────────────────
        item {
            ActionRow(
                uploading = uploading > 0,
                hasFailed = failed > 0,
                onScan = { viewModel.scanNow() },
                onRetry = { viewModel.retryFailed() }
            )
        }

        // ── Last upload time ─────────────────────────────────────────────────
        if (lastUploadTime != null) {
            item {
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                Text(
                    text = "Dernier envoi : ${fmt.format(Date(lastUploadTime!!))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }

        // ── Sandbox ───────────────────────────────────────────────────────────
        item {
            SandboxCard(
                status = sandboxStatus,
                onGenerate = { viewModel.generateDummyCallRecording() }
            )
        }

        // ── Recent uploads ───────────────────────────────────────────────────
        if (uploads.isNotEmpty()) {
            item {
                Text(
                    text = "Fichiers indexés (${uploads.size})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            items(uploads.take(50), key = { it.id }) { upload ->
                UploadItemRow(upload = upload)
            }
            if (uploads.size > 50) {
                item {
                    Text(
                        text = "… et ${uploads.size - 50} autres",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            item {
                TextButton(
                    onClick = { viewModel.clearAllUploads() },
                    modifier = Modifier.testTag("clear_db_uploads_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vider l'index local", style = MaterialTheme.typography.labelSmall)
                }
            }
        } else {
            item {
                EmptyState()
            }
        }
    }
}

// ── Sub-components ──────────────────────────────────────────────────────────

@Composable
private fun ServiceStatusCard(modifier: Modifier, isActive: Boolean) {
    val containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceVariant
    val contentColor   = if (isActive) MaterialTheme.colorScheme.secondary
                         else MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(contentColor)
            )
            Column {
                Text(
                    text = if (isActive) "Actif" else "Inactif",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = "Moniteur",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ConnectionStatusCard(
    modifier: Modifier,
    isConnecting: Boolean,
    connOk: Boolean?,
    onTest: () -> Unit
) {
    val (containerColor, contentColor, label) = when {
        isConnecting -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, "Test…")
        connOk == true  -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, "Connecté")
        connOk == false -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "Erreur")
        else -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Non testé")
    }

    Card(
        modifier = modifier,
        onClick = onTest,
        enabled = !isConnecting,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(8.dp),
                    strokeWidth = 1.5.dp,
                    color = contentColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(contentColor)
                )
            }
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = "Serveur",
                    style = MaterialTheme.typography.labelSmall,
                    color = contentColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).padding(top = 1.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun StatChip(modifier: Modifier, label: String, value: String, color: Color) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionRow(uploading: Boolean, hasFailed: Boolean, onScan: () -> Unit, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onScan,
            modifier = Modifier.weight(1f),
            enabled = !uploading
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Scanner", style = MaterialTheme.typography.labelMedium)
        }
        if (hasFailed) {
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Réessayer", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SandboxCard(status: String, onGenerate: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Science,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Bac à sable",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "Génère un enregistrement .m4a fictif dans le dossier surveillé pour tester le pipeline complet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                onClick = onGenerate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("generate_dummy_recording_button"),
                enabled = status.isEmpty()
            ) {
                if (status.isNotEmpty()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(status, style = MaterialTheme.typography.labelMedium)
                } else {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Générer fichier test .m4a", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun UploadItemRow(upload: Upload) {
    val (dotColor, statusText) = when (upload.status) {
        "COMPLETED"  -> Pair(MaterialTheme.colorScheme.secondary, "Envoyé")
        "UPLOADING"  -> Pair(MaterialTheme.colorScheme.primary,   "Envoi…")
        "FAILED"     -> Pair(MaterialTheme.colorScheme.error,     "Erreur")
        else         -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "En attente")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = upload.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (upload.status == "FAILED" && upload.errorMessage != null) {
                Text(
                    text = upload.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor,
            fontWeight = FontWeight.SemiBold
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            Icons.Default.FolderOff,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Aucun fichier indexé",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Configurez le dossier dans ⚙ Paramètres\nou générez un fichier test ci-dessus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
