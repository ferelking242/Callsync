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

    companion object {
        private const val CHANNEL_ID      = "CallSyncServiceChannel"
        private const val NOTIFICATION_ID = 1001
        private const val RESTART_ACTION  = "com.example.RESTART_SERVICE"
        private const val WORK_NAME       = "CallSyncPeriodicWorker"

        // Intervalle watchdog : re-connexion + scan delta toutes les 15 min
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1_000L

        val isRunning      = MutableStateFlow(false)
        val lastUploadTime = MutableStateFlow<Long?>(null)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        repository = CallSyncRepository(this)
        isRunning.value = true
        createNotificationChannel()
        acquireWakeLock()
        scheduleWorkManagerBackup()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        serviceScope.launch {
            // 1. Connexion automatique au serveur + refresh cache SHA256 (silencieux)
            repository.addLog("Service", "Démarrage — connexion automatique au serveur…")
            repository.autoConnectIfNeeded()

            // 2. Réinitialiser les uploads bloqués en UPLOADING (crash précédent)
            repository.resetStuckUploads()

            // 3. Scan incrémentiel — seulement les fichiers nouveaux depuis le dernier scan
            //    Premier démarrage = scan complet ; suivants = delta uniquement
            val found = repository.scanFolderIncremental()
            if (found > 0) repository.addLog("Service", "$found nouveau(x) fichier(s) détecté(s)")

            // 4. Lancer l'upload de tout ce qui est en queue
            triggerUploadQueue()
        }

        // Surveiller le dossier en temps réel (nouveaux fichiers → index + upload immédiat)
        startMonitoring()

        // Watchdog : re-connexion + scan delta + retry périodiques
        startWatchdog()

        // START_STICKY : l'OS redémarre le service automatiquement après un kill
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App balayée du gestionnaire de tâches → alarme de redémarrage dans 3 s
        scheduleAlarmRestart(delayMs = 3_000L)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopObservers()
        watchdogJob?.cancel()
        serviceJob.cancel()
        wakeLock?.release()
        isRunning.value = false
        // Redémarrage via AlarmManager si le service est tué de façon inattendue
        scheduleAlarmRestart(delayMs = 1_500L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Wake lock — empêche le CPU de s'endormir pendant les uploads ──────────

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallSync::UploadWakeLock"
            ).apply { acquire(10 * 60 * 1_000L) } // max 10 min par acquisition
        } catch (_: Exception) {}
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

        repository.addLog("Service", "Nouveau fichier détecté: ${file.name} — attente fin d'écriture…")

        // Attendre que le fichier soit stable (taille constante pendant 2 checks)
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

        // Dédup par chemin
        if (repository.uploadDao.getUploadByPath(file.absolutePath) != null) return

        val sha256 = repository.calculateSHA256(file)

        // Dédup par SHA256 (fichier déjà uploadé sous un autre nom)
        val existingBySha = repository.uploadDao.getUploadBySha256(sha256)
        if (existingBySha != null && existingBySha.status == "COMPLETED") {
            repository.addLog("Service", "Ignoré (déjà uploadé): ${file.name}")
            return
        }

        // Déjà sur le serveur (cache SHA256)
        if (repository.isOnServer(sha256)) {
            repository.uploadDao.insertUpload(
                Upload(sha256 = sha256, path = file.absolutePath, name = file.name,
                    size = file.length(), status = "COMPLETED",
                    uploadedAt = System.currentTimeMillis())
            )
            repository.addLog("Service", "Ignoré (déjà sur serveur): ${file.name}")
            return
        }

        // Nouveau fichier — ajout à l'index et upload immédiat
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
            delay(800)
            acquireWakeLock() // renouveler le wake lock avant upload
            val uploaded = repository.uploadPendingFiles()
            repository.pollAndExecuteDeleteCommands()
            if (uploaded > 0) {
                lastUploadTime.value = System.currentTimeMillis()
                repository.addLog("Service", "$uploaded fichier(s) envoyé(s)")
                updateNotification("✓ $uploaded fichier(s) envoyé(s)")
            } else {
                updateNotification("Surveillance en cours…")
            }
        }
    }

    // ── Watchdog — re-connexion + scan delta + retry toutes les 15 min ────────

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                try {
                    // Re-connexion silencieuse si le token est expiré
                    repository.autoConnectIfNeeded()
                    // Scan delta — fichiers créés depuis le dernier scan
                    val found = repository.scanFolderIncremental()
                    // Retry les fichiers en échec
                    repository.retryFailedUploads()
                    // Upload tout ce qui est en attente
                    triggerUploadQueue()
                    if (found > 0) {
                        repository.addLog("Watchdog", "$found nouveau(x) fichier(s) détecté(s)")
                    }
                } catch (e: Exception) {
                    repository.addLog("Watchdog", "Erreur watchdog: ${e.message}", true)
                }
            }
        }
    }

    // ── AlarmManager restart (pattern ntfy) ───────────────────────────────────

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
            try {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
                val intent = Intent(this, ServiceRestartReceiver::class.java).apply { action = RESTART_ACTION }
                val pi = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
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

    // ── Notification foreground ───────────────────────────────────────────────

    private fun startForegroundCompat() {
        val notification = buildNotification("Connexion au serveur…")
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
