package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.Upload
import com.example.ui.viewmodel.CallSyncViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun UploaderScreen(
    viewModel: CallSyncViewModel,
    modifier: Modifier = Modifier
) {
    val uploads         by viewModel.uploads.collectAsState()
    val isServiceActive by viewModel.isServiceActive.collectAsState()
    val lastUploadTime  by viewModel.lastUploadTime.collectAsState()
    val isConnecting    by viewModel.isConnecting.collectAsState()
    val connOk          by viewModel.isConnectionSuccessful.collectAsState()
    val connError       by viewModel.connectionError.collectAsState()

    val completed = remember(uploads) { uploads.count { it.status == "COMPLETED" } }
    val pending   = remember(uploads) { uploads.count { it.status == "PENDING" } }
    val failed    = remember(uploads) { uploads.count { it.status == "FAILED" } }
    val uploading = remember(uploads) { uploads.count { it.status == "UPLOADING" } }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Status row ─────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ServiceStatusCard(modifier = Modifier.weight(1f), isActive = isServiceActive)
                ConnectionStatusCard(
                    modifier     = Modifier.weight(1f),
                    isConnecting = isConnecting,
                    connOk       = connOk,
                    onTest       = { viewModel.testConnection() }
                )
            }
        }

        // ── Connection error ────────────────────────────────────────────────
        if (connError.isNotEmpty()) {
            item { UploaderErrorBanner(message = connError) }
        }

        // ── Upload in-progress banner ───────────────────────────────────────
        if (uploading > 0) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape    = RoundedCornerShape(12.dp),
                    colors   = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Column {
                            Text(
                                "Upload en cours — $uploading fichier(s)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Envoi parallèle (4 simultanés max)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }
        }

        // ── Stats row ───────────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChip(
                    modifier = Modifier.weight(1f),
                    label    = "Envoyés",
                    value    = "$completed",
                    color    = MaterialTheme.colorScheme.secondary
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    label    = "En attente",
                    value    = "$pending",
                    color    = MaterialTheme.colorScheme.primary
                )
                StatChip(
                    modifier = Modifier.weight(1f),
                    label    = "Erreurs",
                    value    = "$failed",
                    color    = MaterialTheme.colorScheme.error
                )
            }
        }

        // ── Quick actions ───────────────────────────────────────────────────
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick  = { viewModel.scanNow() },
                    modifier = Modifier.weight(1f),
                    enabled  = uploading == 0
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Scanner", style = MaterialTheme.typography.labelMedium)
                }
                if (failed > 0) {
                    OutlinedButton(
                        onClick  = { viewModel.retryFailed() },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Réessayer", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ── Delete all local files ──────────────────────────────────────────
        if (uploads.isNotEmpty()) {
            item {
                DeleteAllLocalButton(viewModel = viewModel)
            }
        }

        // ── Last upload time ────────────────────────────────────────────────
        if (lastUploadTime != null) {
            item {
                val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.CheckCircle, null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        "Dernier envoi : ${fmt.format(Date(lastUploadTime!!))}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        // ── File list header ────────────────────────────────────────────────
        if (uploads.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Fichiers indexés (${uploads.size})",
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier   = Modifier.padding(top = 4.dp)
                    )
                    TextButton(
                        onClick  = { viewModel.clearAllUploads() },
                        modifier = Modifier.testTag("clear_db_uploads_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Vider l'index", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            items(uploads, key = { it.id }) { upload ->
                UploadItemRow(upload = upload)
            }
        } else {
            item { EmptyUploaderState() }
        }

        // ── Clear logs ──────────────────────────────────────────────────────
        item {
            TextButton(
                onClick  = { viewModel.clearAllLogs() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.ClearAll, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Effacer les logs", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun ServiceStatusCard(modifier: Modifier, isActive: Boolean) {
    val containerColor = if (isActive) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surfaceVariant
    val contentColor   = if (isActive) MaterialTheme.colorScheme.secondary
                         else MaterialTheme.colorScheme.onSurfaceVariant
    Card(
        modifier = modifier,
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(contentColor))
            Column {
                Text(
                    if (isActive) "Actif" else "Inactif",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    "Moniteur",
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
        isConnecting    -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary, "Test…")
        connOk == true  -> Triple(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.secondary, "Connecté")
        connOk == false -> Triple(MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.error, "Erreur")
        else            -> Triple(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, "Tester")
    }
    Card(
        modifier = modifier,
        onClick  = onTest,
        enabled  = !isConnecting,
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        shape    = RoundedCornerShape(12.dp)
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
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(contentColor))
            }
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = contentColor)
                Text("Serveur", style = MaterialTheme.typography.labelSmall, color = contentColor.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun UploaderErrorBanner(message: String) {
    Surface(
        shape    = RoundedCornerShape(8.dp),
        color    = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                Icons.Default.ErrorOutline, null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp).padding(top = 1.dp)
            )
            Text(
                message,
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
        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape    = RoundedCornerShape(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun UploadItemRow(upload: Upload) {
    val (dotColor, statusText) = when (upload.status) {
        "COMPLETED" -> Pair(MaterialTheme.colorScheme.secondary, "Envoyé ✓")
        "UPLOADING" -> Pair(MaterialTheme.colorScheme.primary,   "Envoi…")
        "FAILED"    -> Pair(MaterialTheme.colorScheme.error,     "Erreur")
        else        -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "En attente")
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                upload.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (upload.status == "FAILED" && upload.errorMessage != null) {
                Text(
                    upload.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(
            statusText,
            style = MaterialTheme.typography.labelSmall,
            color = dotColor,
            fontWeight = FontWeight.SemiBold
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
}

@Composable
private fun EmptyUploaderState() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            Icons.Default.Sync, null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Text(
            "Aucun fichier indexé",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Configurez le dossier dans ⚙ Paramètres\nou attendez la détection automatique",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

// ── Delete all local files button ─────────────────────────────────────────────

@Composable
private fun DeleteAllLocalButton(viewModel: CallSyncViewModel) {
    val isDeletingAll by viewModel.isDeletingAll.collectAsState()
    val deleteAllResult by viewModel.deleteAllResult.collectAsState()
    var showConfirm by remember { mutableStateOf(false) }

    // Show result snackbar-style
    if (deleteAllResult != null) {
        LaunchedEffect(deleteAllResult) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearDeleteAllResult()
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape    = RoundedCornerShape(10.dp),
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CheckCircle, null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.secondary)
                Text("$deleteAllResult fichier(s) supprimé(s) du dossier + index vidé",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    OutlinedButton(
        onClick  = { showConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        enabled  = !isDeletingAll,
        colors   = ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.error
        ),
        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        if (isDeletingAll) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.error
            )
        } else {
            Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (isDeletingAll) "Suppression…" else "Supprimer tout le dossier + index",
            style = MaterialTheme.typography.labelMedium
        )
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Supprimer tous les fichiers ?") },
            text  = {
                Text(
                    "Tous les enregistrements dans le dossier surveillé seront " +
                    "définitivement effacés et l'index sera vidé.\n\n" +
                    "Les fichiers déjà envoyés au serveur ne seront pas affectés."
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirm = false; viewModel.deleteAllLocal() },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Tout supprimer") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Annuler") }
            }
        )
    }
}
