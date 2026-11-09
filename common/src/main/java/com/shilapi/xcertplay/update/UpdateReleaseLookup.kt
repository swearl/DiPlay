package com.shilapi.xcertplay.update

import java.net.URL
import java.net.URLConnection

internal object UpdateReleaseLookup {
    fun latest(
        installedVersion: String,
        userAgent: String,
        // 默认必须委托 UpdateClient 的新 TLS 栈（内置 Conscrypt + OkUrlFactory），
    // 不能用系统 it.openConnection()——Android 4.4 系统栈缺 AEAD 套件必握手失败
    openConnection: (URL) -> URLConnection = { UpdateClient.open(it) },
    ): UpdateRelease? {
        // 第一步：镜像优先抓 latest 页，取最新 tag（Android 4.4 无法直连 GitHub，镜像回退直连）
        val html = UpdateClient.fetchTextMirrored(
            UpdateClient.LATEST_PAGE_URL,
            null,
            userAgent,
            openConnection,
        )
        val tag = UpdateCatalog.parseLatestTag(html) ?: return null
        // 第二步：抓 expanded_assets 页，解析成对的 APK 与 .sha256 校验文件
        val assets = UpdateClient.fetchTextMirrored(
            UpdateClient.expandedAssetsUrl(tag),
            null,
            userAgent,
            openConnection,
        )
        val release = UpdateCatalog.parseAssets(assets, tag) ?: return null
        return release.takeIf { UpdateVersion.isNewer(it.tagName, installedVersion) }
    }
}
