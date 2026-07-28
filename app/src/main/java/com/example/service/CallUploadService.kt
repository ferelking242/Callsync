package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.MainActivity
import com.example.data.model.Upload
import com.example.data.repository.CallSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CallUploadService : Service() {

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: CallSyncRepository

    private val fileObservers = mutableListOf<CustomFileObserver>()
    private var uploadJob: Job? = null
    private var retryJob: Job? = null

    companion object {
        private const val CHANNEL_ID       = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID  = 1001
        private const val RESTART_ACTION   = "com.example.RESTART_SERVICE"
        private const val WORK_NAME        = "CallSyncPeriodicWorker"

        val isRunning      = MutableStateFlow(false)
        val lastUploadTime = MutableStateFlow<Long?>(null)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        isRunning.value = true
        createNotificationChannel()
        scheduleWorkManagerBackup()
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
        // START_STICKY: OS restarts this service with null intent after kill
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App swiped from recents — schedule alarm restart in 3 s (ntfy pattern)
        scheduleAlarmRestart(delayMs = 3_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        retryJob?.cancel()
        serviceJob.cancel()
        isRunning.value = false
        // Schedule restart via AlarmManager so we survive unexpected kills
        scheduleAlarmRestart(delayMs = 1_500L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Monitoring ────────────────────────────────────────────────────────────

    private fun startMonitoring() {
        stopObservers()
        val rootPath   = repository.getMonitorFolderPath()
        val rootFolder = File(rootPath).also { it.mkdirs() }

        addObserver(rootFolder)
        rootFolder.listFiles()?.filter { it.isDirectory }?.forEach { sub -> addObserver(sub) }

        serviceScope.launch { repository.addLog("Service", "Monitoring: $rootPath (+sub-dirs)") }
    }

    private fun addObserver(dir: File) {
        if (!dir.exists()) return
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

        repository.addLog("Service", "New file: ${file.name} — waiting for write to finish…")

        // Wait until file write stabilises (size unchanged for 2 consecutive checks)
        var previousSize = -1L
        var stableCount  = 0
        run stabilityCheck@{
            repeat(120) { // max 60 seconds
                val currentSize = file.length()
                if (currentSize == previousSize && currentSize > 0) {
                    stableCount++
                    if (stableCount >= 2) return@stabilityCheck
                } else {
                    stableCount  = 0
                    previousSize = currentSize
                }
                delay(500)
            }
        }

        // Dedup by path + sha256
        if (repository.uploadDao.getUploadByPath(file.absolutePath) != null) return

        val sha256 = repository.calculateSHA256(file)

        // Already uploaded (same content, different path or re-detected)
        val existingBySha = repository.uploadDao.getUploadBySha256(sha256)
        if (existingBySha != null && existingBySha.status == "COMPLETED") {
            repository.addLog("Service", "Skipped (already uploaded): ${file.name}")
            return
        }

        // Already on server (server SHA256 cache)
        if (repository.isOnServer(sha256)) {
            repository.uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "COMPLETED",
                    uploadedAt = System.currentTimeMillis())
            )
            repository.addLog("Service", "Skipped (already on server): ${file.name}")
            return
        }

        repository.uploadDao.insertUpload(
            Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                size = file.length(), status = "PENDING")
        )
        repository.addLog("Service", "Queued for upload: ${file.name}")
        triggerUploadQueue()
    }

    // ── Upload queue ──────────────────────────────────────────────────────────

    private fun triggerUploadQueue() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            delay(800)
            val uploaded = repository.uploadPendingFiles()
            // Poll & execute any pending delete-at-source commands
            repository.pollAndExecuteDeleteCommands()
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
                delay(5 * 60 * 1_000L)
                val pending = repository.uploadDao.getPendingUploads()
                if (pending.isNotEmpty()) {
                    repository.addLog("Service", "Periodic retry: ${pending.size} file(s)")
                    triggerUploadQueue()
                }
            }
        }
    }

    // ── AlarmManager restart (ntfy self-restart pattern) ─────────────────────

    private fun scheduleAlarmRestart(delayMs: Long) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = RESTART_ACTION
            }
            val pi = PendingIntent.getBroadcast(
                this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
            // Fallback: schedule a less-exact alarm
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(this, ServiceRestartReceiver::class.java).apply { action = RESTART_ACTION }
                val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10_000L, pi)
            } catch (_: Exception) {}
        }
    }

    // ── WorkManager backup (every 15 min) ─────────────────────────────────────

    private fun scheduleWorkManagerBackup() {
        try {
            val request = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (_: Exception) {}
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Surveillance en cours…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
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
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallSync")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pi)
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

    // ── FileObserver wrapper ──────────────────────────────────────────────────

    private class CustomFileObserver(path: String, private val onCreated: (String) -> Unit)
        : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null) onCreated(path)
        }
    }
}
