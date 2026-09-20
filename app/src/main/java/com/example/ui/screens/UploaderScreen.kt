package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.service.CallUploadService
import com.example.ui.viewmodel.CallSyncViewModel

@Composable
fun UploaderScreen(
    viewModel: CallSyncViewModel,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {}
) {
    val uploads by viewModel.uploads.collectAsState()
    val isActive by viewModel.isServiceActive.collectAsState()
    val isOnline by CallUploadService.isOnline.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val scanMessage by viewModel.scanMessage.collectAsState()
    val failed = uploads.count { it.status == "FAILED" }
    val pending = uploads.count { it.status == "PENDING" || it.status == "UPLOADING" }
    val completed = uploads.count { it.status == "COMPLETED" }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Default.CloudUpload, contentDescription = null)
                        Column {
                            Text("Envoi automatique", fontWeight = FontWeight.Bold)
                            Text(
                                if (isActive) "Surveillance active" else "En attente du service",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Text(
                        "Les nouveaux fichiers audio sont détectés, stabilisés puis envoyés seuls au serveur.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = if (isOnline) Icons.Default.CloudUpload else Icons.Default.CloudOff,
                    title = if (isOnline) "En ligne" else "Hors ligne",
                    subtitle = "Réseau"
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Folder,
                    title = "$pending en attente",
                    subtitle = "$completed envoyés"
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = if (failed == 0) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    title = "$failed erreur(s)",
                    subtitle = "Reprise automatique"
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.scanNow() },
                    enabled = !isScanning,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text(if (isScanning) "Scan…" else "Scanner")
                }
                OutlinedButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Text("Paramètres")
                }
            }
        }
        if (scanMessage.isNotBlank()) {
            item {
                Text(
                    scanMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (failed > 0) {
            item {
                Button(onClick = { viewModel.retryFailed() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Réessayer les erreurs")
                }
            }
        }
        item {
            Text("Derniers fichiers", fontWeight = FontWeight.Bold)
        }
        items(uploads.take(50), key = { it.id }) { upload ->
            UploadRow(upload.name, upload.status, upload.errorMessage)
        }
    }
}

@Composable
private fun StatusCard(
    modifier: Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String
) {
    Card(modifier = modifier) {
        Column(Modifier.padding(10.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UploadRow(name: String, status: String, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            Text(
                when (status) {
                    "COMPLETED" -> "Envoyé"
                    "UPLOADING" -> "Envoi en cours"
                    "PENDING" -> "En attente"
                    else -> "Échec${if (!error.isNullOrBlank()) ": $error" else ""}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (status == "FAILED") MaterialTheme.colorScheme.error
                else Color.Unspecified
            )
        }
    }
}