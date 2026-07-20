package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.repository.CallSyncRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received action: $action")
        
        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.MY_PACKAGE_REPLACED" == action) {
            val repository = CallSyncRepository(context)
            CoroutineScope(Dispatchers.IO).launch {
                repository.addLog("Service", "BootReceiver triggered. Starting CallUploadService automatically.")
            }

            val serviceIntent = Intent(context, CallUploadService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("BootReceiver", "Failed to start service on boot: ${e.message}")
            }
        }
    }
}
