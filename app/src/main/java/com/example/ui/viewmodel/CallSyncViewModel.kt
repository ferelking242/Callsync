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
import java.io.FileOutputStream

class CallSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val repository = CallSyncRepository(context)

    // ── DB flows ──────────────────────────────────────────────────────────────
    val uploads: StateFlow<List<Upload>> = repository.allUploads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntry>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Service state ─────────────────────────────────────────────────────────
    val isServiceActive: StateFlow<Boolean> = CallUploadService.isRunning
    val lastUploadTime: StateFlow<Long?> = CallUploadService.lastUploadTime

    // ── Connection states ─────────────────────────────────────────────────────
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    /** null = not tested yet, true = ok, false = error */
    private val _isConnectionSuccessful = MutableStateFlow<Boolean?>(null)
    val isConnectionSuccessful: StateFlow<Boolean?> = _isConnectionSuccessful

    private val _connectionError = MutableStateFlow("")
    val connectionError: StateFlow<String> = _connectionError

    // ── Viewer records ────────────────────────────────────────────────────────
    private val _serverRecords = MutableStateFlow<List<RecordingResponse>>(emptyList())
    val serverRecords: StateFlow<List<RecordingResponse>> = _serverRecords

    private val _isRecordsLoading = MutableStateFlow(false)
    val isRecordsLoading: StateFlow<Boolean> = _isRecordsLoading

    private val _recordsError = MutableStateFlow("")
    val recordsError: StateFlow<String> = _recordsError

    // ── Delete state ──────────────────────────────────────────────────────────
    private val _deletingId = MutableStateFlow<Long?>(null)
    val deletingId: StateFlow<Long?> = _deletingId

    private val _deleteError = MutableStateFlow("")
    val deleteError: StateFlow<String> = _deleteError

    // ── Sandbox ───────────────────────────────────────────────────────────────
    private val _sandboxStatus = MutableStateFlow("")
    val sandboxStatus: StateFlow<String> = _sandboxStatus

    // ── Player ────────────────────────────────────────────────────────────────
    private var exoPlayer: ExoPlayer? = null

    private val _currentPlayingRecord = MutableStateFlow<RecordingResponse?>(null)
    val currentPlayingRecord: StateFlow<RecordingResponse?> = _currentPlayingRecord

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration

    // ── Settings ──────────────────────────────────────────────────────────────
    val serverUrl   = MutableStateFlow(repository.getServerUrl())
    val username    = MutableStateFlow(repository.getUsername())
    val password    = MutableStateFlow(repository.getPassword())
    val monitorFolder = MutableStateFlow(repository.getMonitorFolderPath())

    private var playbackProgressJob: Job? = null

    init {
        initPlayer()
        startPlaybackProgressTracker()
        // Reset any stuck uploads from a previous crash
        viewModelScope.launch {
            repository.resetStuckUploads()
        }
        // Auto-start service and index files immediately
        startService()
        // Fetch server records if already authenticated
        if (repository.getAuthToken().isNotEmpty()) {
            fetchServerRecords()
        }
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startService() {
        val intent = Intent(context, CallUploadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    // ── Connection / Auth ─────────────────────────────────────────────────────

    fun testConnection() {
        viewModelScope.launch {
            _isConnecting.value = true
            _isConnectionSuccessful.value = null
            _connectionError.value = ""

            // Step 1: check reachability
            val (reachable, reachErr) = repository.testConnection()
            if (!reachable) {
                _isConnectionSuccessful.value = false
                _connectionError.value = reachErr
                _isConnecting.value = false
                return@launch
            }

            // Step 2: authenticate
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

    // ── Settings ──────────────────────────────────────────────────────────────

    fun saveSettings(url: String, user: String, pass: String, folder: String) {
        repository.setServerUrl(url)
        repository.setUsername(user)
        repository.setPassword(pass)
        repository.setMonitorFolderPath(folder)
        repository.setAuthToken("") // force re-login with new credentials

        serverUrl.value = repository.getServerUrl()
        username.value = user
        password.value = pass
        monitorFolder.value = folder

        // Reset connection state after settings change
        _isConnectionSuccessful.value = null
        _connectionError.value = ""
    }

    // ── Sandbox ───────────────────────────────────────────────────────────────

    fun generateDummyCallRecording() {
        viewModelScope.launch {
            _sandboxStatus.value = "Génération…"
            val folderPath = repository.getMonitorFolderPath()
            val folder = File(folderPath)

            withContext(Dispatchers.IO) {
                folder.mkdirs()
                val timestamp = System.currentTimeMillis()
                val file = File(folder, "Test_Call_${timestamp}.m4a")

                // Write a minimal valid M4A header so it's recognized as audio
                FileOutputStream(file).use { fos ->
                    // ftyp box — marks this as an M4A/MP4 file
                    val ftyp = byteArrayOf(
                        0,0,0,32,  // box size = 32
                        0x66,0x74,0x79,0x70, // 'ftyp'
                        0x4D,0x34,0x41,0x20, // 'M4A '
                        0,0,0,0,             // minor version
                        0x4D,0x34,0x41,0x20, // 'M4A '
                        0x6D,0x70,0x34,0x32, // 'mp42'
                        0x69,0x73,0x6F,0x6D, // 'isom'
                        0x00,0x00,0x00,0x00  // padding
                    )
                    fos.write(ftyp)
                    // Pad to make a non-zero file
                    fos.write(ByteArray(1024) { (it % 256).toByte() })
                }
                repository.addLog("Sandbox", "Created test file: ${file.name}")
            }

            // Trigger immediate scan so it gets queued
            val found = repository.scanFolderManually()
            _sandboxStatus.value = if (found > 0) "✓ Fichier créé et détecté — upload en cours" else "Fichier créé (déjà indexé)"

            // Kick off upload
            startService()

            delay(3000)
            _sandboxStatus.value = ""
        }
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

    fun clearAllLogs() {
        viewModelScope.launch { repository.clearLogs() }
    }

    fun clearAllUploads() {
        viewModelScope.launch { repository.clearUploads() }
    }

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
                delay(500)
                val player = exoPlayer ?: continue
                if (player.isPlaying) {
                    _playbackPosition.value = player.currentPosition
                    _playbackDuration.value = player.duration.coerceAtLeast(0L)
                }
            }
        }
    }

    fun playRecord(record: RecordingResponse) {
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

    fun togglePlayPause() {
        exoPlayer?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

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
