package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.hsae.autosdk.bt.phone.BtPhoneProxy
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.util.concurrent.TimeUnit

/**
 * HSAE 第三方蓝牙模块的适配器实现。
 *
 * 电源/配对列表/本机 MAC 经由 autosdk 的 [BtPhoneProxy] 读取，
 * RFCOMM 数据通道经 anwbtsdk 的 AnwPhoneLink 服务（见 [HsaeRfcommStream]）。
 * 适用场景：车机无通用蓝牙模块，只有 HSAE 第三方 BLE 模块。
 */
class HsaeBluetoothAdapter(private val context: Context) : CarPlayBluetoothAdapter {

    companion object {
        private const val TAG = "HsaeBluetoothAdapter"
        /** HSAE 蓝牙服务所在包，存在即视为设备带 HSAE 模块。 */
        const val SERVICE_PACKAGE = "com.anwbtsdk.service"
        private val MAC = Regex("^(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}$")
    }

    /** 设备是否安装了 HSAE 蓝牙服务包。 */
    fun isSupported(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0) != null
    }.getOrDefault(false)

    override fun isPresent(): Boolean = isSupported()

    override fun isEnabled(): Boolean = try {
        val status = BtPhoneProxy.getInstance().btPowerStatus
        Log.i(TAG, "HSAE getBTPowerStatus=$status")
        status == 1
    } catch (error: Exception) {
        Log.e(TAG, "Error reading HSAE power status", error)
        false
    }

    override fun state(): Int? =
        if (isPresent()) {
            if (isEnabled()) BluetoothAdapter.STATE_ON else BluetoothAdapter.STATE_OFF
        } else {
            null
        }

    override fun awaitEnabled(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (isEnabled()) return true
            try {
                TimeUnit.MILLISECONDS.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return isEnabled()
            }
        }
        return isEnabled()
    }

    override fun pairedDevices(): List<CarPlayBluetoothDevice> = try {
        val paired = BtPhoneProxy.getInstance().pairedList.orEmpty()
        paired.map { dev ->
            val mac = dev.macAddress ?: ""
            val name = dev.devicesName?.takeIf { it.isNotBlank() } ?: "iPhone"
            // 音频或通话任一 profile 在线即视为已连接
            val isConnected = dev.a2dpStatus != 0 || dev.hfpStatus != 0
            CarPlayBluetoothDevice(name = name, address = mac, isConnected = isConnected)
        }.filter { it.address.isNotBlank() }
    } catch (error: Exception) {
        Log.e(TAG, "Error calling HSAE getPairedDevices", error)
        emptyList()
    }

    override fun localAddress(): String? {
        val mac = runCatching { BtPhoneProxy.getInstance().btMacAddress }.getOrNull()
        if (mac != null && MAC.matches(mac) && mac != "00:00:00:00:00:00") {
            return mac
        }
        return null
    }

    override fun openRfcomm(
        address: String,
        uuid: String,
        onDiagnostic: (String) -> Unit,
    ): BlockingDuplexByteStream = HsaeRfcommStream.connect(context, address, uuid, onDiagnostic)

    override fun connectionSnapshot(address: String, serviceUuid: String): String? {
        val device = pairedDevices().firstOrNull { it.address.equals(address, ignoreCase = true) }
            ?: return null
        return "mode=hsae name=${device.name ?: "unknown"} connected=${device.isConnected}"
    }
}
