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
    private var fileObserver: CustomFileObserver? = null
    private var uploadJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID = 1001

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _lastUploadTime = MutableStateFlow<Long?>(null)
        val lastUploadTime: StateFlow<Long?> = _lastUploadTime
    }

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        _isRunning.value = true
        serviceScope.launch {
            repository.addLog("Service", "Foreground service initialized.")
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceScope.launch {
            repository.addLog("Service", "Service started onStartCommand.")
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                // Fallback if dataSync is restricted
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startMonitoring()
        triggerQueueProcessing()

        return START_STICKY
    }

    private fun startMonitoring() {
        val folderPath = repository.getMonitorFolderPath()
        val folder = File(folderPath)

        if (!folder.exists()) {
            folder.mkdirs()
        }

        fileObserver?.stopWatching()
        fileObserver = CustomFileObserver(folderPath) { fileName ->
            serviceScope.launch {
                repository.addLog("Service", "FileObserver event detected for: $fileName")
                handleNewFile(File(folder, fileName))
            }
        }
        fileObserver?.startWatching()

        serviceScope.launch {
            repository.addLog("Service", "Started monitoring folder: $folderPath")
        }
    }

    private suspend fun handleNewFile(file: File) {
        val ext = file.extension.lowercase()
        val supportedExtensions = listOf("mp3", "m4a", "wav", "amr", "3gp", "ogg")
        if (!supportedExtensions.contains(ext)) {
            repository.addLog("Uploader", "Skipping non-audio file detected: ${file.name}")
            return
        }

        repository.addLog("Uploader", "New file detected: ${file.name}. Waiting for write completion...")
        
        // Wait 4 seconds to allow writer to write some data
        delay(4000)

        // Verify size is stable (size does not change anymore)
        var lastSize = -1L
        var sizeIsStable = false
        var attempts = 0
        
        while (!sizeIsStable && attempts < 10) {
            val currentSize = file.length()
            if (currentSize > 0 && currentSize == lastSize) {
                sizeIsStable = true
            } else {
                lastSize = currentSize
                delay(2000)
            }
            attempts++
        }

        if (sizeIsStable && file.exists()) {
            val sha256 = repository.calculateSHA256(file)
            val existing = repository.uploadDao.getUploadByPath(file.absolutePath)
            
            if (existing == null) {
                val upload = Upload(
                    sha256 = sha256,
                    path = file.absolutePath,
                    name = file.name,
                    size = file.length(),
                    status = "PENDING"
                )
                repository.uploadDao.insertUpload(upload)
                repository.addLog("Uploader", "Saved pending upload: ${file.name} - SHA-256: ${sha256.take(10)}")
                triggerQueueProcessing()
            } else {
                repository.addLog("Uploader", "File ${file.name} already logged in database. Status: ${existing.status}")
            }
        } else {
            repository.addLog("Uploader", "File size not stable or file removed: ${file.name}", true)
        }
    }

    fun triggerQueueProcessing() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            processUploadQueue()
        }
    }

    private suspend fun processUploadQueue() {
        var pendingList = repository.uploadDao.getPendingUploads()
        
        while (pendingList.isNotEmpty()) {
            repository.addLog("Service", "Processing upload queue: ${pendingList.size} file(s) pending")
            
            for (upload in pendingList) {
                // Check if file still exists
                val file = File(upload.path)
                if (!file.exists()) {
                    repository.addLog("Uploader", "File no longer exists: ${upload.name}. Removing from queue.", true)
                    repository.uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "File deleted locally"))
                    continue
                }

                // If retry count exceeds 10, mark it as FAILED with backoff message
                if (upload.retryCount >= 10) {
                    repository.addLog("Uploader", "Max retries reached for ${upload.name}. Marked as failed.", true)
                    repository.uploadDao.updateUpload(upload.copy(status = "FAILED", errorMessage = "Max retries exceeded"))
                    continue
                }

                // Check duplicate by SHA-256 on local complete first
                val duplicateCount = repository.uploadDao.getUploadBySha256(upload.sha256)
                if (duplicateCount != null && duplicateCount.status == "COMPLETED" && duplicateCount.id != upload.id) {
                    repository.addLog("Uploader", "${upload.name} matches completed hash. Skipped.", false)
                    repository.uploadDao.updateUpload(upload.copy(status = "COMPLETED", uploadedAt = System.currentTimeMillis()))
                    continue
                }

                // Wait backoff if retry_count > 0
                if (upload.retryCount > 0) {
                    val backoffMs = (Math.pow(2.0, upload.retryCount.toDouble()) * 1000).toLong().coerceAtMost(300000) // Max 5 mins
                    repository.addLog("Service", "Applying backoff of ${backoffMs / 1000}s for retry #${upload.retryCount} of ${upload.name}")
                    delay(backoffMs)
                }

                val success = repository.uploadFile(upload)
                if (success) {
                    _lastUploadTime.value = System.currentTimeMillis()
                } else {
                    // Break loop to prevent flooding if network is completely down
                    repository.addLog("Service", "Queue upload failed for ${upload.name}. Stopping batch. Will retry later.", true)
                    break
                }
            }

            // Fetch list again to see if any new arrived or retries pending
            pendingList = repository.uploadDao.getPendingUploads()
            
            // Limit loop if they keep failing to prevent infinite tight CPU spin
            val hasActiveRetries = pendingList.any { it.retryCount < 10 }
            if (!hasActiveRetries) {
                break
            }
            delay(10000) // Sleep 10s between checks
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        fileObserver?.stopWatching()
        serviceJob.cancel()
        _isRunning.value = false
        _isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "CallSync Call Recording Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CallSync")
            .setContentText("Monitoring call recordings...")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    // Modern file observer wrapper
    private class CustomFileObserver(path: String, val onFileCreated: (String) -> Unit) :
        FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null) {
                onFileCreated(path)
            }
        }
    }
}
