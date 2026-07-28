package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.LogEntry
import com.example.data.model.Upload
import com.example.data.repository.CallSyncRepository
import com.example.service.CallUploadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CallSyncViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    val repository = CallSyncRepository(context)

    // ── DB flows ──────────────────────────────────────────────────────────────
    val uploads: StateFlow<List<Upload>> =
        repository.allUploads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val logs: StateFlow<List<LogEntry>> =
        repository.allLogs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Service state ─────────────────────────────────────────────────────────
    val isServiceActive: StateFlow<Boolean> = CallUploadService.isRunning
    val lastUploadTime:  StateFlow<Long?>   = CallUploadService.lastUploadTime

    // ── Connection states ─────────────────────────────────────────────────────
    private val _isConnecting            = MutableStateFlow(false)
    val isConnecting: StateFlow<Boolean> = _isConnecting

    private val _isConnectionSuccessful              = MutableStateFlow<Boolean?>(null)
    val isConnectionSuccessful: StateFlow<Boolean?> = _isConnectionSuccessful

    private val _connectionError           = MutableStateFlow("")
    val connectionError: StateFlow<String> = _connectionError

    // ── Settings ──────────────────────────────────────────────────────────────
    val serverUrl     = MutableStateFlow(repository.getServerUrl())
    val username      = MutableStateFlow(repository.getUsername())
    val password      = MutableStateFlow(repository.getPassword())
    val monitorFolder = MutableStateFlow(repository.getMonitorFolderPath())

    init {
        viewModelScope.launch { repository.resetStuckUploads() }
        startService()
    }

    // ── Service control ───────────────────────────────────────────────────────

    fun startService() {
        val intent = Intent(context, CallUploadService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            context.startForegroundService(intent)
        else
            context.startService(intent)
    }

    // ── Connection ────────────────────────────────────────────────────────────

    fun testConnection() {
        viewModelScope.launch {
            _isConnecting.value          = true
            _isConnectionSuccessful.value = null
            _connectionError.value       = ""

            val (reachable, reachErr) = repository.testConnection()
            if (!reachable) {
                _isConnectionSuccessful.value = false
                _connectionError.value       = reachErr
                _isConnecting.value          = false
                return@launch
            }

            val (authed, authErr) = repository.login()
            _isConnectionSuccessful.value = authed
            _connectionError.value       = if (authed) "" else authErr
            _isConnecting.value          = false

            if (authed) {
                // Refresh server SHA256 cache for dedup
                repository.refreshServerSha256Cache()
            }
        }
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
        _connectionError.value       = ""

        // Restart service with new folder
        startService()
    }

    fun autoDetectFolder() {
        viewModelScope.launch {
            val detected = repository.autoDetectCallRecordingsFolder()
            if (detected.isNotEmpty()) {
                repository.setMonitorFolderPath(detected)
                monitorFolder.value = detected
                repository.addLog("Settings", "Auto-détection: $detected")
                startService()
            }
        }
    }

    // ── Upload controls ───────────────────────────────────────────────────────

    fun scanNow() {
        viewModelScope.launch {
            val n = repository.scanFolderManually()
            repository.addLog("Scanner", "Scan manuel: $n nouveau(x) fichier(s)")
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

    // ── Delete all local files + index ────────────────────────────────────────

    private val _deleteAllResult           = MutableStateFlow<Int?>(null)
    val deleteAllResult: StateFlow<Int?>   = _deleteAllResult
    private val _isDeletingAll             = MutableStateFlow(false)
    val isDeletingAll: StateFlow<Boolean>  = _isDeletingAll

    fun deleteAllLocal() {
        viewModelScope.launch {
            _isDeletingAll.value = true
            _deleteAllResult.value = null
            val deleted = repository.deleteAllLocalFilesAndIndex()
            _deleteAllResult.value = deleted
            _isDeletingAll.value = false
        }
    }

    fun clearDeleteAllResult() { _deleteAllResult.value = null }
}
