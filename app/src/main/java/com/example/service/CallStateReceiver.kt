package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.TelephonyManager
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Wakes CallSync around phone calls. Recorders generally finish writing just
 * after the call becomes idle, so the worker is delayed a few seconds for the
 * final file close while the foreground service keeps watching continuously.
 */
class CallStateReceiver : BroadcastReceiver() {

    companion object {
        private const val WORK_NAME = "CallSyncAfterCall"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state != TelephonyManager.EXTRA_STATE_RINGING &&
            state != TelephonyManager.EXTRA_STATE_OFFHOOK &&
            state != TelephonyManager.EXTRA_STATE_IDLE
        ) return

        startService(context)

        val delay = if (state == TelephonyManager.EXTRA_STATE_IDLE) 5L else 0L
        val request = OneTimeWorkRequestBuilder<CallSyncWorker>()
            .setInitialDelay(delay, TimeUnit.SECONDS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        try {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (_: Exception) {
            // The foreground service remains the primary real-time path.
        }
    }

    private fun startService(context: Context) {
        try {
            val serviceIntent = Intent(context, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {
            // Android may block a background FGS start; WorkManager above
            // still performs the post-call scan and upload.
        }
    }
}