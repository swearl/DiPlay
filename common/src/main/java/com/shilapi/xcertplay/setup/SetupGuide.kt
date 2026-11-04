package com.shilapi.xcertplay.setup

import android.content.Context
import com.shilapi.xcertplay.AirPlayPersistence

/**
 * 首次启动设置向导（精简移植：删除 BYD DiLink 代际探测与 BYD 导航等专属特性，
 * 仅保留通用引导步骤与基础特性开关）。
 */
object SetupGuide {
    const val STEP_CAR = 0
    const val STEP_CONNECTION = 1
    const val STEP_IPHONE = 2
    const val STEP_FEATURES = 3
    const val STEP_DONE = 4
    const val STEP_COUNT = 5

    enum class Feature { AUTO_CONNECT, LOCATION, CLUSTER_MAP, CALLS_ON_DASHBOARD, CALL_KEYS }

    /** TESTED: confirmed on at least one car. HIDDEN: not offered in the guide. */
    enum class Status { TESTED, EXPERIMENTAL, HIDDEN }

    data class Entry(val feature: Feature, val status: Status, val needsAdb: Boolean = false)

    fun features(): List<Entry> = listOf(
        Entry(Feature.AUTO_CONNECT, Status.TESTED),
        Entry(Feature.LOCATION, Status.TESTED),
        Entry(Feature.CLUSTER_MAP, Status.EXPERIMENTAL),
        Entry(Feature.CALLS_ON_DASHBOARD, Status.EXPERIMENTAL),
        Entry(Feature.CALL_KEYS, Status.EXPERIMENTAL),
    )

    /**
     * Applies the defaults when the driver confirms the car on a first setup only. A driver who
     * re-runs the guide, or set DiPlay up before the guide existed, keeps the routing left on automatic.
     */
    fun applyFirstSetupDefaults(context: Context, phoneChosen: Boolean): Boolean =
        shouldOpenOnLaunch(seen(context), phoneChosen) &&
            AirPlayPersistence.applySetupNavigationAudioChannelDefault(context, 0)

    private fun prefs(context: Context) = context.getSharedPreferences("diplay", Context.MODE_PRIVATE)

    fun seen(context: Context): Boolean = prefs(context).getBoolean("setup_guide_seen", false)

    fun markSeen(context: Context) {
        prefs(context).edit().putBoolean("setup_guide_seen", true).apply()
    }

    /** Only a first launch opens the guide by itself; anyone with a saved iPhone has already set up. */
    fun shouldOpenOnLaunch(seen: Boolean, phoneChosen: Boolean): Boolean = !seen && !phoneChosen
}
