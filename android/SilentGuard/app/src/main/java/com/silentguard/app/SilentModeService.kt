package com.silentguard.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that keeps the device silent by:
 *
 * 1. Holding an exclusive, voice-call-flavoured audio focus while looping a
 *    silent PCM stream. Any well-behaved app that respects audio focus
 *    (music players, games, browsers) gets ducked/paused by the system for
 *    as long as this focus is held, exactly like an incoming call would.
 * 2. Repeatedly zeroing every stream volume + forcing DND, to catch system
 *    sounds and apps that only look at stream volume rather than focus.
 * 3. Backing off the moment a *real* phone call starts, so this never
 *    blocks or garbles an actual call.
 */
class SilentModeService : Service() {

    companion object {
        private const val TAG = "SilentModeService"
        private const val CHANNEL_ID = "silent_guard_channel"
        private const val NOTIFICATION_ID = 1
        private const val REMUTE_INTERVAL_MS = 3_000L
        const val ACTION_STOP = "com.silentguard.app.action.STOP"
    }

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager
    private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    @Volatile private var running = false

    private var focusRequest: AudioFocusRequest? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val remuteRunnable = object : Runnable {
        override fun run() {
            if (!isRealCallActive()) {
                AudioMuteManager.muteEverything(this@SilentModeService)
                ensureFocusHeld()
            }
            mainHandler.postDelayed(this, REMUTE_INTERVAL_MS)
        }
    }

    private val ringerAndVolumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!isRealCallActive()) {
                AudioMuteManager.muteEverything(this@SilentModeService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()

        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(AudioManager.VOLUME_CHANGED_ACTION)
        }
        registerReceiver(ringerAndVolumeReceiver, filter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelfClean()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())

        if (!running) {
            running = true
            AudioMuteManager.muteEverything(this)
            requestExclusiveFocus()
            startSilentPlaybackLoop()
            mainHandler.post(remuteRunnable)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSilentPlaybackLoop()
        abandonFocus()
        mainHandler.removeCallbacks(remuteRunnable)
        try {
            unregisterReceiver(ringerAndVolumeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        running = false
        super.onDestroy()
    }

    private fun stopSelfClean() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // -- Real call detection, so we never fight an actual phone call ---------

    private fun isRealCallActive(): Boolean {
        return try {
            val state = telephonyManager.callStateCompat()
            state != TelephonyManager.CALL_STATE_IDLE
        } catch (_: SecurityException) {
            // Can't verify -> assume a call might be in progress so we
            // never fight one blind.
            true
        }
    }

    private fun TelephonyManager.callStateCompat(): Int {
        return this.callState
    }

    // -- Exclusive audio focus, dressed up as a call ------------------------

    private fun callLikeAudioAttributes(): AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    private fun requestExclusiveFocus() {
        if (isRealCallActive()) return

        val attrs = callLikeAudioAttributes()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(attrs)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        // Something with higher priority (e.g. a real call) took over.
                        // Back off; the remute loop will try to reclaim focus once
                        // that call ends.
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        AudioMuteManager.muteEverything(this)
                    }
                }
            }
            .build()

        focusRequest = request
        val result = audioManager.requestAudioFocus(request)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request was not granted immediately (result=$result)")
        }
    }

    private fun ensureFocusHeld() {
        // Re-requesting is cheap and idempotent while we already hold it;
        // it's what lets us reclaim focus after a real call ends.
        requestExclusiveFocus()
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // -- Silent PCM loop ------------------------------------------------------

    private fun startSilentPlaybackLoop() {
        val sampleRate = 16_000
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSize = maxOf(minBufferSize, sampleRate) // >= ~0.5s of headroom

        val track = AudioTrack.Builder()
            .setAudioAttributes(callLikeAudioAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()

        val silence = ShortArray(sampleRate / 10) // 100ms chunks, all zeros
        playbackThread = Thread({
            while (running) {
                track.write(silence, 0, silence.size)
            }
        }, "SilentGuard-PCM").apply { start() }
    }

    private fun stopSilentPlaybackLoop() {
        running = false
        playbackThread?.join(500)
        playbackThread = null
        audioTrack?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        audioTrack = null
    }

    // -- Notification ---------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SilentModeService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_mute)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .addAction(0, getString(R.string.action_stop), stopPendingIntent)
            .build()
    }
}
