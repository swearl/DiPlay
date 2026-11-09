package com.shilapi.xcertplay.update

import java.net.URI
import org.json.JSONArray

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    // 校验文件与 APK 同名，后缀 .sha256（如 DiPlay-0.2.22-20-release.apk.sha256）
    private const val CHECKSUMS_SUFFIX = ".sha256"
    private const val DOWNLOAD_HOST = "github.com"
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")

    /** 校验文件名规则：APK 文件名后加 .sha256 */
    internal fun checksumsName(apkName: String) = "$apkName$CHECKSUMS_SUFFIX"

    private fun isDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(DOWNLOAD_HOST, ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port == -1
    }

    /** Returns the first published release that carries an APK and its checksums, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val releases = JSONArray(json)
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft")) continue
            val assets = release.optJSONArray("assets") ?: return null
            // 第一遍：找到 APK 资产及其原始下载链接（镜像在客户端按前缀拼接，不来自 release JSON）
            var apkName = ""
            var apkUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!isDownloadUrl(url)) continue
                if (APK_NAME.matches(name)) {
                    apkName = name
                    apkUrl = url
                }
            }
            if (apkUrl.isEmpty()) return null
            // 第二遍：找与 APK 同名的 .sha256 校验文件（与资产顺序无关）
            val checksumsName = checksumsName(apkName)
            var checksumsUrl = ""
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (isDownloadUrl(url) && name.equals(checksumsName, ignoreCase = true)) {
                    checksumsUrl = url
                }
            }
            if (checksumsUrl.isEmpty()) return null
            return UpdateRelease(release.optString("tag_name"), apkName, apkUrl, checksumsUrl)
        }
        return null
    }
}
