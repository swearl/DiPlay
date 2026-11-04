package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.Button
import com.shilapi.xcertplay.compat.ColorBlend
import com.shilapi.xcertplay.compat.CompatUi

internal enum class DiPlayButtonKind { PRIMARY, SECONDARY, DANGER, TONAL_DANGER }

/**
 * Button for views built in code outside `DiPlayActivity`, whose own `button()` stays private.
 * The corner radius follows the control height, as in `DiPlayActivity.buttonShape`.
 */
internal class DiPlayButton(context: Context) : Button(context) {
    private var kind = DiPlayButtonKind.SECONDARY
    private var palette: DiPlayPalette? = null
    private var overlay = false

    init {
        isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    /** [overlay] picks the in-car menu colours instead of the app chrome colours. */
    fun style(kind: DiPlayButtonKind, palette: DiPlayPalette, overlay: Boolean) {
        this.kind = kind
        this.palette = palette
        this.overlay = overlay
        repaint()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h != oldh) repaint()
    }

    private fun repaint() {
        val palette = palette ?: return
        val density = resources.displayMetrics.density
        val fill: Int
        val text: Int
        val stroke: Int
        when (kind) {
            DiPlayButtonKind.PRIMARY -> {
                fill = if (overlay) palette.overlayAccent else palette.accent
                text = if (overlay) palette.overlayOnAccent else palette.onAccent
                stroke = fill
            }
            DiPlayButtonKind.SECONDARY -> {
                fill = if (overlay) palette.overlayTrackOff else palette.button
                text = if (overlay) palette.overlayPrimaryText else palette.primaryText
                stroke = if (overlay) fill else palette.outline
            }
            DiPlayButtonKind.DANGER -> {
                fill = palette.overlayDanger
                text = Color.WHITE
                stroke = fill
            }
            DiPlayButtonKind.TONAL_DANGER -> {
                val surface = if (overlay) palette.overlayBackground else palette.surface
                fill = ColorBlend.blendARGB(surface, palette.overlayDanger, 0.16f)
                text = if (ColorBlend.calculateLuminance(surface) < 0.5)
                    ColorBlend.blendARGB(palette.overlayDanger, Color.WHITE, 0.40f)
                else palette.overlayDanger
                stroke = ColorBlend.blendARGB(surface, palette.overlayDanger, 0.48f)
            }
        }
        val radius = minOf(24f * density, (if (height > 0) height else minHeight) * 20f / 56)
        setTextColor(text)
        val shape = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius
            setStroke((1 * density).toInt().coerceAtLeast(1), stroke)
        }
        // API 19 无 RippleDrawable/foreground：按压变色 + 焦点描边合入背景状态列表
        background = CompatUi.pressable(shape, palette.ripple, GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            cornerRadius = radius
            setStroke((3 * density).toInt(), palette.focusRing)
        })
    }
}
