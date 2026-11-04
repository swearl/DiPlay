package com.shilapi.xcertplay

import android.content.Context

/**
 * Own-app numeric exit metadata only; never read traces, descriptions or process state blobs.
 * 精简移植：getHistoricalProcessExitReasons 为 Android 11+（API 30）专属 API，
 * 目标平台 API 19 恒定返回不支持说明。
 */
internal object ProcessExitDiagnostics {
    fun report(context: Context): String =
        "Process exits available=false requiresApi=30"
}
