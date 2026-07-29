package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.example.data.repository.CallSyncRepository

/**
 * WorkManager worker — deux rôles :
 *
 * 1. PÉRIODIQUE (15 min) : filet de sécurité, s'assure que le service foreground tourne.
 * 2. EXPEDITED (one-shot) : déclenché immédiatement après onTaskRemoved / onDestroy.
 *    Android 12+ : s'exécute même en battery saver grâce à getForegroundInfo().
 *    Pattern utilisé par Signal, Nextcloud, Syncthing pour reprendre en arrière-plan
 *    sans attendre le prochain cycle WorkManager.
 */
class CallSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID    = "CallSyncWorkerChannel"
        private const val NOTIFICATION_ID = 1002
    }

    /**
     * Requis pour les workers expedited sur Android 12+.
     * WorkManager affiche cette notification si le worker tourne en foreground.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("CallSync")
            .setContentText("Reprise des uploads…")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val repository = CallSyncRepository(applicationContext)
        repository.addLog("Worker", "WorkManager heartbeat")

        return try {
            repository.autoConnectIfNeeded()

            val added = repository.scanFolderIncremental()
            if (added > 0) repository.addLog("Worker", "$added nouveau(x) fichier(s) en queue")

            repository.retryFailedUploads()
            repository.uploadPendingFiles()
            repository.pollAndExecuteDeleteCommands()

            // S'assurer que le service foreground est vivant
            val serviceIntent = Intent(applicationContext, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Result.success()
        } catch (e: Exception) {
            repository.addLog("Worker", "WorkManager job échoué: ${e.message}", true)
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "CallSync Worker", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
