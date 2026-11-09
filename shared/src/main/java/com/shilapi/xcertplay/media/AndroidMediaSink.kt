package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.airplay.AudioStreamId
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.airplay.MicrophoneConfig
import com.shilapi.xcertplay.airplay.VideoCodec
import com.shilapi.xcertplay.airplay.toHexString
import com.shilapi.xcertplay.compat.AudioAttributesCompat
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit

/** 精简移植：目标平台 API 19 没有 AudioTrack 的属性读取接口，直接使用配置值。 */
internal fun audioTrackAttributesForFocus(track: AudioTrack, configured: AudioAttributesCompat): AudioAttributesCompat =
    configured

/** 精简移植：AudioAttributes 为 API 21+，按 usage 推导 legacy stream type（语音通话走通话流，其余走媒体流）。 */
private fun legacyStreamTypeFor(usage: Int): Int =
    if (usage == AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioManager.STREAM_VOICE_CALL
    else AudioManager.STREAM_MUSIC

/** Owns one focus request for all eligible tracks in a CarPlay sink. */
internal class AudioFocusCoordinator(
    context: Context?,
    private val enabled: Boolean,
    private val muteMediaOnTransientLoss: Boolean = true,
    private val report: (String) -> Unit = {},
) {
    private data class Entry(val channel: AudioChannel, val attributes: AudioAttributesCompat, var appliedVolume: Float = FULL_VOLUME)

    private val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val active = LinkedHashMap<AudioTrack, Entry>()
    private var request: AudioFocusRequestCompat? = null
    private var requestedChannel: AudioChannel? = null
    private var mediaVolume = FULL_VOLUME
    private var closed = false
    private var focusGeneration = 0L
    private var currentListener = listenerFor(focusGeneration)
    internal val listener: AudioManager.OnAudioFocusChangeListener get() = currentListener

    private fun listenerFor(generation: Long) = AudioManager.OnAudioFocusChangeListener { change ->
        synchronized(this) {
            // Android may have queued callbacks before a request was abandoned or replaced.
            if (generation != focusGeneration || request == null || active.isEmpty()) {
                // Dropping this silently hides the one window that matters: between a telephony
                // track closing and media reopening, a head unit that keeps focus looks like
                // nothing at all. Say the callback arrived and why it was not acted on.
                runCatching {
                    report("Audio: focus change=$change dropped" +
                        " stale=${generation != focusGeneration} noRequest=${request == null}" +
                        " activeTracks=${active.size}")
                }
                return@synchronized
            }
            runCatching { report("Audio: focus change=$change activeTracks=${active.size}") }
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> setMediaVolume(DUCKED_VOLUME)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    if (muteMediaOnTransientLoss) {
                        // This reports a temporary Android focus owner, not a confirmed call.
                        Log.i(TAG, "Audio: muting media during transient focus loss")
                        setMediaVolume(0f)
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> setMediaVolume(FULL_VOLUME)
                // Keep CarPlay audio running on permanent loss. Some head units
                // do not send a later gain callback after taking focus back.
            }
        }
    }

    @Synchronized
    fun acquire(track: AudioTrack, channel: AudioChannel, attributes: AudioAttributesCompat) {
        if (closed || !enabled || manager == null || channel == AudioChannel.NAVIGATION) return
        active[track] = Entry(channel, attributes)
        refreshRequest()
        applyMediaVolume(track, active.getValue(track))
    }

    @Synchronized
    fun release(track: AudioTrack) {
        if (active.remove(track) != null) refreshRequest()
    }

    fun onExternalFocusChange(change: Int) {
        val current = synchronized(this) { currentListener }
        current.onAudioFocusChange(change)
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        active.clear()
        refreshRequest()
    }

    private fun refreshRequest() {
        val primary = active.values.maxByOrNull { it.channel.focusPriority() }
        if (primary == null) {
            focusGeneration += 1
            val abandoned = request
            request = null
            requestedChannel = null
            mediaVolume = FULL_VOLUME
            manager?.let { abandoned?.abandon(it) }
            return
        }
        if (request != null && requestedChannel == primary.channel) return
        focusGeneration += 1
        manager?.let { request?.abandon(it) }
        val gain = when (primary.channel) {
            AudioChannel.MEDIA -> AudioManager.AUDIOFOCUS_GAIN
            AudioChannel.PHONE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            AudioChannel.ASSISTANT -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            AudioChannel.NAVIGATION -> return
        }
        currentListener = listenerFor(focusGeneration)
        val next = AudioFocusRequestCompat(gain, primary.attributes, currentListener, Handler(Looper.getMainLooper()))
        request = next
        requestedChannel = primary.channel
        val result = manager?.let(next::request)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) setMediaVolume(FULL_VOLUME)
        val line = "Audio: focus requested channel=${primary.channel} gain=$gain granted=$result activeTracks=${active.size}"
        Log.i(TAG, line)
        runCatching { report(line) }
    }

    private fun setMediaVolume(volume: Float) {
        mediaVolume = volume
        active.forEach { (track, entry) -> applyMediaVolume(track, entry) }
    }

    private fun applyMediaVolume(track: AudioTrack, entry: Entry) {
        // Telephony and assistant speech remain audible, and navigation never enters this map.
        // This changes only the renderer's relative gain, never Android's user stream volume.
        if (entry.channel != AudioChannel.MEDIA || entry.appliedVolume == mediaVolume) return
        val applied = runCatching { track.setStereoVolume(mediaVolume, mediaVolume) == AudioTrack.SUCCESS }.getOrDefault(false)
        if (applied) entry.appliedVolume = mediaVolume
    }

    private fun AudioChannel.focusPriority(): Int = when (this) {
        AudioChannel.MEDIA -> 3
        AudioChannel.PHONE -> 2
        AudioChannel.ASSISTANT -> 1
        AudioChannel.NAVIGATION -> 0
    }

    private companion object {
        const val TAG = "DiPlay-AudioFocus"
        const val FULL_VOLUME = 1f
        const val DUCKED_VOLUME = 0.2f
    }
}

/**
 * Android rendering backend for the CarPlay media engine. Video frames are
 * decoded with MediaCodec onto a Surface; audio streams are decoded to PCM and
 * played through AudioTrack. Each audio stream keeps its own track and usage so
 * media and navigation guidance stay independently routable. Call [close]
 * when the session tears down.
 */
class AndroidMediaSink(
    surface: Surface? = null,
    private val videoWidth: Int = 1280,
    private val videoHeight: Int = 720,
    private val preferSoftwareHevcDecoder: Boolean = false,
    private val advancedAudioChannelMapping: Boolean = false,
    private val audioFocusEnabled: Boolean = false,
    private val audioFocusAutoYield: Boolean = true,
    private val mediaChannel: Int = 0,
    private val navigationChannel: Int = 0,
    navigationAttenuationDb: Int = 0,
    context: Context? = null,
    private val navigationStreamType: Int = AudioChannelMapper.DEFAULT_NAVIGATION_STREAM_TYPE,
    onScreenStreamActiveChanged: ((Int, Boolean) -> Unit)? = null,
    private val mediaBufferMillis: Int = MediaAudioBuffer.DEFAULT_MILLIS,
    private val onAudioDiagnostic: (String) -> Unit = {},
    /** True while any music ("media") audio stream is running; called from media threads. */
    private val onMediaAudioChanged: (Boolean) -> Unit = {},
    /** Opt-in call echo cancellation, replacing a controllable platform canceller while available. */
    private val callEchoCancellation: Boolean = false,
    /** Cut the bass that head units add when they play a call as music. */
    private val callVoiceFilter: Boolean = false,
    /** Read at each call: a phone or tablet used as the head unit plays calls on its loudspeaker (#701). */
    private val callSpeakerphone: () -> Boolean = { false },
    /**
     * Smooth video: show main-screen frames at the iPhone's frame time plus a delay that starts here and
     * then follows how late the decoder releases frames ([PacingDelay]); 0 shows each as soon as it is
     * decoded. Only a SurfaceView honours the timestamps, so the host sets it with one.
     */
    private val videoPacingDelayMillis: Int = 0,
    /** The main screen's frame rate (the frame-rate setting), requested from its decoder as the operating rate; 0 for none. */
    private val mainVideoFrameRate: Int = 0,
    /**
     * Low-latency decoding (a setting): ask Qualcomm decoders to output each frame as soon as it is decoded,
     * and hand decoded frames to the screen from a thread of their own.
     */
    private val vendorLowLatencyDecoder: Boolean = false,
    /** Opt-in MTK ACodec low-latency/no-reorder modes, with per-session automatic fallback. */
    private val mtkDecoderTuningEnabled: Boolean = false,
    /** Opt-in (#946): navigation prompts request duckable focus while the iPhone ducks audio. Needs [audioFocusEnabled]. */
    navigationPromptAudioFocus: Boolean = false,
) : MediaSink {
    // Each downlink publishes its own reference; a mic must match that stream and sample rate.
    private val callEchoReferences = ConcurrentHashMap<AudioStreamId, EchoReference>()
    private val appContext = context?.applicationContext
    private val audioManager = (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)
    private val audioFocusCoordinator = AudioFocusCoordinator(
        appContext,
        audioFocusEnabled,
        audioFocusAutoYield,
        onAudioDiagnostic,
    )

    private val navigationPromptFocus = NavigationPromptFocus(
        appContext,
        promptFocusEnabled = navigationPromptAudioFocus,
        audioFocusEnabled = audioFocusEnabled,
        attributes = { navigationRouteAttributes(navigationChannel) },
        report = onAudioDiagnostic,
    )

    override fun onAudioDuck(ducked: Boolean) {
        if (ducked) navigationPromptFocus.onDuck() else navigationPromptFocus.onUnduck()
    }

    /** The media-key session may own Android's current focus request for this same sink. */
    fun onMediaAudioFocusChanged(change: Int) {
        audioFocusCoordinator.onExternalFocusChange(change)
    }

    private val screenStateLock = Any()
    private val activeScreenTypes = mutableSetOf<Int>()
    private var defaultSurface = surface
    @Volatile private var screenStreamActiveChanged = onScreenStreamActiveChanged
    private val surfaces = ConcurrentHashMap<Int, Surface>()
    private val videoDecoders = ConcurrentHashMap<Int, VideoDecoder>()
    // Surface capture, worker publication, retirement and detach snapshots share one ownership boundary.
    // Native codec work and waits never run under this lock.
    private val videoOwnershipLock = Any()
    private var videoClosed = false
    private val videoReleasedListeners = ArrayList<() -> Unit>()
    // Closed decoders whose workers may still hold a codec on a surface until they exit; a surface
    // detach also waits for them.
    // 精简移植：ConcurrentHashMap.newKeySet 为 API 24+，用 Collections.newSetFromMap 替代
    private val closingVideoDecoders: MutableSet<VideoDecoder> =
        Collections.newSetFromMap(ConcurrentHashMap<VideoDecoder, Boolean>())
    // How long a surface detach waits for decoders to confirm, and then for an unconfirmed one to exit
    // after it is closed. Variables so tests can shorten them.
    internal var detachTimeoutNanos = 2_000_000_000L
    internal var detachGraceNanos = 500_000_000L
    private val mediaAudioTypes = mutableSetOf<AudioStreamId>()
    private val audioRenderers = ConcurrentHashMap<AudioStreamId, AudioRenderer>()
    @Volatile private var navigationAttenuationDb = NavigationAudioGain.clamp(navigationAttenuationDb)

    /** Apply a saved Audio setting to existing navigation tracks without reconnecting CarPlay. */
    fun setNavigationAttenuationDb(db: Int) {
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
        // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
        for (renderer in audioRenderers.values) renderer.setNavigationAttenuationDb(navigationAttenuationDb)
    }
    private val microphoneUplinks = ConcurrentHashMap<AudioStreamId, MicrophoneUplink>()
    private val audioModeLock = Any()
    private var communicationModeStream: AudioStreamId? = null
    private var callSpeaker: CallSpeaker? = null // guarded by audioModeLock
    private var earpieceFallbackTried = false // guarded by audioModeLock
    private val pendingVideoCodec = ConcurrentHashMap<Int, VideoCodec>()
    private val videoRecoveryHandlers = ConcurrentHashMap<Int, () -> Unit>()
    private val videoDiagnosticHandlers = ConcurrentHashMap<Int, (String) -> Unit>()
    private val recoveryPending = AtomicBoolean(false)
    // Extra decoders draw the same stream on other surfaces, such as the centre card.
    private val mirrorLock = Any()
    private val mirrorSurfaces = HashMap<Pair<Int, String>, Surface>()
    private val mirrorDecoders = HashMap<Pair<Int, String>, VideoDecoder>()
    private val lastVideoConfig = ConcurrentHashMap<Int, Pair<VideoCodec, ByteArray>>()
    private val recoveryExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "carplay-video-recovery").apply { isDaemon = true }
    }

    override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
        videoRecoveryHandlers[type] = handler
    }

    override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
        videoDiagnosticHandlers[type] = handler
    }

    private fun requestVideoRecovery(type: Int) {
        if (!recoveryPending.compareAndSet(false, true)) return
        try {
            recoveryExecutor.execute {
                try { videoRecoveryHandlers[type]?.invoke() }
                catch (error: Exception) { Log.w("xcertplay-usb", "Video keyframe request failed", error) }
                finally { recoveryPending.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { recoveryPending.set(false) }
    }

    fun setSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            surfaces[type] = surface
            videoDecoders[type]?.setSurface(surface)
        }
    }

    fun clearSurface(type: Int, surface: Surface) {
        synchronized(videoOwnershipLock) {
            if (surfaces.remove(type, surface)) videoDecoders[type]?.setSurface(null)
        }
    }

    /**
     * Starts moving every decoder of this sink off [surface], which a SurfaceHolder destroy callback must
     * not return before. Each worker decides from the surface its codec actually renders to, after any
     * surface change queued before this: one already elsewhere has nothing to do. With [parkMain] the
     * main-screen decoder moves to an offscreen consumer it owns and keeps its state (smooth video, so the
     * picture can return at once); other decoders are released. Decoders closed earlier whose workers have
     * not exited yet are waited for too. [SurfaceDetach.await] returns the result.
     */
    fun beginSurfaceDetach(surface: Surface, parkMain: Boolean): SurfaceDetach {
        val deadline = System.nanoTime() + detachTimeoutNanos
        val requests = ArrayList<SurfaceDetachRequest>()
        val steps = ArrayList<() -> Boolean>()
        synchronized(videoOwnershipLock) {
            // 精简移植：Collection.removeIf 为 API 24+，改用迭代器循环删除
            val surfaceIterator = surfaces.entries.iterator()
            while (surfaceIterator.hasNext()) {
                if (surfaceIterator.next().value === surface) surfaceIterator.remove()
            }
            if (defaultSurface === surface) defaultSurface = null
            for ((type, decoder) in videoDecoders.entries.toList()) {
                val request = decoder.requestDetach(surface, park = parkMain && type == MAIN_SCREEN_TYPE)
                requests += request
                steps += { settleDetach(type, decoder, request, deadline) }
            }
            val closingIterator = closingVideoDecoders.iterator()
            while (closingIterator.hasNext()) {
                if (closingIterator.next().exited) closingIterator.remove()
            }
            closingVideoDecoders.toList().forEach { closing -> steps += { closing.awaitExitUntil(deadline + detachGraceNanos) } }
        }
        return SurfaceDetach(requests, steps)
    }

    /** A surface detach in flight across one sink's decoders; see [beginSurfaceDetach]. */
    class SurfaceDetach internal constructor(
        internal val requests: List<SurfaceDetachRequest>,
        private val steps: List<() -> Boolean>,
    ) {
        /**
         * Waits until every decoder has let go of the surface (moved, parked, released, or its worker
         * exited). False when one did not confirm in time and its worker, closed then, did not exit within
         * the grace period either; nothing more can be done from here, as with any native call that hangs.
         */
        fun await(): Boolean = steps.map { it() }.all { it }
    }

    private fun settleDetach(type: Int, decoder: VideoDecoder, request: SurfaceDetachRequest, deadline: Long): Boolean {
        if (decoder.awaitDetach(request, deadline)) return true
        Log.w("xcertplay-usb", "Video detach not confirmed in time type=$type; closing that decoder")
        videoDiagnosticHandlers[type]?.invoke("surface detach not confirmed; decoder closed")
        // The stream's next frame starts a fresh decoder on no surface, so it cannot compete for this one.
        retire(type, decoder)
        return decoder.awaitExitUntil(deadline + detachGraceNanos)
    }

    /** Closes a stream's decoder and keeps it visible to surface detaches until its worker exits. */
    private fun retire(type: Int, decoder: VideoDecoder) {
        synchronized(videoOwnershipLock) {
            // A timed-out detach can reach here after the worker has already completed cleanup. Its
            // onExit removed both registrations; do not republish it after its sole release notification.
            if (videoDecoders[type] !== decoder && decoder !in closingVideoDecoders) return
            closingVideoDecoders += decoder
            videoDecoders.remove(type, decoder)
            decoder.close()
        }
    }

    /** After [close], waits up to [timeoutMillis] for this sink's decoders to release their codecs. */
    fun awaitVideoReleased(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val closing = synchronized(videoOwnershipLock) { closingVideoDecoders.toList() }
        return closing.map { it.awaitExitUntil(deadline) }.all { it }
    }

    /** After [close], observes actual release of the tracked stream codecs, outside ownership locks. */
    fun whenVideoReleased(onReleased: () -> Unit) {
        val alreadyReleased = synchronized(videoOwnershipLock) {
            check(videoClosed) { "close before observing video release" }
            if (videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) true
            else {
                videoReleasedListeners += onReleased
                false
            }
        }
        if (alreadyReleased) runCatching(onReleased)
    }

    private fun onVideoDecoderExit(decoder: VideoDecoder) {
        val listeners = synchronized(videoOwnershipLock) {
            closingVideoDecoders -= decoder
            // 精简移植：Collection.removeIf 为 API 24+，改用迭代器循环删除
            val entryIterator = videoDecoders.entries.iterator()
            while (entryIterator.hasNext()) {
                if (entryIterator.next().value === decoder) entryIterator.remove()
            }
            if (videoClosed && videoDecoders.isEmpty() && closingVideoDecoders.isEmpty()) {
                videoReleasedListeners.toList().also { videoReleasedListeners.clear() }
            } else emptyList()
        }
        listeners.forEach { runCatching(it) }
    }

    /** True when this sink paces main-screen frames (smooth video); fixed for its lifetime. */
    val videoPacingEnabled: Boolean get() = videoPacingDelayMillis > 0

    /**
     * False until the main screen's first video frame. The screen stream's keepalives never reach the sink,
     * so a stream that carries only those, as from the stuck iPhones in #975 and #249, leaves it false.
     */
    @Volatile var mainVideoReceived = false
        private set

    /** Requests a keyframe for stream [type], e.g. after its surface came back. */
    /** Running totals of the main screen's decoder for a live FPS counter; null while it has none. */
    fun liveVideoCounters(): LiveVideoCounters? = videoDecoders[MAIN_SCREEN_TYPE]?.liveCounters()

    fun refreshPicture(type: Int) {
        videoDecoders[type]?.refreshPicture()
    }

    /**
     * Also decodes stream [type] onto [surface] with its own decoder, one per [key], which
     * starts at the next keyframe it asks for; null stops it. The stream's own surface is not affected.
     */
    fun setMirrorSurface(type: Int, key: String, surface: Surface?) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val id = type to key
            synchronized(mirrorLock) {
                mirrorDecoders.remove(id)?.close()
                if (surface == null) {
                    mirrorSurfaces.remove(id)
                    return
                }
                mirrorSurfaces[id] = surface
            }
            lastVideoConfig[type]?.let { (codec, data) ->
                // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
                for (decoder in mirrorDecoders(type)) decoder.configure(codec, data)
            }
        }
    }

    private fun mirrorDecoders(type: Int): List<VideoDecoder> = synchronized(videoOwnershipLock) {
        if (videoClosed) return emptyList()
        synchronized(mirrorLock) {
            if (mirrorSurfaces.isEmpty()) return emptyList()
            mirrorSurfaces.filterKeys { it.first == type }.map { (id, surface) ->
                mirrorDecoders.getOrPut(id) { newVideoDecoder(type, surface, " stream=$type mirror=${id.second}") }
            }
        }
    }

    fun setScreenStreamActiveChangedListener(listener: ((Int, Boolean) -> Unit)?) {
        synchronized(screenStateLock) {
            screenStreamActiveChanged = listener
            activeScreenTypes.forEach { listener?.invoke(it, true) }
        }
    }

    override fun onVideoCodec(type: Int, codec: VideoCodec) {
        synchronized(videoOwnershipLock) {
            if (!videoClosed) pendingVideoCodec[type] = codec
        }
    }

    override fun onVideoConfig(type: Int, codecData: ByteArray) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            val codec = pendingVideoCodec[type] ?: VideoCodec.H264
            lastVideoConfig[type] = codec to codecData
            videoDecoder(type).configure(codec, codecData)
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (decoder in mirrorDecoders(type)) decoder.configure(codec, codecData)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes)
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (decoder in mirrorDecoders(type)) decoder.submit(naluBytes)
        }
    }

    override fun onVideoFrame(type: Int, naluBytes: ByteArray, senderNanos: Long, arrivalNanos: Long) {
        if (type == MAIN_SCREEN_TYPE) mainVideoReceived = true
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            videoDecoder(type).submit(naluBytes, senderNanos, arrivalNanos)
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (decoder in mirrorDecoders(type)) decoder.submit(naluBytes)
        }
    }

    override fun onScreenStreamActive(type: Int, active: Boolean) {
        synchronized(videoOwnershipLock) {
            if (videoClosed) return
            if (!active) {
                videoRecoveryHandlers.remove(type)
                videoDiagnosticHandlers.remove(type)
                videoDecoders[type]?.let { retire(type, it) }
                synchronized(mirrorLock) {
                    mirrorDecoders.keys.filter { it.first == type }.forEach { mirrorDecoders.remove(it)?.close() }
                }
                lastVideoConfig.remove(type)
                pendingVideoCodec.remove(type)
            }
        }
        synchronized(screenStateLock) {
            if (active) activeScreenTypes.add(type) else activeScreenTypes.remove(type)
            screenStreamActiveChanged?.invoke(type, active)
        }
    }

    override fun onAudioStarted(id: AudioStreamId, format: AudioFormat, firstSample: Int) {
        audioRenderer(id, format).start()
        if (format.audioType == "media") updateMediaAudio(id, true)
    }

    override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
        audioRenderer(id, format).submit(rtp, sample)
    }

    override fun onAudioStopped(id: AudioStreamId) {
        synchronized(this) {
            audioRenderers.remove(id)?.close()
            callEchoReferences.remove(id)
        }
        updateMediaAudio(id, false)
    }

    private fun updateMediaAudio(id: AudioStreamId, active: Boolean) {
        val (before, after) = synchronized(mediaAudioTypes) {
            val before = mediaAudioTypes.isNotEmpty()
            if (active) mediaAudioTypes.add(id) else mediaAudioTypes.remove(id)
            before to mediaAudioTypes.isNotEmpty()
        }
        if (before != after) onMediaAudioChanged(after)
    }

    override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
        // This callback runs on the downlink thread; microphone failures must not stop playback.
        try {
            // 精简移植：BydBluetoothSuspend 已删除，通话默认不处于免提挂起状态
            val speakerphoneCall = false
            if (config.audioType == "telephony" && !speakerphoneCall) enterCommunicationMode(id)
            // 精简移植：computeIfAbsent 为 API 24+，用 ConcurrentMap 的原子 getOrPut 扩展替代
            val uplink = microphoneUplinks.getOrPut(id) {
                MicrophoneUplink(config, onAudioDiagnostic,
                    speakerphoneCall = speakerphoneCall,
                    echoReference = if (config.audioType == TELEPHONY_AUDIO_TYPE) callEchoReferences[id] else null,
                    audioManager = audioManager)
            }
            if (!uplink.start()) {
                microphoneUplinks.remove(id, uplink)
                restoreAudioMode(id)
            }
        } catch (error: Exception) {
            Log.e("xcertplay-usb", "microphone start failed stream=$id", error)
            MicrophoneCaptureStats.reportStartFailure(config, error, onAudioDiagnostic)
            onMicrophoneStopped(id)
        }
    }

    override fun onMicrophoneStopped(id: AudioStreamId) {
        try {
            microphoneUplinks.remove(id)?.close()
        } finally {
            restoreAudioMode(id)
        }
    }

    private fun enterCommunicationMode(id: AudioStreamId) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            if (communicationModeStream != null) return
            // Select the HAL communication path before AudioRecord is created.
            val before = manager.mode
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            communicationModeStream = id
            earpieceFallbackTried = false
            val speakerRequested = runCatching(callSpeakerphone).getOrDefault(false)
            if (speakerRequested) callSpeaker = CallSpeaker.on(manager)
            Log.i("xcertplay-usb", "audio mode $before -> ${manager.mode} for telephony stream=$id")
            // The report, not just logcat: a head unit left in the voice path plays no media,
            // and that cannot be diagnosed from a car without adb. A failing report must not
            // disturb the mode, so it is the last thing done and it cannot throw out of here.
            runCatching {
                onAudioDiagnostic("Audio: mode entered communication from=$before" +
                    " now=${manager.mode} stream=$id" +
                    if (speakerRequested) " loudspeaker=${callSpeaker != null}" else "")
            }
        }
    }

    /**
     * A head unit built on a phone chip plays communication audio on an earpiece, which nobody hears in a car: the
     * Podofo SM4250 in #782 routed a whole call there (`routeType=1`), and the navigation prompts during it. BYD
     * head units route calls to the loudspeaker (`routeType=2`) and never get here. Once a call's track is routed
     * to the earpiece, the call moves to the loudspeaker as **Calls on the loudspeaker** would, once per call.
     */
    private fun moveCallToLoudspeaker(id: AudioStreamId) {
        val manager = audioManager ?: return
        val line = synchronized(audioModeLock) {
            if (communicationModeStream != id || callSpeaker != null || earpieceFallbackTried) return
            earpieceFallbackTried = true
            callSpeaker = CallSpeaker.on(manager)
            "Audio: call routed to the earpiece; loudspeaker=${callSpeaker != null} stream=$id"
        }
        runCatching { onAudioDiagnostic(line) }
    }

    private fun restoreAudioMode(id: AudioStreamId?) {
        val manager = audioManager ?: return
        synchronized(audioModeLock) {
            val active = communicationModeStream ?: return
            if (id != null && id != active) return
            communicationModeStream = null
            callSpeaker?.restore()
            callSpeaker = null
            // The report is built inside the try but sent outside it: reporting from in there
            // would let a failing callback be caught as a failed restore.
            val line = try {
                // AudioService keeps one mode request per process and uses the newest. Setting back the mode found
                // at the start would keep DiPlay asking for it after the process that set it, such as the Telecom
                // call for the car's phone audio, lets go, and the head unit stays in the voice path (#987).
                // MODE_NORMAL withdraws DiPlay's request: the mode returns to another process's, or to normal.
                manager.mode = AudioManager.MODE_NORMAL
                Log.i("xcertplay-usb", "audio mode restored to ${manager.mode}")
                "Audio: mode restored requested=${AudioManager.MODE_NORMAL} now=${manager.mode} stream=$active"
            } catch (error: RuntimeException) {
                Log.w("xcertplay-usb", "could not restore audio mode", error)
                val currentMode = runCatching { manager.mode.toString() }.getOrDefault("unknown")
                "Audio: mode restore failed requested=${AudioManager.MODE_NORMAL} now=$currentMode" +
                    " error=${error.javaClass.simpleName}"
            }
            runCatching { onAudioDiagnostic(line) }
        }
    }

    fun close() {
        synchronized(videoOwnershipLock) {
            videoClosed = true
            videoDecoders.entries.toList().forEach { (type, decoder) -> retire(type, decoder) }
            synchronized(mirrorLock) {
                mirrorDecoders.values.forEach(VideoDecoder::close)
                mirrorDecoders.clear()
                mirrorSurfaces.clear()
            }
        }
        synchronized(screenStateLock) {
            activeScreenTypes.forEach { screenStreamActiveChanged?.invoke(it, false) }
            activeScreenTypes.clear()
            screenStreamActiveChanged = null
        }
        videoRecoveryHandlers.clear()
        videoDiagnosticHandlers.clear()
        recoveryExecutor.shutdownNow()
        // Audio workers close asynchronously; none may reacquire focus after their sink closes.
        audioFocusCoordinator.close()
        navigationPromptFocus.close()
        // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
        for (renderer in audioRenderers.values) renderer.close()
        audioRenderers.clear()
        callEchoReferences.clear()
        val hadMedia = synchronized(mediaAudioTypes) { mediaAudioTypes.isNotEmpty().also { mediaAudioTypes.clear() } }
        if (hadMedia) onMediaAudioChanged(false)
        try {
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (uplink in microphoneUplinks.values) uplink.close()
        } finally {
            microphoneUplinks.clear()
            restoreAudioMode(null)
        }
    }

    private fun videoDecoder(type: Int): VideoDecoder = synchronized(videoOwnershipLock) {
        videoDecoders[type] ?: run {
            // A decoder that replaces a closed one picks up the stream's codec configuration.
            val decoder = newVideoDecoder(type, surfaces[type] ?: defaultSurface, startImmediately = false)
            videoDecoders[type] = decoder
            lastVideoConfig[type]?.let { (codec, data) -> decoder.configure(codec, data) }
            decoder.start()
            decoder
        }
    }

    private fun newVideoDecoder(type: Int, surface: Surface?, statsLabel: String? = null, startImmediately: Boolean = true) = VideoDecoder(
        type,
        surface,
        videoWidth,
        videoHeight,
        preferSoftwareHevcDecoder,
        requestKeyFrame = { requestVideoRecovery(type) },
        report = { videoDiagnosticHandlers[type]?.invoke(it) },
        statsLabel = statsLabel,
        onExit = ::onVideoDecoderExit,
        // Only the main screen goes to the host's SurfaceView; mirrors and the cluster keep their path.
        pacingDelayNanos = if (type == MAIN_SCREEN_TYPE && statsLabel == null) videoPacingDelayMillis * 1_000_000L else 0L,
        operatingRate = videoOperatingRate(type, statsLabel, mainVideoFrameRate),
        vendorLowLatency = vendorLowLatencyDecoder,
        dedicatedOutputThread = vendorLowLatencyDecoder,
        framesAtOnce = type == MAIN_SCREEN_TYPE && statsLabel == null && videoPacingDelayMillis > 0,
        // Keep the experimental MTK mode on the main CarPlay picture only. Mirrors and the
        // cluster have separate timing and must retain their established decoder configuration.
        mtkDecoderTuningEnabled = mtkDecoderTuningEnabled && type == MAIN_SCREEN_TYPE && statsLabel == null,
    ).also { if (startImmediately) it.start() }

    @Synchronized
    private fun audioRenderer(id: AudioStreamId, format: AudioFormat): AudioRenderer {
        val existing = audioRenderers[id]
        if (existing?.format == format) return existing
        existing?.close()
        // A replacement owns a fresh ring: the old worker can still finish a blocking write.
        // The car's phone audio state cancels the echo itself; a second canceller would work against it.
        // 精简移植：PhoneAudioRoute（Telecom 通话路由）已删除，通话回声参考始终创建
        val echoReference = if (callEchoCancellation && format.audioType == TELEPHONY_AUDIO_TYPE && format.sampleRate > 0) {
            EchoReference(format.sampleRate).also { callEchoReferences[id] = it }
        } else {
            callEchoReferences.remove(id)
            null
        }
        return AudioRenderer(
            format,
            advancedAudioChannelMapping,
            audioFocusEnabled,
            mediaChannel,
            navigationChannel,
            audioFocusCoordinator,
            navigationStreamType,
            mediaBufferMillis,
            onAudioDiagnostic,
            speakerphoneCall = false,
            echoReference = echoReference,
            voiceFilter = callVoiceFilter && format.audioType == TELEPHONY_AUDIO_TYPE,
        ).also {
            it.setNavigationAttenuationDb(navigationAttenuationDb)
            if (format.audioType == TELEPHONY_AUDIO_TYPE) it.onRoutedToEarpiece = { moveCallToLoudspeaker(id) }
            audioRenderers[id] = it
            // Cover a Settings change made while this renderer was being prepared.
            it.setNavigationAttenuationDb(navigationAttenuationDb)
        }
    }

    private companion object {
        const val TELEPHONY_AUDIO_TYPE = "telephony"
    }
}

/** CarPlay's main screen stream type. */
private const val MAIN_SCREEN_TYPE = 110

/**
 * An offscreen consumer a parked decoder renders to while its SurfaceView is gone. It drops each image
 * on its own thread, so it keeps draining while the main thread waits on a surface handoff. Only the
 * decoder worker creates and closes it, after the codec no longer renders to it.
 */
internal class ParkingOutput(width: Int, height: Int) {
    private val consumerLock = Any()
    private var closed = false
    private val thread = android.os.HandlerThread("carplay-video-parking").apply { start() }
    private val reader: android.media.ImageReader
    val surface: Surface
    /** The looper the consumer drains on, never the main one. */
    val listenerLooper: android.os.Looper get() = thread.looper

    init {
        try {
            reader = android.media.ImageReader.newInstance(
                width.coerceAtLeast(1), height.coerceAtLeast(1), android.graphics.ImageFormat.PRIVATE, 3,
            )
            reader.setOnImageAvailableListener({ source ->
                synchronized(consumerLock) {
                    if (!closed) source.acquireLatestImage()?.close()
                }
            }, android.os.Handler(thread.looper))
            surface = reader.surface
        } catch (error: Throwable) {
            thread.quitSafely()
            throw error
        }
    }

    fun close() {
        try {
            synchronized(consumerLock) {
                if (!closed) {
                    closed = true
                    reader.close()
                }
            }
        } finally {
            thread.quitSafely()
        }
    }
}

/**
 * The operating rate requested from a stream's decoder, 0 for none: the main screen asks for the frame
 * rate the iPhone is asked for ([frameRate], the frame-rate setting). At 60 fps on my Tang, the Qualcomm
 * decoder's median time from queueing a frame to dequeueing its output was 8.5–9 ms lower with it.
 * Mirrors and the cluster stream keep the format they had.
 */
internal fun videoOperatingRate(type: Int, statsLabel: String?, frameRate: Int): Int =
    if (type == MAIN_SCREEN_TYPE && statsLabel == null && frameRate > 0) frameRate else 0

internal data class DecoderAttempt(
    val codecName: String?,
    val tuned: Boolean,
    val operatingRate: Int = 0,
    val mtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
)

/**
 * Configure attempts in order. Some vendor decoders (e.g. MediaTek c2.mtk.avc.decoder) reject the tuned
 * parameters with BAD_VALUE, so a minimal format and then software follow. An [operatingRate] is tried
 * first on its own, so a decoder that refuses it keeps the tuned format it gets without one. A
 * [lowLatencyDecoder] gets its tuned attempts before the default decoder's.
 */
internal fun videoDecoderAttempts(
    operatingRate: Int,
    softwareDecoder: String?,
    lowLatencyDecoder: String? = null,
    maximumMtkMode: MtkDecoderMode = MtkDecoderMode.NONE,
): List<DecoderAttempt> = buildList {
    if (operatingRate > 0) lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true, operatingRate)) }
    lowLatencyDecoder?.let { add(DecoderAttempt(it, tuned = true)) }
    val mtkModes = maximumMtkMode.attempts()
    if (operatingRate > 0) {
        // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
        for (mode in mtkModes) add(DecoderAttempt(null, tuned = true, operatingRate, mode))
    }
    for (mode in mtkModes) add(DecoderAttempt(null, tuned = true, mtkMode = mode))
    add(DecoderAttempt(codecName = null, tuned = false))
    softwareDecoder?.let { add(DecoderAttempt(it, tuned = false)) }
}

/**
 * The operating rate to ask for at the next configure: none once a later attempt ([used]) worked after
 * the rate was refused, so the refusal is not paid again; unchanged when every attempt failed.
 */
internal fun nextOperatingRate(requested: Int, used: DecoderAttempt?): Int =
    if (requested > 0 && used != null && used.operatingRate == 0) 0 else requested

/**
 * Qualcomm's vendor parameters that make its decoders output each frame as soon as it is decoded, in
 * decode order: without them c2.qti.avc.decoder released a frame only after about two more had been
 * queued (docs/SMOOTH_WIRELESS.md). Empty for other decoders. CarPlay's screen stream has no B-frames,
 * so decode order is display order.
 */
internal fun vendorLowLatencyKeys(codecName: String): List<String> =
    if (codecName.startsWith("c2.qti.") || codecName.startsWith("OMX.qcom.")) {
        listOf("vendor.qti-ext-dec-low-latency.enable", "vendor.qti-ext-dec-picture-order.enable")
    } else emptyList()

/** One regular decoder of the stream's type, as [lowLatencyDecoderName] sees it. */
internal data class DecoderCandidate(val name: String, val hardware: Boolean, val alias: Boolean, val lowLatency: Boolean)

/**
 * The decoder the main screen tries first with smooth video: the first hardware decoder (not an alias) that
 * advertises the low-latency feature, whose tuned format then asks for low latency. On my Tang this is
 * c2.qti.avc.decoder.low_latency; the default c2.qti.avc.decoder does not advertise the feature.
 */
internal fun lowLatencyDecoderName(candidates: List<DecoderCandidate>): String? =
    candidates.firstOrNull { it.lowLatency && it.hardware && !it.alias }?.name

/** The low-latency decoder to try at the next configure: none once another decoder ([used]) worked after it. */
internal fun nextLowLatencyDecoder(requested: String?, used: DecoderAttempt?): String? =
    if (requested != null && used != null && used.codecName != requested) null else requested

/**
 * Qualcomm's decoder parameter for output in decoding order. Without it my Tang's decoders released a frame
 * only once one or two later frames had been queued. Decoding order is the display order only for a stream
 * without reordered (B) frames; the iPhone's CarPlay H.264 had none. It is set only for the main screen's H.264
 * with smooth video, and only on a decoder that lists the parameter.
 */
internal const val PICTURE_ORDER_PARAMETER = "vendor.qti-ext-dec-picture-order.enable"

/** Serial MediaCodec video decoder: one worker owns configure and frame feeding. */
private class VideoDecoder(
    streamType: Int,
    surface: Surface?,
    private val width: Int,
    private val height: Int,
    private val preferSoftwareHevcDecoder: Boolean,
    private val requestKeyFrame: () -> Unit,
    private val report: (String) -> Unit,
    statsLabel: String? = null,
    pacingDelayNanos: Long = 0L,
    /** Called on the worker as its last step, after it has released its codec. */
    private val onExit: (VideoDecoder) -> Unit = {},
    operatingRate: Int = 0,
    /** Set [vendorLowLatencyKeys] on the tuned configure attempts. */
    private val vendorLowLatency: Boolean = false,
    /**
     * Release decoded frames from a thread that waits on the codec's output, instead of when the worker
     * next comes back from its queue or from feeding: a frame is shown as soon as it is decoded.
     */
    dedicatedOutputThread: Boolean = false,
    /**
     * The main screen with smooth video: for H.264, try a low-latency decoder first ([lowLatencyDecoderName]) and
     * ask decoders that list it for [PICTURE_ORDER_PARAMETER], so each frame leaves the decoder as soon as it can.
     */
    private val framesAtOnce: Boolean = false,
    private val mtkDecoderTuningEnabled: Boolean = false,
) : Closeable {
    private val pacer = FramePacer()
    private val pacingDelay = if (pacingDelayNanos > 0) PacingDelay(pacingDelayNanos) else null
    // Per queued presentation time (decode thread only): when it was queued, and whether it is a paced
    // frame's local time rather than the queue time.
    private val queuedPresentationUs = LongArray(64)
    private val queuedAtNanos = LongArray(64)
    private val queuedPaced = BooleanArray(64)
    // Set on the paced frames queued just before a pause in the iPhone's frames: the decoder releases a
    // frame only once about two more are queued, so they waited for that pause, which no display delay
    // can hide.
    private val queuedBeforePause = BooleanArray(64)
    private val recentPacedSlots = IntArray(PAUSE_HELD_FRAMES) { -1 }
    private var queuedSlot = 0
    // The decoder releases a frame only once later input arrives, so a frame queued before a still-screen
    // gap waits for the next one; such waits are not decode time and are left out of the stats.
    private var lastQueuedNanos = 0L
    // 精简移植：getInputBuffer 为 API 21+，缓存 getInputBuffers() 数组，释放解码器后置空重建
    private var cachedInputBuffers: Array<ByteBuffer?>? = null
    private var resumedAtNanos = 0L
    private var lastQueuedLocalNanos = 0L
    private val queue = VideoDecodeQueue()
    @Volatile private var running = true
    @Volatile private var decoder: MediaCodec? = null
    private var outputSurface: Surface? = surface
    private var parking: ParkingOutput? = null
    private var lastConfig: VideoJob.Config? = null
    // 精简移植：configure 时使用的 csd 原始 SPS/PPS（不含起始码），用于与流内参数集比对（防御老解码器花屏）
    private var configuredSps: ByteArray? = null
    private var configuredPps: ByteArray? = null
    // 精简移植：流内参数集不一致触发恢复的限频时间戳（0 表示从未触发）
    private var lastParameterSetRecoverNs = 0L
    @Volatile private var renderedFrameLogged = false
    // The operating rate still asked for, dropped for this stream once a decoder refuses it; and the rate
    // the current codec was configured with.
    private var operatingRate = operatingRate
    private var configuredRate = 0
    private var maximumMtkMode = if (mtkDecoderTuningEnabled) {
        MtkDecoderMode.LOW_LATENCY_NO_REORDER
    } else MtkDecoderMode.NONE
    private var configuredMtkMode = MtkDecoderMode.NONE
    private var configuredMtkCodec2LowLatency = false
    // Whether the vendor low-latency parameters were set on the codec being configured.
    private var configuredVendorLowLatency = false
    // Likewise the low-latency decoder, not tried again once another decoder had to take over; and what the
    // codec that tryConfigure started last was given.
    private var lowLatencyDecoderRefused = false
    private var startedLowLatency = false
    private var startedPictureOrder = false
    private var submittedFrameLogged = false
    private var duplicateConfigLogged = false
    private var failureReports = 0
    // 精简移植：上次真正 stop/release 一个 codec 的时刻；老 OMX 组件卸载是异步的，
    // 刚 release 就 create+configure 会以 0x80001001 失败（表现为黑屏），重建前需等待 settle 时间。
    private var lastCodecReleaseNanos = 0L
    // 精简移植：configure 连续失败计数与退避到期时刻；失败后指数退避，避免每个 IDR 都立即重建敲打老 OMX。
    private var configureFailures = 0
    private var configureBackoffUntilNanos = 0L
    // 精简移植：IDR 输出确认超时（自适应）：连续超时逐次翻倍（封顶 KEYFRAME_CONFIRM_TIMEOUT_MAX_NS），
    // 参考链确认成功后重置，避免解码器偏慢的设备陷入无限重建风暴。
    private var keyframeConfirmTimeoutNs = KEYFRAME_CONFIRM_TIMEOUT_NS
    private val referenceChain = VideoReferenceChain()
    private val backlogRecovery = VideoBacklogRecovery()
    private var lastKeyFrameRequestNs = 0L
    // The main screen keeps the historical log format; other screens are labelled.
    private val stats = VideoStats(statsLabel ?: if (streamType == MAIN_SCREEN_TYPE) "" else " stream=$streamType")
    // Running totals for the live FPS counter, read from the UI thread.
    private val liveReceived = AtomicLong()
    private val liveRendered = AtomicLong()
    private val liveDecodeNanos = AtomicLong()
    private val liveDecodeSamples = AtomicLong()

    fun liveCounters() = LiveVideoCounters(
        received = liveReceived.get(),
        rendered = liveRendered.get(),
        decodeNanos = liveDecodeNanos.get(),
        decodeSamples = liveDecodeSamples.get(),
    )
    private val thread = Thread(::run, "carplay-video").apply { isDaemon = true }
    private val outputThread = if (dedicatedOutputThread) {
        Thread(::runOutput, "carplay-video-output").apply { isDaemon = true }
    } else null
    // Guards the codec handoff between the worker and the output thread: [decoder] changes, [outputSurface]
    // and [parking] changes, the per-frame bookkeeping, and the output thread's handling of one buffer.
    // The output thread waits inside dequeueOutputBuffer without it.
    private val outputLock = Object()
    // The codec the output thread is waiting on; the worker waits for it to return before releasing that codec.
    private var dequeuing: MediaCodec? = null
    // A failure on the output thread, handled by the worker as if its own job had failed.
    @Volatile private var outputFailure: Exception? = null

    fun start() {
        thread.start()
        outputThread?.start()
    }

    fun configure(codec: VideoCodec, codecData: ByteArray) {
        queue.offer(VideoJob.Config(codec, codecData))
    }

    fun submit(nalus: ByteArray, senderNanos: Long = 0L, arrivalNanos: Long = 0L) {
        stats.onReceived(nalus.size)
        liveReceived.incrementAndGet()
        // Pacing runs on the worker, in queue order: callbacks of a replaced stream may still deliver here.
        queue.offer(VideoJob.Frame(nalus, senderNanos = senderNanos, arrivalNanos = arrivalNanos))
    }

    fun setSurface(surface: Surface?) {
        queue.offer(VideoJob.SurfaceChanged(surface))
    }

    /** Asks the worker to stop rendering to [surface], parking the codec when [park]. */
    fun requestDetach(surface: Surface, park: Boolean): SurfaceDetachRequest {
        check(Thread.currentThread() !== thread) { "detach from the decoder worker" }
        return SurfaceDetachRequest(surface, park).also { queue.offer(VideoJob.DetachSurface(it)) }
    }

    /** True once [request] is confirmed or the worker has exited; false when [deadlineNanos] passed first. */
    fun awaitDetach(request: SurfaceDetachRequest, deadlineNanos: Long): Boolean =
        request.await(deadlineNanos - System.nanoTime(), workerAlive = thread::isAlive)

    /** True once the worker has exited, so its codec is released. */
    val exited: Boolean get() = !thread.isAlive

    /**
     * After [close], waits until [deadlineNanos] for the worker to release its codec and exit. An
     * interrupt does not end the wait early; it is restored before returning.
     */
    fun awaitExitUntil(deadlineNanos: Long): Boolean {
        var interrupted = false
        try {
            while (thread.isAlive) {
                val leftMillis = (deadlineNanos - System.nanoTime()) / 1_000_000L
                if (leftMillis <= 0) break
                try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        return !thread.isAlive
    }

    fun refreshPicture() {
        queue.offer(VideoJob.RefreshPicture)
    }

    override fun close() {
        running = false
        thread.interrupt()
    }

    private fun run() {
        try {
            while (running) {
                val job = queue.poll(5)
                outputFailure?.let { failure ->
                    outputFailure = null
                    onJobFailure("output", failure)
                }
                try {
                    when (job) {
                        is VideoJob.Config -> configureDecoder(job)
                        is VideoJob.Frame -> {
                            val backlog = queue.backlogAfterCurrent()
                            val overloaded = !referenceChain.needsKeyFrame && backlogRecovery.observe(
                                System.nanoTime(), job.receivedNs,
                                backlog.pendingFrames, backlog.newestPendingReceivedNs,
                            )
                            if (overloaded) {
                                recover("video backlog kept growing or exceeded hard limit")
                            } else feed(job.nalus, job.receivedNs, localTimeOf(job))
                        }
                        is VideoJob.SurfaceChanged -> changeSurface(job.surface)
                        is VideoJob.DetachSurface -> detach(job.request)
                        // Once per new surface, so it skips the rate limit an earlier request may still hold.
                        is VideoJob.RefreshPicture -> if (decoder != null) {
                            lastKeyFrameRequestNs = System.nanoTime()
                            requestKeyFrame()
                        }
                        is VideoJob.Resync -> recover("video queue overflow")
                        null -> Unit
                    }
                    // 精简移植：IDR 入队后长时间无解码输出，老解码器可能已静默丢弃关键帧，重建解码器重新等 IDR。
                    // 仅在解码器仍存活时判定（重建失败期间由退避机制控制重试节奏）。
                    if (decoder != null &&
                        referenceChain.isPendingConfirmation(System.nanoTime(), keyframeConfirmTimeoutNs)
                    ) {
                        recover("keyframe output not confirmed within ${keyframeConfirmTimeoutNs / 1_000_000L} ms")
                        // 恢复后仍无输出说明解码器可能只是偏慢而非丢帧，超时翻倍（上限 4s），确认成功后重置。
                        if (keyframeConfirmTimeoutNs < KEYFRAME_CONFIRM_TIMEOUT_MAX_NS) {
                            keyframeConfirmTimeoutNs = minOf(
                                keyframeConfirmTimeoutNs * 2, KEYFRAME_CONFIRM_TIMEOUT_MAX_NS)
                        }
                    }
                    if (outputThread == null) decoder?.let(::drainOutput)
                    stats.logIfDue()?.let(report)
                    if (referenceChain.needsKeyFrame && lastConfig != null && outputSurface != null) requestKeyFrameIfDue()
                } catch (error: Exception) {
                    onJobFailure(job?.javaClass?.simpleName ?: "drain", error)
                } catch (error: LinkageError) {
                    if (running) reportFailure("stage=${job?.javaClass?.simpleName ?: "drain"}", error)
                    throw error
                }
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } finally {
            running = false
            releaseDecoder()
            outputThread?.let { output ->
                output.interrupt()
                joinUninterruptibly(output, OUTPUT_EXIT_WAIT_MS)
            }
            // The codec is gone, so nothing renders to any surface or to the parking consumer any more.
            parking?.close()
            parking = null
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (job in queue.drain()) {
                (job as? VideoJob.DetachSurface)?.request?.complete(DetachOutcome.RELEASED)
            }
            runCatching { onExit(this) }
        }
    }

    private fun onJobFailure(stage: String, error: Exception) {
        if (running) Log.e(TAG, "video decoder job failed: $stage", error)
        if (running) reportFailure("stage=$stage", error)
        // MTK vendor failures may surface as CodecException or the broader IllegalStateException.
        // Retire the active experimental mode before recreating the decoder.
        if (error is IllegalStateException) {
            dropMtkTuning("codec failed during $stage")
            dropMtkCodec2LowLatency("codec failed during $stage")
        }
        // A codec can accept the rate and still fail once it runs (start errors may surface on
        // later calls): one that failed before its first frame with it is not given it again.
        // 精简移植：MediaCodec.CodecException 为 API 21+，统一按 IllegalStateException 处理；
        // 配置了 operatingRate 且首帧未渲染时不再重试该速率。
        if (configuredRate > 0 && !renderedFrameLogged) {
            dropOperatingRate("codec failed before its first frame")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    /** The output thread: waits on the current codec's output and releases each frame as it comes. */
    private fun runOutput() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY) }
        val info = MediaCodec.BufferInfo()
        while (running) {
            val codec = synchronized(outputLock) {
                val current = decoder
                if (current == null || outputFailure != null) {
                    try { outputLock.wait(OUTPUT_IDLE_WAIT_MS) } catch (_: InterruptedException) { return }
                    null
                } else {
                    dequeuing = current
                    current
                }
            } ?: continue
            var index = MediaCodec.INFO_TRY_AGAIN_LATER
            var failure: Exception? = null
            try {
                index = codec.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)
            } catch (error: Exception) {
                failure = error
            }
            synchronized(outputLock) {
                dequeuing = null
                outputLock.notifyAll()
                // A codec the worker released or replaced meanwhile has no buffers left to hand out.
                if (decoder === codec) {
                    try {
                        if (failure != null) throw failure
                        handleOutput(codec, index, info)
                    } catch (error: Exception) {
                        if (running) outputFailure = error
                    }
                }
            }
        }
    }

    private fun configureDecoder(config: VideoJob.Config) {
        val previous = lastConfig
        if (
            decoder != null &&
            previous?.codec == config.codec &&
            previous.codecData.contentEquals(config.codecData)
        ) {
            if (!duplicateConfigLogged) {
                duplicateConfigLogged = true
                Log.i(TAG, "video decoder config unchanged; keeping existing decoder")
            }
            return
        }
        // 精简移植：configure 连续失败时指数退避。退避期间仅记录最新配置，等退避到期再重建
        // （退避期间解码器必然为 null，不影响已成功配置的解码器）。
        if (System.nanoTime() < configureBackoffUntilNanos) {
            lastConfig = config
            referenceChain.reset()
            return
        }
        lastConfig = config
        duplicateConfigLogged = false
        releaseDecoder()
        referenceChain.reset()
        val surface = outputSurface ?: return
        val codec = config.codec
        val codecData = config.codecData
        val mime = if (codec == VideoCodec.H265) MediaFormat.MIMETYPE_VIDEO_HEVC
        else MediaFormat.MIMETYPE_VIDEO_AVC
        val csd = if (codec == VideoCodec.H265) {
            MediaCodecSupport.hevcCodecSpecificData(codecData).takeIf { it.isNotEmpty() }
                ?.let { listOf(it) } ?: emptyList()
        } else {
            val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
            // 精简移植：缓存配置时使用的原始 SPS/PPS，供流内参数集一致性校验
            configuredSps = sps.takeIf { it.isNotEmpty() }
            configuredPps = pps.takeIf { it.isNotEmpty() }
            listOfNotNull(
                sps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
                pps.takeIf { it.isNotEmpty() }?.let { START_CODE + it },
            )
        }
        val requestedRate = operatingRate
        val atOnce = framesAtOnce && codec == VideoCodec.H264
        val requestedMtkCodec2 = if (mtkDecoderTuningEnabled && !lowLatencyDecoderRefused) {
            MtkDecoderTuning.codec2LowLatencyDecoderName(mime)
        } else null
        val requestedLowLatency = requestedMtkCodec2
            ?: if (atOnce && !lowLatencyDecoderRefused) findLowLatencyDecoder(mime) else null
        val requestedMtkMode = if (mtkDecoderTuningEnabled &&
            MtkDecoderTuning.legacyAcodecDecoderName(mime) != null) maximumMtkMode else MtkDecoderMode.NONE
        var configured: ConfiguredVideoDecoder? = null
        var used: DecoderAttempt? = null
        // 精简移植：老 OMX（如 OMX.qcom.*）卸载组件是异步的，刚 release 就 create+configure 会以
        // 0x80001001 失败并表现为黑屏；距上次释放不足 settle 时间时补足等待，仅在确实释放过时付出代价。
        if (lastCodecReleaseNanos != 0L) {
            val settleLeftMs = RECREATE_SETTLE_MS -
                (System.nanoTime() - lastCodecReleaseNanos) / 1_000_000L
            if (settleLeftMs > 0) Thread.sleep(settleLeftMs)
        }
        for (attempt in videoDecoderAttempts(
            requestedRate,
            softwareDecoderName(mime),
            requestedLowLatency,
            requestedMtkMode,
        )) {
            configured = tryConfigure(mime, csd, surface, attempt, atOnce)
            if (configured != null) { used = attempt; break }
        }
        if (configured == null) {
            queue.discardCurrentChain()
            // 精简移植：整体失败后启动指数退避（500ms → 1s → 2s → 4s → 8s 封顶），
            // 成功后清零；期间新 IDR/Config 不再立即重建，避免持续敲打脆弱的老 OMX 服务。
            configureFailures++
            val backoffMs = minOf(
                CONFIGURE_BACKOFF_BASE_MS shl (configureFailures - 1), CONFIGURE_BACKOFF_MAX_MS)
            configureBackoffUntilNanos = System.nanoTime() + backoffMs * 1_000_000L
            report("decoder configuration failed mime=$mime size=${width}x$height " +
                "failures=$configureFailures retryInMs=$backoffMs")
            Log.w(TAG, "video decoder configure failed; backoff ${backoffMs} ms " +
                "failures=$configureFailures mime=$mime")
        } else {
            // 精简移植：configure 成功，失败计数与退避清零
            configureFailures = 0
            configureBackoffUntilNanos = 0L
        }
        if (nextOperatingRate(requestedRate, used) != requestedRate) dropOperatingRate("refused at configure")
        if (nextLowLatencyDecoder(requestedLowLatency, used) != requestedLowLatency) {
            report("low-latency decoder $requestedLowLatency dropped: refused at configure")
            lowLatencyDecoderRefused = true
        }
        renderedFrameLogged = false
        submittedFrameLogged = false
        synchronized(outputLock) {
            decoder = configured?.codec
            outputLock.notifyAll()
        }
        configuredRate = used?.operatingRate ?: 0
        configuredMtkMode = configured?.mtkMode ?: MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = configured != null && requestedMtkCodec2 != null &&
            configured.codecName.equals(requestedMtkCodec2, ignoreCase = true)
        if (configured != null && MtkDecoderTuning.supportsLegacyAcodecKeys(configured.codecName) &&
            configuredMtkMode.ordinal < requestedMtkMode.ordinal) {
            report("MTK decoder tuning fell back at configure from=${requestedMtkMode.diagnosticName} " +
                "to=${configuredMtkMode.diagnosticName}")
            maximumMtkMode = configuredMtkMode
        }
        if (configured != null) {
            val rate = if (requestedRate > 0) " operatingRate=$configuredRate" else ""
            val lowLatency = if (vendorLowLatency) " vendorLowLatency=$configuredVendorLowLatency" else ""
            val output = if (outputThread != null) " outputThread=true" else ""
            val atOnceReport = if (atOnce) " lowLatency=$startedLowLatency pictureOrder=$startedPictureOrder" else ""
            val mtk = when {
                configuredMtkCodec2LowLatency -> " mtkTuning=codec2-low-latency"
                MtkDecoderTuning.isDecoderName(configured.codecName) ->
                    " mtkTuning=${configuredMtkMode.diagnosticName}"
                else -> ""
            }
            report("decoder=${configured.codecName} mime=$mime size=${width}x$height" +
                "$rate$lowLatency$output$atOnceReport$mtk")
            Log.i(
                TAG,
                "video decoder configured name=${configured.codecName} mime=$mime size=${width}x$height$mtk",
            )
        }
    }

    private fun dropOperatingRate(reason: String) {
        report("operating rate $operatingRate dropped: $reason")
        operatingRate = 0
    }

    private fun dropMtkTuning(reason: String) {
        val previous = configuredMtkMode
        if (previous == MtkDecoderMode.NONE) return
        val fallback = previous.fallback()
        if (fallback.ordinal < maximumMtkMode.ordinal) maximumMtkMode = fallback
        report("MTK decoder tuning ${previous.diagnosticName} dropped: $reason; " +
            "next=${fallback.diagnosticName}")
    }

    private fun dropMtkCodec2LowLatency(reason: String) {
        if (!configuredMtkCodec2LowLatency) return
        configuredMtkCodec2LowLatency = false
        lowLatencyDecoderRefused = true
        report("MTK Codec2 low-latency decoder dropped: $reason; next=default")
    }

    private fun buildFormat(
        mime: String,
        csd: List<ByteArray>,
        attempt: DecoderAttempt,
        mtkMode: MtkDecoderMode,
    ): MediaFormat =
        MediaFormat.createVideoFormat(mime, width, height).apply {
            if (attempt.tuned) {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                if (attempt.operatingRate > 0) setInteger(MediaFormat.KEY_OPERATING_RATE, attempt.operatingRate)
            }
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (key in mtkMode.formatKeys()) setInteger(key, 1)
            csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes)) }
        }

    private data class ConfiguredVideoDecoder(
        val codec: MediaCodec,
        val codecName: String,
        val mtkMode: MtkDecoderMode,
    )

    private fun tryConfigure(
        mime: String,
        csd: List<ByteArray>,
        surface: Surface,
        attempt: DecoderAttempt,
        /** Ask for [PICTURE_ORDER_PARAMETER] when the decoder lists it ([framesAtOnce], H.264). */
        atOnce: Boolean = false,
    ): ConfiguredVideoDecoder? {
        var candidate: MediaCodec? = null
        var mtkMode = MtkDecoderMode.NONE
        var codecName = attempt.codecName ?: "default"
        var phase = "create"
        var phaseStartNs = System.nanoTime()
        val startedNs = phaseStartNs
        return try {
            val codec = attempt.codecName?.let { MediaCodec.createByCodecName(it) } ?: createDecoder(mime)
            candidate = codec
            val createdName = runCatching { codec.name }.getOrNull().orEmpty()
            codecName = createdName.ifEmpty { codecName }
            mtkMode = appliedMtkDecoderMode(attempt.mtkMode, createdName)
            val format = buildFormat(mime, csd, attempt, mtkMode)
            phase = "configure"
            phaseStartNs = System.nanoTime()
            // 精简移植：low-latency 特性为 Android 11+；API 19 无 getCapabilitiesForType，
            // 用 runCatching 优雅降级为 false。
            val lowLatency = attempt.tuned && runCatching {
                codec.codecInfo.getCapabilitiesForType(mime).isFeatureSupported("low-latency")
            }.getOrDefault(false)
            if (lowLatency) format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val vendorKeys = if (attempt.tuned && vendorLowLatency) vendorLowLatencyKeys(codec.name.orEmpty()) else emptyList()
            // 精简移植：Iterable.forEach 为 API 24+，改用 for 循环
            for (key in vendorKeys) format.setInteger(key, 1)
            configuredVendorLowLatency = vendorKeys.isNotEmpty()
            val pictureOrder = attempt.tuned && atOnce && listsPictureOrder(codec)
            if (pictureOrder) format.setInteger(PICTURE_ORDER_PARAMETER, 1)
            codec.configure(format, surface, null, 0)
            phase = "start"
            phaseStartNs = System.nanoTime()
            codec.start()
            startedLowLatency = lowLatency
            startedPictureOrder = pictureOrder
            val configuredName = runCatching { codec.name }.getOrNull().orEmpty()
                .ifEmpty { createdName }
                .ifEmpty { attempt.codecName ?: "default" }
            ConfiguredVideoDecoder(codec, configuredName, mtkMode)
        } catch (error: Exception) {
            val phaseMs = (System.nanoTime() - phaseStartNs) / 1_000_000
            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            runCatching { candidate?.release() }.onFailure {
                Log.w(TAG, "video decoder candidate release failed name=$codecName", it)
            }
            reportFailure("stage=configure phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                "mtkTuning=${mtkMode.diagnosticName} mime=$mime", error)
            Log.w(
                TAG,
                "video decoder configure failed name=$codecName phase=$phase phaseMs=$phaseMs elapsedMs=$elapsedMs " +
                    "tuned=${attempt.tuned} operatingRate=${attempt.operatingRate} " +
                    "mtkTuning=${mtkMode.diagnosticName} mime=$mime size=${width}x$height",
                error,
            )
            null
        }
    }

    /** Android 11 added the low-latency feature; 精简移植：目标平台 API 19 恒定不查找。 */
    private fun findLowLatencyDecoder(mime: String): String? = null

    /** Android 12 lets a codec list its vendor parameters; 精简移植：目标平台 API 19 不支持。 */
    private fun listsPictureOrder(codec: MediaCodec): Boolean = false

    /** 精简移植：isSoftwareOnly 为 API 29+，目标平台不识别软件解码器。 */
    private fun softwareDecoderName(mime: String): String? = null

    private fun createDecoder(mime: String): MediaCodec {
        // 精简移植：删除了 Android 10+ 的软件 HEVC 解码器优先路径（isSoftwareOnly 为 API 29+）。
        return MediaCodec.createDecoderByType(mime)
    }

    private fun localTimeOf(frame: VideoJob.Frame): Long =
        if (pacingDelay != null && frame.senderNanos > 0 && frame.arrivalNanos > 0) {
            pacer.localTime(frame.senderNanos, frame.arrivalNanos)
        } else 0L

    /** Completes [request] once the codec no longer renders to its surface, whatever happens here. */
    private fun detach(request: SurfaceDetachRequest): Unit = synchronized(outputLock) {
        var outcome = DetachOutcome.RELEASED
        try {
            outcome = detachAction(outputSurface, request.surface, request.park, decoder != null)
            when (outcome) {
                DetachOutcome.NOT_RENDERING -> Unit
                DetachOutcome.PARKED -> {
                    // 精简移植：setOutputSurface 为 API 23+，API 19 不支持运行时切面到离屏消费者，
                    // 走与原“驻留失败”相同的释放路径，等待下一帧重建解码器。
                    Log.i(TAG, "video decoder parking unsupported on API 19; releasing it")
                    outcome = DetachOutcome.RELEASED
                    outputSurface = null
                    releaseDecoder()
                }
                DetachOutcome.RELEASED -> {
                    outputSurface = null
                    releaseDecoder()
                    Log.i(TAG, "video decoder detached from surface")
                }
            }
        } catch (error: Exception) {
            outcome = DetachOutcome.RELEASED
            outputSurface = null
            releaseDecoder()
            throw error
        } finally {
            request.complete(outcome)
        }
    }

    private fun changeSurface(surface: Surface?): Unit = synchronized(outputLock) {
        if (outputSurface === surface) return
        outputSurface = surface
        if (surface == null) {
            releaseDecoder()
            queue.discardCurrentChain()
            Log.i(TAG, "video decoder detached from surface")
            return
        }
        // 精简移植：setOutputSurface 为 API 23+，API 19 不支持运行时切换输出表面；
        // 记录日志并走既有的释放重建路径。
        if (decoder != null) {
            Log.i(TAG, "video decoder output surface change requires reconfigure on API 19")
        }
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        lastConfig?.let(::configureDecoder)
    }

    private fun feed(nalus: ByteArray, receivedNs: Long, presentNs: Long = 0L) {
        val annexB = MediaCodecSupport.toAnnexB(nalus)
        val config = lastConfig ?: return
        if (outputSurface == null) return
        if (annexB.isEmpty()) { recover("invalid video access unit"); return }
        // 精简移植：只扫描一次 Annex B，同时用于参考链放行判定与关键帧入队确认
        val keyFrame = MediaCodecSupport.isRandomAccess(annexB, config.codec)
        if (!referenceChain.accepts(keyFrame)) {
            requestKeyFrameIfDue()
            return
        }
        // 精简移植：关键帧携带流内 SPS/PPS 时与配置 csd 比对。不一致说明编码参数已变化，
        // API 19 老 OMX 解码器不支持流内参数集更新，会产生花屏/绿屏，限频触发解码器重建。
        if (keyFrame && (configuredSps != null || configuredPps != null)) {
            val inBand = MediaCodecSupport.spsPpsFromAnnexB(annexB)
            val inSps = inBand.firstOrNull { it[0].toInt() and 31 == 7 }
            val inPps = inBand.firstOrNull { it[0].toInt() and 31 == 8 }
            val mismatched = (inSps != null && configuredSps != null && !nalEquals(inSps, configuredSps!!)) ||
                (inPps != null && configuredPps != null && !nalEquals(inPps, configuredPps!!))
            if (mismatched) {
                val nowNs = System.nanoTime()
                if (lastParameterSetRecoverNs == 0L || nowNs - lastParameterSetRecoverNs >= PARAMETER_SET_RECHECK_NS) {
                    lastParameterSetRecoverNs = nowNs
                    recover("in-band parameter set differs from configured csd")
                    return
                }
                Log.w(TAG, "video in-band parameter set differs from configured csd; recovery rate limited")
            }
        }
        if (decoder == null) configureDecoder(config)
        val codec = decoder ?: return
        // A slow rebuild must not immediately restart on its obsolete triggering IDR.
        if (referenceChain.needsKeyFrame && VideoRecoveryFrameAge.isObsolete(
                System.nanoTime(), receivedNs, queue.backlogAfterCurrent(),
            )) {
            queue.discardCurrentChain()
            backlogRecovery.reset()
            requestKeyFrameIfDue()
            return
        }
        if (!submittedFrameLogged) {
            submittedFrameLogged = true
            Log.i(
                TAG,
                "video decoder first input avcc=${nalus.size} annexB=${annexB.size}",
            )
        }
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { if (outputThread == null) drainOutput(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
        )
        if (index < 0) {
            dropMtkTuning("video decoder input stalled")
            dropMtkCodec2LowLatency("video decoder input stalled")
            recover("video decoder input stalled")
            return
        }
        // 精简移植：getInputBuffer 为 API 21+，使用缓冲区数组（codec.start() 后获取）
        val input = checkNotNull(inputBuffersOf(codec)[index]) { "Decoder input buffer unavailable" }
        input.clear()
        if (annexB.size <= input.remaining()) {
            input.put(annexB)
            // A paced frame carries its local time; MediaCodec returns it with the decoded output.
            val presentationUs = if (presentNs > 0) presentNs / 1000 else System.nanoTime() / 1000
            // Recorded before queueing: the output thread may dequeue the frame as soon as it is queued.
            synchronized(outputLock) {
                val queuedNow = System.nanoTime()
                if (lastQueuedNanos != 0L && queuedNow - lastQueuedNanos > STILL_GAP_NS) resumedAtNanos = queuedNow
                lastQueuedNanos = queuedNow
                queuedPresentationUs[queuedSlot] = presentationUs
                queuedAtNanos[queuedSlot] = queuedNow
                queuedPaced[queuedSlot] = presentNs > 0
                queuedBeforePause[queuedSlot] = false
                if (presentNs > 0) {
                    // A gap between consecutive iPhone frame times, not a decoder backlog, marks a pause.
                    if (lastQueuedLocalNanos != 0L && presentNs - lastQueuedLocalNanos > SENDER_PAUSE_NS) {
                        recentPacedSlots.forEach { if (it >= 0) queuedBeforePause[it] = true }
                    }
                    lastQueuedLocalNanos = presentNs
                    recentPacedSlots.copyInto(recentPacedSlots, 1, 0, PAUSE_HELD_FRAMES - 1)
                    recentPacedSlots[0] = queuedSlot
                }
                queuedSlot = (queuedSlot + 1) % queuedPresentationUs.size
            }
            codec.queueInputBuffer(index, 0, annexB.size, presentationUs, 0)
            // 关键帧入队后进入待确认状态，等首个解码输出才放行后续 P 帧（防老解码器静默丢 IDR 导致花屏）
            if (keyFrame) referenceChain.onKeyFrameQueued(System.nanoTime())
        } else {
            recover("video frame exceeded codec input capacity")
            return
        }
        if (outputThread == null) drainOutput(codec)
    }

    private fun recover(reason: String) {
        Log.w(TAG, "Video recovery: $reason; waiting for keyframe")
        stats.onRecovery()
        report("recovery: $reason; waiting for keyframe")
        // Recreate with codec-specific data: flush can discard CSD before the first output.
        releaseDecoder()
        queue.discardCurrentChain()
        referenceChain.reset()
        requestKeyFrameIfDue()
    }

    private fun requestKeyFrameIfDue() {
        val now = System.nanoTime()
        if (lastKeyFrameRequestNs != 0L && now - lastKeyFrameRequestNs < 1_000_000_000L) return
        lastKeyFrameRequestNs = now
        requestKeyFrame()
    }

    /** 精简移植：去除 NAL 尾部多余的零字节后比对，兼容 Annex B 中可能附加的 trailing zero 字节。 */
    private fun nalEquals(a: ByteArray, b: ByteArray): Boolean {
        var endA = a.size
        var endB = b.size
        while (endA > 0 && a[endA - 1] == 0.toByte()) endA--
        while (endB > 0 && b[endB - 1] == 0.toByte()) endB--
        if (endA != endB) return false
        for (index in 0 until endA) {
            if (a[index] != b[index]) return false
        }
        return true
    }

    private fun queuedSlotOf(presentationUs: Long): Int =
        queuedPresentationUs.indices.firstOrNull { queuedPresentationUs[it] == presentationUs } ?: -1

    private fun drainOutput(codec: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (running) {
            if (!handleOutput(codec, codec.dequeueOutputBuffer(info, 0), info)) return
        }
    }

    /** Handles one dequeueOutputBuffer result; false when there is nothing more to drain for now. */
    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): Boolean {
        when {
            index == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> logOutputFormat(codec.outputFormat)
            index >= 0 -> {
                val render = outputSurface != null
                // Parked output goes to the offscreen consumer: it keeps the codec's state, but it is
                // not shown, so it is neither paced nor counted.
                val shown = render && outputSurface !== parking?.surface
                val now = System.nanoTime()
                // 解码输出确认参考链建立；入队到输出的延迟用于诊断（老解码器静默丢 IDR 时不会走到这里）
                val confirmedAt = referenceChain.takePendingConfirmation(now)
                if (confirmedAt != 0L) {
                    Log.i(TAG, "video decoder keyframe confirmed latencyMs=${(now - confirmedAt) / 1_000_000L}")
                    // 精简移植：参考链确认成功，确认超时恢复默认值
                    keyframeConfirmTimeoutNs = KEYFRAME_CONFIRM_TIMEOUT_NS
                }
                val slot = queuedSlotOf(info.presentationTimeUs)
                val queued = if (slot >= 0) queuedAtNanos[slot] else 0L
                val heldOverGap = queued in 1 until resumedAtNanos
                if (queued > 0 && !heldOverGap) {
                    stats.onDecodeLatency(now - queued)
                    liveDecodeNanos.addAndGet(now - queued)
                    liveDecodeSamples.incrementAndGet()
                }
                val delay = pacingDelay
                val paced = shown && delay != null && slot >= 0 && queuedPaced[slot]
                val localNs = info.presentationTimeUs * 1000
                val pauseHeld = paced && queuedBeforePause[slot]
                val targetNs = if (paced && delay != null) {
                    if (!heldOverGap && !pauseHeld) delay.onFrame(now - localNs)
                    stats.onPacingDelay(delay.nanos)
                    localNs + delay.nanos
                } else 0L
                if (targetNs - now in 1..MAX_PACING_AHEAD_NS) {
                    // 精简移植：按时间戳延迟渲染的 releaseOutputBuffer(index, nanos) 为 API 21+，
                    // 降级为立即渲染（时间戳平滑语义放弃，只保留显示）。
                    codec.releaseOutputBuffer(index, render)
                } else {
                    if (shown && delay != null && !heldOverGap && !pauseHeld) stats.onLate()
                    codec.releaseOutputBuffer(index, render)
                }
                if (shown) {
                    stats.onRendered()
                    liveRendered.incrementAndGet()
                }
                if (shown && !renderedFrameLogged) {
                    renderedFrameLogged = true
                    report("first frame rendered")
                    Log.i(TAG, "video decoder rendered first frame bytes=${info.size}")
                }
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
            }
            else -> return false
        }
        return true
    }

    private fun logOutputFormat(format: MediaFormat) {
        report("output format requested=${width}x${height} " +
            "coded=${format.intOrNull(MediaFormat.KEY_WIDTH)}x${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
            "crop=${format.intOrNull("crop-left")},${format.intOrNull("crop-top")}," +
            "${format.intOrNull("crop-right")},${format.intOrNull("crop-bottom")} " +
            "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
            "color=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)}/${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)}/${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}")
        Log.i(
            TAG,
            "video decoder output format " +
                "size=${format.intOrNull(MediaFormat.KEY_WIDTH)}x" +
                "${format.intOrNull(MediaFormat.KEY_HEIGHT)} " +
                "stride=${format.intOrNull(MediaFormat.KEY_STRIDE)} " +
                "slice=${format.intOrNull(MediaFormat.KEY_SLICE_HEIGHT)} " +
                "standard=${format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)} " +
                "range=${format.intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                "transfer=${format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)}",
        )
    }

    private fun reportFailure(context: String, error: Throwable) {
        // A bad decoder can fail again on every frame; exported detail stays bounded per worker.
        if (failureReports >= 8) return
        failureReports++
        runCatching { report("decoder failed $context " +
            MediaFailureSummary.describe(error)) }
    }

    // Worker only; [outputLock] orders it against the output thread.
    // 精简移植：getInputBuffer 为 API 21+，用缓冲区数组下标访问
    private fun inputBuffersOf(codec: MediaCodec): Array<ByteBuffer?> =
        cachedInputBuffers ?: codec.inputBuffers.also { cachedInputBuffers = it }

    /** 精简移植：释放当前解码器；返回是否真的 stop/release 了一个 codec（供重建前 settle 等待判断）。 */
    private fun releaseDecoder(): Boolean {
        backlogRecovery.reset()
        val codec: MediaCodec?
        synchronized(outputLock) {
            codec = decoder
            decoder = null
            // The output thread returns from dequeueOutputBuffer within its timeout and then sees no codec.
            awaitNotDequeuing(codec)
        }
        cachedInputBuffers = null
        configuredRate = 0
        configuredMtkMode = MtkDecoderMode.NONE
        configuredMtkCodec2LowLatency = false
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
            // 精简移植：记录释放时刻，供 configureDecoder 重建前等待老 OMX 完成异步卸载
            lastCodecReleaseNanos = System.nanoTime()
            return true
        }
        return false
    }

    /** Waits, holding [outputLock], until the output thread no longer waits on [codec]; keeps an interrupt. */
    private fun awaitNotDequeuing(codec: MediaCodec?) {
        if (codec == null) return
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(OUTPUT_EXIT_WAIT_MS)
        while (dequeuing === codec) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { outputLock.wait(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinUninterruptibly(thread: Thread, timeoutMillis: Long) {
        var interrupted = false
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        while (thread.isAlive) {
            val leftMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (leftMillis <= 0) break
            try { thread.join(leftMillis) } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val MAX_INPUT_SIZE = 8 * 1024 * 1024
        // The output thread's wait for a decoded frame; a release waits at most this long for it to return.
        const val OUTPUT_TIMEOUT_US = 10_000L
        const val OUTPUT_IDLE_WAIT_MS = 10L
        const val OUTPUT_EXIT_WAIT_MS = 500L
        const val INPUT_TIMEOUT_US = 10_000L
        // A paced frame more than this ahead is treated as a bad mapping and shown at once.
        const val MAX_PACING_AHEAD_NS = 1_000_000_000L
        // Longer input gaps are a still screen, not decoding work.
        const val STILL_GAP_NS = 500_000_000L
        // Consecutive frames this far apart in iPhone time are a pause in its frames: about four frame
        // intervals at 30 fps.
        const val SENDER_PAUSE_NS = 120_000_000L
        // Frames before a pause that wait for it: the decoder holds about two, sometimes three.
        const val PAUSE_HELD_FRAMES = 3
        // 精简移植：IDR 入队后等待首个解码输出的超时（初始值）；宽松阈值避免慢解码器误触发恢复。
        const val KEYFRAME_CONFIRM_TIMEOUT_NS = 500_000_000L
        // 精简移植：确认超时自适应翻倍的封顶值，避免解码器偏慢的设备陷入无限重建风暴。
        const val KEYFRAME_CONFIRM_TIMEOUT_MAX_NS = 4_000_000_000L
        // 精简移植：流内 SPS/PPS 与配置 csd 不一致时的恢复限频间隔，避免异常流导致恢复风暴。
        const val PARAMETER_SET_RECHECK_NS = 10_000_000_000L
        // 精简移植：释放 codec 后等待老 OMX 组件完成异步卸载的时间；不足该时间就重建会 0x80001001 失败。
        const val RECREATE_SETTLE_MS = 100L
        // 精简移植：configure 连续失败的指数退避基数（500ms 起步）与上限（8s）。
        const val CONFIGURE_BACKOFF_BASE_MS = 500L
        const val CONFIGURE_BACKOFF_MAX_MS = 8_000L
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}

/** Use the existing hard backlog age only when a newer frame remains in this control segment. */
internal object VideoRecoveryFrameAge {
    fun isObsolete(nowNs: Long, receivedNs: Long, backlog: VideoDecodeQueue.Backlog): Boolean =
        nowNs >= receivedNs && nowNs - receivedNs >= 1_500_000_000L &&
            backlog.pendingFrames > 0 && backlog.newestPendingReceivedNs?.let {
                it <= nowNs && it - receivedNs >= 100_000_000L
            } == true
}

private fun MediaFormat.intOrNull(key: String): Int? =
    if (!containsKey(key)) {
        null
    } else {
        try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }

/** Decodes AAC-LC/Opus to PCM and plays it, or plays wired LPCM directly. */
private class AudioRenderer(
    val format: AudioFormat,
    private val advancedAudioChannelMapping: Boolean,
    private val audioFocusEnabled: Boolean,
    private val mediaChannel: Int,
    private val navigationChannel: Int,
    private val audioFocusCoordinator: AudioFocusCoordinator,
    private val navigationStreamType: Int,
    private val mediaBufferMillis: Int,
    private val report: (String) -> Unit,
    private val speakerphoneCall: Boolean = false,
    /** Receives the played call audio so the call microphone can cancel its echo. */
    private val echoReference: EchoReference? = null,
    voiceFilter: Boolean = false,
) : Closeable {
    private data class AudioPacket(val rtp: ByteArray, val sample: Int, val arrivedNs: Long)

    private val pcmChannels = if (format.channels >= 2) 2 else 1
    private val voiceFilter = if (voiceFilter && format.sampleRate > 0) VoiceFilter(format.sampleRate, pcmChannels) else null

    private var trackAttributes: AudioAttributesCompat? = null
    private var mappedChannel: AudioChannel? = null
    private val navigationPlaybackToken = NavigationPlayback.open()
    private var actualLegacyStreamType: Int? = null
    private val queue = LinkedBlockingQueue<AudioPacket>(MAX_QUEUED_PACKETS)
    /** Music with sustained decoder backpressure must not keep playing minutes-old packets. */
    private val mediaLagGuard = if (format.audioType == "media") MediaPacketLagGuard(format.rtpClockRate) else null
    @Volatile private var newestRtpSample = 0
    private var lagRecoveryReports = 0
    @Volatile private var running = true
    @Volatile private var started = false
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    @Volatile private var navigationAttenuationDb = 0
    private var appliedNavigationAttenuationDb: Int? = null
    private var pcm = ByteArray(64 * 1024)
    // 精简移植：bufferSizeInFrames 为 API 23+，缓存轨道缓冲区字节数供统计换算
    private var trackBufferBytes = 0
    // 精简移植：getInputBuffer/getOutputBuffer 为 API 21+，缓存缓冲区数组，输出格式变化后刷新
    private var cachedInputBuffers: Array<ByteBuffer?>? = null
    private var cachedOutputBuffers: Array<ByteBuffer?>? = null
    private var playbackStarted = false
    private var prebufferBytes = 0
    private var startThresholdBytes = 0
    private var fadeApplied = false
    private var droppedPacketsLogged = false
    private var firstAacPayloadLogged = false
    private var firstOpusShortPacketLogged = false
    private var firstInputQueuedLogged = false
    private var inputQueued = 0
    private var inputDropped = 0
    private var shortOpusPackets = 0
    private var decoderUnavailablePackets = 0
    private var outputBuffers = 0
    private var firstPcmLogged = false
    private val packetsReceived = AtomicInteger()
    private val packetsDropped = AtomicInteger()
    private val packetsDroppedTotal = AtomicInteger()
    private val lastArrivalNs = AtomicLong()
    private val maxArrivalGapMs = AtomicLong()
    private val frameBytes = if (format.channels >= 2) 4 else 2
    private var totalWrittenFrames = 0L
    private var writtenFramesThisWindow = 0L
    private var writeErrorsThisWindow = 0
    private var lastWriteErrorCode: Int? = null
    private var zeroWritesThisWindow = 0
    private var partialWritesThisWindow = 0
    private var lastPlaybackHeadFrames: Long? = null
    private var maxWriteMs = 0L
    private var statsWindowStartNs = 0L
    private var statsLastUnderruns = 0
    private var bytesPerSecond = 0
    private val bufferProgress = AudioBufferProgress(if (format.channels >= 2) 4 else 2)
    private var underrunsAtPlaybackStart = 0
    private var headAtPlaybackStart = 0
    private var droppedAtLastTrim = 0
    private var clockTrim: MediaClockTrim? = null
    /** Called when this call's track is routed to the earpiece; the sink moves the call to the loudspeaker. */
    @Volatile var onRoutedToEarpiece: (() -> Unit)? = null
    private var lastPcmWriteNs = 0L
    private var rebufferCount = 0
    private var rebufferPauseReportCount = 0
    private var rebufferRecoverReportCount = 0
    private var queueFullReportCount = 0
    private var diagnosticStage = "starting"
    private var lastDecoderOutputMetadata: String? = null
    private var decoderOutputReports = 0
    private val thread = Thread(::run, "carplay-audio").apply { isDaemon = true }

    fun start() {
        if (started) return
        started = true
        thread.start()
    }

    fun submit(rtp: ByteArray, sample: Int) {
        val now = System.nanoTime()
        if (started) {
            packetsReceived.incrementAndGet()
            val previous = lastArrivalNs.getAndSet(now)
            if (previous != 0L) {
                // 精简移植：AtomicLong.accumulateAndGet 为 API 24+，用 CAS 循环记录最大到达间隔
                val gapMs = (now - previous) / 1_000_000L
                while (true) {
                    val current = maxArrivalGapMs.get()
                    if (gapMs <= current || maxArrivalGapMs.compareAndSet(current, gapMs)) break
                }
            }
            newestRtpSample = sample
            // Hold short Wi-Fi bursts intact. If compressed audio remains >1.5s behind for
            // another 1.5s, skip only stale undecoded packets to catch up to the live song.
            val removed = mediaLagGuard?.recover(queue, sample, AudioPacket::sample, now) ?: 0
            if (removed > 0) {
                packetsDropped.addAndGet(removed)
                packetsDroppedTotal.addAndGet(removed)
                if (lagRecoveryReports < MAX_REBUFFER_REPORTS) {
                    lagRecoveryReports++
                    report("Audio: stale media packet backlog recovered audioType=${format.audioType} " +
                        "packetsDiscarded=$removed remaining=${queue.size}")
                }
            }
        }
        if (!started || !queue.offer(AudioPacket(rtp, sample, now))) {
            if (started) {
                packetsDropped.incrementAndGet()
                packetsDroppedTotal.incrementAndGet()
            }
            if (started && !droppedPacketsLogged) {
                droppedPacketsLogged = true
                Log.w(TAG, "audio queue full; dropping newest packets to bound latency")
                if (queueFullReportCount < MAX_QUEUE_FULL_REPORTS) {
                    queueFullReportCount++
                    report("Audio: queue full audioType=${format.audioType} count=$queueFullReportCount")
                }
            }
        }
    }

    override fun close() {
        running = false
        NavigationPlayback.close(navigationPlaybackToken)
        thread.interrupt()
    }

    private fun run() {
        runCatching { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO) }
        try {
            runCatching { report("Audio: starting " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} channels=${format.channels} " +
                "mapping=${if (advancedAudioChannelMapping) "automotive" else "mobile"} " +
                "mediaChannel=$mediaChannel navigationChannel=$navigationChannel focus=$audioFocusEnabled") }
            when (format.codec) {
                AudioCodecKind.AAC_LC -> configureCodec(MediaFormat.MIMETYPE_AUDIO_AAC)
                AudioCodecKind.OPUS -> configureCodec(MediaFormat.MIMETYPE_AUDIO_OPUS)
                AudioCodecKind.LPCM -> Unit
            }
            createTrack()
            diagnosticStage = "focus"
            requestAudioFocus()
            while (running) {
                // Report Settings changes from this worker, never from the UI thread, also between prompts.
                applyNavigationAttenuation()
                diagnosticStage = "packet"
                queue.poll(AUDIO_POLL_MILLIS, TimeUnit.MILLISECONDS)?.let(::handle)
                // Output becomes ready asynchronously, including after the last packet of a burst.
                // Waiting for the next UDP packet can strand decoded sound for hundreds of ms.
                diagnosticStage = "decoder-output"
                codec?.let(::drainCodec)
                diagnosticStage = "buffer-maintenance"
                maintainPlaybackBuffer()
                diagnosticStage = "stats"
                logStatsIfDue()
            }
        } catch (_: InterruptedException) {
            // Worker shut down.
        } catch (error: Exception) {
            if (running) {
                Log.e(TAG, "audio renderer worker failed", error)
                reportFailure(error)
            }
        } catch (error: LinkageError) {
            // Record an unsupported platform API without changing the existing crash semantics.
            if (running) reportFailure(error)
            throw error
        } finally {
            runCatching { logStatsIfDue(force = true) }
            release()
        }
    }

    private fun configureCodec(mime: String) {
        diagnosticStage = "decoder-format"
        val mediaFormat = MediaFormat().apply {
            setString(MediaFormat.KEY_MIME, mime)
            setInteger(MediaFormat.KEY_SAMPLE_RATE, format.sampleRate)
            setInteger(MediaFormat.KEY_CHANNEL_COUNT, format.channels)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                setInteger(MediaFormat.KEY_IS_ADTS, 1)
                setByteBuffer("csd-0", ByteBuffer.wrap(aacAudioSpecificConfig()))
            } else {
                setByteBuffer("csd-0", ByteBuffer.wrap(opusHead()))
                setByteBuffer("csd-1", ByteBuffer.wrap(opusCodecDelay()))
                setByteBuffer("csd-2", ByteBuffer.wrap(opusSeekPreRoll()))
            }
        }
        if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
            Log.i(
                TAG,
                "audio AAC config rate=${format.sampleRate} channels=${format.channels} " +
                    "csd0=${aacAudioSpecificConfig().toHexString()}",
            )
        }
        codec = try {
            MediaCodecStartup.create(
                create = { diagnosticStage = "decoder-create"; MediaCodec.createDecoderByType(mime) },
                configure = { diagnosticStage = "decoder-configure"; it.configure(mediaFormat, null, null, 0) },
                start = { diagnosticStage = "decoder-start"; it.start() },
                release = { it.release() },
            ).also {
                // A vendor name getter or diagnostic callback must not discard a started codec.
                runCatching {
                    val name = it.name
                    Log.i(TAG, "audio decoder configured mime=$mime name=$name")
                    report("Audio: decoder ready audioType=${format.audioType} codec=${format.codec} name=$name")
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "audio decoder configuration failed mime=$mime", error)
            reportFailure(error)
            null
        }
    }

    private fun createTrack() {
        diagnosticStage = "track-buffer-size"
        val encoding = AndroidAudioFormat.ENCODING_PCM_16BIT
        val channelMask = if (format.channels >= 2) AndroidAudioFormat.CHANNEL_OUT_STEREO
        else AndroidAudioFormat.CHANNEL_OUT_MONO
        val minBuffer = AudioTrack.getMinBufferSize(format.sampleRate, channelMask, encoding)
        if (minBuffer <= 0) {
            Log.e(TAG, "AudioTrack buffer size unavailable rate=${format.sampleRate} channels=${format.channels}")
            runCatching { report("Audio: track unavailable stage=$diagnosticStage " +
                "audioType=${format.audioType} codec=${format.codec} rate=${format.sampleRate} " +
                "channels=${format.channels} minBufferResult=$minBuffer") }
            return
        }
        val selection = mappedSelection()
        mappedChannel = selection.channel
        val streamOverride = channelOverride(selection.channel)
        actualLegacyStreamType = streamOverride.takeIf { it != 0 }
        var attributes = audioAttributesFor(selection, streamOverride)
        trackAttributes = attributes
        val plan = MediaAudioBuffer.plan(selection.channel == AudioChannel.MEDIA,
            format.sampleRate, format.channels, minBuffer, mediaBufferMillis)
        bytesPerSecond = format.sampleRate * frameBytes
        trackBufferBytes = plan.trackBufferBytes
        val built: AudioTrack
        var routeLabel: String
        diagnosticStage = "track-build"
        // 精简移植：AudioTrack.Builder 为 API 23+，AudioAttributes 为 API 21+，
        // 统一用旧构造器：usage 映射为 legacy stream type（语音通话走通话流，其余走媒体流）。
        val trackStreamType = streamOverride.takeIf { it != 0 } ?: legacyStreamTypeFor(usageFor(selection.channel))
        routeLabel = "streamType=$trackStreamType" + if (streamOverride == 0) "(usage)" else ""
        built = LegacyAudioFallback.build(
            createLegacy = {
                AudioTrack(trackStreamType, format.sampleRate, channelMask, encoding,
                    plan.trackBufferBytes, AudioTrack.MODE_STREAM)
            },
            isInitialized = { it.state == AudioTrack.STATE_INITIALIZED },
            release = { it.release() },
            createFallback = {
                diagnosticStage = "track-fallback-build"
                routeLabel = "streamType=$trackStreamType(fallback=streamMusic)"
                actualLegacyStreamType = null
                Log.w(TAG, "streamType=$trackStreamType rejected by this ROM; falling back to music stream")
                attributes = audioAttributesFor(selection)
                AudioTrack(AudioManager.STREAM_MUSIC, format.sampleRate, channelMask, encoding,
                    plan.trackBufferBytes, AudioTrack.MODE_STREAM)
            },
        )
        track = built
        if (mappedChannel == AudioChannel.PHONE) {
            // 精简移植：AudioRouting 监听为 API 23+，API 19 无法监听路由变化；
            // 通话被路由到听筒的检测不可用（onRoutedToEarpiece 回退不再触发）。
        }
        applyNavigationAttenuation()
        diagnosticStage = "track-attributes"
        trackAttributes = audioTrackAttributesForFocus(built, attributes)
        diagnosticStage = "track-capacity"
        // 精简移植：bufferSizeInFrames 为 API 23+，用缓冲区字节数与每帧字节数换算
        val capacityBytes = plan.trackBufferBytes
        startThresholdBytes = MediaAudioBuffer.startBytesFor(plan.startBytes, capacityBytes, PREBUFFER_WRITE_CHUNK_BYTES)
        // Only music plays long enough for the clocks to drift apart; calls keep their echo reference exact.
        clockTrim = if (mappedChannel == AudioChannel.MEDIA) MediaClockTrim(format.sampleRate, (startThresholdBytes / frameBytes).toLong(),
            measuresTarget = true) else null
        val trackMetadata = runCatching {
            "trackState=${built.state} trackRate=${built.sampleRate} " +
                "usage=${trackAttributes?.usage} contentType=${trackAttributes?.contentType} " +
                "attributesSource=configured"
        }.getOrDefault("trackMetadata=unavailable")
        report("Audio: ready audioType=${format.audioType} codec=${format.codec} " +
            "rate=${format.sampleRate} channels=${format.channels} " +
            "route=$routeLabel " +
            "bufferMs=${capacityBytes * 1000L / bytesPerSecond} startMs=${startThresholdBytes * 1000L / bytesPerSecond} " +
            trackMetadata)
        Log.i(
            TAG,
            "audio track prepared type=${format.payloadType} audioType=${format.audioType} " +
                "codec=${format.codec} " +
                "rate=${format.sampleRate} channels=${format.channels} " +
                "route=$routeLabel " +
                "buffer=${capacityBytes * 1000L / bytesPerSecond}ms start=${startThresholdBytes * 1000L / bytesPerSecond}",
        )
        Log.i(
            TAG,
            "audio route type=${format.payloadType} audioType=${format.audioType} " +
                "mode=${if (advancedAudioChannelMapping) AudioChannelMappingMode.AUTOMOTIVE_BUS else AudioChannelMappingMode.MOBILE_COMPATIBLE} " +
                "channel=${selection.channel} usage=${usageFor(selection.channel)} " +
                "contentType=${contentTypeFor(selection.contentType)} " +
                "streamOverride=$streamOverride " +
                "focus=${if (audioFocusEnabled) "on" else "off"}",
        )
    }

    /** 0 uses usage-based routing; 1–20 attempt legacy stream types supported by the head unit. */
    private fun channelOverride(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> mediaChannel
        AudioChannel.NAVIGATION -> navigationChannel
        else -> 0
    }

    fun setNavigationAttenuationDb(db: Int) {
        // The Settings callback must never wait for release(), which may be blocked in
        // vendor MediaCodec.stop()/release() while holding the renderer monitor.
        navigationAttenuationDb = NavigationAudioGain.clamp(db)
    }

    /**
     * Worker-thread only. The guidance is scaled in [attenuateNavigation] as it is written, not with
     * AudioTrack.setVolume: on a BYD DiLink 3.0 the track accepted every setVolume and the guidance on
     * stream 14 stayed as loud (#719). Each change is reported once, when it reaches this stream.
     */
    private fun applyNavigationAttenuation() {
        if (mappedChannel != AudioChannel.NAVIGATION) return
        val requestedDb = navigationAttenuationDb
        // Leave default playback exactly as it was before this setting existed.
        if (requestedDb == 0 && appliedNavigationAttenuationDb == null) return
        if (appliedNavigationAttenuationDb == requestedDb) return
        appliedNavigationAttenuationDb = requestedDb
        runCatching { report("Audio: navigation attenuation=" + requestedDb + "dB applied=true") }
    }

    /** Lowers navigation guidance in the PCM itself; other channels and 0 dB pass through untouched. */
    private fun attenuateNavigation(data: ByteArray, offset: Int, length: Int) {
        if (mappedChannel != AudioChannel.NAVIGATION) return
        NavigationAudioGain.scalePcm16(data, offset, length, navigationAttenuationDb)
    }

    private fun audioAttributesFor(
        selection: AudioChannelSelection,
        streamOverride: Int,
    ): AudioAttributesCompat = LegacyAudioFallback.attributes(streamOverride) ?: audioAttributesFor(selection)

    private fun mappedSelection(): AudioChannelSelection {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        val selection = AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
        )
        return if (speakerphoneCall) AudioChannelMapper.playCallOnSpeaker(selection) else selection
    }

    // 精简移植：AudioAttributes 为 API 21+，用 int 语义载体保存 usage/contentType
    private fun audioAttributesFor(selection: AudioChannelSelection): AudioAttributesCompat =
        AudioAttributesCompat(usageFor(selection.channel), contentTypeFor(selection.contentType))

    /**
     * Shares a sink-level focus request across all active non-navigation renderers.
     * Navigation guidance takes no focus here: it overlays media without ducking it. The opt-in
     * [NavigationPromptFocus] requests focus for each prompt, between duckAudio and unduckAudio.
     */
    private fun requestAudioFocus() {
        val channel = mappedChannel ?: return
        val attributes = trackAttributes ?: return
        if (channel == AudioChannel.NAVIGATION) {
            Log.i(TAG, "audio focus skipped channel=NAVIGATION; overlays without ducking")
            return
        }
        track?.let { audioFocusCoordinator.acquire(it, channel, attributes) }
    }

    private fun abandonAudioFocus() {
        track?.let(audioFocusCoordinator::release)
    }

    private fun streamType(): Int {
        val mode = if (advancedAudioChannelMapping) {
            AudioChannelMappingMode.AUTOMOTIVE_BUS
        } else {
            AudioChannelMappingMode.MOBILE_COMPATIBLE
        }
        return AudioChannelMapper.map(
            audioType = format.audioType,
            payloadType = format.payloadType,
            mode = mode,
            navigationStreamType = navigationStreamType,
        ).streamType
    }

    private fun aacAudioSpecificConfig(): ByteArray {
        val frequencyIndex = MediaCodecSupport.aacFrequencyIndex(format.sampleRate)
        val value = (AAC_OBJECT_TYPE_LC shl 11) or
            (frequencyIndex shl 7) or
            (format.channels.coerceIn(1, 7) shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun usageFor(channel: AudioChannel): Int = when (channel) {
        AudioChannel.MEDIA -> AudioAttributes.USAGE_MEDIA
        AudioChannel.PHONE -> AudioAttributes.USAGE_VOICE_COMMUNICATION
        // 精简移植：USAGE_ASSISTANT 为 API 26+，目标平台按媒体用途处理。
        AudioChannel.ASSISTANT -> AudioAttributes.USAGE_MEDIA
        AudioChannel.NAVIGATION -> AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE
    }

    private fun contentTypeFor(contentType: AudioContentType): Int = when (contentType) {
        AudioContentType.MUSIC -> AudioAttributes.CONTENT_TYPE_MUSIC
        AudioContentType.SPEECH -> AudioAttributes.CONTENT_TYPE_SPEECH
    }

    /** Minimal OpusHead CSD for the mono 48 kHz stream CarPlay negotiates. */
    private fun opusHead(): ByteArray {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = format.channels.toByte()
        head[10] = 0x38
        head[11] = 0x01
        head[12] = format.sampleRate.toByte()
        head[13] = (format.sampleRate ushr 8).toByte()
        head[14] = (format.sampleRate ushr 16).toByte()
        head[15] = (format.sampleRate ushr 24).toByte()
        return head
    }

    private fun opusCodecDelay(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_CODEC_DELAY_NANOS)
            .array()

    private fun opusSeekPreRoll(): ByteArray =
        java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(OPUS_SEEK_PRE_ROLL_NANOS)
            .array()

    private fun handle(packet: AudioPacket) {
        if (queue.isEmpty()) {
            droppedPacketsLogged = false
        }
        val rtp = packet.rtp
        val timestampUs = sampleTimestampUs(packet.sample)
        when (format.codec) {
            AudioCodecKind.LPCM -> writePcm(byteSwapS16(rtp.copyOfRange(12, rtp.size)))
            AudioCodecKind.AAC_LC -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.isNotEmpty()) {
                    if (!firstAacPayloadLogged) {
                        firstAacPayloadLogged = true
                        Log.i(
                            TAG,
                            "audio AAC access unit bytes=${accessUnit.size}",
                        )
                    }
                    feedCodec(
                        MediaCodecSupport.adtsFrame(accessUnit, format.sampleRate, format.channels),
                        timestampUs,
                    )
                }
            }
            AudioCodecKind.OPUS -> {
                val accessUnit = rtp.copyOfRange(12, rtp.size)
                if (accessUnit.size < MIN_OPUS_PACKET_BYTES) {
                    shortOpusPackets++
                    if (!firstOpusShortPacketLogged) {
                        firstOpusShortPacketLogged = true
                        Log.i(
                            TAG,
                            "audio Opus skipping short packet bytes=${accessUnit.size}",
                        )
                    }
                    return
                }
                feedCodec(accessUnit, timestampUs)
            }
        }
    }

    private fun sampleTimestampUs(sample: Int): Long =
        (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.sampleRate

    private fun feedCodec(payload: ByteArray, presentationTimeUs: Long) {
        val codec = codec ?: run { decoderUnavailablePackets++; return }
        diagnosticStage = "decoder-input"
        // While a blocking track write holds this thread, decoded output fills up and the decoder
        // stops taking input. Dropping the packet after one 10 ms wait cut ~21 ms of music every
        // 10-20 s on DiLink 3.0 (#381); drain the output and wait again instead. One drain and
        // retry is enough for that, and the pump always makes it. Waiting the video's 500 ms
        // for every packet let a slowed decoder fall ever further behind instead: with the
        // CarPlay screen in the background, the queue grew to 190 packets and the track
        // underran every few seconds (#649).
        val index = DecoderInputPump.acquire(
            running = { running }, drain = { drainCodec(codec) },
            dequeue = { codec.dequeueInputBuffer(INPUT_TIMEOUT_US) },
            timeoutNs = AUDIO_INPUT_WAIT_NS,
        )
        if (index < 0) {
            inputDropped++
            if (inputDropped == 1) {
                Log.w(
                    TAG,
                    "audio decoder input unavailable codec=${format.codec} " +
                        "queued=$inputQueued dropped=$inputDropped",
                )
            }
            return
        }
        // 精简移植：getInputBuffer 为 API 21+，使用缓冲区数组下标访问
        val input = (cachedInputBuffers ?: codec.inputBuffers.also { cachedInputBuffers = it })[index] ?: return
        input.clear()
        if (payload.size <= input.remaining()) {
            input.put(payload)
            codec.queueInputBuffer(index, 0, payload.size, presentationTimeUs, 0)
            inputQueued++
            if (!firstInputQueuedLogged) {
                firstInputQueuedLogged = true
                Log.i(
                    TAG,
                    "audio decoder first input codec=${format.codec} bytes=${payload.size}",
                )
            }
        } else {
            codec.queueInputBuffer(index, 0, 0, 0, 0)
            inputDropped++
        }
        drainCodec(codec)
    }

    private fun drainCodec(codec: MediaCodec) {
        diagnosticStage = "decoder-output"
        val info = MediaCodec.BufferInfo()
        while (running) {
            diagnosticStage = "decoder-output"
            val index = codec.dequeueOutputBuffer(info, 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // 精简移植：输出格式变化后缓冲区数组需重新获取（API 19 数组语义）
                    cachedOutputBuffers = null
                    // Vendor metadata inspection must not interrupt otherwise working playback.
                    if (decoderOutputReports < 4) runCatching {
                        val outputFormat = codec.outputFormat
                        val metadata = "rate=${outputFormat.intOrNull(MediaFormat.KEY_SAMPLE_RATE)} " +
                            "channels=${outputFormat.intOrNull(MediaFormat.KEY_CHANNEL_COUNT)} " +
                            "pcmEncoding=${outputFormat.intOrNull(MediaFormat.KEY_PCM_ENCODING)}"
                        if (metadata != lastDecoderOutputMetadata) {
                            lastDecoderOutputMetadata = metadata
                            decoderOutputReports++
                            report("Audio: decoded format audioType=${format.audioType} codec=${format.codec} $metadata")
                        }
                    }
                }
                index >= 0 -> {
                    val size = info.size
                    if (size > 0) {
                        outputBuffers++
                        if (outputBuffers == 1 || outputBuffers % DECODED_BUFFER_LOG_INTERVAL == 0) {
                            Log.i(
                                TAG,
                                "audio decoder output codec=${format.codec} " +
                                    "buffers=$outputBuffers bytes=$size " +
                                    "queued=$inputQueued dropped=$inputDropped",
                            )
                        }
                    }
                    var copied = false
                    try {
                        if (size > 0) {
                            // 精简移植：getOutputBuffer 为 API 21+，使用缓冲区数组下标访问
                            val output = (cachedOutputBuffers ?: codec.outputBuffers.also { cachedOutputBuffers = it })[index]
                            if (output != null) {
                                if (size > pcm.size) pcm = ByteArray(size)
                                output.position(info.offset)
                                output.limit(info.offset + size)
                                output.get(pcm, 0, size)
                                copied = true
                            }
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false)
                    }
                    if (copied) writePcm(pcm, 0, size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    private fun writePcm(data: ByteArray, offset: Int = 0, length: Int = data.size) {
        val track = track ?: return
        diagnosticStage = "track-write"
        if (!firstPcmLogged && length > 0) {
            firstPcmLogged = true
            Log.i(
                TAG,
                "audio first PCM type=${format.payloadType} bytes=$length",
            )
        }
        voiceFilter?.process(data, offset, length)
        applyNavigationAttenuation()
        attenuateNavigation(data, offset, length)
        if (!fadeApplied) {
            applyFadeIn(data, offset, length)
            fadeApplied = true
        }
        var written = 0
        while (written < length && running) {
            val writeLength = if (playbackStarted) {
                length - written
            } else {
                minOf(length - written, PREBUFFER_WRITE_CHUNK_BYTES)
            }
            val writeStarted = System.nanoTime()
            diagnosticStage = "track-write"
            // 精简移植：带 writeMode 的 write 重载为 API 21+；MODE_STREAM 下旧 write 本身即阻塞语义
            val count = track.write(data, offset + written, writeLength)
            maxWriteMs = maxOf(maxWriteMs, (System.nanoTime() - writeStarted) / 1_000_000L)
            if (count < 0) {
                writeErrorsThisWindow++
                lastWriteErrorCode = count
                break
            }
            if (count == 0) {
                zeroWritesThisWindow++
                break
            }
            if (count < writeLength) partialWritesThisWindow++
            val framesWritten = count / frameBytes
            written += count
            totalWrittenFrames += framesWritten
            writtenFramesThisWindow += framesWritten
            bufferProgress.written(count)
            lastPcmWriteNs = System.nanoTime()
            if (playbackStarted) reportNavigationPlayback(track)
            if (playbackStarted) trimClock(track)
            if (!playbackStarted) {
                prebufferBytes += count
                if (prebufferBytes >= startThresholdBytes) {
                    startPlayback(track)
                    Log.i(TAG, "audio playback started type=${format.payloadType}")
                }
            }
            echoReference?.let { reference ->
                val pending = if (playbackStarted) {
                    totalWrittenFrames - (track.playbackHeadPosition.toLong() and 0xffffffffL)
                } else {
                    null
                }
                reference.append(data, offset + written - count, count, pcmChannels, pending, System.nanoTime())
            }
        }
    }

    private fun startPlayback(track: AudioTrack) {
        diagnosticStage = "track-play"
        // 精简移植：getUnderrunCount 为 API 21+，恒为 0（欠载统计不可用）
        underrunsAtPlaybackStart = 0
        headAtPlaybackStart = track.playbackHeadPosition
        clockTrim?.measureAgain()
        track.play()
        playbackStarted = true
        reportNavigationPlayback(track)
        if (rebufferCount > 0 && rebufferRecoverReportCount < MAX_REBUFFER_REPORTS) {
            rebufferRecoverReportCount++
            report("Audio: rebuffer recovered audioType=${format.audioType} rebufferCount=$rebufferCount")
        }
    }

    private fun reportNavigationPlayback(track: AudioTrack) {
        if (!running) return
        val priorityVoice = mappedChannel == AudioChannel.PHONE || mappedChannel == AudioChannel.ASSISTANT
        if (mappedChannel != AudioChannel.NAVIGATION && !priorityVoice) return
        // The optional key-routing hint must never interrupt audio on unusual vendor ROMs.
        runCatching {
            val pendingFrames = bufferProgress.queuedBytes(track.playbackHeadPosition) / frameBytes
            val bufferedMillis = (pendingFrames * 1000L + format.sampleRate - 1L) / format.sampleRate
            NavigationPlayback.played(navigationPlaybackToken, actualLegacyStreamType, bufferedMillis, priorityVoice)
        }
    }

    // 精简移植：AudioRouting/AudioDeviceInfo 为 API 23+，API 19 无法查询通话路由设备，
    // 原听筒检测（checkCallRoute）不可用，onRoutedToEarpiece 不再触发。

    private fun trimClock(track: AudioTrack) {
        val trim = clockTrim ?: return
        val now = System.nanoTime()
        val compressedRtpFrames = mediaLagGuard?.pendingFrames(queue, newestRtpSample, AudioPacket::sample)
            ?: 0L
        val compressedPcmFrames = compressedRtpFrames * format.sampleRate /
            format.rtpClockRate.coerceAtLeast(1)
        val dropped = packetsDroppedTotal.get() + inputDropped
        if (dropped != droppedAtLastTrim) {
            droppedAtLastTrim = dropped
            trim.measureAgain()
        }
        val head = track.playbackHeadPosition
        trim.sample(bufferProgress.queuedBytes(head) / frameBytes, now, compressedPcmFrames,
            headMoving = head != headAtPlaybackStart, arrivedFrameNs = queuedFrameNsSince(trim.lastSampleNs, now))
        val rate = trim.rateDue(now) ?: return
        val result = runCatching { track.setPlaybackRate(rate) }.getOrDefault(AudioTrack.ERROR)
        if (result != AudioTrack.SUCCESS) {
            // The track keeps its rate; music then plays as it did before the trim.
            clockTrim = null
            report("Audio: clock trim off audioType=${format.audioType} rate=$rate result=$result")
        }
    }

    /** For the packets queued since [sinceNs], the frames each adds to the queue times how long it has waited. */
    private fun queuedFrameNsSince(sinceNs: Long, nowNs: Long): Double {
        val clockRate = format.rtpClockRate.coerceAtLeast(1)
        var frameNs = 0.0
        var previousSample: Int? = null
        for (packet in queue) {
            val previous = previousSample
            previousSample = packet.sample
            if (previous == null || packet.arrivedNs <= sinceNs) continue
            // As in MediaPacketLagGuard.pendingFrames, a packet adds the RTP time since the one before it, and a
            // jump of more than a second is the iPhone restarting its timestamps, not audio.
            val rtpFrames = (packet.sample.toLong() - previous) and 0xffff_ffffL
            if (rtpFrames > clockRate) continue
            frameNs += rtpFrames.toDouble() * format.sampleRate / clockRate * (nowNs - packet.arrivedNs)
        }
        return frameNs
    }

    private fun maintainPlaybackBuffer() {
        val track = track ?: return
        if (bufferProgress.shouldRebuffer(mappedChannel == AudioChannel.MEDIA, playbackStarted,
                // 精简移植：getUnderrunCount 为 API 21+，恒为 false（欠载触发不可用）
                false, queue.isEmpty(), track.playbackHeadPosition,
                startThresholdBytes / 2L)) {
            // The hardware buffer has starved below the recovery floor. Pause without flushing
            // or discarding PCM, then use the configured start threshold again when music resumes.
            track.pause()
            playbackStarted = false
            // Pausing retains queued PCM. Count it toward the restart threshold so a
            // blocking write cannot fill the paused track before we call play().
            prebufferBytes = bufferProgress.queuedBytes(track.playbackHeadPosition)
                .coerceAtMost(startThresholdBytes.toLong()).toInt()
            lastPcmWriteNs = System.nanoTime()
            rebufferCount++
            if (rebufferPauseReportCount < MAX_REBUFFER_REPORTS) {
                rebufferPauseReportCount++
                val retainedMs = if (bytesPerSecond > 0) prebufferBytes * 1000L / bytesPerSecond else 0L
                report("Audio: rebuffer paused audioType=${format.audioType} rebufferCount=$rebufferCount retainedMs=$retainedMs")
            }
        }
        // A short final burst may never reach the start threshold. Play it after a bounded wait.
        if (!playbackStarted && prebufferBytes > 0 && queue.isEmpty() &&
            System.nanoTime() - lastPcmWriteNs >= BUFFER_TAIL_WAIT_NS) {
            startPlayback(track)
        }
    }

    // Persist counters even during packet starvation, and flush before disconnect releases the track.
    private fun logStatsIfDue(force: Boolean = false) {
        val now = System.nanoTime()
        if (statsWindowStartNs == 0L) statsWindowStartNs = now
        if (!force && now - statsWindowStartNs < STATS_WINDOW_NS) return
        // 精简移植：getUnderrunCount 为 API 21+，恒为 0（欠载统计不可用）
        val underruns = 0
        val lastRx = lastArrivalNs.get()
        val currentTrack = track
        val playbackHeadFrames = currentTrack?.playbackHeadPosition
            ?.toLong()?.and(0xffff_ffffL)
        val playbackAdvanceFrames = playbackHeadFrames?.let { current ->
            val previous = lastPlaybackHeadFrames
            lastPlaybackHeadFrames = current
            previous?.let { (current - it) and 0xffff_ffffL }
        }
        val queuedFrames = playbackHeadFrames?.let { (totalWrittenFrames - it).coerceAtLeast(0L) }
        // 精简移植：getRoutedDevice/AudioDeviceInfo 为 API 23+，API 19 无法查询路由设备，
        // 原通话路由检测与 routeType 统计不可用（routeType 恒为 -1）。
        // "PHONE " marks the iPhone's own log lines, which the report drops, and it dropped every
        // call's playback line with it (#647).
        val channelName = if (mappedChannel == AudioChannel.PHONE) "CALL" else mappedChannel?.name
        val line = "audio stats audioType=${format.audioType} channel=$channelName " +
            "routeType=-1 codec=${format.codec} " +
            "trackState=${currentTrack?.state ?: -1} playState=${currentTrack?.playState ?: -1} " +
            "sampleRate=${currentTrack?.sampleRate ?: format.sampleRate} " +
            "trackBufferFrames=${trackBufferBytes / frameBytes} " +
            "rx=${packetsReceived.getAndSet(0)} " +
            "dropped=${packetsDropped.getAndSet(0)} underruns=+${underruns - statsLastUnderruns} queue=${queue.size} " +
            "playing=$playbackStarted maxGapMs=${maxArrivalGapMs.getAndSet(0)} " +
            "sinceRxMs=${if (lastRx == 0L) -1 else (now - lastRx) / 1_000_000L} maxWriteMs=$maxWriteMs " +
            "writtenFrames=$writtenFramesThisWindow totalWrittenFrames=$totalWrittenFrames " +
            "playbackHeadFrames=${playbackHeadFrames ?: -1} playbackAdvanceFrames=${playbackAdvanceFrames ?: -1} " +
            "estimatedQueuedFrames=${queuedFrames ?: -1} writeErrors=$writeErrorsThisWindow " +
            "lastWriteError=${lastWriteErrorCode ?: "none"} zeroWrites=$zeroWritesThisWindow " +
            "partialWrites=$partialWritesThisWindow " +
            "decoderDroppedTotal=$inputDropped outputBuffersTotal=$outputBuffers rebuffers=$rebufferCount " +
            "playbackRate=${clockTrim?.rate ?: -1} ended=$force"
        Log.i(STATS_TAG, line)
        report(line)
        if (format.codec != AudioCodecKind.LPCM) {
            // Keep this separate: the exported diagnostic recorder caps each line at 700 characters.
            val decoderLine = "Audio: decoder stats audioType=${format.audioType} codec=${format.codec} " +
                "inputQueuedTotal=$inputQueued inputDroppedTotal=$inputDropped " +
                "shortOpusPacketsTotal=$shortOpusPackets decoderUnavailablePacketsTotal=$decoderUnavailablePackets " +
                "outputBuffersTotal=$outputBuffers ended=$force"
            Log.i(STATS_TAG, decoderLine)
            runCatching { report(decoderLine) }
        }
        statsLastUnderruns = underruns
        maxWriteMs = 0L
        writtenFramesThisWindow = 0L
        writeErrorsThisWindow = 0
        lastWriteErrorCode = null
        zeroWritesThisWindow = 0
        partialWritesThisWindow = 0
        statsWindowStartNs = now
    }

    private fun reportFailure(error: Throwable) {
        runCatching { report("Audio: renderer failed " +
            "audioType=${format.audioType} codec=${format.codec} stage=$diagnosticStage " +
            MediaFailureSummary.describe(error)) }
    }

    private fun applyFadeIn(data: ByteArray, offset: Int, length: Int) {
        val samples = (length - length % 2) / 2
        val fadeSamples = minOf(samples, maxOf(1, format.sampleRate / 100))
        for (index in 0 until fadeSamples) {
            val position = offset + index * 2
            val sample = (data[position].toInt() and 0xff) or (data[position + 1].toInt() shl 8)
            val scaled = (sample.toLong() * (index + 1) / fadeSamples).toInt()
            data[position] = scaled.toByte()
            data[position + 1] = (scaled shr 8).toByte()
        }
    }

    private fun byteSwapS16(source: ByteArray): ByteArray {
        for (index in 0 until source.size - 1 step 2) {
            val tmp = source[index]
            source[index] = source[index + 1]
            source[index + 1] = tmp
        }
        return source
    }

    @Synchronized
    private fun release() {
        NavigationPlayback.close(navigationPlaybackToken)
        abandonAudioFocus()
        val codec = codec
        this.codec = null
        if (codec != null) {
            try {
                codec.stop()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                codec.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
        val track = track
        this.track = null
        if (track != null) {
            try {
                track.pause()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.flush()
            } catch (_: Exception) {
                // Best effort.
            }
            try {
                track.release()
            } catch (_: Exception) {
                // Best effort.
            }
        }
    }

    private companion object {
        const val TAG = "xcertplay-usb"
        const val AAC_OBJECT_TYPE_LC = 2
        const val MIN_OPUS_PACKET_BYTES = 4
        const val OPUS_CODEC_DELAY_NANOS = 6_500_000L
        const val OPUS_SEEK_PRE_ROLL_NANOS = 80_000_000L
        const val INPUT_TIMEOUT_US = 10_000L
        const val AUDIO_INPUT_WAIT_NS = 100_000_000L
        const val AUDIO_POLL_MILLIS = 10L
        const val BUFFER_TAIL_WAIT_NS = 500_000_000L
        // Holds a burst after a Wi-Fi gap (~4 s of AAC) instead of dropping it.
        const val MAX_QUEUED_PACKETS = 192
        const val PREBUFFER_WRITE_CHUNK_BYTES = 2 * 1024
        const val STATS_TAG = "DiPlay-AudioStats"
        const val STATS_WINDOW_NS = 5_000_000_000L
        const val DECODED_BUFFER_LOG_INTERVAL = 50
        const val MAX_REBUFFER_REPORTS = 5
        const val MAX_QUEUE_FULL_REPORTS = 5
    }
}
