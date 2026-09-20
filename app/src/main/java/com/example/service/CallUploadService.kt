package com.example.service

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
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class CallUploadService : Service() {

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: CallSyncRepository
    private var wakeLock: PowerManager.WakeLock? = null

    private val fileObservers = mutableListOf<CustomFileObserver>()
    private val observedDirectories = mutableSetOf<String>()
    private var uploadJob:   Job? = null
    private var watchdogJob: Job? = null
    private var backgroundPumpJob: Job? = null
    private var lastNotificationText: String? = null
    private var lastNotificationAt = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val CHANNEL_ID            = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID       = 1001
        private const val WORK_NAME_PERIODIC    = "CallSyncPeriodicWorker"
        private const val WORK_NAME_IMMEDIATE   = "CallSyncImmediateWorker"
        private const val WATCHDOG_INTERVAL_MS  = 15 * 60 * 1_000L  // 15 min watchdog coroutine
        private const val BACKGROUND_PUMP_INTERVAL_MS = 30 * 1_000L
        // Wake lock renouvelé 2 min avant expiry (toutes les 28 min sur une durée de 30)
        private const val WAKELOCK_DURATION_MS  = 30 * 60 * 1_000L
        private const val WAKELOCK_RENEW_MS     = 28 * 60 * 1_000L

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
        startForegroundCompat("Démarrage…")

        serviceScope.launch {
            repository.addLog("Service", "Démarrage de la synchronisation serveur…")
            updateNotification("Surveillance active")
            repository.resetStuckUploads()
            val found = repository.scanFolderIncremental()
            if (found > 0) updateNotification("$found fichier(s) indexé(s)…")
            repository.queueIndexedFilesForServer()
            repository.autoConnectIfNeeded()
            repository.uploadPendingFiles()
        }

        startMonitoring()
        startBackgroundPump()
        startWatchdog()
        startWakeLockRenewer()

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // The periodic WorkManager job remains the battery-friendly recovery
        // path. Exact alarms are intentionally avoided.
        triggerExpeditedWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        unregisterNetworkCallback()
        watchdogJob?.cancel()
        backgroundPumpJob?.cancel()
        serviceJob.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        isRunning.value = false
        triggerExpeditedWorker()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Wake lock ─────────────────────────────────────────────────────────────
    // Durée 30 min, renouvelé toutes les 28 min → jamais expiré pendant un batch d'uploads

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallSync::UploadWakeLock"
            ).apply { acquire(WAKELOCK_DURATION_MS) }
        } catch (_: Exception) {}
    }

    /** Coroutine qui renouvelle le wake lock 2 min avant son expiry. */
    private fun startWakeLockRenewer() {
        serviceScope.launch {
            while (true) {
                delay(WAKELOCK_RENEW_MS)
                acquireWakeLock()
            }
        }
    }

    // ── Network callback ──────────────────────────────────────────────────────

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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

    // ── FileObserver ──────────────────────────────────────────────────────────

    private fun startMonitoring() {
        stopObservers()
        val rootPath   = repository.getMonitorFolderPath()
        if (repository.isSafFolderSelected()) {
            serviceScope.launch {
                repository.addLog(
                    "Service",
                    "Dossier SAF sélectionné — détection par scans périodiques"
                )
            }
            return
        }
        if (rootPath.isBlank()) {
            serviceScope.launch {
                repository.addLog("Service", "Aucun dossier sélectionné — utilisez Parcourir", true)
            }
            return
        }
        val rootFolder = File(rootPath).also { it.mkdirs() }
        // Observe every existing nested directory. Call recorder apps often
        // create Recordings/Call/<date>/<number>, so watching only the first
        // child directory misses later CLOSE_WRITE events.
        rootFolder.walkTopDown()
            .filter { it.isDirectory }
            .forEach { addObserver(it) }
        serviceScope.launch { repository.addLog("Service", "Surveillance: $rootPath (+sous-dossiers)") }
    }

    private fun addObserver(dir: File) {
        if (!dir.exists()) return
        val canonicalPath = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
        if (!observedDirectories.add(canonicalPath)) return
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
        observedDirectories.clear()
    }

    private suspend fun handleNewFile(file: File) {
        if (!file.exists() || !file.isFile || !repository.isAudioFile(file)) return

        repository.addLog("Service", "Nouveau fichier: ${file.name} — attente stabilisation…")

        // Attendre stabilisation (taille constante 2 checks consécutifs)
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

        val existingByPath = repository.uploadDao.getUploadByPath(file.absolutePath)
        if (existingByPath != null &&
            existingByPath.size == file.length() &&
            existingByPath.modifiedAt == file.lastModified()
        ) return

        val sha256 = repository.calculateSHA256(file)
        if (existingByPath != null && existingByPath.sha256 == sha256) {
            repository.uploadDao.updateUpload(existingByPath.copy(
                name = file.name,
                size = file.length(),
                modifiedAt = file.lastModified()
            ))
            return
        }
        if (existingByPath != null) {
            repository.uploadDao.updateUpload(
                existingByPath.copy(
                    sha256 = sha256,
                    name = file.name,
                    size = file.length(),
                    modifiedAt = file.lastModified(),
                    status = "PENDING",
                    uploadedAt = null,
                    retryCount = 0,
                    errorMessage = null,
                    nextRetryAt = 0L
                )
            )
            repository.addLog("Service", "Fichier modifié remis en queue: ${file.name}")
            triggerUploadQueue()
            return
        }
        val existingBySha = repository.uploadDao.getUploadBySha256(sha256)
        if (existingBySha != null && existingBySha.status == "COMPLETED") {
            repository.addLog("Service", "Ignoré (déjà uploadé): ${file.name}")
            return
        }

        repository.uploadDao.insertUpload(
            Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                size = file.length(), modifiedAt = file.lastModified(), status = "PENDING")
        )
        repository.addLog(
            "Service",
            "Indexé pour envoi serveur: ${file.name}"
        )
        triggerUploadQueue()
    }

    // ── Upload queue ──────────────────────────────────────────────────────────

    private fun triggerUploadQueue() {
        if (uploadJob?.isActive == true) return
        uploadJob = serviceScope.launch {
            repository.autoConnectIfNeeded()
            val uploaded = repository.uploadPendingFiles()
            updateNotification(
                if (uploaded > 0) "$uploaded fichier(s) envoyé(s) au serveur"
                else "Surveillance active — serveur configuré"
            )
        }
    }

    // ── Background upload pump ────────────────────────────────────────────────

    /**
     * SAF folders cannot be watched with FileObserver. Poll them frequently
     * while this foreground service is alive, and retry pending uploads after
     * transient network failures. The old 15-minute watchdog remains a safety
     * net, not the primary upload trigger.
     */
    private fun startBackgroundPump() {
        backgroundPumpJob?.cancel()
        backgroundPumpJob = serviceScope.launch {
            while (isActive) {
                try {
                    val found = if (repository.isSafFolderSelected()) {
                        repository.scanFolderIncremental()
                    } else {
                        0
                    }
                    if (found > 0) {
                        updateNotification("$found fichier(s) détecté(s)…")
                    }
                    repository.pollAndExecuteDeleteCommands()
                    triggerUploadQueue()
                } catch (e: Exception) {
                    repository.addLog("Background", "Cycle upload échoué: ${e.message}", true)
                }
                delay(BACKGROUND_PUMP_INTERVAL_MS)
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    // Renouveler le wake lock si nécessaire
                    if (wakeLock?.isHeld == false) acquireWakeLock()

                    // ── FileObserver sanity check ──────────────────────────────────────
                    // If the monitored folder was deleted (e.g. after a mass purge or an
                    // OEM cleanup), FileObserver silently stops receiving events.
                    // Re-create the folder and restart observers so scanning resumes.
                    if (!repository.isSafFolderSelected()) {
                        val rootFolder = File(repository.getMonitorFolderPath())
                        if (!rootFolder.exists() || fileObservers.isEmpty()) {
                            rootFolder.mkdirs()
                            startMonitoring()
                            repository.addLog(
                                "Watchdog",
                                "FileObserver redémarré (dossier recréé: ${rootFolder.path})"
                            )
                        }
                    }

                    val found = repository.scanFolderIncremental()
                    repository.autoConnectIfNeeded()
                    repository.retryFailedUploads()
                    repository.pollAndExecuteDeleteCommands()
                    triggerUploadQueue()
                    if (found > 0) repository.addLog("Watchdog", "$found nouveau(x) fichier(s)")
                } catch (e: Exception) {
                    repository.addLog("Watchdog", "Erreur: ${e.message}", true)
                }
            }
        }
    }

    // ── WorkManager : périodique + reprise immédiate ─────────────────────────

    private fun scheduleWorkManagerBackup() {
        try {
            val request = PeriodicWorkRequestBuilder<CallSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.NONE)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                WORK_NAME_PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        } catch (_: Exception) {}
    }

    /**
     * Lance un worker one-shot expedited immédiatement.
     * Utilisé après onTaskRemoved/onDestroy pour reprendre le travail ASAP
     * même si le service foreground met quelques secondes à redémarrer.
     */
    private fun triggerExpeditedWorker() {
        try {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                WORK_NAME_IMMEDIATE,
                ExistingWorkPolicy.REPLACE,
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
            val now = System.currentTimeMillis()
            if (text == lastNotificationText && now - lastNotificationAt < 30_000L) return
            lastNotificationText = text
            lastNotificationAt = now
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
                CHANNEL_ID, "CallSync (silencieux)", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Surveillance & envoi automatique des enregistrements"
                setShowBadge(false)
                // Ne pas jouer de son pour les mises à jour de statut fréquentes
                setSound(null, null)
                enableVibration(false)
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
