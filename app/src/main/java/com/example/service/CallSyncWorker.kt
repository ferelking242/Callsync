package com.example.service

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.repository.CallSyncRepository

class CallSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = CallSyncRepository(applicationContext)
        repository.addLog("Worker", "WorkManager background sync triggered")

        try {
            // 1. Scan folder for any offline-created recordings
            val added = repository.scanFolderManually()
            if (added > 0) {
                repository.addLog("Worker", "WorkManager discovered $added unrecorded files")
            }

            // 2. Try to start the service to ensure persistent tracking is alive
            val serviceIntent = Intent(applicationContext, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }

            return Result.success()
        } catch (e: Exception) {
            repository.addLog("Worker", "WorkManager background job failed: ${e.message}", true)
            return Result.retry()
        }
    }
}
