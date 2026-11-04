package com.shilapi.xcertplay

import android.content.Context

/**
 * 精简移植：氛围灯功能已删除（AmbientMusicSettings / AmbientIdleBehavior 等类均已移除）。
 * 这里仅保留 DiPlayActivity 遗留调用点所需的最小桩，恒返回空文案；
 * 待 DiPlayActivity 清理氛围灯设置代码后，整体删除本文件。
 */
internal object AmbientBehaviorText {
    fun lines(context: Context, values: Any?): List<String> = emptyList()
}
