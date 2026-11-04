package com.shilapi.xcertplay.compat

import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * One audio focus request that works on every supported Android version.
 * 精简移植：目标平台 API 19 使用基于流的经典焦点调用（AudioFocusRequest 为 API 26+），
 * 回调统一移到 [handler] 线程上。
 */
class AudioFocusRequestCompat(
    val gain: Int,
    val attributes: AudioAttributes,
    private val listener: AudioManager.OnAudioFocusChangeListener,
    private val handler: Handler,
) {
    private val legacyListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (Looper.myLooper() == handler.looper) listener.onAudioFocusChange(change)
        else handler.post { listener.onAudioFocusChange(change) }
    }

    fun request(manager: AudioManager): Int {
        @Suppress("DEPRECATION")
        return manager.requestAudioFocus(legacyListener, legacyStreamType(attributes.usage), gain)
    }

    fun abandon(manager: AudioManager): Int {
        @Suppress("DEPRECATION")
        return manager.abandonAudioFocus(legacyListener)
    }

    internal companion object {
        /** The stream Android 7.x derives from these usages for its own focus bookkeeping. */
        fun legacyStreamType(usage: Int): Int = when (usage) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
            AudioAttributes.USAGE_ALARM -> AudioManager.STREAM_ALARM
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> AudioManager.STREAM_RING
            AudioAttributes.USAGE_NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
    }
}
