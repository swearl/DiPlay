package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.compat.AudioAttributesCompat

/** A ROM can reject a legacy stream by throwing or returning an uninitialized track. */
internal object LegacyAudioFallback {
    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val candidate = try { createLegacy() } catch (_: RuntimeException) { null }
        if (candidate != null) {
            if (isInitialized(candidate)) return candidate
            release(candidate)
        }
        return createFallback()
    }

    /**
     * The attributes of a chosen legacy stream, or null to keep usage routing. Android accepts only its
     * defined legacy stream IDs here. BYD audio policy can map these standard streams to vehicle outputs;
     * arbitrary channel numbers are not valid AudioAttributes legacy stream types.
     */
    fun attributes(streamType: Int): AudioAttributesCompat? {
        if (streamType !in AudioManager.STREAM_SYSTEM..AudioManager.STREAM_ACCESSIBILITY) return null
        // 精简移植：AudioAttributes 为 API 21+，API 19 无法构造 legacy 属性对象；
        // 仅保留“选中该 legacy 流”的语义，usage/contentType 记为未设置（0）。
        return AudioAttributesCompat(usage = 0, contentType = 0)
    }
}
