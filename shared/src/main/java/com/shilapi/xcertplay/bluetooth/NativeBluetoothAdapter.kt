package com.shilapi.xcertplay.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.transport.BluetoothLinkCheck
import com.shilapi.xcertplay.transport.BluetoothRfcommDuplexStream
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 通用蓝牙模块的适配器实现，包装 Android 原生 [BluetoothAdapter]。
 *
 * 收编自 CarPlayController 的原生蓝牙逻辑：适配器获取、开启等待、
 * 配对设备与 profile 连接状态、本机 MAC、RFCOMM 连接（含超时控制）。
 */
class NativeBluetoothAdapter(private val context: Context) : CarPlayBluetoothAdapter {

    companion object {
        private const val TAG = "NativeBluetoothAdapter"
        /** RFCOMM 连接超时，沿用 CarPlayController 原值。 */
        private const val RFCOMM_CONNECT_TIMEOUT_MILLIS = 15_000L
        // BluetoothProfile.A2DP_SINK 和 HEADSET_CLIENT，对公开 SDK 隐藏。
        private const val PROFILE_A2DP_SINK = 11
        private const val PROFILE_HEADSET_CLIENT = 16
        private val BLUETOOTH_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
        private const val ADAPTER_ADDRESS_PLACEHOLDER = "02:00:00:00:00:00"
    }

    /** 获取原生适配器；系统服务缺失或异常时为 null。 */
    private fun adapter(): BluetoothAdapter? =
        runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: runCatching { BluetoothAdapter.getDefaultAdapter() }.getOrNull()

    override fun isPresent(): Boolean = adapter() != null

    override fun isEnabled(): Boolean = runCatching { adapter()?.isEnabled }.getOrNull() == true

    override fun state(): Int? = runCatching { adapter()?.state }.getOrNull()

    override fun awaitEnabled(timeoutMillis: Long): Boolean {
        val adapter = adapter() ?: return false
        if (adapter.isEnabled) return true
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = runCatching { adapter.state }.getOrNull()
            if (state == BluetoothAdapter.STATE_ON || adapter.isEnabled) return true
            if (state != BluetoothAdapter.STATE_TURNING_ON) break
            SystemClock.sleep(100)
        }
        return adapter.isEnabled
    }

    override fun pairedDevices(): List<CarPlayBluetoothDevice> {
        val adapter = adapter() ?: return emptyList()
        val bonded = runCatching { adapter.bondedDevices.orEmpty() }.getOrNull() ?: return emptyList()
        val connectedAddresses = profileConnectedAddresses(adapter).mapTo(mutableSetOf()) { it.address }
        return bonded.mapNotNull { device ->
            val address = device.address ?: return@mapNotNull null
            CarPlayBluetoothDevice(
                name = runCatching { device.name }.getOrNull(),
                address = address,
                isConnected = isConnected(device) || address in connectedAddresses,
            )
        }
    }

    override fun localAddress(): String? {
        val adapterAddress = try {
            adapter()?.address
        } catch (_: SecurityException) {
            null
        }
        val settingsAddress = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        } catch (_: SecurityException) {
            null
        }
        return listOfNotNull(adapterAddress, settingsAddress).firstOrNull {
            BLUETOOTH_ADDRESS.matches(it) && !it.equals(ADAPTER_ADDRESS_PLACEHOLDER, ignoreCase = true)
        }
    }

    override fun openRfcomm(
        address: String,
        uuid: String,
        onDiagnostic: (String) -> Unit,
    ): BlockingDuplexByteStream {
        val adapter = adapter() ?: throw IOException("Bluetooth adapter is unavailable")
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrElse {
            throw IOException("Invalid Bluetooth address $address", it)
        }
        val socket = runCatching {
            device.createRfcommSocketToServiceRecord(UUID.fromString(uuid))
        }.getOrElse { throw IOException("Could not create RFCOMM socket for $address", it) }
        try {
            connectSocket(socket, address, onDiagnostic)
            return try {
                BluetoothRfcommDuplexStream(socket, onDiagnostic)
            } catch (error: Throwable) {
                runCatching { socket.close() }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }

    override fun connectionSnapshot(address: String, serviceUuid: String): String? {
        return try {
            val device = adapter()?.bondedDevices.orEmpty()
                .firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: return null
            val bondName = when (device.bondState) {
                BluetoothDevice.BOND_NONE -> "NONE"
                BluetoothDevice.BOND_BONDING -> "BONDING"
                BluetoothDevice.BOND_BONDED -> "BONDED"
                else -> "UNKNOWN"
            }
            val cachedServices = runCatching { device.uuids }
            val uuids = cachedServices.getOrNull()
            val service = UUID.fromString(serviceUuid)
            "enabled=${isEnabled()} bondState=$bondName " +
                "cachedServicesReadable=${cachedServices.isSuccess} " +
                "cachedServiceCount=${uuids?.size ?: "unknown"} " +
                "cachedIap2Service=${uuids?.any { it.uuid == service } ?: "unknown"}"
        } catch (error: RuntimeException) {
            Log.w(TAG, "Bluetooth snapshot unavailable for $address", error)
            null
        }
    }

    /**
     * 原生专有：假链路检测的控制连接实验。
     * 连到一个随机 UUID 的服务（真机协议栈不可能成功），返回（是否连上、耗时 ms）。
     */
    fun controlConnect(address: String): Pair<Boolean, Long> {
        val started = System.nanoTime()
        val connected = runCatching {
            val adapter = adapter() ?: return@runCatching false
            val device = adapter.getRemoteDevice(address)
            device.createRfcommSocketToServiceRecord(UUID.randomUUID()).use { socket ->
                connectSocket(socket, address) {}
                true
            }
        }.getOrDefault(false)
        val millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        return connected to millis
    }

    /**
     * 在独立线程中连接 socket 并限时等待，避免阻塞式 connect 卡死调用线程。
     * 收编自 CarPlayController.connectBluetoothSocket。
     */
    private fun connectSocket(socket: BluetoothSocket, address: String, onDiagnostic: (String) -> Unit) {
        val result = AtomicReference<Throwable?>()
        val connected = CountDownLatch(1)
        Thread(
            {
                try {
                    socket.connect()
                } catch (error: Throwable) {
                    result.set(error)
                } finally {
                    connected.countDown()
                }
            },
            "wireless-rfcomm-connect",
        ).apply {
            isDaemon = true
            start()
        }
        val completed = try {
            connected.await(RFCOMM_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            runCatching { socket.close() }
            throw IOException("Interrupted while connecting RFCOMM to $address", error)
        }
        if (!completed) {
            onDiagnostic("Bluetooth RFCOMM connect timed out after $RFCOMM_CONNECT_TIMEOUT_MILLIS address=$address")
            runCatching { socket.close() }
            throw IOException(
                "Timed out after $RFCOMM_CONNECT_TIMEOUT_MILLIS connecting RFCOMM to $address",
            )
        }
        val failure = result.get() ?: return
        if (BluetoothLinkCheck.socketServiceMissing(failure)) {
            onDiagnostic("Bluetooth socket manager missing failureClass=${failure.javaClass.simpleName}")
        }
        throw BluetoothLinkCheck.connectFailure(failure, address)
    }

    /** 反射读取设备的连接状态（isConnected 为隐藏 API）。收编自 CarPlayController。 */
    private fun isConnected(device: BluetoothDevice): Boolean = try {
        val method = BluetoothDevice::class.java.getMethod("isConnected")
        method.invoke(device) as? Boolean == true
    } catch (_: ReflectiveOperationException) {
        false
    } catch (error: RuntimeException) {
        Log.w(TAG, "Could not read Bluetooth connection state", error)
        false
    }

    /** 通过 HFP/A2DP（含隐藏的 sink/client profile）收集当前已连接设备。收编自 CarPlayController。 */
    private fun profileConnectedAddresses(adapter: BluetoothAdapter): Set<BluetoothDevice> = buildSet {
        addAll(profileConnectedDevices(adapter, BluetoothProfile.HEADSET, BluetoothHeadset::class.java))
        addAll(profileConnectedDevices(adapter, BluetoothProfile.A2DP, BluetoothA2dp::class.java))
        // 车机是手机音频/通话的接收端，iPhone 会出现在这些隐藏 profile 下。
        // 只在已连接时才询问：缺失的 profile 服务不响应代理请求，会吃满超时。
        for (profile in intArrayOf(PROFILE_A2DP_SINK, PROFILE_HEADSET_CLIENT)) {
            val connected = try {
                adapter.getProfileConnectionState(profile) == BluetoothProfile.STATE_CONNECTED
            } catch (error: SecurityException) {
                Log.w(TAG, "Could not read Bluetooth profile $profile state", error)
                false
            }
            if (connected) addAll(profileConnectedDevices(adapter, profile, BluetoothProfile::class.java))
        }
    }

    private fun <T : BluetoothProfile> profileConnectedDevices(
        adapter: BluetoothAdapter,
        profile: Int,
        profileClass: Class<T>,
    ): Set<BluetoothDevice> {
        val latch = CountDownLatch(1)
        val devices = java.util.Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profileId: Int, proxy: BluetoothProfile) {
                try {
                    if (profileClass.isInstance(proxy)) {
                        devices.addAll(proxy.connectedDevices.orEmpty())
                    }
                } catch (error: SecurityException) {
                    Log.w(TAG, "Could not read connected Bluetooth devices", error)
                } finally {
                    adapter.closeProfileProxy(profileId, proxy)
                    latch.countDown()
                }
            }

            override fun onServiceDisconnected(profileId: Int) {
                latch.countDown()
            }
        }
        if (!adapter.getProfileProxy(context, listener, profile)) return emptySet()
        if (!latch.await(3, TimeUnit.SECONDS)) {
            Log.w(TAG, "Timed out reading Bluetooth profile $profile")
        }
        return synchronized(devices) { devices.toSet() }
    }
}
