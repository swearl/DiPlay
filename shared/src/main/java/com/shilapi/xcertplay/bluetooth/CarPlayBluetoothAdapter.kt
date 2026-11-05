package com.shilapi.xcertplay.bluetooth

import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

/**
 * 统一蓝牙适配器接口：屏蔽通用蓝牙模块（Android 原生协议栈）与
 * HSAE 第三方蓝牙模块（anwbtsdk 服务）的差异。
 *
 * [state] 返回 android.bluetooth.BluetoothAdapter.STATE_* 常量语义，
 * 供 [com.shilapi.xcertplay.transport.BluetoothRadioCheck] 前置检查复用。
 */
interface CarPlayBluetoothAdapter {

    /** 本设备是否提供该蓝牙模块。 */
    fun isPresent(): Boolean

    /** 蓝牙是否已开启。 */
    fun isEnabled(): Boolean

    /** 当前状态，取 BluetoothAdapter.STATE_* 值；无法判定时为 null。 */
    fun state(): Int?

    /** 等待蓝牙开启，返回是否在超时内变为开启。 */
    fun awaitEnabled(timeoutMillis: Long): Boolean

    /** 已配对设备列表（含连接状态采样）。 */
    fun pairedDevices(): List<CarPlayBluetoothDevice>

    /** 本机蓝牙 MAC 地址，取不到时为 null。 */
    fun localAddress(): String?

    /**
     * 连接指定设备上 [uuid] 标识的 iAP2 RFCOMM 通道。
     * 连接为阻塞式，失败抛 IOException；成功返回已就绪的字节流。
     */
    fun openRfcomm(
        address: String,
        uuid: String,
        onDiagnostic: (String) -> Unit,
    ): BlockingDuplexByteStream

    /**
     * 连接前后的诊断快照文本（状态、缓存服务等）；无法提供时为 null。
     */
    fun connectionSnapshot(address: String, serviceUuid: String): String?
}
