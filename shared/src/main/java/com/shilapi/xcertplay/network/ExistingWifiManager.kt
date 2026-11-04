package com.shilapi.xcertplay.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Looper
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation
import com.shilapi.xcertplay.transport.Iap2WirelessSecurity
import java.io.IOException
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
    private val preferredInterface: String? = null,
) : WirelessHotspotManager {
    private val appContext = context.applicationContext
    // 精简移植：Context.getSystemService(Class) 为 API 23+，改用经典字符串常量。
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    private val invalidated = AtomicBoolean()
    @Volatile private var closed = false
    @Volatile private var host: InetAddress? = null
    @Volatile private var hosts: List<InetAddress> = emptyList()
    private var interfaceIndex = 0
    @Volatile private var interfaceName: String? = null
    private var receiverRegistered = false

    // 精简移植：API 19 没有 NetworkCallback，改用 Wi-Fi 状态/网络状态广播近似检测链路变化。
    private val networkStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!linkStillMatches()) invalidate("interface or address changed")
        }
    }

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ExistingWifiManager.start must not run on the main thread"
        }
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        check(interfaceName == null) { "ExistingWifiManager has already started" }
        val started = System.nanoTime()
        // An explicit interface covers units whose station LAN runs on an interface Android never
        // reports as Wi-Fi, such as a USB NIC presenting itself as eth1 (#573 family). The SSID and
        // security checks below read WifiInfo, which such an interface cannot provide.
        val configured = preferredInterface?.trim()?.takeIf { it.isNotEmpty() }
        while (!closed) {
            // 精简移植：API 19 无法枚举 Network/LinkProperties，改用 WifiInfo.ipAddress 在
            // NetworkInterface 中定位当前站点接口；显式指定接口时按名称直接查找。
            val iface: NetworkInterface? = if (configured != null) {
                runCatching { NetworkInterface.getByName(configured) }.getOrNull()
            } else {
                interfaceForStationAddress(stationIpAddress())
            }
            val name = iface?.name
            val linkAddresses: List<InetAddress> =
                iface?.let { Collections.list(it.inetAddresses) } ?: emptyList()
            val address = if (iface == null) null else wirelessHostAddress(linkAddresses, iface.index)
            if (iface != null && name != null && address != null) {
                val addresses = existingWifiHostAddresses(linkAddresses, iface.index)
                var frequency: Int? = null
                var channel = 0
                var apBssid: ByteArray? = null
                var networkNameReadable = false
                if (configured == null) {
                    // 精简移植：目标平台 API 19 只有经典 connectionInfo 站点快照可用。
                    @Suppress("DEPRECATION")
                    val stationInfo = try { wifi.connectionInfo } catch (_: SecurityException) { null }
                    val info = stationInfo
                    val liveSsid = readableSsid(info)
                    networkNameReadable = liveSsid != null
                    if (liveSsid != null && liveSsid != ssid) {
                        throw IOException("Configured Wi-Fi does not match the connected network; check Wi-Fi settings and saved details")
                    }
                    // API 19 无 WifiInfo.getFrequency（API 21），信道信息降级为未知
                    frequency = null
                    channel = 0
                    apBssid = accessPointAddress(info?.bssid)
                }
                synchronized(lock) {
                    check(!closed) { "ExistingWifiManager is closed" }
                    host = address
                    hosts = addresses
                    interfaceIndex = iface.index
                    interfaceName = name
                    registerNetworkStateReceiverLocked()
                }
                // Close the gap between reading the link and registering the receiver: if the
                // stored interface or address no longer matches, treat the link as changed.
                if (invalidated.get() || !linkStillMatches()) {
                    throw IOException("Existing Wi-Fi changed during startup; connect again")
                }
                onDiagnostic("Existing Wi-Fi attached iface=$name host=${address.hostAddress} " +
                    "networkNameReadable=$networkNameReadable channel=$channel security=${security()} " +
                    "receiverIdentity=saved" + if (configured != null) " configuredInterface=$configured" else "")
                return WirelessHotspotInfo(ssid, passphrase, security(), channel, frequency,
                    // The router's BSSID is not this receiver's AirPlay device identity.
                    null, name, address, when {
                        frequency == null -> "Auto"
                        frequency < 2500 -> "2.4 GHz"
                        frequency < 5955 -> "5 GHz"
                        else -> "6 GHz"
                    }, WirelessHotspotBackend.EXISTING_WIFI, hostAddresses = addresses, accessPointBssid = apBssid)
            }
            if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                val interfaceMissing = configured != null &&
                    runCatching { NetworkInterface.getByName(configured) }.getOrNull() == null
                throw IOException(
                    if (interfaceMissing) {
                        "Existing Wi-Fi interface $configured was not found; check the interface setting"
                    } else {
                        "Existing Wi-Fi is not connected or has no usable address. Connect both devices to the same Wi-Fi in system settings"
                    },
                )
            }
            Thread.sleep(200)
        }
        throw IOException("Existing Wi-Fi attachment was cancelled")
    }

    private fun security(): Iap2WirelessSecurity =
        if (passphrase.isEmpty()) Iap2WirelessSecurity.NONE else Iap2WirelessSecurity.WPA_WPA2

    private fun readableSsid(info: WifiInfo?): String? = info?.ssid?.removeSurrounding("\"")
        ?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isEmpty() }

    private fun accessPointAddress(text: String?): ByteArray? {
        if (text == null || !text.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) return null
        val bytes = text.split(':').map { it.toInt(16).toByte() }.toByteArray()
        if (bytes.all { it == 0.toByte() } || bytes[0].toInt() and 1 != 0 ||
            text.equals("02:00:00:00:00:00", ignoreCase = true)) return null
        return bytes
    }

    private fun invalidate(reason: String) {
        if (!closed && invalidated.compareAndSet(false, true)) {
            onDiagnostic("Existing Wi-Fi $reason; restarting wireless session")
            onNetworkChanged()
        }
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed || invalidated.get()) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            if (receiverRegistered) {
                runCatching { appContext.unregisterReceiver(networkStateReceiver) }
                receiverRegistered = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun stationIpAddress(): InetAddress? {
        // 精简移植：WifiInfo.ipAddress 为小端序 int（API 19 经典字段），还原为 IPv4 地址。
        val info = try { wifi.connectionInfo } catch (_: SecurityException) { null } ?: return null
        val ip = info.ipAddress
        if (ip == 0) return null
        val bytes = byteArrayOf(
            (ip and 0xff).toByte(),
            (ip shr 8 and 0xff).toByte(),
            (ip shr 16 and 0xff).toByte(),
            (ip shr 24 and 0xff).toByte(),
        )
        return runCatching { InetAddress.getByAddress(bytes) }.getOrNull()
    }

    /** 精简移植：在系统网络接口中查找持有该站点 IPv4 地址的唯一接口。 */
    private fun interfaceForStationAddress(address: InetAddress?): NetworkInterface? {
        if (address == null) return null
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) }
        }.getOrNull().orEmpty()
        val matches = interfaces.filter { candidate ->
            Collections.list(candidate.inetAddresses).any { it.address.contentEquals(address.address) }
        }
        return matches.singleOrNull()
    }

    // 精简移植：API 19 无 LinkProperties，直接核对存储的接口名与地址集合是否仍与快照一致。
    private fun linkStillMatches(): Boolean {
        val name = interfaceName ?: return true
        val iface = runCatching { NetworkInterface.getByName(name) }.getOrNull() ?: return false
        val current = Collections.list(iface.inetAddresses)
        return current.any { it == host } &&
            existingWifiHostAddresses(current, iface.index).toSet() == hosts.toSet()
    }

    /** Caller must hold [lock]. Registers the Wi-Fi state receiver once. */
    private fun registerNetworkStateReceiverLocked() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        runCatching { appContext.registerReceiver(networkStateReceiver, filter) }
        receiverRegistered = true
    }
}
