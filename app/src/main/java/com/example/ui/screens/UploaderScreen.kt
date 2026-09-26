package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
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
import com.example.ui.theme.StatusGreen
import com.example.ui.theme.StatusGreenSubtle
import com.example.ui.theme.StatusOrange
import com.example.ui.theme.StatusOrangeSubtle
import com.example.ui.theme.StatusRed
import com.example.ui.theme.StatusRedSubtle
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
                    subtitle = "Réseau",
                    tint = if (isOnline) StatusGreen else StatusRed,
                    containerColor = if (isOnline) StatusGreenSubtle else StatusRedSubtle
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Default.Folder,
                    title = "$pending en attente",
                    subtitle = "$completed envoyés",
                    tint = if (pending == 0) StatusGreen else StatusOrange,
                    containerColor = if (pending == 0) StatusGreenSubtle else StatusOrangeSubtle
                )
                StatusCard(
                    modifier = Modifier.weight(1f),
                    icon = if (failed == 0) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                    title = "$failed erreur(s)",
                    subtitle = if (failed == 0) "Tout est à jour" else "Reprise automatique",
                    tint = if (failed == 0) StatusGreen else StatusRed,
                    containerColor = if (failed == 0) StatusGreenSubtle else StatusRedSubtle
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
    subtitle: String,
    tint: Color,
    containerColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(tint.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UploadRow(name: String, status: String, error: String?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(name, fontWeight = FontWeight.Medium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isCompleted = status == "COMPLETED"
                val statusColor = when {
                    isCompleted -> StatusGreen
                    status == "FAILED" -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Box(
                    modifier = Modifier
                        .background(
                            if (isCompleted) StatusGreenSubtle
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        when (status) {
                            "COMPLETED" -> "Envoyé"
                            "UPLOADING" -> "Envoi en cours"
                            "PENDING" -> "En attente"
                            else -> "Échec"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
                if (status == "FAILED" && !error.isNullOrBlank()) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1
                    )
                }
            }
        }
    }
}