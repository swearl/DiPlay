package com.shilapi.xcertplay.bluetooth

import android.content.Context

/**
 * 蓝牙适配器工厂：按设备能力自动选择实现。
 *
 * 检测到 HSAE 蓝牙服务包（com.anwbtsdk.service）时优先使用 HSAE 模块，
 * 否则回退到 Android 原生蓝牙协议栈。
 */
object CarPlayBluetoothAdapterFactory {

    fun create(context: Context): CarPlayBluetoothAdapter {
        val hsae = HsaeBluetoothAdapter(context)
        if (hsae.isSupported()) return hsae
        return NativeBluetoothAdapter(context)
    }
}
