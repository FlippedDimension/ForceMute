package com.forcemute.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

class MuteService : Service() {

    companion object {
        private const val TAG = "ForceMute"
        const val CHANNEL_ID = "force_mute_channel"
        const val NOTIFICATION_ID = 1
        private const val POLL_INTERVAL_MS = 150L
        private const val SAMPLE_RATE = 8000

        const val ACTION_MUTE = "com.forcemute.app.ACTION_MUTE"
        const val ACTION_UNMUTE = "com.forcemute.app.ACTION_UNMUTE"
        const val ACTION_MUTE_ALL = "com.forcemute.app.ACTION_MUTE_ALL"
        const val ACTION_STOP = "com.forcemute.app.ACTION_STOP"

        private const val SPEECH_CHANNEL_ID = "speech_alert_channel"
        private const val SPEECH_NOTIFICATION_ID = 2
        private const val SPEECH_ALERT_COOLDOWN_MS = 10_000L

        /** Global flag — callers can set false to instantly stop all muting. */
        @Volatile var isActive = false
    }

    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    private var volumeObserver: ContentObserver? = null
    private var silentTrack: AudioTrack? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var muteMic = false
    private var captionsEnabled = false
    private var lastSpeechAlertTime = 0L
    private var captionsWereEnabled = false   // original Live Caption state before we turned it on
    private var captionsActivatedByUs = false // true if we auto-enabled Live Caption
    private var savedRingerMode = AudioManager.RINGER_MODE_NORMAL
    private var savedInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_ALL

    // Guard against feedback loops — skip re-entrant muteEverything() calls
    @Volatile private var isMuting = false
    // Cooldown: ignore observer/callback triggers for a short time after we mute
    private var lastMuteTime = 0L
    private val MUTE_COOLDOWN_MS = 80L

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!isActive) return
            doMute()
            ensureSilentTrack()
            handler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")
        try {
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            createNotificationChannel()
            // Restore saved ringer mode in case this is a fresh process
            // (field defaults to RINGER_MODE_NORMAL which may be wrong)
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            savedRingerMode = prefs.getInt("saved_ringer_mode", AudioManager.RINGER_MODE_NORMAL)
            savedInterruptionFilter = prefs.getInt("saved_interruption_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
        } catch (e: Exception) {
            Log.e(TAG, "onCreate FAILED", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action=$action")

        // ALWAYS call startForeground first
        try {
            val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
            muteMic = prefs.getBoolean(MainActivity.KEY_MUTE_MIC, false)
            captionsEnabled = prefs.getBoolean(MainActivity.KEY_CAPTIONS, false)
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground FAILED", e)
        }

        // Handle notification button actions
        when (action) {
            ACTION_MUTE -> {
                Log.d(TAG, "ACTION_MUTE")
                isActive = true
                muteMic = false
                saveMicPref()
                try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }
                updateNotification()  // instant UI feedback
                reestablishMuting()   // heavy work after
                Log.d(TAG, "ACTION_MUTE done, isActive=$isActive")
                return START_STICKY
            }
            ACTION_UNMUTE -> {
                Log.d(TAG, "ACTION_UNMUTE")
                // Stop muting instantly
                isActive = false
                handler.removeCallbacks(pollRunnable)

                // Update notification FIRST for instant UI feedback
                updateNotification()

                // Heavy cleanup on background thread so UI stays responsive
                Thread({
                    stopSilentAudioTrack()
                    dismissSpeechAlert()
                    restoreLiveCaption()
                    try {
                        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                        prefs.edit().putBoolean(MainActivity.KEY_IS_MUTED, false).apply()
                        for (stream in MainActivity.ALL_STREAMS) {
                            try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) { }
                            val saved = prefs.getInt("saved_vol_$stream", -1)
                            if (saved >= 0) {
                                try { audioManager.setStreamVolume(stream, saved, 0) } catch (_: Exception) { }
                            }
                        }
                        try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }
                        try { audioManager.ringerMode = savedRingerMode } catch (_: Exception) { }
                        try {
                            getSystemService(NotificationManager::class.java)
                                .setInterruptionFilter(savedInterruptionFilter)
                        } catch (_: Exception) { }
                        try {
                            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                                audioManager.mode = AudioManager.MODE_NORMAL
                            }
                        } catch (_: Exception) { }
                        audioFocusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) { } }
                        audioFocusRequest = null
                    } catch (e: Exception) {
                        Log.e(TAG, "ACTION_UNMUTE error", e)
                    }
                }, "UnmuteWorker").start()
                return START_STICKY
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP")
                isActive = false
                handler.removeCallbacks(pollRunnable)
                // Restore volumes if still muted
                val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs.getBoolean(MainActivity.KEY_IS_MUTED, false)) {
                    prefs.edit().putBoolean(MainActivity.KEY_IS_MUTED, false).apply()
                    for (stream in MainActivity.ALL_STREAMS) {
                        try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) { }
                        val saved = prefs.getInt("saved_vol_$stream", -1)
                        if (saved >= 0) {
                            try { audioManager.setStreamVolume(stream, saved, 0) } catch (_: Exception) { }
                        }
                    }
                    try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }
                }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MUTE_ALL -> {
                Log.d(TAG, "ACTION_MUTE_ALL")
                isActive = true
                muteMic = true
                saveMicPref()
                updateNotification()  // instant UI feedback
                reestablishMuting()   // heavy work after
                Log.d(TAG, "ACTION_MUTE_ALL done, isActive=$isActive")
                return START_STICKY
            }
        }

        // Normal startup
        Log.d(TAG, "Normal startup")
        if (!isActive) {
            isActive = true

            // Save ringer mode (only on first start, persist for process death)
            try {
                val currentRinger = audioManager.ringerMode
                // Only save if not already silent (avoid saving our own override)
                if (currentRinger != AudioManager.RINGER_MODE_SILENT) {
                    savedRingerMode = currentRinger
                } else {
                    // Process restart — read previously persisted value
                    val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    savedRingerMode = prefs.getInt("saved_ringer_mode", AudioManager.RINGER_MODE_NORMAL)
                }
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt("saved_ringer_mode", savedRingerMode).apply()
            } catch (_: Exception) { }

            // Save and set DND to total silence (suppresses voice call audio)
            try {
                val nm = getSystemService(NotificationManager::class.java)
                val currentFilter = nm.currentInterruptionFilter
                if (currentFilter != NotificationManager.INTERRUPTION_FILTER_NONE) {
                    savedInterruptionFilter = currentFilter
                    nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Log.d(TAG, "Set DND to total silence (was $currentFilter)")
                }
                getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putInt("saved_interruption_filter", savedInterruptionFilter).apply()
            } catch (e: Exception) { Log.e(TAG, "DND err", e) }

            try { startSilentAudioTrack() } catch (e: Exception) { Log.e(TAG, "SilentTrack err", e) }
            try { registerPlaybackCallback() } catch (e: Exception) { Log.e(TAG, "PlaybackCb err", e) }
            try { registerVolumeObserver() } catch (e: Exception) { Log.e(TAG, "VolObs err", e) }
        }

        doMute()

        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)

        try { requestExclusiveAudioFocus() } catch (e: Exception) { Log.e(TAG, "Focus err", e) }
        try { audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (_: Exception) { }

        // Update notification now that isActive is true
        updateNotification()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        isActive = false
        handler.removeCallbacks(pollRunnable)
        stopSilentAudioTrack()
        dismissSpeechAlert()
        restoreLiveCaption()

        playbackCallback?.let { try { audioManager.unregisterAudioPlaybackCallback(it) } catch (_: Exception) { } }
        playbackCallback = null
        recordingCallback?.let { try { audioManager.unregisterAudioRecordingCallback(it) } catch (_: Exception) { } }
        recordingCallback = null
        volumeObserver?.let { try { contentResolver.unregisterContentObserver(it) } catch (_: Exception) { } }
        volumeObserver = null

        for (stream in MainActivity.ALL_STREAMS) {
            try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Exception) { }
        }
        try { audioManager.isMicrophoneMute = false } catch (_: Exception) { }
        try { audioManager.ringerMode = savedRingerMode } catch (_: Exception) { }
        try {
            getSystemService(NotificationManager::class.java)
                .setInterruptionFilter(savedInterruptionFilter)
        } catch (_: Exception) { }
        try {
            if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        } catch (_: Exception) { }
        audioFocusRequest?.let { try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) { } }
        audioFocusRequest = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun saveMicPref() {
        try {
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(MainActivity.KEY_MUTE_MIC, muteMic).apply()
        } catch (_: Exception) { }
    }

    // ── Core mute with re-entrancy guard ────────────────────────────────

    private fun doMute() {
        if (!isActive || isMuting) return
        isMuting = true
        try {
            for (stream in MainActivity.ALL_STREAMS) {
                try { audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0) } catch (_: Exception) { }
                try { audioManager.setStreamVolume(stream, 0, 0) } catch (_: Exception) { }
            }
            // Voice call & alarm streams have min=1, force DND total silence
            try {
                getSystemService(NotificationManager::class.java)
                    .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } catch (_: Exception) { }
            // Route voice call to earpiece (much quieter than speaker)
            try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) { }
            if (muteMic) {
                try { if (!audioManager.isMicrophoneMute) audioManager.isMicrophoneMute = true } catch (_: Exception) { }
            }
            lastMuteTime = System.currentTimeMillis()
        } finally {
            isMuting = false
        }
    }

    /** Called from reactive listeners — respects cooldown to avoid feedback. */
    private fun reactiveMute() {
        if (!isActive) return
        val now = System.currentTimeMillis()
        if (now - lastMuteTime < MUTE_COOLDOWN_MS) return
        doMute()
    }

    /** Re-establish all muting layers after unmute→mute transition */
    private fun reestablishMuting() {
        doMute()
        try { startSilentAudioTrack() } catch (e: Exception) { Log.e(TAG, "SilentTrack err", e) }
        try { registerPlaybackCallback() } catch (e: Exception) { Log.e(TAG, "PlaybackCb err", e) }
        try { registerVolumeObserver() } catch (e: Exception) { Log.e(TAG, "VolObs err", e) }
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        try { requestExclusiveAudioFocus() } catch (e: Exception) { Log.e(TAG, "Focus err", e) }
        try { audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT } catch (_: Exception) { }
        try {
            getSystemService(NotificationManager::class.java)
                .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
        } catch (_: Exception) { }
        // Save muted state
        try {
            getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(MainActivity.KEY_IS_MUTED, true).apply()
        } catch (_: Exception) { }
    }

    // ── Silent AudioTrack ───────────────────────────────────────────────

    private fun startSilentAudioTrack() {
        if (silentTrack != null) return
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val bufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufSize <= 0) { Log.e(TAG, "Invalid bufSize=$bufSize"); return }

        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufSize * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track.play()
        track.write(ByteArray(bufSize), 0, bufSize)

        Thread({
            val buf = ByteArray(bufSize)
            while (true) {
                try {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                    track.write(buf, 0, buf.size)
                } catch (_: Exception) { break }
            }
        }, "SilentTrackFeeder").apply { isDaemon = true }.start()

        silentTrack = track
        Log.d(TAG, "Silent track started")
    }

    private fun stopSilentAudioTrack() {
        silentTrack?.let {
            try { it.stop() } catch (_: Exception) { }
            try { it.release() } catch (_: Exception) { }
        }
        silentTrack = null
    }

    private fun ensureSilentTrack() {
        val t = silentTrack
        if (t == null || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
            stopSilentAudioTrack()
            try { startSilentAudioTrack() } catch (_: Exception) { }
        }
    }

    // ── Playback / Recording callbacks ──────────────────────────────────

    private fun registerPlaybackCallback() {
        if (playbackCallback != null) return
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
                reactiveMute()
                ensureAudioFocus()
            }
        }
        audioManager.registerAudioPlaybackCallback(cb, handler)
        playbackCallback = cb

        val recCb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                reactiveMute()
                if (isActive) {
                    try { requestExclusiveAudioFocus() } catch (_: Exception) { }
                    // Speech detection: if captions enabled and someone is recording (likely a call)
                    if (captionsEnabled && configs != null && configs.isNotEmpty()) {
                        enableLiveCaption()
                    }
                }
            }
        }
        audioManager.registerAudioRecordingCallback(recCb, handler)
        recordingCallback = recCb
    }

    // ── ContentObserver — only on volume URIs, with cooldown ────────────

    private fun registerVolumeObserver() {
        if (volumeObserver != null) return
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // Only react if we didn't just mute (avoids feedback loop)
                reactiveMute()
            }
        }
        contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        volumeObserver = observer
    }

    // ── Audio focus ─────────────────────────────────────────────────────

    private fun ensureAudioFocus() {
        if (!isActive) return
        try {
            val mode = audioManager.mode
            if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
                requestExclusiveAudioFocus()
            }
        } catch (_: Exception) { }
    }

    private fun requestExclusiveAudioFocus() {
        audioFocusRequest?.let {
            try { audioManager.abandonAudioFocusRequest(it) } catch (_: Exception) { }
        }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(req)
        audioFocusRequest = req
        try { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) { }
        // MODE_IN_COMMUNICATION can unmute voice call stream — re-mute immediately
        handler.post { doMute() }
    }

    // ── Notification ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL_ID, "Force Mute Active", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shows while Force Mute is keeping your phone silent"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)

        val speechCh = NotificationChannel(SPEECH_CHANNEL_ID, "Speech Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts when someone may be speaking while muted"
            setShowBadge(true)
            enableVibration(true)
        }
        nm.createNotificationChannel(speechCh)
    }

    // ── Live Caption auto-enable ─────────────────────────────────────────

    private fun enableLiveCaption() {
        if (captionsActivatedByUs) return // already turned on by us
        val now = System.currentTimeMillis()
        if (now - lastSpeechAlertTime < SPEECH_ALERT_COOLDOWN_MS) return
        lastSpeechAlertTime = now

        try {
            // Save current state so we can restore later
            captionsWereEnabled = try {
                Settings.Secure.getInt(contentResolver, "ods_captions_enabled", 0) == 1
            } catch (_: Exception) { false }

            if (!captionsWereEnabled) {
                // Try to enable Live Caption programmatically
                val success = Settings.Secure.putInt(contentResolver, "ods_captions_enabled", 1)
                if (success) {
                    captionsActivatedByUs = true
                    Log.d(TAG, "Live Caption auto-enabled")
                    showSpeechNotification("\uD83D\uDDE3\uFE0F Live Caption enabled", "Captions turned on automatically")
                } else {
                    Log.w(TAG, "Failed to enable Live Caption — falling back to notification")
                    showSpeechNotification("\uD83D\uDDE3\uFE0F Someone may be speaking", "Tap to open Live Caption settings")
                }
            } else {
                // Captions already on — just notify
                captionsActivatedByUs = false
                showSpeechNotification("\uD83D\uDDE3\uFE0F Someone may be speaking", "Live Caption is already on")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SECURE_SETTINGS not granted — showing fallback notification", e)
            showSpeechNotification("\uD83D\uDDE3\uFE0F Someone may be speaking",
                "Grant permission via ADB to auto-enable captions")
        } catch (e: Exception) {
            Log.e(TAG, "enableLiveCaption err", e)
        }
    }

    private fun restoreLiveCaption() {
        if (!captionsActivatedByUs) return
        try {
            // Only turn off if WE turned it on (it was off before)
            if (!captionsWereEnabled) {
                Settings.Secure.putInt(contentResolver, "ods_captions_enabled", 0)
                Log.d(TAG, "Live Caption restored to off")
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreLiveCaption err", e)
        }
        captionsActivatedByUs = false
    }

    private fun showSpeechNotification(title: String, text: String) {
        try {
            val captionIntent = Intent("android.settings.CAPTIONING_SETTINGS")
            val captionPending = PendingIntent.getActivity(
                this, 100, captionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, SPEECH_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_volume_off)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(captionPending)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .build()

            getSystemService(NotificationManager::class.java)
                .notify(SPEECH_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "showSpeechNotification err", e)
        }
    }

    private fun dismissSpeechAlert() {
        try {
            getSystemService(NotificationManager::class.java).cancel(SPEECH_NOTIFICATION_ID)
        } catch (_: Exception) { }
    }

    private fun updateNotification() {
        try {
            Log.d(TAG, "updateNotification isActive=$isActive muteMic=$muteMic")
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "updateNotification err", e)
        }
    }

    private fun buildNotification(): Notification {
        val openPending = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopPending = PendingIntent.getActivity(
            this, 4,
            Intent(this, MuteActionActivity::class.java).setAction(ACTION_STOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_volume_off)
            .setOngoing(true)
            .setContentIntent(openPending)

        if (isActive) {
            // Currently muted — show Unmute + Stop
            val unmutePending = PendingIntent.getActivity(
                this, 2,
                Intent(this, MuteActionActivity::class.java).setAction(ACTION_UNMUTE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val text = if (muteMic) "All audio + mic muted" else "All audio muted (mic on)"
            builder.setContentTitle("Force Mute Active")
                .setContentText(text)
                .addAction(android.R.drawable.ic_lock_silent_mode_off, "Unmute", unmutePending)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
        } else {
            // Currently unmuted — show Mute, Mute All, Stop
            val mutePending = PendingIntent.getActivity(
                this, 1,
                Intent(this, MuteActionActivity::class.java).setAction(ACTION_MUTE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val muteAllPending = PendingIntent.getActivity(
                this, 3,
                Intent(this, MuteActionActivity::class.java).setAction(ACTION_MUTE_ALL)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setContentTitle("Force Mute")
                .setContentText("Unmuted — tap a button to mute")
                .addAction(android.R.drawable.ic_lock_silent_mode, "Mute", mutePending)
                .addAction(android.R.drawable.ic_menu_call, "Mute All", muteAllPending)
                .addAction(android.R.drawable.ic_delete, "Stop", stopPending)
        }

        return builder.build()
    }
}
