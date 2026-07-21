package com.example.ui.screens

import androidx.compose.animation.*
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
import com.example.data.api.RecordingResponse
import com.example.data.model.DownloadedRecord
import com.example.ui.viewmodel.CallSyncViewModel
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ViewerScreen(viewModel: CallSyncViewModel) {
    val serverRecords        by viewModel.serverRecords.collectAsState()
    val isRecordsLoading     by viewModel.isRecordsLoading.collectAsState()
    val recordsError         by viewModel.recordsError.collectAsState()
    val currentPlaying       by viewModel.currentPlayingRecord.collectAsState()
    val isPlaying            by viewModel.isPlaying.collectAsState()
    val playbackPosition     by viewModel.playbackPosition.collectAsState()
    val playbackDuration     by viewModel.playbackDuration.collectAsState()
    val deletingId           by viewModel.deletingId.collectAsState()
    val deleteError          by viewModel.deleteError.collectAsState()
    val downloadedRecords    by viewModel.downloadedRecords.collectAsState()
    val isAutoDownloading    by viewModel.isAutoDownloading.collectAsState()
    val autoDownloadProgress by viewModel.autoDownloadProgress.collectAsState()
    val autoDownloadError    by viewModel.autoDownloadError.collectAsState()
    val isPurgingRemote      by viewModel.isPurgingRemote.collectAsState()
    val purgeResult          by viewModel.purgeResult.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortBy      by remember { mutableStateOf("date") }
    var tab         by remember { mutableStateOf(0) } // 0=Serveur, 1=Local
    var confirmDeleteRecord  by remember { mutableStateOf<RecordingResponse?>(null) }
    var confirmDeleteLocal   by remember { mutableStateOf<DownloadedRecord?>(null) }
    var showPurgeRemoteDialog by remember { mutableStateOf(false) }
    var showPurgeLocalDialog  by remember { mutableStateOf(false) }

    val downloadedIds = remember(downloadedRecords) { downloadedRecords.map { it.recordId }.toSet() }

    LaunchedEffect(Unit) { viewModel.fetchServerRecords() }

    // Show purge result snackbar
    LaunchedEffect(purgeResult) {
        if (purgeResult != null) {
            kotlinx.coroutines.delay(3000)
            viewModel.clearPurgeResult()
        }
    }

    val filteredServer = remember(serverRecords, searchQuery, sortBy) {
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

    val filteredLocal = remember(downloadedRecords, searchQuery, sortBy) {
        downloadedRecords
            .filter { it.name.contains(searchQuery, ignoreCase = true) }
            .sortedWith { a, b ->
                when (sortBy) {
                    "nom"    -> a.name.compareTo(b.name, ignoreCase = true)
                    "taille" -> b.size.compareTo(a.size)
                    else     -> b.downloadedAt.compareTo(a.downloadedAt)
                }
            }
    }

    // ── Delete server record dialog ───────────────────────────────────────────
    confirmDeleteRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { confirmDeleteRecord = null },
            icon  = { Icon(Icons.Default.DeleteForever, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Supprimer du serveur ?") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(record.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Ce fichier sera retiré du serveur.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteServerRecord(record.id); if (currentPlaying?.id == record.id) viewModel.stopPlayback(); confirmDeleteRecord = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDeleteRecord = null }) { Text("Garder") } }
        )
    }

    // ── Delete local file dialog ──────────────────────────────────────────────
    confirmDeleteLocal?.let { local ->
        AlertDialog(
            onDismissRequest = { confirmDeleteLocal = null },
            icon  = { Icon(Icons.Default.PhoneAndroid, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Supprimer la copie locale ?") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(local.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Le fichier local sera supprimé. La version serveur reste intacte.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteLocalDownload(local.recordId); confirmDeleteLocal = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Supprimer") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmDeleteLocal = null }) { Text("Garder") } }
        )
    }

    // ── Purge remote dialog ───────────────────────────────────────────────────
    if (showPurgeRemoteDialog) {
        val serverCount = serverRecords.size
        val localCount  = serverRecords.count { r -> downloadedIds.contains(r.id) }
        val allSafe     = localCount >= serverCount && serverCount > 0

        AlertDialog(
            onDismissRequest = { showPurgeRemoteDialog = false },
            icon  = { Icon(Icons.Default.CloudOff, null, tint = if (allSafe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) },
            title = { Text("Vider le serveur ?") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Verification status
                    VerifRow(
                        label  = "Fichiers sur le serveur",
                        value  = "$serverCount",
                        ok     = serverCount > 0 || localCount == 0
                    )
                    VerifRow(
                        label  = "Copies locales présentes",
                        value  = "$localCount / $serverCount",
                        ok     = allSafe
                    )
                    HorizontalDivider()
                    if (allSafe) {
                        Text(
                            "✅ Toutes les copies sont en sécurité localement. Vous pouvez vider le serveur.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    } else if (serverCount == 0) {
                        Text(
                            "ℹ️ Le serveur est déjà vide.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            "⚠️ ${serverCount - localCount} fichier(s) pas encore téléchargés localement. Lancez le téléchargement auto d'abord.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPurgeRemoteDialog = false; viewModel.purgeRemote() },
                    enabled = allSafe,
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Vider le serveur")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPurgeRemoteDialog = false }) { Text("Annuler") }
            }
        )
    }

    // ── Purge local dialog ────────────────────────────────────────────────────
    if (showPurgeLocalDialog) {
        AlertDialog(
            onDismissRequest = { showPurgeLocalDialog = false },
            icon  = { Icon(Icons.Default.FolderDelete, null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Purger les fichiers locaux ?") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Tous les ${downloadedRecords.size} fichier(s) téléchargés localement seront supprimés de cet appareil.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Les fichiers sur le serveur restent intacts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showPurgeLocalDialog = false; viewModel.purgeAllLocalDownloads() },
                    colors  = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Tout purger") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPurgeLocalDialog = false }) { Text("Annuler") }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = if (currentPlaying != null) 88.dp else 0.dp)
        ) {

            // ── Toolbar ───────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Search + action buttons row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value       = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Rechercher…") },
                        leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Clear, null, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier  = Modifier.weight(1f).testTag("search_recordings_input"),
                        singleLine = true,
                        shape      = RoundedCornerShape(10.dp),
                        textStyle  = MaterialTheme.typography.bodySmall
                    )

                    // Refresh
                    IconButton(onClick = { viewModel.fetchServerRecords() }, enabled = !isRecordsLoading) {
                        if (isRecordsLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Default.Refresh, contentDescription = "Actualiser")
                    }

                    // ── VIDE REMOTE button ─────────────────────────────────
                    if (isPurgingRemote) {
                        Box(
                            modifier = Modifier.size(40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        FilledTonalIconButton(
                            onClick  = { showPurgeRemoteDialog = true },
                            modifier = Modifier.size(40.dp),
                            colors   = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Icon(
                                Icons.Default.CloudOff,
                                contentDescription = "Vider le serveur",
                                tint     = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Sort chips + tab switcher
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf("date" to "Date", "nom" to "Nom", "taille" to "Taille").forEach { (key, label) ->
                        FilterChip(
                            selected = sortBy == key,
                            onClick  = { sortBy = key },
                            label    = { Text(label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    val count = if (tab == 0) filteredServer.size else filteredLocal.size
                    Text(
                        "$count enreg.",
                        style  = MaterialTheme.typography.labelSmall,
                        color  = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }

                // Tab row: Serveur / Locaux
                TabRow(
                    selectedTabIndex = tab,
                    modifier = Modifier.fillMaxWidth(),
                    containerColor = Color.Transparent,
                    contentColor   = MaterialTheme.colorScheme.primary
                ) {
                    Tab(
                        selected = tab == 0,
                        onClick  = { tab = 0 },
                        text     = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Cloud, null, modifier = Modifier.size(14.dp))
                                Text("Serveur (${serverRecords.size})", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                    Tab(
                        selected = tab == 1,
                        onClick  = { tab = 1 },
                        text     = {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(14.dp))
                                Text("Locaux (${downloadedRecords.size})", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                }
            }

            // ── Auto-download progress bar ────────────────────────────────────
            AnimatedVisibility(visible = isAutoDownloading) {
                val (done, total) = autoDownloadProgress
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Téléchargement auto…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "$done / $total",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { if (total > 0) done.toFloat() / total else 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // ── Purge result banner ───────────────────────────────────────────
            purgeResult?.let { (ok, msg) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (ok) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint     = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            msg,
                            style    = MaterialTheme.typography.labelSmall,
                            color    = if (ok) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // ── Error banners ─────────────────────────────────────────────────
            if (recordsError.isNotEmpty()) {
                ViewerErrorBanner(message = recordsError, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }
            if (deleteError.isNotEmpty()) {
                ViewerErrorBanner(message = "Suppression échouée : $deleteError", modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }
            if (autoDownloadError.isNotEmpty()) {
                ViewerErrorBanner(message = autoDownloadError, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(4.dp))
            }

            // ── Content ───────────────────────────────────────────────────────
            when (tab) {
                0 -> ServerRecordsList(
                    records      = filteredServer,
                    downloadedIds = downloadedIds,
                    isLoading    = isRecordsLoading,
                    hasError     = recordsError.isNotEmpty(),
                    currentPlaying = currentPlaying,
                    isPlaying    = isPlaying,
                    deletingId   = deletingId,
                    onPlay       = { record ->
                        if (currentPlaying?.id == record.id) viewModel.togglePlayPause()
                        else viewModel.playRecord(record)
                    },
                    onDelete     = { confirmDeleteRecord = it },
                    onDownload   = { viewModel.downloadSingleRecord(it) },
                    onRefresh    = { viewModel.fetchServerRecords() },
                    onAutoDownload = { viewModel.autoDownloadAll() }
                )
                1 -> LocalRecordsList(
                    records        = filteredLocal,
                    currentPlaying = currentPlaying,
                    isPlaying      = isPlaying,
                    onPlay         = { record ->
                        // Map local → server record for the player
                        val serverRecord = serverRecords.find { it.id == record.recordId }
                            ?: RecordingResponse(
                                id = record.recordId, name = record.name, size = record.size,
                                sha256 = record.sha256, duration = 0.0,
                                uploadDate = "", creationDate = "", path = record.localPath, deviceId = ""
                            )
                        if (currentPlaying?.id == record.recordId) viewModel.togglePlayPause()
                        else viewModel.playRecord(serverRecord)
                    },
                    onDelete       = { confirmDeleteLocal = it },
                    onPurgeAll     = { showPurgeLocalDialog = true }
                )
            }
        }

        // ── Persistent player bar ─────────────────────────────────────────────
        AnimatedVisibility(
            visible  = currentPlaying != null,
            enter    = slideInVertically { it },
            exit     = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            currentPlaying?.let { record ->
                PlayerBar(
                    record   = record,
                    isPlaying = isPlaying,
                    position  = playbackPosition,
                    duration  = playbackDuration,
                    onPlayPause = { viewModel.togglePlayPause() },
                    onStop      = { viewModel.stopPlayback() },
                    onSeek      = { viewModel.seekTo(it) }
                )
            }
        }
    }
}

// ── Server records list ───────────────────────────────────────────────────────

@Composable
private fun ServerRecordsList(
    records: List<RecordingResponse>,
    downloadedIds: Set<Long>,
    isLoading: Boolean,
    hasError: Boolean,
    currentPlaying: RecordingResponse?,
    isPlaying: Boolean,
    deletingId: Long?,
    onPlay: (RecordingResponse) -> Unit,
    onDelete: (RecordingResponse) -> Unit,
    onDownload: (RecordingResponse) -> Unit,
    onRefresh: () -> Unit,
    onAutoDownload: () -> Unit
) {
    if (records.isEmpty() && !isLoading) {
        ViewerEmptyState(hasError = hasError, onRefresh = onRefresh)
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Auto-download all button
        if (records.isNotEmpty()) {
            item {
                OutlinedButton(
                    onClick   = onAutoDownload,
                    modifier  = Modifier.fillMaxWidth(),
                    shape     = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Tout télécharger localement (${records.count { !downloadedIds.contains(it.id) }} manquant(s))",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        items(records, key = { it.id }) { record ->
            RecordingCard(
                record             = record,
                isDownloadedLocally = downloadedIds.contains(record.id),
                isPlaying          = currentPlaying?.id == record.id && isPlaying,
                isCurrentlyPlaying = currentPlaying?.id == record.id,
                isDeleting         = deletingId == record.id,
                onPlay             = { onPlay(record) },
                onDelete           = { onDelete(record) },
                onDownload         = { onDownload(record) }
            )
        }
    }
}

// ── Local records list ────────────────────────────────────────────────────────

@Composable
private fun LocalRecordsList(
    records: List<DownloadedRecord>,
    currentPlaying: RecordingResponse?,
    isPlaying: Boolean,
    onPlay: (DownloadedRecord) -> Unit,
    onDelete: (DownloadedRecord) -> Unit,
    onPurgeAll: () -> Unit
) {
    if (records.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(
                "Aucun fichier téléchargé localement",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Utilisez l'onglet Serveur → Tout télécharger",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Storage info + purge all
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape  = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Storage, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${records.size} fichier(s) local",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            formatFileSize(records.sumOf { it.size }),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledTonalButton(
                        onClick = onPurgeAll,
                        colors  = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor   = MaterialTheme.colorScheme.error
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.FolderDelete, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Tout purger", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        items(records, key = { it.id }) { local ->
            LocalRecordCard(
                record             = local,
                isPlaying          = currentPlaying?.id == local.recordId && isPlaying,
                isCurrentlyPlaying = currentPlaying?.id == local.recordId,
                onPlay             = { onPlay(local) },
                onDelete           = { onDelete(local) }
            )
        }
    }
}

// ── Recording card (server) ───────────────────────────────────────────────────

@Composable
private fun RecordingCard(
    record: RecordingResponse,
    isDownloadedLocally: Boolean,
    isPlaying: Boolean,
    isCurrentlyPlaying: Boolean,
    isDeleting: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit
) {
    val containerColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.primaryContainer
                         else MaterialTheme.colorScheme.surface

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(10.dp),
        colors    = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentlyPlaying) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
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
                    text  = record.name,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatDateString(record.creationDate), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatFileSize(record.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Download status badge
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isDownloadedLocally) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                    )
                    Text(
                        if (isDownloadedLocally) "Local ✓" else "Cloud",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isDownloadedLocally) MaterialTheme.colorScheme.secondary
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }

            // Download locally button (if not yet downloaded)
            if (!isDownloadedLocally) {
                IconButton(onClick = onDownload, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Download, contentDescription = "Télécharger", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }

            // Delete button
            if (isDeleting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Supprimer", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ── Local record card ─────────────────────────────────────────────────────────

@Composable
private fun LocalRecordCard(
    record: DownloadedRecord,
    isPlaying: Boolean,
    isCurrentlyPlaying: Boolean,
    onPlay: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.secondaryContainer
                         else MaterialTheme.colorScheme.surface

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(10.dp),
        colors    = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isCurrentlyPlaying) 2.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilledIconButton(
                onClick = onPlay,
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (isCurrentlyPlaying) MaterialTheme.colorScheme.secondary
                                     else MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isCurrentlyPlaying) MaterialTheme.colorScheme.onSecondary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(record.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(formatFileSize(record.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("•", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        formatTimestamp(record.downloadedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val fileOk = File(record.localPath).exists()
                    if (!fileOk) {
                        Text("⚠ manquant", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ── Verification row helper ───────────────────────────────────────────────────

@Composable
private fun VerifRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Icon(
                if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                null,
                tint     = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(14.dp)
            )
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
        tonalElevation  = 4.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text     = record.name,
                    style    = MaterialTheme.typography.labelMedium,
                    color    = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onStop, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Arrêter", modifier = Modifier.size(18.dp))
                }
            }
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
            null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text(
            if (hasError) "Impossible de charger les enregistrements" else "Aucun enregistrement sur le serveur",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (hasError) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Réessayer")
            }
        }
    }
}

@Composable
private fun ViewerErrorBanner(message: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
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
        val sdf  = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val date = sdf.parse(dateStr.take(19))
        if (date != null) SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date) else dateStr
    } catch (_: Exception) { dateStr }
}

fun formatTimestamp(ms: Long): String =
    SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))

fun formatProgressTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}
