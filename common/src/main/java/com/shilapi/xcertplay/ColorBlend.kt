package com.shilapi.xcertplay.compat

import android.graphics.Color

/**
 * 精简移植：替代 androidx.core.graphics.ColorUtils 的最小颜色工具。
 * 仅实现项目内实际用到的颜色混合函数。
 */
internal object ColorBlend {

    /** 在 [colorStart] 与 [colorEnd] 之间按 [ratio]（0..1）做线性插值。 */
    fun blendARGB(colorStart: Int, colorEnd: Int, ratio: Float): Int {
        val r = (ratio.coerceIn(0f, 1f) * 255f).toInt()
        val inverse = 255 - r
        val a = (colorStart ushr 24) * inverse / 255 + (colorEnd ushr 24) * r / 255
        val red = (colorStart shr 16 and 0xff) * inverse / 255 + (colorEnd shr 16 and 0xff) * r / 255
        val green = (colorStart shr 8 and 0xff) * inverse / 255 + (colorEnd shr 8 and 0xff) * r / 255
        val blue = (colorStart and 0xff) * inverse / 255 + (colorEnd and 0xff) * r / 255
        return a shl 24 or (red shl 16) or (green shl 8) or blue
    }

    /** 设置 [color] 的 alpha 分量。 */
    fun setAlphaComponent(color: Int, alpha: Int): Int {
        require(alpha in 0..255) { "alpha must be in 0..255" }
        return color and 0x00ffffff or (alpha shl 24)
    }

    /** 计算颜色的相对亮度（sRGB 加权），用于在黑/白前景色之间按对比度选择。 */
    fun calculateLuminance(color: Int): Double {
        fun component(c: Int): Double {
            val s = c / 255.0
            return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * component(Color.red(color)) +
            0.7152 * component(Color.green(color)) +
            0.0722 * component(Color.blue(color))
    }
}
