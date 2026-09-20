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
import com.example.update.UpdateManager
import com.example.update.UpdateState
import kotlinx.coroutines.delay
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

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _scanMessage = MutableStateFlow("")
    val scanMessage: StateFlow<String> = _scanMessage

    private val _scanError = MutableStateFlow(false)
    val scanError: StateFlow<Boolean> = _scanError

    // ── Auto-update ───────────────────────────────────────────────────────────
    private val updateManager = UpdateManager(context)
    val updateState: StateFlow<UpdateState> = updateManager.state

    // ── Settings ──────────────────────────────────────────────────────────────
    val serverUrl     = MutableStateFlow(repository.getServerUrl())
    val username      = MutableStateFlow(repository.getUsername())
    val password      = MutableStateFlow(repository.getPassword())
    val monitorFolder = MutableStateFlow(repository.getMonitorFolderPath())

    init {
        viewModelScope.launch { repository.resetStuckUploads() }
        startService()
        // Check for update on launch (delayed to not block startup)
        viewModelScope.launch {
            delay(5_000)
            val result = updateManager.checkForUpdate()
            if (result is UpdateState.Available) {
                updateManager.showUpdateAvailableNotification(result.version)
            }
        }
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

    fun saveSettings(
        url: String,
        user: String,
        pass: String,
        folder: String,
    ) {
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

        viewModelScope.launch {
            repository.queueIndexedFilesForServer()
            repository.autoConnectIfNeeded()
            repository.uploadPendingFiles()
        }
    }

    // ── Upload controls ───────────────────────────────────────────────────────

    fun scanNow() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanError.value = false
            val folderPath = repository.getMonitorFolderPath()
            _scanMessage.value = if (folderPath.isBlank()) {
                "Choisissez d’abord un dossier avec Parcourir…"
            } else {
                "Analyse du dossier sélectionné…"
            }
            try {
                val result = repository.scanFolderManually()
                repository.addLog(
                    "Scanner",
                    "Scan manuel: ${result.totalAudioFiles} trouvé(s), " +
                        "${result.newlyIndexed} nouveau(x), " +
                        "${result.alreadyIndexed} déjà indexé(s)"
                )
                _scanMessage.value = when {
                    !repository.isSafFolderSelected() ->
                        "Aucun dossier SAF sélectionné : utilisez Parcourir dans les paramètres"
                    result.totalAudioFiles == 0 ->
                        "Scan terminé : aucun fichier audio trouvé"
                    result.newlyIndexed == 0 ->
                        "Scan terminé : ${result.totalAudioFiles} fichier(s) audio trouvé(s), déjà indexé(s)"
                    else ->
                        "Scan terminé : ${result.totalAudioFiles} trouvé(s), " +
                            "${result.newlyIndexed} ajouté(s) à la file serveur"
                }
            } catch (error: Exception) {
                val message = error.message ?: "erreur inconnue"
                repository.addLog("Scanner", "Échec du scan : $message", true)
                _scanError.value = true
                _scanMessage.value = "Échec du scan : $message"
            } finally {
                _isScanning.value = false
            }
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
            // Restart service so FileObserver re-attaches to the (now-recreated) folder
            startService()
        }
    }

    fun clearDeleteAllResult() { _deleteAllResult.value = null }

    // ── Update ────────────────────────────────────────────────────────────────

    fun checkUpdateManually() {
        viewModelScope.launch { updateManager.checkForUpdate() }
    }

    fun downloadAndInstallUpdate(downloadUrl: String) {
        viewModelScope.launch {
            updateManager.downloadAndInstall(downloadUrl)
            updateManager.dismissDownloadNotification()
        }
    }
}
