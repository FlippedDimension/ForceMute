package com.forcemute.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles notification button presses instantly via broadcast.
 * Sets the static isActive flag immediately, then tells the running service to sync.
 */
class MuteActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d("ForceMute", "MuteActionReceiver action=$action")

        when (action) {
            MuteService.ACTION_MUTE, MuteService.ACTION_MUTE_ALL -> {
                MuteService.isActive = true
            }
            MuteService.ACTION_UNMUTE -> {
                MuteService.isActive = false
            }
        }

        // Forward to the service for full handling
        val serviceIntent = Intent(context, MuteService::class.java).setAction(action)
        try {
            context.startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e("ForceMute", "MuteActionReceiver err", e)
        }
    }
}
