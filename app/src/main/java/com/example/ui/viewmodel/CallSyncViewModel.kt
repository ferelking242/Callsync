package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.example.data.api.RecordingResponse
import com.example.data.model.DownloadedRecord
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import com.example.data.repository.CallSyncRepository
import com.example.service.CallUploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CallSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val repository = CallSyncRepository(context)

    // ── DB flows ──────────────────────────────────────────────────────────────
    val uploads: StateFlow<List<Upload>> =
        repository.allUploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntry>> =
        repository.allLogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val downloadedRecords: StateFlow<List<DownloadedRecord>> =
        repository.allDownloadedRecords.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Service state ─────────────────────────────────────────────────────────
    val isServiceActive:  StateFlow<Boolean>  = CallUploadService.isRunning
    val lastUploadTime:   StateFlow<Long?>    = CallUploadService.lastUploadTime

    // ── Connection states ─────────────────────────────────────────────────────
    private val _isConnecting            = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isConnectionSuccessful          = MutableStateFlow<Boolean?>(null)
    val isConnectionSuccessful: StateFlow<Boolean?> = _isConnectionSuccessful

    private val _connectionError            = MutableStateFlow("")
    val connectionError: StateFlow<String>  = _connectionError

    // ── Viewer records ────────────────────────────────────────────────────────
    private val _serverRecords                    = MutableStateFlow<List<RecordingResponse>>(emptyList())
    val serverRecords: StateFlow<List<RecordingResponse>> = _serverRecords

    private val _isRecordsLoading            = MutableStateFlow(false)
    val isRecordsLoading: StateFlow<Boolean> = _isRecordsLoading

    private val _recordsError            = MutableStateFlow("")
    val recordsError: StateFlow<String>  = _recordsError

    // ── Delete state ──────────────────────────────────────────────────────────
    private val _deletingId           = MutableStateFlow<Long?>(null)
    val deletingId: StateFlow<Long?>  = _deletingId

    private val _deleteError            = MutableStateFlow("")
    val deleteError: StateFlow<String>  = _deleteError

    // ── Auto-download state ───────────────────────────────────────────────────
    private val _isAutoDownloading            = MutableStateFlow(false)
    val isAutoDownloading: StateFlow<Boolean> = _isAutoDownloading

    private val _autoDownloadProgress            = MutableStateFlow(Pair(0, 0)) // (done, total)
    val autoDownloadProgress: StateFlow<Pair<Int, Int>> = _autoDownloadProgress

    private val _autoDownloadError            = MutableStateFlow("")
    val autoDownloadError: StateFlow<String>  = _autoDownloadError

    // ── Purge remote state ────────────────────────────────────────────────────
    private val _isPurgingRemote            = MutableStateFlow(false)
    val isPurgingRemote: StateFlow<Boolean> = _isPurgingRemote

    private val _purgeResult            = MutableStateFlow<Pair<Boolean, String>?>(null)
    val purgeResult: StateFlow<Pair<Boolean, String>?> = _purgeResult

    // ── Player ────────────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null

    private val _currentPlayingRecord                      = MutableStateFlow<RecordingResponse?>(null)
    val currentPlayingRecord: StateFlow<RecordingResponse?> = _currentPlayingRecord

    private val _isPlaying            = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackPosition         = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _playbackDuration         = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration

    // ── Settings ──────────────────────────────────────────────────────────────
    val serverUrl     = MutableStateFlow(repository.getServerUrl())
    val username      = MutableStateFlow(repository.getUsername())
    val password      = MutableStateFlow(repository.getPassword())
    val monitorFolder = MutableStateFlow(repository.getMonitorFolderPath())

    private var playbackProgressJob: Job? = null

    init {
        initPlayer()
        startPlaybackProgressTracker()
        viewModelScope.launch { repository.resetStuckUploads() }
        startService()
        if (repository.getAuthToken().isNotEmpty()) {
            fetchServerRecords()
        }
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startService() {
        val intent = Intent(context, CallUploadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    // ── Connection / Auth ─────────────────────────────────────────────────────

    fun testConnection() {
        viewModelScope.launch {
            _isConnecting.value = true
            _isConnectionSuccessful.value = null
            _connectionError.value = ""

            val (reachable, reachErr) = repository.testConnection()
            if (!reachable) {
                _isConnectionSuccessful.value = false
                _connectionError.value = reachErr
                _isConnecting.value = false
                return@launch
            }

            val (authed, authErr) = repository.login()
            _isConnectionSuccessful.value = authed
            _connectionError.value = if (authed) "" else authErr
            _isConnecting.value = false

            if (authed) fetchServerRecords()
        }
    }

    // ── Server records ────────────────────────────────────────────────────────

    fun fetchServerRecords() {
        viewModelScope.launch {
            _isRecordsLoading.value = true
            _recordsError.value = ""
            val (records, error) = repository.getServerRecords()
            _serverRecords.value = records
            _recordsError.value = error
            _isRecordsLoading.value = false

            // Auto-trigger download after fetch
            if (records.isNotEmpty()) {
                autoDownloadAll()
            }
        }
    }

    fun deleteServerRecord(id: Long) {
        viewModelScope.launch {
            _deletingId.value = id
            _deleteError.value = ""
            val (ok, err) = repository.deleteRecord(id)
            _deletingId.value = null
            if (ok) {
                _serverRecords.value = _serverRecords.value.filter { it.id != id }
            } else {
                _deleteError.value = err
            }
        }
    }

    // ── Auto-download (receiver) ──────────────────────────────────────────────

    fun autoDownloadAll() {
        if (_isAutoDownloading.value) return
        viewModelScope.launch {
            _isAutoDownloading.value = true
            _autoDownloadError.value = ""
            _autoDownloadProgress.value = Pair(0, 0)

            val records = _serverRecords.value
            val (done, errors) = repository.autoDownloadAll(records) { downloaded, total ->
                _autoDownloadProgress.value = Pair(downloaded, total)
            }

            _isAutoDownloading.value = false
            if (errors > 0) {
                _autoDownloadError.value = "$errors fichier(s) n'ont pas pu être téléchargés"
            }
        }
    }

    fun downloadSingleRecord(record: RecordingResponse) {
        viewModelScope.launch {
            repository.downloadRecordLocally(record)
        }
    }

    // ── Purge remote ──────────────────────────────────────────────────────────

    /** Verifies all server records are locally present, then purges server. */
    fun purgeRemote() {
        viewModelScope.launch {
            _isPurgingRemote.value = true
            _purgeResult.value = null

            val serverRecords = _serverRecords.value
            val result = repository.purgeRemoteRecords(serverRecords)
            _purgeResult.value = result

            if (result.first) {
                // Clear local list after successful purge
                _serverRecords.value = emptyList()
            }
            _isPurgingRemote.value = false
        }
    }

    fun clearPurgeResult() {
        _purgeResult.value = null
    }

    // ── Local downloads management ────────────────────────────────────────────

    fun deleteLocalDownload(recordId: Long) {
        viewModelScope.launch { repository.deleteLocalDownload(recordId) }
    }

    fun purgeAllLocalDownloads() {
        viewModelScope.launch { repository.purgeAllLocalDownloads() }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    fun saveSettings(url: String, user: String, pass: String, folder: String) {
        repository.setServerUrl(url)
        repository.setUsername(user)
        repository.setPassword(pass)
        repository.setMonitorFolderPath(folder)
        repository.setAuthToken("")

        serverUrl.value     = repository.getServerUrl()
        username.value      = user
        password.value      = pass
        monitorFolder.value = folder

        _isConnectionSuccessful.value = null
        _connectionError.value = ""
    }

    // ── Upload controls ───────────────────────────────────────────────────────

    fun scanNow() {
        viewModelScope.launch {
            val n = repository.scanFolderManually()
            repository.addLog("Scanner", "Manual scan: $n new file(s)")
            startService()
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            repository.retryFailedUploads()
            startService()
        }
    }

    fun clearAllLogs()    { viewModelScope.launch { repository.clearLogs() } }
    fun clearAllUploads() { viewModelScope.launch { repository.clearUploads() } }

    // ── Player ────────────────────────────────────────────────────────────────

    private fun initPlayer() {
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _isPlaying.value = false
                        _playbackPosition.value = 0L
                    }
                }
                override fun onIsPlayingChanged(playing: Boolean) {
                    _isPlaying.value = playing
                }
            })
        }
    }

    private fun startPlaybackProgressTracker() {
        playbackProgressJob = viewModelScope.launch {
            while (true) {
                delay(300)
                val player = exoPlayer ?: continue
                if (player.isPlaying) {
                    _playbackPosition.value = player.currentPosition
                    _playbackDuration.value = player.duration.coerceAtLeast(0L)
                }
            }
        }
    }

    fun playRecord(record: RecordingResponse) {
        // Prefer local file if available
        viewModelScope.launch {
            val localEntry = withContext(Dispatchers.IO) {
                repository.downloadedRecordDao.getByRecordId(record.id)
            }

            val localFile = localEntry?.let { File(it.localPath) }
            if (localFile != null && localFile.exists()) {
                // Play from local storage (no network needed)
                exoPlayer?.let { player ->
                    player.stop()
                    player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(localFile)))
                    player.prepare()
                    player.play()
                    _currentPlayingRecord.value = record
                    _isPlaying.value = true
                    _playbackPosition.value = 0L
                }
            } else {
                // Stream from server
                val serverUrl = repository.getServerUrl().trimEnd('/')
                val token = repository.getAuthToken()
                val streamUrl = "$serverUrl/stream/${record.id}"

                val dataSourceFactory = DefaultHttpDataSource.Factory()
                    .setDefaultRequestProperties(mapOf("Authorization" to "Bearer $token"))

                val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(streamUrl))

                exoPlayer?.let { player ->
                    player.stop()
                    player.setMediaSource(mediaSource)
                    player.prepare()
                    player.play()
                    _currentPlayingRecord.value = record
                    _isPlaying.value = true
                    _playbackPosition.value = 0L
                }
            }
        }
    }

    fun togglePlayPause() { exoPlayer?.let { if (it.isPlaying) it.pause() else it.play() } }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
        _playbackPosition.value = positionMs
    }

    fun stopPlayback() {
        exoPlayer?.stop()
        _currentPlayingRecord.value = null
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _playbackDuration.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        playbackProgressJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
