package com.silentguard.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Prefs.KEY_ENABLED, false)) {
            val serviceIntent = Intent(context, SilentModeService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
