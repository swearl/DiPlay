package com.shilapi.xcertplay.update

import java.net.URI

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
    private const val DOWNLOAD_PATH = "/releases/download/"
    // The name becomes a file in the cache folder, so it can hold no path.
    private val APK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.apk")
    // latest 页面中的版本 tag 链接（如 releases/tag/v0.2.22-24）
    private val TAG_LINK = Regex("releases/tag/(v\\d+\\.\\d+\\.\\d+(?:-\\d+)?)")
    // expanded_assets 页面中的资产链接（如 releases/download/v0.2.22-24/DiPlay-0.2.22-24-release.apk）
    private val DOWNLOAD_LINK = Regex("releases/download/([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)")

    /** 校验文件名规则：APK 文件名后加 .sha256 */
    internal fun checksumsName(apkName: String) = "$apkName$CHECKSUMS_SUFFIX"

    private fun isDownloadUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(DOWNLOAD_HOST, ignoreCase = true) &&
            uri.rawUserInfo == null && uri.port == -1
    }

    // 由 tag 与文件名构造规范的 GitHub 绝对下载链接（页面里是相对路径，直接拼接更可靠）
    private fun downloadUrl(tag: String, fileName: String): String? {
        val url = "https://$DOWNLOAD_HOST$DOWNLOAD_PATH$tag/$fileName"
        return url.takeIf { isDownloadUrl(it) }
    }

    /** 从 releases/latest 页面提取最新 tag（形如 v0.2.22-24），无匹配返回 null。 */
    internal fun parseLatestTag(html: String): String? = TAG_LINK.find(html)?.groupValues?.get(1)

    /**
     * 从 expanded_assets 页面解析资产：APK 需与同名 .sha256 校验文件成对出现，
     * 多个 APK 时取第一个成对的；缺任一返回 null（视为无可用发布）。
     */
    internal fun parseAssets(html: String, tag: String): UpdateRelease? {
        val apks = LinkedHashSet<String>()
        val checksums = LinkedHashSet<String>()
        for (match in DOWNLOAD_LINK.findAll(html)) {
            // 只认与 tag 同目录的资产，避免其他路径干扰
            if (match.groupValues[1] != tag) continue
            val name = match.groupValues[2]
            when {
                APK_NAME.matches(name) -> apks.add(name)
                name.endsWith(CHECKSUMS_SUFFIX, ignoreCase = true) ->
                    checksums.add(name.dropLast(CHECKSUMS_SUFFIX.length))
            }
        }
        for (apk in apks) {
            val checksumsName = checksumsName(apk)
            if (checksums.none { it.equals(checksumsName, ignoreCase = true) }) continue
            val apkUrl = downloadUrl(tag, apk) ?: continue
            val checksumsUrl = downloadUrl(tag, checksumsName) ?: continue
            return UpdateRelease(tag, apk, apkUrl, checksumsUrl)
        }
        return null
    }
}
