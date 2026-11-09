package com.shilapi.xcertplay.update

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal object UpdateClient {
    // 发布仓库：swearl/DiPlay，Release tag 形如 v{versionName}-{versionCode}
    internal const val RELEASES_URL =
        "https://api.github.com/repos/swearl/DiPlay/releases?per_page=3"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    // Far above the release APKs (under 20 MB at v0.2.21). It stops a body that never ends.
    internal const val MAXIMUM_DOWNLOAD_BYTES = 64L * 1024 * 1024
    private const val MAXIMUM_TEXT_BYTES = 4 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024
    // 镜像优先的下载前缀，$url 为原始 GitHub 下载链接；最后回退到原始链接
    private val MIRROR_PREFIXES = listOf("https://ghproxy.net/", "https://gh-proxy.com/")

    internal fun fetchText(
        url: String,
        accept: String?,
        userAgent: String,
        openConnection: (URL) -> URLConnection = { it.openConnection() },
    ): String {
        val connection = openConnection(URL(url)) as? HttpURLConnection
            ?: throw IOException("Request to $url did not open an HTTP connection")
        applyTls12(connection)
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            accept?.let { connection.setRequestProperty("Accept", it) }
            connection.setRequestProperty("User-Agent", userAgent)
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { readUtf8Limited(url, it) }.orEmpty()
            if (statusCode !in 200..299) throw IOException("Request to $url failed: $statusCode")
            return body
        } finally {
            connection.disconnect()
        }
    }

    internal fun download(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        // 镜像优先：ghproxy.net → gh-proxy.com → 原始链接，任一候选成功即返回；
        // 单个候选失败时（downloadSingle 内部已删除残留文件）继续尝试下一个，全部失败才抛出最后一个异常
        var lastFailure: Throwable? = null
        for (candidate in MIRROR_PREFIXES.map { it + url } + url) {
            try {
                downloadSingle(candidate, destination, onProgress)
                return
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("Download from $url failed with no attempt")
    }

    private fun downloadSingle(url: String, destination: File, onProgress: (Long, Long?) -> Unit) {
        destination.parentFile?.mkdirs()
        val connection = URL(url).openConnection() as HttpURLConnection
        applyTls12(connection)
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.useCaches = false
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) throw IOException("Download from $url failed: $statusCode")
            // API 19 无 getContentLengthLong（API 24），用 int 版本
            val total = connection.contentLength.takeIf { it > 0 }?.toLong()
            if (total != null && total > MAXIMUM_DOWNLOAD_BYTES) throw IOException("Download from $url is too large")
            var written = 0L
            connection.inputStream.use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > MAXIMUM_DOWNLOAD_BYTES) throw IOException("Download from $url is too large")
                        onProgress(written, total)
                    }
                }
            }
            if (total != null && written != total) throw IOException("Incomplete download from $url")
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        } finally {
            connection.disconnect()
        }
    }

    private fun readUtf8Limited(url: String, stream: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAXIMUM_TEXT_BYTES) throw IOException("Response from $url is too large")
            output.write(buffer, 0, read)
        }
        return output.toString("UTF-8")
    }

    // Android 4.4 (API 19) 的 HttpURLConnection 默认启用协议不含 TLSv1.2，
    // 而 GitHub API 与下载镜像均要求 TLS 1.2+，会导致 SSL handshake aborted。
    // "TLSv1.2" SSLContext 自 API 16 起可用，统一无条件启用即可，无需版本判断。
    private fun applyTls12(connection: HttpURLConnection) {
        if (connection !is HttpsURLConnection) return
        try {
            val context = SSLContext.getInstance("TLSv1.2")
            // init 传 null 时沿用系统默认信任库与随机源，证书校验行为不变
            context.init(null, null, null)
            connection.sslSocketFactory = Tls12SocketFactory(context.socketFactory)
        } catch (ignored: Exception) {
            // 无法创建 TLSv1.2 上下文时保持默认工厂，交由原有握手逻辑报错
        }
    }

    // 委托系统默认 SSLSocketFactory，并在每个新建 socket 上强制启用 TLSv1.2
    // （同时保留设备已支持的旧协议，避免仅剩 TLSv1.2 导致极端设备不可用）
    private class Tls12SocketFactory(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
        override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

        private fun patch(socket: SSLSocket): SSLSocket {
            val enabled = socket.enabledProtocols
            if ("TLSv1.2" !in enabled && "TLSv1.2" in socket.supportedProtocols) {
                socket.enabledProtocols = enabled + "TLSv1.2"
            }
            return socket
        }

        override fun createSocket(s: java.net.Socket, host: String, port: Int, autoClose: Boolean) =
            (delegate.createSocket(s, host, port, autoClose) as SSLSocket).let(::patch)

        override fun createSocket(host: String, port: Int) =
            (delegate.createSocket(host, port) as SSLSocket).let(::patch)

        override fun createSocket(
            host: String,
            port: Int,
            localHost: java.net.InetAddress,
            localPort: Int,
        ) = (delegate.createSocket(host, port, localHost, localPort) as SSLSocket).let(::patch)

        override fun createSocket(
            host: java.net.InetAddress,
            port: Int,
        ) = (delegate.createSocket(host, port) as SSLSocket).let(::patch)

        override fun createSocket(
            address: java.net.InetAddress,
            port: Int,
            localAddress: java.net.InetAddress,
            localPort: Int,
        ) = (delegate.createSocket(address, port, localAddress, localPort) as SSLSocket).let(::patch)
    }
}
