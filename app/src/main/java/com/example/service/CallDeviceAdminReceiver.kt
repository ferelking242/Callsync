package com.example.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Device admin receiver for CallSync.
 * Being registered as a device admin prevents most OEM battery killers
 * (MIUI, One UI, etc.) from force-stopping the upload service, and blocks
 * accidental uninstall without explicitly removing admin rights first.
 *
 * No restrictive policies are used — the admin role is the only goal.
 */
class CallDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d("DeviceAdmin", "CallSync device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d("DeviceAdmin", "CallSync device admin disabled")
    }
}
