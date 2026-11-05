package com.shilapi.xcertplay.bluetooth

/**
 * 统一蓝牙设备描述：原生蓝牙与 HSAE 第三方蓝牙模块共用。
 *
 * [isConnected] 在列表构建时采样，用于优先选择当前已连接的 iPhone。
 */
data class CarPlayBluetoothDevice(
    val name: String?,
    val address: String,
    val isConnected: Boolean,
)
