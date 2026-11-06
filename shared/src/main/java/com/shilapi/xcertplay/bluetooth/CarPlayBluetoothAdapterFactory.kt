package com.shilapi.xcertplay.bluetooth

import android.content.Context

/**
 * 蓝牙适配器工厂：按设备能力自动选择实现。
 *
 * 优先使用 Android 原生蓝牙协议栈（adapter 存在且已开启才算可用）；
 * 原生不可用时尝试 HSAE 第三方蓝牙模块（com.anwbtsdk.service 服务包
 * 已安装即视为存在）；两者都不可用时回退原生，由调用方按 NO_ADAPTER 报错。
 */
object CarPlayBluetoothAdapterFactory {

    fun create(context: Context): CarPlayBluetoothAdapter {
        val native = NativeBluetoothAdapter(context)
        if (native.isPresent() && native.isEnabled()) return native
        val hsae = HsaeBluetoothAdapter(context)
        if (hsae.isSupported()) return hsae
        return native
    }
}
