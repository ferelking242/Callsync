package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Starts CallUploadService on every boot, package replacement, and update.
 * android:directBootAware="true" so it also fires on LOCKED_BOOT_COMPLETED
 * before the user unlocks — compatible with Direct Boot on Android 7+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received: $action")

        val serviceIntent = Intent(context, CallUploadService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Log.d("BootReceiver", "CallUploadService started after: $action")
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service: ${e.message}")
        }
    }
}
