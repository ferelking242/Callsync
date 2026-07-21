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

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: CallSyncRepository

    // One observer per watched directory (root + all sub-dirs)
    private val fileObservers = mutableListOf<CustomFileObserver>()
    private var uploadJob: Job? = null

    companion object {
        private const val CHANNEL_ID       = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID  = 1001

        val isRunning     = MutableStateFlow(false)
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
            // 1. Index everything already on disk
            val found = repository.scanFolderManually()
            if (found > 0) repository.addLog("Service", "Initial scan found $found new file(s)")
            // 2. Upload anything pending
            triggerUploadQueue()
        }
        startMonitoring()
        return START_STICKY
    }

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun startMonitoring() {
        stopObservers()

        val rootPath = repository.getMonitorFolderPath()
        val rootFolder = File(rootPath).also { it.mkdirs() }

        // Watch root folder
        addObserver(rootFolder)

        // Watch existing sub-directories (phone-number folders)
        rootFolder.listFiles()?.filter { it.isDirectory }?.forEach { sub -> addObserver(sub) }

        serviceScope.launch { repository.addLog("Service", "Monitoring: $rootPath (+ sub-dirs)") }
    }

    private fun addObserver(dir: File) {
        val obs = CustomFileObserver(dir.absolutePath) { fileName ->
            serviceScope.launch {
                val file = File(dir, fileName)
                handleNewFile(file)

                // If a new sub-directory appeared, watch it too
                if (file.isDirectory) {
                    addObserver(file)
                }
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
        if (!file.exists() || !file.isFile) return
        if (!repository.isAudioFile(file)) return

        repository.addLog("Service", "New file detected: ${file.name} — waiting for write…")

        // Wait until file size stabilises (write finished)
        var previousSize = -1L
        var stableCount  = 0
        repeat(30) {
            val currentSize = file.length()
            if (currentSize == previousSize && currentSize > 0) {
                stableCount++
                if (stableCount >= 2) return@repeat
            } else {
                stableCount = 0
                previousSize = currentSize
            }
            delay(500)
        }

        val existing = repository.uploadDao.getUploadByPath(file.absolutePath)
        if (existing != null) return

        val sha256 = repository.calculateSHA256(file)
        repository.uploadDao.insertUpload(
            Upload(
                sha256  = sha256,
                path    = file.absolutePath,
                name    = file.name,
                size    = file.length(),
                status  = "PENDING"
            )
        )
        repository.addLog("Service", "Queued for upload: ${file.name}")
        triggerUploadQueue()
    }

    private fun triggerUploadQueue() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            delay(1000) // small debounce
            val uploaded = repository.uploadPendingFiles()
            if (uploaded > 0) {
                lastUploadTime.value = System.currentTimeMillis()
                repository.addLog("Service", "Uploaded $uploaded file(s)")
                updateNotification("Last upload: $uploaded file(s) sent")
            }
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Monitoring call recordings…")
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
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CallSync Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NotificationManager::class.java))?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        serviceJob.cancel()
        isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── FileObserver wrapper ──────────────────────────────────────────────────

    private class CustomFileObserver(
        path: String,
        private val onCreated: (String) -> Unit
    ) : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null) onCreated(path)
        }
    }
}
