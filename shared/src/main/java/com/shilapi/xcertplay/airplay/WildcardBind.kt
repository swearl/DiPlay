package com.shilapi.xcertplay.airplay

import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 通配地址绑定辅助。
 *
 * 部分 Android 4.4 设备（如飞思卡尔车机）把 socket 绑定到 IPv6 通配地址「::」时，
 * libcore 会抛出「Can't bind to a link-local address without a scope id」；
 * 且 java.net 的 bind 失败路径会把 socket 内部关闭，导致后续回退绑定抛「Socket is closed」。
 * 这里统一封装绑定逻辑：优先绑定调用方指定的本地地址（如带 scope 的 IPv6 链路本地地址，
 * 与主 listener 绑定 fe80::…%wlan0 成功的机制一致），再依次回退 IPv6「::」与 IPv4「0.0.0.0」；
 * 若上一次绑定失败已把 socket 关闭，则重建一个再试。
 * 回退通过 try/catch 判定，不依赖 Build.VERSION 版本判断。
 * 两个 bind 方法都会返回实际可用的 socket，调用方必须使用返回值而不是原始入参。
 */
internal object WildcardBind {
    private const val TAG = "WildcardBind"

    /** IPv6 通配地址「::」。 */
    private val ANY_V6: InetAddress = InetAddress.getByName("::")

    /** IPv4 通配地址「0.0.0.0」。 */
    private val ANY_V4: InetAddress = InetAddress.getByName("0.0.0.0")

    /** 依次尝试的绑定地址：首选本地地址 → IPv6 通配 → IPv4 通配。 */
    private fun candidates(preferred: InetAddress?): List<InetAddress> = buildList {
        preferred?.let(::add)
        add(ANY_V6)
        add(ANY_V4)
    }

    /**
     * 绑定 UDP socket 到指定端口，[port] 为 0 表示由系统分配。
     * [preferred] 为优先绑定的本地地址（可为 null）。
     */
    fun bindUdp(socket: DatagramSocket, port: Int = 0, preferred: InetAddress? = null): DatagramSocket {
        var current = socket
        var failure: Exception? = null
        for (address in candidates(preferred)) {
            // Java 的 bind 失败可能已把 socket 内部关闭，重建后再尝试下一个候选地址。
            if (current.isClosed) current = DatagramSocket(null)
            try {
                current.reuseAddress = true
                current.bind(InetSocketAddress(address, port))
                logFallback("UDP", address, port, failure)
                return current
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("UDP bind failed")
    }

    /**
     * 绑定 TCP 服务端到指定端口，[backlog] 为 accept 队列长度。
     * [preferred] 为优先绑定的本地地址（可为 null）。
     */
    fun bindTcp(server: ServerSocket, port: Int, backlog: Int = 50, preferred: InetAddress? = null): ServerSocket {
        var current = server
        var failure: Exception? = null
        for (address in candidates(preferred)) {
            // bind 失败可能已把 socket 内部关闭，重建后再尝试下一个候选地址。
            if (current.isClosed) current = ServerSocket()
            try {
                current.reuseAddress = true
                current.bind(InetSocketAddress(address, port), backlog)
                logFallback("TCP", address, port, failure)
                return current
            } catch (error: Exception) {
                failure = error
            }
        }
        throw failure ?: IllegalStateException("TCP bind failed")
    }

    /** 首选绑定失败并成功回退时记录一条告警，便于诊断绑定地址质量问题。 */
    private fun logFallback(kind: String, address: InetAddress, port: Int, failure: Exception?) {
        if (failure != null) {
            Log.w(TAG, "$kind 绑定回退 address=$address port=$port reason=${failure.message}")
        }
    }
}
