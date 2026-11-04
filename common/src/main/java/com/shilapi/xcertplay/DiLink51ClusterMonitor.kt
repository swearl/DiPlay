package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * BYD DiLink 5.1 仪表活动监控。API 19 没有 UsageStatsManager/使用情况访问（API 21+），
 * 无法获得前台事件，因此始终上报空快照（不显示叠加层）；保留回调节奏供调用方复用。
 */
internal class DiLink51ClusterMonitor(context: Context, private val onState: (ClusterActivityState.Snapshot) -> Unit) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val state = ClusterActivityState()
    @Volatile private var stopped = false

    fun start() {
        executor.scheduleWithFixedDelay({ poll() }, 0, 500, TimeUnit.MILLISECONDS)
    }

    fun stop() {
        stopped = true
        executor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
    }

    private fun poll() {
        if (stopped) return
        val snapshot = state.snapshot() // 无可靠信号意味着不显示叠加层
        handler.post { if (!stopped) onState(snapshot) }
    }

    companion object {
        /** API 19 无使用情况访问机制，恒为 false。 */
        @Suppress("UNUSED_PARAMETER")
        fun hasAccess(context: Context): Boolean = false
    }
}
