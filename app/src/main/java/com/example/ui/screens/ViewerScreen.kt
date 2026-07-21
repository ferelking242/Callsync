package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.api.RecordingResponse
import com.example.ui.viewmodel.CallSyncViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ViewerScreen(viewModel: CallSyncViewModel) {
    val serverRecords     by viewModel.serverRecords.collectAsState()
    val isRecordsLoading  by viewModel.isRecordsLoading.collectAsState()
    val recordsError      by viewModel.recordsError.collectAsState()
    val currentPlaying    by viewModel.currentPlayingRecord.collectAsState()
    val isPlaying         by viewModel.isPlaying.collectAsState()
    val playbackPosition  by viewModel.playbackPosition.collectAsState()
    val playbackDuration  by viewModel.playbackDuration.collectAsState()
    val deletingId        by viewModel.deletingId.collectAsState()
    val deleteError       by viewModel.deleteError.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortBy      by remember { mutableStateOf("date") }
    var confirmDeleteRecord by remember { mutableStateOf<RecordingResponse?>(null) }

    LaunchedEffect(Unit) { viewModel.fetchServerRecords() }

    val filtered = remember(serverRecords, searchQuery, sortBy) {
        serverRecords
            .filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.deviceId.contains(searchQuery, ignoreCase = true)
            }
            .sortedWith { a, b ->
                when (sortBy) {
                    "nom"    -> a.name.compareTo(b.name, ignoreCase = true)
                    "taille" -> b.size.compareTo(a.size)
                    else     -> b.creationDate.compareTo(a.creationDate)
                }
            }
    }

    // ── Delete confirmation dialog ────────────────────────────────────────────
    confirmDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmDeleteRecord = null },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Supprimer l'enregistrement ?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cette action est irréversible — le fichier sera supprimé du serveur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteServerRecord(record.id)
                        if (currentPlaying?.id == record.id) viewModel.stopPlayback()
                        confirmDeleteRecord = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmDeleteRecord = null }) { Text("Annuler") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentPlaying != null) 88.dp else 0.dp)
        ) {
            // ── Toolbar ──────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Rechercher…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Clear, contentDescription = "Effacer", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("search_recordings_input"),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    // Refresh
                    IconButton(
                        onClick = { viewModel.fetchServerRecords() },
                        enabled = !isRecordsLoading
                    ) {
                        if (isRecordsLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                        }
                    }
                }

                // Sort chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("date" to "Date", "nom" to "Nom", "taille" to "Taille").forEach { (key, label) ->
                        FilterChip(
                            selected = sortBy == key,
                            onClick = { sortBy = key },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${filtered.size} enreg.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
            }

            // ── Error banner ─────────────────────────────────────────────────
            if (recordsError.isNotEmpty()) {
                ErrorBanner(
                    message = recordsError,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
            }
            if (deleteError.isNotEmpty()) {
                ErrorBanner(
                    message = "Suppression échouée : $deleteError",
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            // ── List ─────────────────────────────────────────────────────────
            if (filtered.isEmpty() && !isRecordsLoading) {
                ViewerEmptyState(
                    hasError = recordsError.isNotEmpty(),
                    onRefresh = { viewModel.fetchServerRecords() }
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filtered, key = { it.id }) { record ->
                        RecordingCard(
                            record = record,
                            isPlaying = currentPlaying?.id == record.id && isPlaying,
                            isCurrentlyPlaying = currentPlaying?.id == record.id,
                            isDeleting = deletingId == record.id,
                            onPlay = {
                                if (currentPlaying?.id == record.id) {
                                    viewModel.togglePlayPause()
                                } else {
                                    viewModel.playRecord(record)
                                }
                            },
                            onDelete = { confirmDeleteRecord = record }
                        )
                    }
                }
            }
        }

        // ── Persistent player bar ─────────────────────────────────────────────
        AnimatedVisibility(
            visible = currentPlaying != null,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPlaying?.let { record ->
                PlayerBar(
                    record = record,
                    isPlaying = isPlaying,
                    position = playbackPosition,
                    duration = playbackDuration,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onStop = { viewModel.stopPlayback() },
                    onSeek = { viewModel.seekTo(it) }
                )
            }
        }
    }
}

// ── Recording card ────────────────────────────────────────────────────────────

@Composable
private fun RecordingCard(
    record: RecordingResponse,
    isPlaying: Boolean,
    isCurrentlyPlaying: Boolean,
    isDeleting: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (isCurrentlyPlaying)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentlyPlaying) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Play button
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Lire",
                    modifier = Modifier.size(20.dp),
                    tint = if (isCurrentlyPlaying) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatDateString(record.creationDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(record.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (record.deviceId.isNotEmpty()) {
                    Text(
                        text = record.deviceId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Delete button
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// ── Player bar ────────────────────────────────────────────────────────────────

@Composable
private fun PlayerBar(
    record: RecordingResponse,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onSeek: (Long) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Play/Pause
                IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                // Title
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                // Stop
                IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Arrêter", modifier = Modifier.size(18.dp))
                }
            }

            // Seek bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(formatProgressTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = position.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(duration.toFloat().coerceAtLeast(1f)),
                    modifier = Modifier.weight(1f)
                )
                Text(formatProgressTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Empty / Error states ──────────────────────────────────────────────────────

@Composable
private fun ViewerEmptyState(hasError: Boolean, onRefresh: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (hasError) Icons.Default.CloudOff else Icons.Default.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (hasError) "Impossible de charger les enregistrements" else "Aucun enregistrement sur le serveur",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (hasError) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Réessayer")
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(text = message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
        }
    }
}

// ── Formatters ────────────────────────────────────────────────────────────────

fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

fun formatDateString(dateStr: String): String {
    return try {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(dateStr.take(19))
        if (date != null) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date) else dateStr
    } catch (_: Exception) { dateStr }
}

fun formatProgressTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
