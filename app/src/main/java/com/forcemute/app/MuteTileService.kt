package com.forcemute.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

/**
 * Quick Settings tile so you can toggle Force Mute directly from the
 * notification shade — no need to open the app.
 */
class MuteTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val isMuted = prefs.getBoolean(MainActivity.KEY_IS_MUTED, false)
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        if (isMuted) {
            // Kill muting instantly so any pending service poll is a no-op
            MuteService.isActive = false
            prefs.edit().putBoolean(MainActivity.KEY_IS_MUTED, false).apply()

            // Un-mute all streams
            for (stream in MainActivity.ALL_STREAMS) {
                try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) { }
                val saved = prefs.getInt("saved_vol_$stream", -1)
                if (saved >= 0) {
                    try { audioManager.setStreamVolume(stream, saved, 0) } catch (_: Exception) { }
                }
            }
            try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }

            stopService(Intent(this, MuteService::class.java))
        } else {
            // Mute: save current volumes, then start service
            val editor = prefs.edit()
            editor.putBoolean(MainActivity.KEY_IS_MUTED, true)
            for (stream in MainActivity.ALL_STREAMS) {
                editor.putInt("saved_vol_$stream", audioManager.getStreamVolume(stream))
            }
            editor.apply()
            ContextCompat.startForegroundService(this, Intent(this, MuteService::class.java))
        }

        updateTile()
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val isMuted = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(MainActivity.KEY_IS_MUTED, false)

        if (isMuted) {
            tile.state = Tile.STATE_ACTIVE
            tile.label = "Muted"
            tile.contentDescription = "Force Mute is active — tap to unmute"
        } else {
            tile.state = Tile.STATE_INACTIVE
            tile.label = "Force Mute"
            tile.contentDescription = "Tap to force mute all audio"
        }
        tile.updateTile()
    }
}
