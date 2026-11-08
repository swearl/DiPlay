package com.shilapi.xcertplay.media

import android.view.Surface
import com.shilapi.xcertplay.airplay.VideoCodec
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal sealed interface VideoJob {
    data class Config(val codec: VideoCodec, val codecData: ByteArray) : VideoJob
    data class Frame(
        val nalus: ByteArray,
        val receivedNs: Long = System.nanoTime(),
        /** The iPhone's frame time and when the frame arrived (System.nanoTime), 0 when unknown. */
        val senderNanos: Long = 0L,
        val arrivalNanos: Long = 0L,
    ) : VideoJob
    data class SurfaceChanged(val surface: Surface?) : VideoJob
    /** Stop rendering to a surface that is going away; completes [request] once the codec has let go. */
    data class DetachSurface(val request: SurfaceDetachRequest) : VideoJob
    /** Ask the iPhone for a keyframe, so a surface that just came back gets a picture without waiting for motion. */
    data object RefreshPicture : VideoJob
    data object Resync : VideoJob
}

/**
 * Do not resume dependent pictures after losing a reference frame.
 * 精简移植：参考链三态状态机。queueInputBuffer 成功只代表帧进入输入队列，不代表老 OMX/VPU
 * 硬件解码器真正解码成功（IDR 可能被静默丢弃，应用层无任何异常）。因此 IDR 需等到首个解码
 * 输出后才确认参考链，期间 P 帧不入队：花屏窗口归零，代价仅为解码器重建后约一帧的额外延迟。
 */
internal class VideoReferenceChain {
    private enum class State {
        /** 等待 IDR 入队。 */
        WAIT_KEY,
        /** IDR 已入队，等待其产生解码输出。 */
        PENDING_CONFIRM,
        /** 参考链已建立，所有帧放行。 */
        ESTABLISHED,
    }

    // feed 只在 worker 线程写入；输出确认可能在输出线程（若启用）执行。
    // @Volatile 保证可见性；与 worker 的写竞争最坏只延迟一次确认，随后由超时恢复兜底，无需加锁。
    @Volatile private var state = State.WAIT_KEY
    @Volatile private var pendingSinceNanos = 0L

    /** 参考链未建立（等待 IDR 或等待 IDR 输出确认）时为 true，沿用旧语义供恢复/请求关键帧判定。 */
    val needsKeyFrame: Boolean
        get() = state != State.ESTABLISHED

    fun reset() {
        state = State.WAIT_KEY
        pendingSinceNanos = 0L
    }

    /** 仅放行随机访问帧；待确认期间新 IDR 也放行（重新武装超时，替换丢失的旧 IDR）。 */
    fun accepts(isRandomAccess: Boolean): Boolean =
        state == State.ESTABLISHED || isRandomAccess

    /** 关键帧入队成功后调用；参考链已建立时无需再次确认。 */
    fun onKeyFrameQueued(nowNanos: Long) {
        if (state == State.ESTABLISHED) return
        state = State.PENDING_CONFIRM
        pendingSinceNanos = nowNanos
    }

    /**
     * 若处于待确认状态则切换为已建立并返回 IDR 入队时刻（用于诊断延迟），否则返回 0。
     * 只应由看到解码输出的线程调用。
     */
    fun takePendingConfirmation(nowNanos: Long): Long {
        if (state != State.PENDING_CONFIRM) return 0L
        val queuedAt = pendingSinceNanos
        state = State.ESTABLISHED
        pendingSinceNanos = 0L
        return queuedAt
    }

    /** IDR 入队后是否超过 [timeoutNanos] 仍无解码输出（硬解码器可能已静默丢弃关键帧）。 */
    fun isPendingConfirmation(nowNanos: Long, timeoutNanos: Long): Boolean =
        state == State.PENDING_CONFIRM && nowNanos - pendingSinceNanos >= timeoutNanos
}

/** Limit latency and memory without ever dropping a reference frame silently. */
internal class VideoDecodeQueue(
    // Recovery observes sustained backlog; short delayed bursts keep their reference chain.
    private val maxFrames: Int = 60,
    private val maxBytes: Int = 8 * 1024 * 1024,
) {
    private val jobs = LinkedBlockingQueue<VideoJob>()

    data class Backlog(val pendingFrames: Int, val newestPendingReceivedNs: Long?)

    /** Called by the sole consumer after taking its current frame. Control jobs end this chain. */
    @Synchronized fun backlogAfterCurrent(): Backlog {
        var frames = 0
        var newest: Long? = null
        for (job in jobs) {
            if (job !is VideoJob.Frame) break
            frames++
            newest = newest?.let { maxOf(it, job.receivedNs) } ?: job.receivedNs
        }
        return Backlog(frames, newest)
    }

    @Synchronized fun offer(job: VideoJob) {
        if (job is VideoJob.Frame) {
            val frames = jobs.filterIsInstance<VideoJob.Frame>()
            if (frames.size >= maxFrames || frames.sumOf { it.nalus.size.toLong() } + job.nalus.size > maxBytes) {
                discardFrames()
                jobs.offer(VideoJob.Resync)
            }
            // A single oversized frame is also a lost reference chain.
            if (job.nalus.size > maxBytes) return
        }
        jobs.offer(job)
    }

    /** Drop only the invalidated chain; the next config/surface owns later frames. */
    @Synchronized fun discardCurrentChain() {
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            when (iterator.next()) {
                is VideoJob.Frame, VideoJob.Resync -> iterator.remove()
                else -> return
            }
        }
    }

    @Synchronized fun discardFrames() {
        // 精简移植：Collection.removeIf 为 API 24+，改用迭代器循环删除
        val iterator = jobs.iterator()
        while (iterator.hasNext()) {
            val job = iterator.next()
            if (job is VideoJob.Frame || job is VideoJob.Resync) iterator.remove()
        }
    }

    fun poll(timeoutMillis: Long): VideoJob? = jobs.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Removes and returns every queued job, for a worker that is shutting down. */
    @Synchronized fun drain(): List<VideoJob> = ArrayList<VideoJob>().also { jobs.drainTo(it) }
}

/** Drain output while waiting for input: full output buffers can otherwise starve input forever. Video and audio decoders share it. */
internal object DecoderInputPump {
    fun acquire(
        running: () -> Boolean,
        drain: () -> Unit,
        dequeue: () -> Int,
        nanoTime: () -> Long = System::nanoTime,
        timeoutNs: Long = TimeUnit.MILLISECONDS.toNanos(500),
    ): Int {
        val start = nanoTime()
        while (running()) {
            drain()
            val index = dequeue()
            if (index >= 0) return index
            if (nanoTime() - start >= timeoutNs) break
        }
        return -1
    }
}
