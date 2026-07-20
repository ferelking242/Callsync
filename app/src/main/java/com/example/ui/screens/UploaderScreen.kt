package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.viewmodel.CallSyncViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploaderScreen(
    viewModel: CallSyncViewModel,
    onNavigateToLogs: () -> Unit
) {
    val uploads by viewModel.uploads.collectAsState()
    val isServiceActive by viewModel.isServiceActive.collectAsState()
    val lastUploadTime by viewModel.lastUploadTime.collectAsState()
    val isConnecting by viewModel.isConnecting.collectAsState()
    val isConnectionSuccessful by viewModel.isConnectionSuccessful.collectAsState()

    val totalUploaded = remember(uploads) { uploads.count { it.status == "COMPLETED" } }
    val totalPending = remember(uploads) { uploads.count { it.status == "PENDING" || it.status == "FAILED" } }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Identity Header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "MODULE UPLOADER",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Surveillance et envoi automatique des enregistrements",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Service & Connection Status Indicator Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Service Status Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = if (isServiceActive) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (isServiceActive) Icons.Default.Sensors else Icons.Default.SensorsOff,
                        contentDescription = "Service State",
                        tint = if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Moniteur",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isServiceActive) "ACTIF" else "INACTIF",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = if (isServiceActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Server Connection Card
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(
                    containerColor = when (isConnectionSuccessful) {
                        true -> MaterialTheme.colorScheme.secondaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = when (isConnectionSuccessful) {
                            true -> Icons.Default.CloudQueue
                            false -> Icons.Default.CloudOff
                            else -> Icons.Default.CloudSync
                        },
                        contentDescription = "Connection State",
                        tint = when (isConnectionSuccessful) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Serveur",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = when {
                            isConnecting -> "EN COURS"
                            isConnectionSuccessful == true -> "CONNECTÉ"
                            isConnectionSuccessful == false -> "ERREUR"
                            else -> "INCONNU"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Black,
                        color = when (isConnectionSuccessful) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }

        // Stats Card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Statistiques de Synchronisation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Divider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Envoyés:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "$totalUploaded",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("En attente / Erreurs:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "$totalPending",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (totalPending > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Dernier upload:", style = MaterialTheme.typography.bodyMedium)
                    val timeText = if (lastUploadTime != null) {
                        SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(Date(lastUploadTime!!))
                    } else {
                        "Aucun"
                    }
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Appareil:", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = viewModel.repository.getDeviceName(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Controls Section
        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 8.dp)
        )

        Button(
            onClick = { viewModel.triggerManualScan() },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sync_now_button"),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(Icons.Default.Sync, contentDescription = "Sync")
            Spacer(modifier = Modifier.width(8.dp))
            Text("Synchroniser maintenant")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.testConnection() },
                modifier = Modifier
                    .weight(1f)
                    .testTag("test_connection_button")
            ) {
                Icon(Icons.Default.Power, contentDescription = "Test Connection")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Tester connexion")
            }

            Button(
                onClick = onNavigateToLogs,
                modifier = Modifier
                    .weight(1f)
                    .testTag("navigate_to_logs_button"),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
            ) {
                Icon(Icons.Default.Notes, contentDescription = "Logs")
                Spacer(modifier = Modifier.width(4.dp))
                Text("Journaux logs")
            }
        }

        // Service Start / Stop Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Service d'arrière-plan permanent",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Activer pour surveiller le dossier de manière transparente",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                Switch(
                    checked = isServiceActive,
                    onCheckedChange = { active ->
                        if (active) {
                            viewModel.startService()
                        } else {
                            viewModel.stopService()
                        }
                    },
                    modifier = Modifier.testTag("service_toggle_switch")
                )
            }
        }

        // Sandbox Helper Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BugReport,
                        contentDescription = "Test Sandbox",
                        tint = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Bac à sable / Test local",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
                
                Text(
                    text = "Générez un enregistrement d'appel fictif dans le dossier surveillé pour tester l'upload automatique en direct !",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.generateDummyCallRecording() },
                        modifier = Modifier
                            .weight(1.5f)
                            .testTag("generate_dummy_recording_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                    ) {
                        Text("Générer fichier .mp3", style = MaterialTheme.typography.labelMedium)
                    }

                    Button(
                        onClick = { viewModel.clearAllUploads() },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("clear_db_uploads_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Vider DB Uploads", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
