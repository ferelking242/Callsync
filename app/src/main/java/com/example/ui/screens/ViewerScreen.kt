package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.api.RecordingResponse
import com.example.ui.viewmodel.CallSyncViewModel
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(viewModel: CallSyncViewModel) {
    val serverRecords by viewModel.serverRecords.collectAsState()
    val isRecordsLoading by viewModel.isRecordsLoading.collectAsState()
    
    val currentPlayingRecord by viewModel.currentPlayingRecord.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackPosition by viewModel.playbackPosition.collectAsState()
    val playbackDuration by viewModel.playbackDuration.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("date") } // "date", "taille", "nom"
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    // Fetch records initially on screen display
    LaunchedEffect(Unit) {
        viewModel.fetchServerRecords()
    }

    // Filter and Sort recordings
    val filteredRecords = remember(serverRecords, searchQuery, sortBy) {
        serverRecords.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            it.deviceId.contains(searchQuery, ignoreCase = true)
        }.sortedWith { a, b ->
            when (sortBy) {
                "nom" -> a.name.compareTo(b.name, ignoreCase = true)
                "taille" -> b.size.compareTo(a.size) // descending size
                else -> b.uploadDate.compareTo(a.uploadDate) // descending date (default)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentPlayingRecord != null) 100.dp else 0.dp) // Leave space for player bar
        ) {
            // Header Search & Sort Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Rechercher un appel...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Rechercher") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Effacer")
                            }
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("search_recordings_input"),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors()
                )

                // Sort Button
                Box {
                    IconButton(
                        onClick = { isSortMenuExpanded = true },
                        modifier = Modifier.testTag("sort_menu_button")
                    ) {
                        Icon(Icons.Default.Sort, contentDescription = "Trier")
                    }
                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Trier par date (récent)") },
                            onClick = {
                                sortBy = "date"
                                isSortMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.CalendarToday, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Trier par taille") },
                            onClick = {
                                sortBy = "taille"
                                isSortMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.SdStorage, null) }
                        )
                        DropdownMenuItem(
                            text = { Text("Trier par nom") },
                            onClick = {
                                sortBy = "nom"
                                isSortMenuExpanded = false
                            },
                            leadingIcon = { Icon(Icons.Default.SortByAlpha, null) }
                        )
                    }
                }

                // Refresh Button
                IconButton(
                    onClick = { viewModel.fetchServerRecords() },
                    modifier = Modifier.testTag("refresh_records_button")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                }
            }

            // Loader or empty states
            if (isRecordsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (filteredRecords.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Vide",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                        Text(
                            text = "Aucun enregistrement trouvé",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "Assurez-vous que l'uploader a envoyé des fichiers et que le serveur fonctionne.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.align(Alignment.CenterHorizontally)
                        )
                    }
                }
            } else {
                // List of Records
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("recordings_list"),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredRecords) { record ->
                        RecordingItemRow(
                            record = record,
                            isCurrentPlaying = currentPlayingRecord?.id == record.id,
                            isPlaying = isPlaying,
                            onPlayClick = {
                                if (currentPlayingRecord?.id == record.id) {
                                    viewModel.togglePlayback()
                                } else {
                                    viewModel.playRecording(record)
                                }
                            },
                            onDeleteClick = { viewModel.deleteRecording(record) }
                        )
                    }
                }
            }
        }

        // Persistent sliding player bar at the bottom
        AnimatedVisibility(
            visible = currentPlayingRecord != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPlayingRecord?.let { record ->
                PlayerBottomBar(
                    record = record,
                    isPlaying = isPlaying,
                    playbackPosition = playbackPosition,
                    playbackDuration = playbackDuration,
                    onPlayPauseClick = { viewModel.togglePlayback() },
                    onSeek = { viewModel.seekTo(it) },
                    onCloseClick = { viewModel.stopPlayback() }
                )
            }
        }
    }
}

@Composable
fun RecordingItemRow(
    record: RecordingResponse,
    isCurrentPlaying: Boolean,
    isPlaying: Boolean,
    onPlayClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlayClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentPlaying) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon Action (Play/Pause indicator)
            IconButton(
                onClick = onPlayClick,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = if (isCurrentPlaying && isPlaying) Icons.Default.PauseCircleFilled else Icons.Default.PlayCircleFilled,
                    contentDescription = "Écouter",
                    tint = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Metadata Detail
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Appareil: ${record.deviceId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatFileSize(record.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = "Enregistré le: ${formatDateString(record.creationDate)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            // Action Row
            Row {
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Supprimer",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun PlayerBottomBar(
    record: RecordingResponse,
    isPlaying: Boolean,
    playbackPosition: Long,
    playbackDuration: Long,
    onPlayPauseClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onCloseClick: () -> Unit
) {
    Surface(
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(115.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = "Streaming",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Streaming en direct (Sans compression)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause"
                    )
                }

                IconButton(onClick = onCloseClick) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Seek slider row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = formatProgressTime(playbackPosition),
                    style = MaterialTheme.typography.labelSmall
                )

                Slider(
                    value = playbackPosition.toFloat(),
                    onValueChange = { onSeek(it.toLong()) },
                    valueRange = 0f..(playbackDuration.toFloat().coerceAtLeast(1f)),
                    modifier = Modifier.weight(1f)
                )

                Text(
                    text = formatProgressTime(playbackDuration),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// Helpers
fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}

fun formatDateString(dateStr: String): String {
    return try {
        // Parse "2026-07-20T07:49:52-07:00" style or plain milliseconds
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(dateStr)
        if (date != null) {
            SimpleDateFormat("HH:mm dd/MM/yyyy", Locale.getDefault()).format(date)
        } else {
            dateStr
        }
    } catch (e: Exception) {
        dateStr
    }
}

fun formatProgressTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
