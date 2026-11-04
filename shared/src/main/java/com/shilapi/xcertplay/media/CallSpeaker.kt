package com.shilapi.xcertplay.media

import android.media.AudioManager

/**
 * Puts a call in communication mode on the built-in loudspeaker. A phone or tablet used as the head unit plays
 * that mode on its earpiece and records it with the handset microphone, so a CarPlay call was heard only close
 * to the screen (#701).
 * 精简移植：目标平台 API 19 使用经典扬声器标记（setCommunicationDevice 为 API 31+）。
 */
internal class CallSpeaker private constructor(private val undo: () -> Unit) {
    fun restore() {
        runCatching(undo)
    }

    companion object {
        /** Null when this device has no loudspeaker for calls or refused it; the call keeps its route. */
        fun on(manager: AudioManager): CallSpeaker? = runCatching {
            @Suppress("DEPRECATION")
            val before = manager.isSpeakerphoneOn
            @Suppress("DEPRECATION")
            manager.isSpeakerphoneOn = true
            @Suppress("DEPRECATION")
            CallSpeaker { manager.isSpeakerphoneOn = before }
        }.getOrNull()
    }
}
