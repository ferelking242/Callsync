package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
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

    // Flows from DB
    val uploads: StateFlow<List<Upload>> = repository.allUploads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val logs: StateFlow<List<LogEntry>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Foreground service status
    val isServiceActive: StateFlow<Boolean> = CallUploadService.isRunning
    val lastUploadTime: StateFlow<Long?> = CallUploadService.lastUploadTime

    // Server connection test states
    private val _isConnecting = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isConnectionSuccessful = MutableStateFlow<Boolean?>(null)
    val isConnectionSuccessful: StateFlow<Boolean?> = _isConnectionSuccessful

    // Server Records (Viewer)
    private val _serverRecords = MutableStateFlow<List<RecordingResponse>>(emptyList())
    val serverRecords: StateFlow<List<RecordingResponse>> = _serverRecords

    private val _isRecordsLoading = MutableStateFlow(false)
    val isRecordsLoading: StateFlow<Boolean> = _isRecordsLoading

    // Player States
    private var exoPlayer: ExoPlayer? = null

    private val _currentPlayingRecord = MutableStateFlow<RecordingResponse?>(null)
    val currentPlayingRecord: StateFlow<RecordingResponse?> = _currentPlayingRecord

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    private val _playbackPosition = MutableStateFlow(0L)
    val playbackPosition: StateFlow<Long> = _playbackPosition

    private val _playbackDuration = MutableStateFlow(0L)
    val playbackDuration: StateFlow<Long> = _playbackDuration

    // Settings States
    val serverUrl = MutableStateFlow(repository.getServerUrl())
    val username = MutableStateFlow(repository.getUsername())
    val password = MutableStateFlow(repository.getPassword())
    val monitorFolder = MutableStateFlow(repository.getMonitorFolderPath())

    private var playbackProgressJob: Job? = null

    init {
        initPlayer()
        // Start background progress tracking for media player
        startPlaybackProgressTracker()
        // Attempt initial fetch if authenticated
        if (repository.getAuthToken().isNotEmpty()) {
            fetchServerRecords()
        }
    }

    private fun initPlayer() {
        if (exoPlayer == null) {
            exoPlayer = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        _isPlaying.value = playWhenReady && state == Player.STATE_READY
                        if (state == Player.STATE_ENDED) {
                            _isPlaying.value = false
                            _playbackPosition.value = 0
                        }
                        _playbackDuration.value = duration.coerceAtLeast(0L)
                    }

                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }
                })
            }
        }
    }

    private fun startPlaybackProgressTracker() {
        playbackProgressJob?.cancel()
        playbackProgressJob = viewModelScope.launch {
            while (true) {
                exoPlayer?.let { player ->
                    if (player.isPlaying) {
                        _playbackPosition.value = player.currentPosition
                        _playbackDuration.value = player.duration.coerceAtLeast(0L)
                    }
                }
                delay(250)
            }
        }
    }

    fun startService() {
        val intent = Intent(context, CallUploadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            viewModelScope.launch {
                repository.addLog("Service", "Service manual start requested.")
            }
        } catch (e: Exception) {
            viewModelScope.launch {
                repository.addLog("Service", "Failed to start service: ${e.message}", true)
            }
        }
    }

    fun stopService() {
        val intent = Intent(context, CallUploadService::class.java)
        context.stopService(intent)
        viewModelScope.launch {
            repository.addLog("Service", "Service manual stop requested.")
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _isConnecting.value = true
            _isConnectionSuccessful.value = null
            repository.addLog("API", "Testing connection...")

            val isHealthy = repository.checkServerHealth()
            if (isHealthy) {
                _isConnectionSuccessful.value = true
                repository.addLog("API", "Connection check passed.")
            } else {
                // Try logging in as fallback
                repository.addLog("API", "Health check failed. Retrying with login...")
                val isAuth = repository.authenticate()
                if (isAuth) {
                    _isConnectionSuccessful.value = true
                    repository.addLog("API", "Connection and Login authentication verified.")
                } else {
                    _isConnectionSuccessful.value = false
                    repository.addLog("API", "Connection check and Authentication failed.", true)
                }
            }
            _isConnecting.value = false
        }
    }

    fun triggerManualScan() {
        viewModelScope.launch {
            repository.addLog("Uploader", "Manual synchronisation triggered.")
            val added = repository.scanFolderManually()
            if (added > 0) {
                repository.addLog("Uploader", "Discovered $added new file(s) during manual sync.")
            } else {
                repository.addLog("Uploader", "No new call recordings found during manual sync.")
            }
            // Trigger service to start uploading immediately if it is running
            val serviceIntent = Intent(context, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    fun saveSettings(url: String, user: String, pass: String, folder: String) {
        serverUrl.value = url
        username.value = user
        password.value = pass
        monitorFolder.value = folder

        repository.setServerUrl(url)
        repository.setUsername(user)
        repository.setPassword(pass)
        repository.setMonitorFolderPath(folder)

        viewModelScope.launch {
            repository.addLog("Settings", "Settings updated successfully.")
            // Re-auth with new settings
            testConnection()
        }
    }

    fun fetchServerRecords() {
        viewModelScope.launch {
            _isRecordsLoading.value = true
            repository.addLog("Viewer", "Retrieving server records...")
            val records = repository.getServerRecords()
            if (records != null) {
                _serverRecords.value = records
                repository.addLog("Viewer", "Retrieved ${records.size} recording(s) from server.")
            } else {
                repository.addLog("Viewer", "Failed to load records from server.", true)
            }
            _isRecordsLoading.value = false
        }
    }

    fun deleteRecording(record: RecordingResponse) {
        viewModelScope.launch {
            repository.addLog("Viewer", "Deleting recording: ${record.name}")
            val success = repository.deleteServerRecord(record.id)
            if (success) {
                repository.addLog("Viewer", "Successfully deleted record: ${record.name}")
                if (_currentPlayingRecord.value?.id == record.id) {
                    stopPlayback()
                }
                fetchServerRecords()
            } else {
                repository.addLog("Viewer", "Failed to delete record ${record.name}", true)
            }
        }
    }

    // Audio Playback
    fun playRecording(record: RecordingResponse) {
        initPlayer()
        val token = repository.getAuthToken()
        val baseUrl = repository.getServerUrl()
        val streamUrl = "${baseUrl}stream/${record.id}"

        viewModelScope.launch {
            repository.addLog("Viewer", "Streaming audio for: ${record.name} - URL: $streamUrl")
        }

        try {
            _currentPlayingRecord.value = record
            
            // Build ProgressiveMediaSource using custom header for JWT Authorization
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setDefaultRequestProperties(mapOf("Authorization" to token))

            val mediaSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(streamUrl))

            exoPlayer?.let { player ->
                player.stop()
                player.setMediaSource(mediaSource)
                player.prepare()
                player.playWhenReady = true
                _isPlaying.value = true
            }
        } catch (e: Exception) {
            viewModelScope.launch {
                repository.addLog("Viewer", "Playback preparation failed: ${e.message}", true)
            }
        }
    }

    fun togglePlayback() {
        exoPlayer?.let { player ->
            if (player.isPlaying) {
                player.pause()
                _isPlaying.value = false
            } else {
                player.play()
                _isPlaying.value = true
            }
        }
    }

    fun seekTo(position: Long) {
        exoPlayer?.let { player ->
            player.seekTo(position)
            _playbackPosition.value = position
        }
    }

    fun stopPlayback() {
        exoPlayer?.stop()
        _currentPlayingRecord.value = null
        _isPlaying.value = false
        _playbackPosition.value = 0L
        _playbackDuration.value = 0L
    }

    // Helper to generate a dummy local call recording for easy user testing
    fun generateDummyCallRecording() {
        viewModelScope.launch {
            val folderPath = repository.getMonitorFolderPath()
            val folder = File(folderPath)
            if (!folder.exists()) {
                folder.mkdirs()
            }
            val timestamp = System.currentTimeMillis()
            val file = File(folder, "Call_Recording_$timestamp.mp3")
            
            // Write some empty dummy bytes
            withContext(Dispatchers.IO) {
                val outputStream = FileOutputStream(file)
                outputStream.write("MOCK_AUDIO_DATA_FOR_TESTING_CALLSYNC_PROJECT".toByteArray())
                outputStream.flush()
                outputStream.close()
            }
            
            repository.addLog("Uploader", "Generated sandbox dummy recording: ${file.name}")
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearLogs()
        }
    }

    fun clearAllUploads() {
        viewModelScope.launch {
            repository.clearUploads()
        }
    }

    override fun onCleared() {
        super.onCleared()
        playbackProgressJob?.cancel()
        exoPlayer?.release()
        exoPlayer = null
    }
}
