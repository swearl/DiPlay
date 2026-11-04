package com.shilapi.xcertplay

import android.Manifest
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode

/**
 * 无线 CarPlay 启动所需的运行时权限。
 * 精简移植：目标平台 API 19 只有位置权限需要运行时申请，
 * 删除了 API 31+（BLUETOOTH_CONNECT）与 API 33+（NEARBY_WIFI_DEVICES）的版本分支。
 */
internal object WirelessPermissions {
    fun required(hotspotMode: WirelessHotspotMode): List<String> = listOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

    /**
     * 无线启动权限被拒绝时，CarPlay 界面提示的补救内容：位置权限，或"附近设备"权限
     * （蓝牙与附近 Wi-Fi 设备）。其余权限不属于"附近设备"分组，界面只提示去应用权限里检查。
     */
    fun refusalMessage(missing: Collection<String>): Int = when {
        Manifest.permission.ACCESS_FINE_LOCATION in missing || Manifest.permission.ACCESS_COARSE_LOCATION in missing ->
            R.string.allow_precise_location_for_diplay_in_the_head_unit_s_app_p
        Manifest.permission.BLUETOOTH_CONNECT in missing || Manifest.permission.NEARBY_WIFI_DEVICES in missing ->
            R.string.allow_nearby_devices_for_diplay_in_the_head_unit_s_app_per
        else -> R.string.could_not_start_carplay_return_to_diplay_and_check_app_per
    }
}
