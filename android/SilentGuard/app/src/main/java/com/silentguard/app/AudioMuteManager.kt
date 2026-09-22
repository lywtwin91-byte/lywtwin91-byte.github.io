package com.silentguard.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.Build

/**
 * Zeroes every adjustable audio stream and, when the user has granted
 * Do Not Disturb access, also forces ringer mode + interruption filter to
 * silent. This alone does not stop audio that is already playing on a
 * raw AudioTrack/OpenSL stream that ignores stream volume - that's what
 * SilentModeService's exclusive-focus playback is for.
 */
object AudioMuteManager {

    private val STREAMS = intArrayOf(
        AudioManager.STREAM_MUSIC,
        AudioManager.STREAM_RING,
        AudioManager.STREAM_ALARM,
        AudioManager.STREAM_NOTIFICATION,
        AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_DTMF,
        AudioManager.STREAM_VOICE_CALL
    )

    fun hasNotificationPolicyAccess(context: Context): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.isNotificationPolicyAccessGranted
    }

    fun muteEverything(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        for (stream in STREAMS) {
            try {
                if (am.isStreamMute(stream)) continue
                val min = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    am.getStreamMinVolume(stream)
                } else {
                    0
                }
                am.setStreamVolume(stream, min, 0)
            } catch (_: SecurityException) {
                // Some OEM/AOSP builds refuse STREAM_VOICE_CALL or STREAM_DTMF
                // without extra privileges - safe to skip and keep going.
            }
        }

        if (hasNotificationPolicyAccess(context)) {
            try {
                am.ringerMode = AudioManager.RINGER_MODE_SILENT
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            } catch (_: SecurityException) {
                // Policy access was revoked between the check and the call.
            }
        }
    }
}
