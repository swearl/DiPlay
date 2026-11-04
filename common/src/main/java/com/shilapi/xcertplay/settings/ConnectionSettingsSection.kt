package com.shilapi.xcertplay.settings

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioGroup
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

object ConnectionSettingsSection {

    /**
     * 精简移植：MfiTarget 枚举只剩 LOCAL（本地离线签名），单一目标没有选择意义，
     * 不再展示选择行。保留函数签名以兼容 CarPlayHostActivity.buildMfiTargetSection 的既有调用
     * （其使用返回值的 container 与 radioGroup），函数体返回一个隐藏的空容器。
     */
    fun createMfiTargetChoice(
        context: Context,
        selected: MfiTarget,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (MfiTarget) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<MfiTarget> {
        val container = LinearLayout(context).apply { visibility = View.GONE }
        return SettingsWidgets.ChoiceRowResult(container, RadioGroup(context))
    }

    fun createWirelessCarPlayRow(
        context: Context,
        checked: Boolean,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onChanged: (Boolean) -> Unit,
    ): SettingsWidgets.SwitchRowResult = SettingsWidgets.createSwitchRow(
        context = context,
        label = context.getString(R.string.wireless_carplay_2),
        description = context.getString(R.string.wireless_carplay_transport),
        checked = checked,
        theme = theme,
        contentDescription = context.getString(R.string.wireless_carplay_transport),
        labelSizeSp = if (theme.isOverlay) 20f else 18f,
        onChanged = onChanged,
    )

    fun createHotspotModeChoice(
        context: Context,
        selected: WirelessHotspotMode,
        theme: SettingsTheme = SettingsTheme.OVERLAY,
        onSelected: (WirelessHotspotMode) -> Unit,
    ): SettingsWidgets.ChoiceRowResult<WirelessHotspotMode> {
        // The car hotspot keeps the radio on one channel; Wi-Fi Direct shares it with the car's network search.
        // 精简移植：删除 Build.VERSION 版本判断（规则禁止），WIFI_P2P 选项在所有支持的目标平台上提供。
        val modes = buildList {
            add(WirelessHotspotMode.MANUAL to context.getString(R.string.settings_built_in_car_hotspot_recommended))
            add(WirelessHotspotMode.WIFI_P2P to context.getString(R.string.wi_fi_p2p_5_ghz))
            add(WirelessHotspotMode.EXISTING_WIFI to context.getString(R.string.existing_wifi_title))
        }
        return SettingsWidgets.createChoiceRow(
            context = context,
            label = context.getString(R.string.wi_fi_session),
            options = modes,
            selected = selected,
            theme = theme,
            onSelected = onSelected,
        )
    }
}
