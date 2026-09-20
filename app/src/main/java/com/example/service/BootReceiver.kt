package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager

/** Starts the uploader after boot, unlock, and package replacement. */
class BootReceiver : BroadcastReceiver() {
    companion object {
        private const val WORK_NAME_BOOT_RECOVERY = "CallSyncBootRecovery"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received: $action")

        try {
            val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME_BOOT_RECOVERY,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.d("BootReceiver", "Recovery job queued after: $action")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to queue recovery job: ${e.message}")
        }
    }
}
