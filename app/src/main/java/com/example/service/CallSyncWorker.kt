package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.CallSyncRepository

/**
 * WorkManager periodic worker — filet de sécurité toutes les 15 min.
 * Garantit que le service foreground tourne même si AlarmManager a raté.
 * Utilise le scan incrémentiel pour ne vérifier que les fichiers nouveaux.
 */
class CallSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = CallSyncRepository(applicationContext)
        repository.addLog("Worker", "WorkManager heartbeat")

        return try {
            // Connexion silencieuse si le token est expiré
            repository.autoConnectIfNeeded()

            // Scan delta — seulement les fichiers créés depuis le dernier scan
            val added = repository.scanFolderIncremental()
            if (added > 0) repository.addLog("Worker", "$added nouveau(x) fichier(s) en queue")

            // Retry les fichiers en échec
            repository.retryFailedUploads()

            // Poll des ordres de suppression
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
}
