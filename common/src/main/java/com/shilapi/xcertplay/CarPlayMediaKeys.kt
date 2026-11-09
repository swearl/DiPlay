package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.airplay.CarPlayMediaButton
import com.shilapi.xcertplay.compat.AudioAttributesCompat
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.media.CarPlayNowPlaying
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * Steering-wheel and other hardware media buttons for CarPlay.
 *
 * Android delivers media keys to a media session; some head units pick the session of the
 * audio-focus owner. DiPlay normally holds focus and an active session until the CarPlay session
 * ends, but focus can be disabled on head units that otherwise route physical volume buttons to
 * DiPlay. Keys go to the iPhone as CarPlay media HID presses ([CarPlayMediaButton]).
 */
internal object CarPlayMediaKeys {
    private const val TAG = "DiPlay-MediaKeys"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controller: CarPlayController? = null
    private var focusRequest: AudioFocusRequestCompat? = null
    private var focusOwner: Any? = null
    private var focusEventRevision = 0L
    private var focusHeld = false
    /** Some car firmware never sends a gain callback after the phone call ends. */
    private var transientFocusInterrupted = false
    private var appContext: Context? = null
    private var mediaAudioActive = false
    private var mediaKeysStarted = false
    private var nowPlaying = CarPlayNowPlaying()

    @Synchronized
    fun attach(context: Context, next: CarPlayController) {
        if (controller !== next) releaseLocked()
        appContext = context.applicationContext
        controller = next
        // 精简移植：GeelyWheelKeys 与 AmbientMusicController（氛围灯播放联动）已删除
        next.playbackListener = { playing ->
            onIphonePlaying(next, playing)
        }
        next.nowPlayingListener = { _ -> onNowPlayingChanged(next) }
        onNowPlayingChanged(next)
    }

    /** Ends key handling for [expected]; a newer controller's state is left alone. */
    @Synchronized
    fun detach(expected: CarPlayController?) {
        if (expected == null || controller !== expected) return
        expected.playbackListener = null
        expected.nowPlayingListener = null
        controller = null
        releaseLocked()
    }

    /** Called when CarPlay music starts or stops; may run on any thread. */
    fun onMediaAudioChanged(active: Boolean) {
        Log.i(TAG, "media audio active=$active")
        mainHandler.post { synchronized(this) { updateLocked(active) } }
    }

    /** The iPhone started or stopped playing; may run on any thread. */
    private fun onIphonePlaying(expected: CarPlayController, playing: Boolean) {
        // 音乐播放态回调日志：仅在状态变化时触发（由 CarPlayController 去重），直接输出即可
        Log.i(TAG, "playback playing=$playing")
        if (playing) mainHandler.post {
            synchronized(this) {
                if (controller === expected) regainFocusLocked(onIphonePlaybackResume = true)
            }
        }
    }

    /** 处理 iPhone 元数据回调；API 19 无系统媒体会话，仅保留状态记录与回调日志。 */
    private fun onNowPlayingChanged(expected: CarPlayController) {
        mainHandler.post {
            synchronized(this) {
                if (controller !== expected) return@synchronized
                // Resolve retained state at delivery: a queued callback cannot roll a replay back.
                val update = expected.nowPlayingSnapshot()
                val changed = metadataChanged(nowPlaying, update)
                nowPlaying = update
                // 音乐元数据回调日志：进度更新不打印，仅歌名/歌手等元数据变化时输出，避免刷屏
                if (changed) Log.i(TAG,
                    "now playing title=${update.title ?: "-"} artist=${update.artist ?: "-"} " +
                        "album=${update.album ?: "-"} app=${update.sourceApp ?: "-"} playing=${update.playing}")
            }
        }
    }

    // Another car app (its own Spotify, the radio) took audio focus and with it the steering-wheel
    // keys. When CarPlay starts playing again it becomes the car's media source again, as any player
    // would; only the start counts, so a car source picked while the iPhone plays on is not undone.
    private fun regainFocusLocked() = regainFocusLocked(onIphonePlaybackResume = false)

    private fun regainFocusLocked(onIphonePlaybackResume: Boolean) {
        val request = focusRequest ?: return
        // Do not compete with an ongoing call. Only a fresh iPhone play transition may
        // retry a transient loss for which the head unit never sent AUDIOFOCUS_GAIN.
        if (transientFocusInterrupted && !onIphonePlaybackResume) return
        if (focusHeld && !transientFocusInterrupted) return
        val audio = appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        focusHeld = request.request(audio) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (focusHeld) {
            transientFocusInterrupted = false
            forwardGrantedFocusLocked()
        }
        Log.i(TAG, "audio focus regained=$focusHeld")
    }

    private fun updateLocked(active: Boolean) {
        val context = appContext ?: return
        if (controller == null) return
        mediaAudioActive = active
        // API 19 无 MediaSession：以 started 标志代替原先"session == null"的首次启动判断
        if (active && !mediaKeysStarted) { mediaKeysStarted = true; start(context) } else if (active) regainFocusLocked()
    }

    private fun start(context: Context) {
        val expectedController = controller ?: return
        val focusDisabled = AirPlayPersistence.loadDisableMediaKeyAudioFocus(context)
        transientFocusInterrupted = false
        val granted = if (focusDisabled) {
            // Some OEMs route physical volume buttons to the focus owner. Keep an active
            // MediaSession without permanent focus so those buttons remain vehicle-owned.
            focusOwner = null
            focusRequest = null
            focusHeld = false
            false
        } else {
            val owner = Any().also { focusOwner = it }
            focusEventRevision = 0L
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val request = AudioFocusRequestCompat(
                AudioManager.AUDIOFOCUS_GAIN,
                // 精简移植：AudioAttributes 为 API 21+，改用 int 语义载体
                AudioAttributesCompat(AudioAttributes.USAGE_MEDIA, AudioAttributes.CONTENT_TYPE_MUSIC),
                { change -> onFocusChanged(expectedController, owner, change) },
                mainHandler,
            )
            focusRequest = request
            (audio?.let(request::request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED).also {
                focusHeld = it
                if (it) forwardGrantedFocusLocked()
            }
        }
        // API 19 无 MediaSession（API 21）：不创建系统媒体会话，物理媒体键降级为仅外部方向盘按键。
        Log.i(TAG, "media keys active focusDisabled=$focusDisabled focusGranted=$granted")
        WheelKeyLog.note("media keys active focusDisabled=$focusDisabled focusGranted=$granted")
    }

    private fun forwardGrantedFocusLocked() {
        val expectedController = controller ?: return
        val owner = focusOwner ?: return
        val revision = focusEventRevision
        // Immediate grants do not promise a later focus callback. Defer dispatch until the caller
        // releases the media-key monitor. A newer real focus event invalidates this observation,
        // as do a controller or request replacement while the queued work waits.
        mainHandler.post { onFocusChanged(expectedController, owner, AudioManager.AUDIOFOCUS_GAIN, revision) }
    }

    private fun onFocusChanged(expectedController: CarPlayController, owner: Any, change: Int,
        grantedRevision: Long? = null) {
        val current = synchronized(this) {
            if (controller !== expectedController || focusOwner !== owner ||
                (grantedRevision != null && grantedRevision != focusEventRevision)) false
            else {
                focusEventRevision += 1
                // Only permanent loss moves media keys elsewhere; transient losses come back.
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        focusHeld = false
                        transientFocusInterrupted = false
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> transientFocusInterrupted = true
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusHeld = true
                        transientFocusInterrupted = false
                    }
                }
                true
            }
        }
        if (!current) return
        Log.i(TAG, "audio focus change=$change")
        WheelKeyLog.note("media keys audio focus change=$change")
        // Resolve the matching sink and invoke it outside the media-key monitor. An abandoned
        // request must never mute a newer controller, and these owners must not nest locks.
        val background = CarPlayBackgroundSession.snapshot()
        if (background?.controller === expectedController) background.sink.onMediaAudioFocusChanged(change)
    }

    private fun releaseLocked() {
        focusOwner = null
        mediaAudioActive = false
        mediaKeysStarted = false
        nowPlaying = CarPlayNowPlaying()
        focusRequest?.let { request -> (appContext?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.let(request::abandon) }
        focusRequest = null
        focusHeld = false
        transientFocusInterrupted = false
    }

    private fun send(index: Int, source: String) {
        // While the car's video player is on screen the wheel drives it: a CarPlay play/pause would
        // make the iPhone end the video session.
        if (CarPlayVideo.onMediaKey(index)) {
            Log.i(TAG, "media key $source -> car video player $index")
            WheelKeyLog.note("media key $source -> car video player $index")
            return
        }
        val sent = synchronized(this) { controller }?.sendMediaButton(index) ?: false
        Log.i(TAG, "media key $source -> CarPlay $index sent=$sent")
        WheelKeyLog.note("media key $source -> CarPlay $index sent=$sent")
    }

    private fun notUsed(source: String) {
        Log.i(TAG, "media key $source not used by CarPlay")
        WheelKeyLog.note("media key $source not used by CarPlay")
    }

    /** A steering-wheel media key captured outside the Android key stream (Geely/Lynk). */
    internal fun onExternalMediaKey(index: Int) = send(index, "geely-wheel")

    /** The steering-wheel microphone key captured outside the Android key stream (Geely/Lynk). */
    internal fun onExternalSiriKey(): Boolean {
        val active = synchronized(this) { controller } ?: return false
        return runCatching { active.requestSiri() }.getOrDefault(false)
    }

    /** Whether [next] changes what the media session's metadata shows; position and play state do not. */
    private fun metadataChanged(previous: CarPlayNowPlaying, next: CarPlayNowPlaying): Boolean =
        previous.copy(elapsedMillis = null, playing = false) != next.copy(elapsedMillis = null, playing = false)
}

/**
 * Media 键事件 → CarPlay 按键转发。
 * API 19 无 MediaSession.Callback（API 21），保留纯逻辑供外部键事件源调用；
 * 一个不属于 CarPlay 的按键交给 [notUsed]：车机发送自己的方向盘键码时会出现在报告里，
 * 而不是看起来像什么都没发（#610）。
 */
internal class CarPlayMediaCallback(
    private val experimentalDiLink3Keys: () -> Boolean = { false },
    private val notUsed: (source: String) -> Unit = {},
    private val send: (index: Int, source: String) -> Unit,
) {
    fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        val press = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        val index = CarPlayMediaButton.forKeyCode(event.keyCode, experimentalDiLink3Keys())
        if (index == null) {
            if (press) notUsed(KeyEvent.keyCodeToString(event.keyCode))
            return false
        }
        if (press) send(index, KeyEvent.keyCodeToString(event.keyCode))
        return true
    }
}
