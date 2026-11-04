package com.shilapi.xcertplay.network

import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.net.Socket

/**
 * Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure.
 *
 * 精简移植：android.system.Os 为 API 21+，目标平台 API 19 不存在该类，
 * 因此通过反射访问，失败时安静降级为系统默认 keepalive。
 */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>; Android's SDK omits these three OsConstants fields.
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6

    fun configure(socket: Socket, diagnostic: (String) -> Unit) {
        socket.keepAlive = true
        try {
            // API < 29 时 fromSocket 包装的是 socket 自身描述符，需直接取底层 FileDescriptor
            val fd = runCatching {
                ParcelFileDescriptor.fromSocket(socket).fileDescriptor
            }.getOrElse {
                socket.javaClass.getMethod("getFileDescriptor\$").invoke(socket) as FileDescriptor
            }
            tune(fd)
            diagnostic("TCP peer health enabled idle=10s interval=3s count=3 deadline=20000ms; no video-idle timeout")
        } catch (failure: Throwable) {
            diagnostic("TCP peer health tuning unavailable: ${failure.javaClass.simpleName}")
        }
    }

    private fun tune(fd: FileDescriptor) {
        val os = Class.forName("android.system.Os")
        val osConstants = Class.forName("android.system.OsConstants")
        val ipprotoTcp = osConstants.getField("IPPROTO_TCP").get(null) as Int
        val tcpUserTimeout = runCatching { osConstants.getField("TCP_USER_TIMEOUT").get(null) as Int }.getOrDefault(-1)
        val setsockoptInt = os.getMethod(
            "setsockoptInt", FileDescriptor::class.java, Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        setsockoptInt.invoke(null, fd, ipprotoTcp, TCP_KEEPIDLE, 10)
        setsockoptInt.invoke(null, fd, ipprotoTcp, TCP_KEEPINTVL, 3)
        setsockoptInt.invoke(null, fd, ipprotoTcp, TCP_KEEPCNT, 3)
        if (tcpUserTimeout >= 0) setsockoptInt.invoke(null, fd, ipprotoTcp, tcpUserTimeout, 20_000)
    }
}
