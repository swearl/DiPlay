package com.shilapi.xcertplay.airplay

import android.util.Log
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * 通配地址绑定辅助。
 *
 * 部分 Android 4.4 设备（如飞思卡尔车机）把 UDP socket 绑定到 IPv6 通配地址「::」时，
 * libcore 会抛出「Can't bind to a link-local address without a scope id」。
 * 这里统一封装绑定逻辑：优先尝试 IPv6「::」以保留双栈能力，失败时回退 IPv4「0.0.0.0」，
 * 回退通过 try/catch 判定，不依赖 Build.VERSION 版本判断。
 */
internal object WildcardBind {
    private const val TAG = "WildcardBind"

    /** IPv6 通配地址「::」。 */
    private val ANY_V6: InetAddress = InetAddress.getByName("::")

    /** IPv4 通配地址「0.0.0.0」。 */
    private val ANY_V4: InetAddress = InetAddress.getByName("0.0.0.0")

    /** 绑定 UDP socket 到通配端口，[port] 为 0 表示由系统分配。 */
    fun bindUdp(socket: DatagramSocket, port: Int = 0) {
        runCatching {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(ANY_V6, port))
        }.onFailure { primary ->
            // Android 4.4 等平台绑「::」可能失败，回退 IPv4 通配地址。
            socket.bind(InetSocketAddress(ANY_V4, port))
            Log.w(TAG, "UDP 通配绑定回退 IPv4 port=$port reason=${primary.message}")
        }
    }

    /** 绑定 TCP 服务端到通配端口，[backlog] 为 accept 队列长度。 */
    fun bindTcp(server: ServerSocket, port: Int, backlog: Int = 50) {
        runCatching {
            server.reuseAddress = true
            server.bind(InetSocketAddress(ANY_V6, port), backlog)
        }.onFailure { primary ->
            if (primary is SocketException && server.isBound) {
                // 「::」绑定成功后后续步骤出错时不回退，直接抛出原始异常。
                throw primary
            }
            server.bind(InetSocketAddress(ANY_V4, port), backlog)
            Log.w(TAG, "TCP 通配绑定回退 IPv4 port=$port reason=${primary.message}")
        }
    }
}
