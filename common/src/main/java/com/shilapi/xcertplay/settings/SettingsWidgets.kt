package com.shilapi.xcertplay.settings

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.airplay.CarPlayDisplayScale
import com.shilapi.xcertplay.compat.ColorBlend
import com.shilapi.xcertplay.host.R
import kotlin.math.roundToInt

object SettingsWidgets {

    /**
     * 圆形滑块 Drawable：按 bounds 的短边在中心绘制正圆。
     * API 19 的 Switch 会把 thumb 的 bounds 拉伸到整个开关高度，
     * GradientDrawable(OVAL) 填满 bounds 会变成椭圆，此实现保证任意拉伸下始终为圆。
     */
    private class CircleThumbDrawable(private val color: Int, private val density: Float) : android.graphics.drawable.Drawable() {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private var alphaValue = 255

        override fun draw(canvas: android.graphics.Canvas) {
            val w = bounds.width().toFloat()
            val h = bounds.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.color = color
            paint.alpha = alphaValue
            // 取短边的一半作为半径，在 bounds 中心画正圆
            val radius = minOf(w, h) / 2f
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            alphaValue = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

        // 提供与原方案一致的固有尺寸（28dp），供 Switch 测量定位
        override fun getIntrinsicWidth(): Int = (28 * density).toInt()

        override fun getIntrinsicHeight(): Int = (28 * density).toInt()
    }

    private fun TextView.applyTextStyle(context: Context, theme: SettingsTheme, bold: Boolean = false) {
        if (theme.isOverlay) {
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            includeFontPadding = false
        } else {
            typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            setLineSpacing(theme.dp(context, 3).toFloat(), 1f)
        }
    }

    data class SwitchRowResult(
        val rowView: View,
        val switch: Switch,
    )

    data class ChoiceRowResult<T>(
        val container: View,
        val radioGroup: RadioGroup,
    )

    data class ResolutionSliderResult(
        val container: View,
        val valueTextView: TextView,
        val seekBar: SeekBar,
    )

    fun createCategoryHeader(
        context: Context,
        title: String,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
    ): TextView = TextView(context).apply {
        text = title
        textSize = if (theme.isOverlay) 16f else 22f
        setTextColor(if (theme.isOverlay) theme.accent else theme.textPrimary)
        applyTextStyle(context, theme, bold = true)
    }

    /**
     * Replaces the platform switch (about a 36x14dp track) with a 60x34dp track and a 28dp thumb,
     * so the state reads at arm's length on a head unit.
     */
    /**
     * 单选按钮强调色：用状态绘制的自绘 radio drawable 替换 buttonTintList
     * （CompoundButton.setButtonTintList 为 API 21+，Android 4.4 上抛 NoSuchMethodError）。
     * 选中：描边圆环 + 中心圆点（LayerDrawable + setLayerInset，纯 API 19 API）；
     * 未选：仅描边圆环；禁用：整体降透明。
     */
    fun applyRadioAccent(button: android.widget.CompoundButton, checkedColor: Int, uncheckedColor: Int) {
        val density = button.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        fun ring(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(px(2), color)
        }
        fun dot(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        fun checkedDrawable() = android.graphics.drawable.LayerDrawable(
            arrayOf(ring(checkedColor), dot(checkedColor)),
        ).apply {
            val inset = px(5)
            setLayerInset(1, inset, inset, inset, inset)
        }
        button.buttonDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(
                intArrayOf(-android.R.attr.state_enabled),
                ring(ColorBlend.setAlphaComponent(uncheckedColor, 0x61)),
            )
            addState(intArrayOf(android.R.attr.state_checked), checkedDrawable())
            addState(intArrayOf(), ring(uncheckedColor))
        }
    }

    fun applyLargeSwitchStyle(switch: Switch, thumbOn: Int, thumbOff: Int, trackOn: Int, trackOff: Int) {
        val density = switch.resources.displayMetrics.density
        fun px(value: Int) = (value * density).roundToInt()
        // API 19 的 Switch 会把 thumb 的 bounds 拉伸到整个开关高度，
        // GradientDrawable(OVAL) 填满 bounds 会变成椭圆，改用按短边画正圆的自定义 Drawable。
        switch.thumbDrawable = android.graphics.drawable.StateListDrawable().apply {
            fun thumb(color: Int) = CircleThumbDrawable(color, density)
            addState(intArrayOf(-android.R.attr.state_enabled), thumb(ColorBlend.setAlphaComponent(thumbOff, 0x61)))
            addState(intArrayOf(android.R.attr.state_checked), thumb(thumbOn))
            addState(intArrayOf(), thumb(thumbOff))
        }
        // KitKat 的 Switch 无 showText（API 21 才有），总是测量并绘制 on/off 文本，
        // 且 thumb 宽度由文本推导（mThumbWidth = maxTextWidth + 2×thumbTextPadding），
        // 与 thumb drawable 固有尺寸无关，导致轨道宽度不受 switchMinWidth 控制。
        // 置空 on/off 文本后 maxTextWidth = 0，宽度完全由 switchMinWidth 决定，也不再绘制文字。
        switch.textOn = ""
        switch.textOff = ""
        // 反射设置 mThumbTextPadding = 14dp：滑块宽度 = 0 + 14×2 = 28dp，
        // 轨道 56dp（下方 switchMinWidth）恰为滑块的 2 倍；目标设备固定为 Android 4.4，字段确认存在
        runCatching {
            Switch::class.java.getDeclaredField("mThumbTextPadding").apply {
                isAccessible = true
                set(switch, px(14))
            }
        }
        // 轨道宽度为滑块直径（28dp）的 2 倍
        switch.switchMinWidth = px(56)
        fun track(color: Int) = GradientDrawable().apply {
            cornerRadius = px(17).toFloat()
            setSize(px(56), px(34))
            setColor(color)
        }
        switch.trackDrawable = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), track(ColorBlend.setAlphaComponent(trackOff, 0x61)))
            addState(intArrayOf(android.R.attr.state_checked), track(trackOn))
            addState(intArrayOf(), track(trackOff))
        }
    }

    fun createSwitchRow(
        context: Context,
        label: String,
        description: String,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        contentDescription: String = label,
        enabled: Boolean = true,
        labelSizeSp: Float = 18f,
        onChanged: (Boolean) -> Unit,
    ): SwitchRowResult {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val switch = Switch(context).apply {
            isChecked = checked
            this.contentDescription = contentDescription
            isEnabled = enabled
            applyLargeSwitchStyle(this, theme.accent, theme.textSecondary, theme.accentTrack, theme.trackOff)
            if (!theme.isOverlay) minHeight = theme.dp(context, 56)
            setOnCheckedChangeListener { _, isChecked -> onChanged(isChecked) }
        }

        if (theme.isOverlay) {
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textSecondary)
                applyTextStyle(context, theme)
            }
            row.addView(labelView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        } else {
            row.setPadding(0, theme.dp(context, 12), 0, theme.dp(context, 12))
            val textCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val labelView = TextView(context).apply {
                text = label
                textSize = labelSizeSp
                setTextColor(theme.textPrimary)
                applyTextStyle(context, theme, bold = true)
            }
            val descView = TextView(context).apply {
                text = description
                textSize = 14f
                setTextColor(theme.textSecondary)
                setPadding(0, theme.dp(context, 6), theme.dp(context, 16), 0)
                applyTextStyle(context, theme)
            }
            textCol.addView(labelView)
            textCol.addView(descView)
            row.addView(textCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(switch)
            // The whole row is the target; the switch stays out of the focus order so D-pad stops once.
            switch.isFocusable = false
            row.isFocusable = true
            // API 19 无 RippleDrawable/View.foreground：按压变色与焦点描边合入背景状态列表
            row.background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.ColorDrawable(
                    ColorBlend.blendARGB(Color.WHITE, theme.ripple, 0.30f)))
                addState(intArrayOf(android.R.attr.state_focused), android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = theme.dp(context, 12).toFloat()
                    setStroke(theme.dp(context, 3), theme.focusRing)
                })
                addState(intArrayOf(), android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            }
            row.setOnClickListener { if (switch.isEnabled) switch.toggle() }
            switch.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            row.accessibilityDelegate = object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = Switch::class.java.name
                    info.isCheckable = true
                    info.isChecked = switch.isChecked
                    info.isEnabled = switch.isEnabled
                }
            }
        }

        return SwitchRowResult(row, switch)
    }

    fun <T> createChoiceRow(
        context: Context,
        label: String,
        options: List<Pair<T, String>>,
        selected: T,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (T) -> Unit,
    ): ChoiceRowResult<T> {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 18f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        container.addView(
            labelView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val group = RadioGroup(context).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, theme.dp(context, 4), 0, 0)
        }
        var selectedId = View.NO_ID
        for ((value, text) in options) {
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                this.text = text
                textSize = 17f
                setTextColor(theme.textSecondary)
                // API 19 无 buttonTintList（API 21），改用自绘 radio drawable 保持强调色
                applyRadioAccent(this, theme.accent, theme.textSecondary)
                tag = value
                isChecked = value == selected
            }
            if (value == selected) selectedId = button.id
            group.addView(
                button,
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        if (selectedId != View.NO_ID) group.check(selectedId)
        var lastSelectedId = group.checkedRadioButtonId
        group.setOnCheckedChangeListener { radioGroup, checkedId ->
            if (checkedId == View.NO_ID) {
                lastSelectedId = View.NO_ID
                return@setOnCheckedChangeListener
            }
            val button = radioGroup.findViewById<RadioButton>(checkedId) ?: return@setOnCheckedChangeListener
            // RadioGroup may notify while unchecking the old button, then repeat the new ID.
            if (!button.isChecked || checkedId == lastSelectedId) return@setOnCheckedChangeListener
            @Suppress("UNCHECKED_CAST")
            val value = button.tag as? T ?: return@setOnCheckedChangeListener
            lastSelectedId = checkedId
            onSelected(value)
        }
        container.addView(
            group,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ChoiceRowResult(container, group)
    }

    fun createResolutionSlider(
        context: Context,
        initialPercent: Int,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onPercentChanged: (Int) -> Unit,
    ): ResolutionSliderResult {
        val initial = initialPercent.coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(context).apply {
            text = context.getString(R.string.resolution)
            textSize = 20f
            setTextColor(if (theme.isOverlay) theme.textSecondary else theme.textPrimary)
            applyTextStyle(context, theme, bold = !theme.isOverlay)
        }
        header.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueTextView = TextView(context).apply {
            text = "$initial%"
            textSize = 28f
            setTextColor(theme.accent)
            applyTextStyle(context, theme, bold = true)
        }
        header.addView(
            valueTextView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 14) },
        )

        val seekBar = SeekBar(context).apply {
            max = CarPlayDisplayScale.MAX_PERCENT - CarPlayDisplayScale.MIN_PERCENT
            progress = initial - CarPlayDisplayScale.MIN_PERCENT
            splitTrack = false
            // API 19 无 progress/thumbTintList（API 21），进度条使用系统默认配色
            setOnSeekBarChangeListener(
                object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                        val percent = (CarPlayDisplayScale.MIN_PERCENT + progress)
                            .coerceIn(CarPlayDisplayScale.MIN_PERCENT, CarPlayDisplayScale.MAX_PERCENT)
                        valueTextView.text = "$percent%"
                        onPercentChanged(percent)
                    }

                    override fun onStartTrackingTouch(sb: SeekBar) = Unit
                    override fun onStopTrackingTouch(sb: SeekBar) = Unit
                },
            )
        }
        container.addView(
            seekBar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = theme.dp(context, 8) },
        )

        val range = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val minLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MIN_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(minLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val maxLabel = TextView(context).apply {
            text = context.getString(R.string.custom_resolution_summary, CarPlayDisplayScale.MAX_PERCENT)
            textSize = 15f
            setTextColor(theme.textSecondary)
            applyTextStyle(context, theme)
        }
        range.addView(
            maxLabel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        container.addView(
            range,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        return ResolutionSliderResult(container, valueTextView, seekBar)
    }
}
