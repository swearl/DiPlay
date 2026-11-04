package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * 精简移植：车内视频（HLS 播放器）功能已裁剪，原实现（含 CarPlayVideoActivity）已删除。
 * 这里仅保留遗留调用点所需的最小桩：
 *  - CarPlayHostActivity 在会话附加时调用 [attach]（现为空操作）；
 *  - CarPlayMediaKeys 在媒体键处理时调用 [onMediaKey]（恒返回 false，表示没有播放器在前台）。
 * 待 CarPlayHostActivity / CarPlayMediaKeys 清理视频相关调用后，整体删除本文件。
 */
internal object CarPlayVideo {
    fun attach(context: Context, next: CarPlayController) = Unit
    fun onMediaKey(index: Int): Boolean = false
}
