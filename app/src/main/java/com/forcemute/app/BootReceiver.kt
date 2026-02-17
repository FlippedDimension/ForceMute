package com.forcemute.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Starts the MuteService automatically after the device boots,
 * but only if the user enabled "Start on boot" AND mute was active.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val startOnBoot = prefs.getBoolean(MainActivity.KEY_START_ON_BOOT, false)
        val wasMuted = prefs.getBoolean(MainActivity.KEY_IS_MUTED, false)

        if (startOnBoot || wasMuted) {
            // Mark as muted so the UI/service stay in sync
            prefs.edit().putBoolean(MainActivity.KEY_IS_MUTED, true).apply()
            ContextCompat.startForegroundService(
                context,
                Intent(context, MuteService::class.java)
            )
        }
    }
}
