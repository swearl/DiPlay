package com.shilapi.xcertplay.compat

import android.location.LocationManager
import android.net.wifi.p2p.WifiP2pManager

/**
 * 精简移植：目标平台 API 19 没有 Channel.close()（API 27+ 才有），
 * 通道随 GC 释放，这里保持空实现以维持调用方兼容。
 */
fun WifiP2pManager.Channel.closeCompat() {
}

/** [LocationManager.isLocationEnabled] 为 API 28+；之前需检查位置提供器是否开启。 */
fun LocationManager.isLocationEnabledCompat(): Boolean =
    isProviderEnabled(LocationManager.GPS_PROVIDER) || isProviderEnabled(LocationManager.NETWORK_PROVIDER)
