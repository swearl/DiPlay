package com.shilapi.xcertplay.update

internal object UpdateVersion {
    // Release tag 形如 v{versionName}-{versionCode}（如 v0.2.22-20）；
    // 已安装版本名（如 0.2.22）没有 -versionCode 段，视为 0
    private val VERSION = Regex("(\\d+)\\.(\\d+)\\.(\\d+)(?:-(\\d+))?")

    internal data class Version(val major: Int, val minor: Int, val patch: Int, val code: Int)

    internal fun parse(value: String): Version? =
        VERSION.find(value)?.let { match ->
            Version(
                major = match.groupValues[1].toInt(),
                minor = match.groupValues[2].toInt(),
                patch = match.groupValues[3].toInt(),
                // 可选的 -versionCode 段未匹配时 groupValues 为空串，按 0 处理
                code = match.groupValues[4].toIntOrNull() ?: 0,
            )
        }

    internal fun isNewer(remote: String, installed: String): Boolean {
        val target = parse(remote) ?: return false
        val current = parse(installed) ?: return true
        if (target.major != current.major) return target.major > current.major
        if (target.minor != current.minor) return target.minor > current.minor
        if (target.patch != current.patch) return target.patch > current.patch
        // 同 versionName 的新构建靠 versionCode（git 提交数）判定更新
        return target.code > current.code
    }
}
