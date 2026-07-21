package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.model.Upload
import com.example.data.repository.CallSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class CallUploadService : Service() {

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: CallSyncRepository

    private val fileObservers = mutableListOf<CustomFileObserver>()
    private var uploadJob: Job? = null

    // Periodic retry job: every 5 min re-trigger upload for any pending/failed
    private var retryJob: Job? = null

    companion object {
        private const val CHANNEL_ID      = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID = 1001

        val isRunning      = MutableStateFlow(false)
        val lastUploadTime = MutableStateFlow<Long?>(null)
    }

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        isRunning.value = true
        createNotificationChannel()
        serviceScope.launch { repository.addLog("Service", "Foreground service created") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        serviceScope.launch {
            repository.addLog("Service", "Service started — scanning & monitoring")
            val found = repository.scanFolderManually()
            if (found > 0) repository.addLog("Service", "Initial scan: $found new file(s)")
            triggerUploadQueue()
        }
        startMonitoring()
        startPeriodicRetry()
        // Service is restarted automatically if killed (START_STICKY)
        return START_STICKY
    }

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun startMonitoring() {
        stopObservers()
        val rootPath   = repository.getMonitorFolderPath()
        val rootFolder = File(rootPath).also { it.mkdirs() }

        addObserver(rootFolder)
        rootFolder.listFiles()?.filter { it.isDirectory }?.forEach { sub -> addObserver(sub) }

        serviceScope.launch { repository.addLog("Service", "Monitoring: $rootPath (+ sub-dirs)") }
    }

    private fun addObserver(dir: File) {
        val obs = CustomFileObserver(dir.absolutePath) { fileName ->
            serviceScope.launch {
                val file = File(dir, fileName)
                handleNewFile(file)
                if (file.isDirectory) addObserver(file)
            }
        }
        obs.startWatching()
        fileObservers.add(obs)
    }

    private fun stopObservers() {
        fileObservers.forEach { it.stopWatching() }
        fileObservers.clear()
    }

    private suspend fun handleNewFile(file: File) {
        if (!file.exists() || !file.isFile || !repository.isAudioFile(file)) return

        repository.addLog("Service", "New file detected: ${file.name} — waiting for write…")

        // Wait until file write finishes (size stabilises)
        var previousSize = -1L
        var stableCount  = 0
        repeat(60) {
            val currentSize = file.length()
            if (currentSize == previousSize && currentSize > 0) {
                stableCount++
                if (stableCount >= 2) return@repeat
            } else {
                stableCount  = 0
                previousSize = currentSize
            }
            delay(500)
        }

        if (repository.uploadDao.getUploadByPath(file.absolutePath) != null) return

        val sha256 = repository.calculateSHA256(file)
        repository.uploadDao.insertUpload(
            Upload(sha256 = sha256, path = file.absolutePath, name = file.name, size = file.length(), status = "PENDING")
        )
        repository.addLog("Service", "Queued for upload: ${file.name}")
        triggerUploadQueue()
    }

    private fun triggerUploadQueue() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            delay(800) // small debounce
            val uploaded = repository.uploadPendingFiles()
            if (uploaded > 0) {
                lastUploadTime.value = System.currentTimeMillis()
                repository.addLog("Service", "Uploaded $uploaded file(s)")
                updateNotification("✓ $uploaded fichier(s) envoyé(s)")
            }
        }
    }

    /** Retry pending/failed uploads every 5 minutes */
    private fun startPeriodicRetry() {
        retryJob?.cancel()
        retryJob = serviceScope.launch {
            while (true) {
                delay(5 * 60 * 1000L)
                val pending = repository.uploadDao.getPendingUploads()
                if (pending.isNotEmpty()) {
                    repository.addLog("Service", "Periodic retry: ${pending.size} file(s) pending")
                    triggerUploadQueue()
                }
            }
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Surveillance en cours…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } catch (_: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CallSync Monitor", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Surveillance & envoi automatique des enregistrements"
                setShowBadge(false)
            }
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        retryJob?.cancel()
        serviceJob.cancel()
        isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── FileObserver wrapper ──────────────────────────────────────────────────

    private class CustomFileObserver(path: String, private val onCreated: (String) -> Unit)
        : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null) onCreated(path)
        }
    }
}
