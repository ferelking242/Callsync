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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.PowerManager
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
    private var wakeLock: PowerManager.WakeLock? = null

    private val fileObservers = mutableListOf<CustomFileObserver>()
    private var uploadJob:   Job? = null
    private var watchdogJob: Job? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val CHANNEL_ID           = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID      = 1001
        private const val RESTART_ACTION       = "com.example.RESTART_SERVICE"
        private const val WORK_NAME            = "CallSyncPeriodicWorker"
        private const val HEARTBEAT_REQUEST    = 9001  // PendingIntent request code for heartbeat
        private const val HEARTBEAT_INTERVAL_MS = 2 * 60 * 1_000L   // 2 min heartbeat alarm
        private const val WATCHDOG_INTERVAL_MS  = 15 * 60 * 1_000L  // 15 min watchdog coroutine

        val isRunning      = MutableStateFlow(false)
        val lastUploadTime = MutableStateFlow<Long?>(null)
        val isOnline       = MutableStateFlow(false)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        isRunning.value = true
        createNotificationChannel()
        acquireWakeLock()
        scheduleWorkManagerBackup()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Show notification immediately so foreground is satisfied before any async work
        startForegroundCompat("Démarrage…")

        // Renew the 2-min heartbeat alarm — ensures we restart even if killed without callbacks
        scheduleHeartbeatAlarm()

        serviceScope.launch {
            // 1. Connexion auto + refresh cache SHA256
            repository.addLog("Service", "Démarrage — connexion automatique…")
            repository.autoConnectIfNeeded()
            updateNotification("Surveillance active")

            // 2. Réinitialiser les uploads bloqués en UPLOADING (crash précédent)
            repository.resetStuckUploads()

            // 3. Scan incrémentiel (delta depuis le dernier scan)
            val found = repository.scanFolderIncremental()
            if (found > 0) updateNotification("$found fichier(s) en queue…")

            // 4. Upload immédiat si réseau disponible
            triggerUploadQueue()
        }

        startMonitoring()
        startWatchdog()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Swipe depuis le gestionnaire — programmer un redémarrage très rapide (500 ms)
        scheduleRestartAlarm(requestCode = 9002, delayMs = 500L)
        // Garder aussi le heartbeat normal
        scheduleHeartbeatAlarm()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        unregisterNetworkCallback()
        watchdogJob?.cancel()
        serviceJob.cancel()
        wakeLock?.release()
        isRunning.value = false
        // Redémarrage immédiat
        scheduleRestartAlarm(requestCode = 9003, delayMs = 500L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Wake lock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.release()
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallSync::UploadWakeLock"
            ).apply { acquire(10 * 60 * 1_000L) }
        } catch (_: Exception) {}
    }

    // ── Network callback — déclenche l'upload dès que le réseau revient ───────

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                // Init de l'état actuel
                isOnline.value = isNetworkAvailable()

                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        isOnline.value = true
                        serviceScope.launch {
                            repository.addLog("Réseau", "Connexion disponible — reprise uploads")
                            repository.autoConnectIfNeeded()
                            updateNotification("Surveillance active")
                            triggerUploadQueue()
                        }
                    }
                    override fun onLost(network: Network) {
                        isOnline.value = false
                        updateNotification("Hors ligne — en attente de connexion")
                    }
                }

                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, networkCallback!!)
            } catch (_: Exception) {}
        } else {
            isOnline.value = isNetworkAvailable()
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
        } catch (_: Exception) {}
        networkCallback = null
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val net  = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    // ── FileObserver — nouveaux fichiers en temps réel ────────────────────────

    private fun startMonitoring() {
        stopObservers()
        val rootPath   = repository.getMonitorFolderPath()
        val rootFolder = File(rootPath).also { it.mkdirs() }
        addObserver(rootFolder)
        rootFolder.listFiles()?.filter { it.isDirectory }?.forEach { sub -> addObserver(sub) }
        serviceScope.launch { repository.addLog("Service", "Surveillance: $rootPath (+sous-dossiers)") }
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

        repository.addLog("Service", "Nouveau fichier: ${file.name} — attente stabilisation…")

        // Attendre stabilisation (taille constante 2 checks)
        var previousSize = -1L
        var stableCount  = 0
        run stabilityCheck@{
            repeat(120) {
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

        if (repository.uploadDao.getUploadByPath(file.absolutePath) != null) return

        val sha256 = repository.calculateSHA256(file)
        val existingBySha = repository.uploadDao.getUploadBySha256(sha256)
        if (existingBySha != null && existingBySha.status == "COMPLETED") {
            repository.addLog("Service", "Ignoré (déjà uploadé): ${file.name}")
            return
        }

        if (repository.isOnServer(sha256)) {
            repository.uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "COMPLETED",
                    uploadedAt = System.currentTimeMillis())
            )
            repository.addLog("Service", "Ignoré (serveur): ${file.name}")
            return
        }

        repository.uploadDao.insertUpload(
            Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                size = file.length(), status = "PENDING")
        )
        repository.addLog("Service", "Mis en queue: ${file.name}")
        triggerUploadQueue()
    }

    // ── Upload queue ──────────────────────────────────────────────────────────

    private fun triggerUploadQueue() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            delay(300)

            // Ne pas tenter si hors ligne
            if (!isNetworkAvailable()) {
                repository.addLog("Uploader", "Hors ligne — upload différé")
                updateNotification("Hors ligne — en attente de connexion")
                return@launch
            }

            acquireWakeLock()

            val uploaded = repository.uploadPendingFiles()
            repository.pollAndExecuteDeleteCommands()

            if (uploaded > 0) {
                lastUploadTime.value = System.currentTimeMillis()
                repository.addLog("Service", "$uploaded fichier(s) envoyé(s)")
                updateNotification("✓ $uploaded fichier(s) envoyé(s)")
            } else {
                updateNotification("Surveillance active")
            }
        }
    }

    // ── Watchdog — reconnexion + scan delta + retry toutes les 15 min ────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    repository.autoConnectIfNeeded()
                    val found = repository.scanFolderIncremental()
                    repository.retryFailedUploads()
                    repository.pollAndExecuteDeleteCommands()
                    triggerUploadQueue()
                    // Renouveler le heartbeat
                    scheduleHeartbeatAlarm()
                    if (found > 0) repository.addLog("Watchdog", "$found nouveau(x) fichier(s)")
                } catch (e: Exception) {
                    repository.addLog("Watchdog", "Erreur: ${e.message}", true)
                }
            }
        }
    }

    // ── Heartbeat alarm (2 min) — redémarre même si killed sans callback ──────

    /**
     * Planifie une alarme dans 2 min qui appellera startForegroundService().
     * Renouvelée à chaque onStartCommand → boucle auto-réparatrice.
     * Si le process est tué sans onDestroy/onTaskRemoved, l'alarme redémarre le service.
     */
    private fun scheduleHeartbeatAlarm() {
        scheduleRestartAlarm(requestCode = HEARTBEAT_REQUEST, delayMs = HEARTBEAT_INTERVAL_MS)
    }

    private fun scheduleRestartAlarm(requestCode: Int, delayMs: Long) {
        try {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ServiceRestartReceiver::class.java).apply {
                action = RESTART_ACTION
            }
            val pi = PendingIntent.getBroadcast(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = SystemClock.elapsedRealtime() + delayMs
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (_: Exception) {
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(this, ServiceRestartReceiver::class.java).apply { action = RESTART_ACTION }
                val pi = PendingIntent.getBroadcast(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE)
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 10_000L, pi)
            } catch (_: Exception) {}
        }
    }

    // ── WorkManager backup (toutes les 15 min) ────────────────────────────────

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

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
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
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (_: Exception) {}
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

    // ── FileObserver ──────────────────────────────────────────────────────────

    private class CustomFileObserver(path: String, private val onCreated: (String) -> Unit)
        : FileObserver(path, CREATE or MOVED_TO or CLOSE_WRITE) {
        override fun onEvent(event: Int, path: String?) {
            if (path != null) onCreated(path)
        }
    }
}
