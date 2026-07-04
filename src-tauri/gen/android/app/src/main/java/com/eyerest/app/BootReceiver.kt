package com.eyerest.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts the screen-timer service after the device finishes booting,
 * so the reminder runs by default without the user opening the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            val serviceIntent = Intent(context, ScreenTimerService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}
