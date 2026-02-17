package com.forcemute.app

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var toggleButton: Button
    private lateinit var statusText: TextView
    private lateinit var bootSwitch: Switch
    private lateinit var captionSwitch: Switch
    private lateinit var audioManager: AudioManager

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val PHONE_STATE_PERMISSION_CODE = 1002
        const val PREFS_NAME = "ForceMutePrefs"
        const val KEY_IS_MUTED = "isMuted"
        const val KEY_START_ON_BOOT = "startOnBoot"
        const val KEY_MUTE_MIC = "muteMic"
        const val KEY_CAPTIONS = "captionsEnabled"
        const val KEY_MUTE_ALL = "muteAll"

        /** Call-related streams only (default mute mode) */
        val CALL_STREAMS = intArrayOf(
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_DTMF
        )

        /** All audio streams (mute-all mode) */
        val ALL_STREAMS = intArrayOf(
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_RING,
            AudioManager.STREAM_NOTIFICATION,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_SYSTEM,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.STREAM_DTMF
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        toggleButton = findViewById(R.id.toggleButton)
        statusText = findViewById(R.id.statusText)
        bootSwitch = findViewById(R.id.bootSwitch)
        captionSwitch = findViewById(R.id.captionSwitch)

        // ── Version display ────────────────────────────────────────────
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            findViewById<TextView>(R.id.versionText).text = "v$versionName"
        } catch (_: Exception) { }

        // ── Notification permission (Android 13+) ──────────────────────
        requestNotificationPermission()

        // ── Phone state permission (for call detection) ────────────────
        requestPhoneStatePermission()

        // ── DND access ─────────────────────────────────────────────────
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            statusText.text = "Please grant Do Not Disturb access, then return here."
            try {
                startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) { }
        }

        // ── Boot switch ────────────────────────────────────────────────
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        bootSwitch.isChecked = prefs.getBoolean(KEY_START_ON_BOOT, false)
        bootSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_START_ON_BOOT, checked).apply()
        }

        // ── Speech detection / captions switch ───────────────────────────────
        captionSwitch.isChecked = prefs.getBoolean(KEY_CAPTIONS, false)
        captionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(KEY_CAPTIONS, checked).apply()
        }

        // ── Toggle button ──────────────────────────────────────────────
        toggleButton.setOnClickListener {
            if (isMuteActive()) stopMuting() else startMuting()
            updateUI()
        }

        updateUI()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    // ── Notification permission ────────────────────────────────────────

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    private fun requestPhoneStatePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_PHONE_STATE),
                PHONE_STATE_PERMISSION_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            // If denied, the service still runs but notifications may be hidden.
            // We can inform the user but it's not fatal.
            if (grantResults.isNotEmpty() && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                statusText.text = "Notification permission denied — the mute service will still work but you won't see the notification controls."
            }
        }
    }

    // ── Mute control ───────────────────────────────────────────────────

    private fun isMuteActive(): Boolean {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_MUTED, false)
    }

    private fun startMuting() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        prefs.putBoolean(KEY_IS_MUTED, true)
        prefs.putBoolean(KEY_MUTE_ALL, false)  // default to call-only mute
        // Save current volumes so we can restore later
        for (stream in ALL_STREAMS) {
            prefs.putInt("saved_vol_$stream", audioManager.getStreamVolume(stream))
        }
        prefs.apply()

        ContextCompat.startForegroundService(this, Intent(this, MuteService::class.java))
    }

    private fun stopMuting() {
        // Stop all muting instantly — prevents race with pending service polls
        MuteService.isActive = false

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IS_MUTED, false).apply()

        // Un-mute all streams
        for (stream in ALL_STREAMS) {
            try {
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            } catch (_: Exception) { }
        }

        // Restore saved volumes
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        for (stream in ALL_STREAMS) {
            val saved = prefs.getInt("saved_vol_$stream", -1)
            if (saved >= 0) {
                try { audioManager.setStreamVolume(stream, saved, 0) } catch (_: Exception) { }
            }
        }

        // Un-mute microphone
        try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }

        stopService(Intent(this, MuteService::class.java))
    }

    // ── UI ──────────────────────────────────────────────────────────────

    private fun updateUI() {
        val active = isMuteActive()
        if (active) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val muteAll = prefs.getBoolean(KEY_MUTE_ALL, false)
            toggleButton.text = "UNMUTE"
            toggleButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.unmute_color))
            statusText.text = if (muteAll)
                "\uD83D\uDD07  ALL SOUND IS FORCE MUTED\nA service is keeping all streams at zero."
            else
                "\uD83D\uDD07  CALL AUDIO IS MUTED\nMedia and other audio can still play normally."
        } else {
            toggleButton.text = "FORCE MUTE ALL"
            toggleButton.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.mute_color))
            statusText.text = "Tap the button to silence every audio stream.\nApps won't be able to raise the volume."
        }
    }
}
