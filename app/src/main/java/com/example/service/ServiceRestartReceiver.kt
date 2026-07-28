package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Catches the AlarmManager alarm scheduled in CallUploadService.onDestroy().
 * This ensures the service restarts even if it is killed by the OS or the user.
 * Pattern inspired by ntfy (github.com/binwiederhier/ntfy).
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ServiceRestartReceiver", "Alarm received — restarting CallUploadService")
        try {
            val serviceIntent = Intent(context, CallUploadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("ServiceRestartReceiver", "Failed to restart service: ${e.message}")
        }
    }
}
