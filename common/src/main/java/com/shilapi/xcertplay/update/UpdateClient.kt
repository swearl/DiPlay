package com.shilapi.xcertplay.update

import android.content.Context
import com.shilapi.xcertplay.host.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import java.net.URLConnection
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.TlsVersion
import okhttp3.OkUrlFactory
import org.conscrypt.Conscrypt

internal object UpdateClient {
    // 发布仓库：swearl/DiPlay，Release tag 形如 v{versionName}-{versionCode}
    // Android 4.4 系统 HttpsURLConnection（内置 okhttp + 系统 Conscrypt）缺 AEAD(GCM)
    // 套件、且部分 OEM 框架强制 SSLv3-only ConnectionSpec，直连必握手失败，
    // 因此改用内置 Conscrypt + okhttp3 OkUrlFactory 的独立 TLS 栈；版本检查不走
    // API JSON，用 release 网页两步解析（latest 页取 tag，expanded_assets 页取资产
    // 链接），抓取与下载均镜像优先、直连回退。
    private const val RELEASES_PAGE = "https://github.com/swearl/DiPlay/releases"
    internal val LATEST_PAGE_URL = "$RELEASES_PAGE/latest"
    internal fun expandedAssetsUrl(tag: String) = "$RELEASES_PAGE/expanded_assets/$tag"
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 30_000
    // Far above the release APKs (under 20 MB at v0.2.21). It stops a body that never ends.
    internal const val MAXIMUM_DOWNLOAD_BYTES = 64L * 1024 * 1024
    private const val MAXIMUM_TEXT_BYTES = 4 * 1024 * 1024
    private const val BUFFER_BYTES = 64 * 1024
    // 镜像优先的前缀，$url 为原始 GitHub 链接；最后回退到原始链接。
    // gh-proxy.com 兼容 Android 4.4 的 CBC 套件，排首位；ghproxy.net 仅作后备。
    private val MIRROR_PREFIXES = listOf("https://gh-proxy.com/", "https://ghproxy.net/")

    internal fun fetchText(
        url: String,
        accept: String?,
        userAgent: String,
        openConnection: (URL) -> URLConnection = ::open,
    ): String {
        val connection = openConnection(URL(url)) as? HttpURLConnection
            ?: throw IOException("Request to $url did not open an HTTP connection")
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

    // 页面抓取与下载一致：镜像候选逐一尝试，任一成功即返回，全部失败抛最后一个异常
    internal fun fetchTextMirrored(
        url: String,
        accept: String?,
        userAgent: String,
        openConnection: (URL) -> URLConnection = ::open,
    ): String {
        var lastFailure: Throwable? = null
        for (candidate in MIRROR_PREFIXES.map { it + url } + url) {
            try {
                return fetchText(candidate, accept, userAgent, openConnection)
            } catch (failure: Throwable) {
                lastFailure = failure
            }
        }
        throw lastFailure ?: IOException("Request to $url failed with no attempt")
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
        val connection = open(URL(url)) as HttpURLConnection
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

    // ---------- 独立 TLS 栈（参照 lite 项目已验证方案） ----------

    // 应用上下文仅用于读取内置 CA 证书包资源；configure 幂等，可重复调用
    @Volatile private var appContext: Context? = null

    /** 在任何更新网络请求前由 Activity 调用一次（onCreate），缓存上下文供信任库懒加载使用。 */
    fun configure(context: Context) {
        appContext = context.applicationContext
    }

    private fun open(url: URL): URLConnection = try {
        // OkUrlFactory 走独立 okhttp3 栈：显式 TLS 1.2 ConnectionSpec，
        // 完全绕开系统 HttpsURLConnection 的协议/套件限制
        legacyHttp.open(url)
    } catch (unused: Exception) {
        // 防御性回退：未 configure 时退回系统栈（会复现握手失败，仅避免崩溃）
        android.util.Log.w("DiPlay-Update", "UpdateClient.configure not called; using system TLS stack")
        url.openConnection()
    }

    private data class LegacyTls(val factory: javax.net.ssl.SSLSocketFactory, val trust: X509TrustManager)

    // 信任库 + TLS 工厂 + HTTP 栈均为懒加载单例，仅首次请求时构建
    private val legacyTls: LegacyTls by lazy {
        val context = appContext ?: throw IOException("Updater trust store not initialized")
        val factory = CertificateFactory.getInstance("X.509")
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        context.resources.openRawResource(R.raw.update_ca_bundle).use { stream ->
            // Android 4.4 的 generateCertificates() 对含注释行的多证书 PEM 会解析失败，
            // 逐个 PEM 块解析并跳过坏块
            val pem = stream.readBytes().toString(Charsets.UTF_8)
            val begin = "-----BEGIN CERTIFICATE-----"
            val end = "-----END CERTIFICATE-----"
            val blocks = pem.split(begin).drop(1)
            var loaded = 0
            blocks.forEachIndexed { index, block ->
                val stop = block.indexOf(end)
                if (stop < 0) return@forEachIndexed
                val certPem = begin + block.substring(0, stop) + end + "\n"
                try {
                    val cert = factory.generateCertificate(certPem.byteInputStream())
                    store.setCertificateEntry("update-root-$index", cert)
                    loaded++
                } catch (_: Exception) {
                    // 跳过旧平台解析失败的证书，保留其余
                }
            }
            if (loaded == 0) throw IOException("Bundled update trust roots are empty")
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trust.init(store)
        val manager = trust.trustManagers.filterIsInstance<X509TrustManager>().single()
        // TLSv1.2 为 GitHub 与镜像必需；绝不回退 SSLv3，也绝不放宽证书/主机名校验。
        // 内置 Conscrypt 自带 BoringSSL，补齐系统栈缺失的 AEAD(GCM) 套件
        val tlsFactory = SSLContext.getInstance("TLSv1.2", Conscrypt.newProvider())
            .apply { init(null, arrayOf(manager), null) }.socketFactory
        LegacyTls(tlsFactory, manager)
    }

    private val legacyHttp: OkUrlFactory by lazy {
        val tls = legacyTls
        @Suppress("DEPRECATION")
        OkUrlFactory(OkHttpClient.Builder()
            .sslSocketFactory(tls.factory, tls.trust)
            // 显式限定 TLS 1.2：部分 OEM 4.4 框架会喂 SSLv3-only 的 ConnectionSpec 给系统栈
            .connectionSpecs(listOf(
                ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .tlsVersions(TlsVersion.TLS_1_2)
                    .build()))
            .protocols(listOf(Protocol.HTTP_1_1))
            // Android 4.4 车机的 IPv6 路由经常不可用，AAAA 记录会导致连接超时，IPv4 优先
            .dns(Dns { hostname ->
                Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 }
            })
            .connectTimeout(CONNECT_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MILLIS.toLong(), TimeUnit.MILLISECONDS)
            .build())
    }
}
