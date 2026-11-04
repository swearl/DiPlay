package com.shilapi.xcertplay

import android.Manifest

/**
 * 有线 CarPlay 启动所需的运行时权限。
 * 精简移植：ACCESS_LOCAL_NETWORK 为 Android 17+ 专属权限，目标平台 API 19 无需任何运行时权限。
 */
internal object WiredPermissions {
    fun required(): List<String> = emptyList()
}
