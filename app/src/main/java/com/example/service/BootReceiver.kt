package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Starts the uploader after boot, unlock, and package replacement. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("BootReceiver", "Received: $action")

        try {
            val serviceIntent = Intent(context, CallUploadService::class.java)
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
