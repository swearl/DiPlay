// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.compat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter

/** API 19 视觉兼容工具：无 RippleDrawable / View.foreground（API 21/23）时的等价降级实现。 */
internal object CompatUi {
    /**
     * 用按压态变色的 [StateListDrawable] 替代涟漪效果：按压时以 [rippleColor] 半透明叠加在
     * [normal] 形状之上，其余状态保持原样。可传入 [focused] 描边形状，供遥控器/方向键导航
     * 显示焦点（替代 API 23 才有的 View.foreground 焦点环）。
     */
    fun pressable(normal: GradientDrawable, rippleColor: Int, focused: GradientDrawable? = null): Drawable {
        val pressed = normal.constantState?.newDrawable()?.mutate() as? GradientDrawable ?: normal
        // 半透明叠加：SRC_ATOP 保留形状底色，只混入涟漪色
        val baseAlpha = rippleColor ushr 24
        val alpha = if (baseAlpha >= 0xFF) 0x40 else (baseAlpha / 4).coerceIn(1, 0xFF)
        pressed.colorFilter =
            PorterDuffColorFilter((alpha shl 24) or (rippleColor and 0xFFFFFF), PorterDuff.Mode.SRC_ATOP)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            focused?.let { addState(intArrayOf(android.R.attr.state_focused), it) }
            addState(intArrayOf(), normal)
        }
    }
}
