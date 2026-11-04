package com.shilapi.xcertplay.media

import android.media.MediaCodecList
import android.media.MediaFormat

/**
 * Whether this head unit can encode Opus for the CarPlay microphone uplink.
 *
 * The platform codec list alone is not the capability: the bundled software encoder also works on
 * head units without a platform Opus encoder. Probe it before advertising the microphone formats,
 * without opening the microphone or changing the audio mode.
 */
object OpusEncoderSupport {
    fun isAvailable(): Boolean = isAvailable(
        platformAvailable = ::platformAvailable,
        software = { SoftwareOpusEncoder(bitrate = 48_000) },
    )

    internal fun isAvailable(
        platformAvailable: () -> Boolean,
        software: () -> MicrophoneOpusEncoder,
    ): Boolean {
        if (runCatching(platformAvailable).getOrDefault(false)) return true
        return runCatching {
            software().use { encoder ->
                encoder.available && encoder.encode(ByteArray(SoftwareOpusEncoder.FRAME_BYTES))
                    .any { it.isNotEmpty() }
            }
        }.getOrDefault(false)
    }

    private fun platformAvailable(): Boolean {
        // 精简移植：MediaCodecList(codecInfos) 构造器为 API 21+，改用 API 19 的静态方法遍历
        val count = MediaCodecList.getCodecCount()
        for (index in 0 until count) {
            val info = MediaCodecList.getCodecInfoAt(index)
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true)) return true
            }
        }
        return false
    }
}
