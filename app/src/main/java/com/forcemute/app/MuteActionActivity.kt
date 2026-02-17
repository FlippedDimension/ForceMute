package com.forcemute.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Invisible Activity that handles notification action button taps.
 * Launching an Activity automatically collapses the notification shade.
 * Uses Theme.NoDisplay with all animations disabled to avoid visual artifacts.
 */
class MuteActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val action = intent?.action
        Log.d("ForceMute", "MuteActionActivity action=$action")

        if (action != null) {
            // Set flag immediately
            when (action) {
                MuteService.ACTION_MUTE, MuteService.ACTION_MUTE_ALL -> {
                    MuteService.isActive = true
                }
                MuteService.ACTION_UNMUTE -> {
                    MuteService.isActive = false
                }
            }

            // Forward to service for full handling
            val serviceIntent = Intent(this, MuteService::class.java).setAction(action)
            try {
                startForegroundService(serviceIntent)
            } catch (e: Exception) {
                Log.e("ForceMute", "MuteActionActivity startForegroundService err", e)
            }
        }

        finish()
        overridePendingTransition(0, 0)
    }
}
