package com.shilapi.xcertplay

import android.content.Context
import com.shilapi.xcertplay.bluetooth.CarPlayBluetoothAdapterFactory

internal object DiPlayBluetooth {
    /**
     * 本机蓝牙 MAC：按设备能力自动选择来源——通用蓝牙模块读原生协议栈
     * （含 Settings.Secure 兜底），HSAE 第三方蓝牙模块读 BtPhoneProxy。
     * 取不到时返回 null，由调用方决定回退值。
     */
    fun localAddress(context: Context): String? =
        CarPlayBluetoothAdapterFactory.create(context.applicationContext).localAddress()
}
