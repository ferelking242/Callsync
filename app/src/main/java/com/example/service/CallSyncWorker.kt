package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * WorkManager periodic worker — runs every 15 min as a reliability backstop.
 * Ensures the foreground service is alive even if the alarm was missed.
 */
class CallSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = com.example.data.repository.CallSyncRepository(applicationContext)
        repository.addLog("Worker", "WorkManager heartbeat — ensuring service is running")

        return try {
            // Scan for any new files created while service was offline
            val added = repository.scanFolderManually()
            if (added > 0) {
                repository.addLog("Worker", "WorkManager discovered $added new file(s)")
            }

            // Restart the foreground service if it is not running
            val serviceIntent = Intent(applicationContext, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            Result.success()
        } catch (e: Exception) {
            repository.addLog("Worker", "WorkManager job failed: ${e.message}", true)
            Result.retry()
        }
    }
}
