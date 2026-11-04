package com.shilapi.xcertplay

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.util.Log
import java.io.File
import java.io.IOException

/** Saves an app-owned report without depending on an OEM's document-picker activity. */
internal object DiagnosticExportStore {
    private const val TAG = "DiagnosticExport"

    data class SavedReport(
        val uri: Uri,
        val savedInApp: Boolean = false,
        val savedPath: String? = null,
    )

    /**
     * Saves to the visible Downloads/DiPlay folder whenever the head unit allows it, so the
     * report can be reached from a file manager or USB. Order:
     *  1. Direct file in public Downloads/DiPlay（需要 WRITE_EXTERNAL_STORAGE 权限）。
     *  2. App-specific external storage (Android/data/<package>, hidden on many head units).
     *  3. Internal app storage, shared through the report viewer.
     * 精简移植：删除了 Android 10+ 的 MediaStore Downloads 路径（saveToDownloads），
     * 目标平台 API 19 直接走公共目录写入。
     */
    fun saveWithoutPicker(context: Context, fileName: String, report: String): SavedReport {
        if (canWritePublicDownloads(context)) {
            try {
                return saveToPublicDownloads(context, fileName, report)
            } catch (error: Exception) {
                Log.w(TAG, "Public Downloads folder unavailable; saving in app storage", error)
            }
        }
        try {
            // Use Android's package-specific directory, including debug application IDs.
            // No storage permission or document-picker activity is needed.
            val externalFiles = context.getExternalFilesDir(null)
            if (externalFiles != null) {
                return saveInDirectory(context, File(externalFiles, "diagnostic-reports"), fileName, report)
            }
        } catch (_: Exception) {
            // A missing, read-only or full external volume must not prevent export.
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true)
    }

    private fun saveInDirectory(
        context: Context,
        directory: File,
        fileName: String,
        report: String,
        savedInApp: Boolean = false,
    ): SavedReport {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        // Each export has a new URI: an earlier share grant cannot read a later report.
        val file = File.createTempFile(fileName.removeSuffix(".txt") + "-", ".txt", directory)
        try {
            file.writeText(report, Charsets.UTF_8)
            // 精简移植：不再使用 androidx FileProvider，改用 MiniFileProvider 的 content Uri。
            // 只有位于应用私有根目录（filesDir 的父目录）内的文件能拼出可用 Uri；
            // 外部私有目录（Android/data/<包名>）不在根内，共享改用私有根内的内部副本。
            val uri = providerUri(context, file)
                ?: saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri
            // Retain only the newest eight reports; never prune the export being returned.
            directory.listFiles()?.filter { it != file && it.isFile }
                ?.sortedByDescending { it.lastModified() }?.drop(7)
        ?.let { files -> for (file in files) file.delete() } // API 19 无 Iterable#forEach 默认方法
            return SavedReport(uri, savedInApp = savedInApp, savedPath = if (savedInApp) null else file.absolutePath)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    /**
     * 精简移植：为私有根目录内的文件拼出 MiniFileProvider 可解析的 content Uri
     * （content://<包名>.diagnostic-reports/<相对路径>）。文件不在根内时返回 null。
     */
    private fun providerUri(context: Context, file: File): Uri? {
        val root = context.filesDir.parentFile ?: return null
        val relative = file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return null
        return Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.diagnostic-reports")
            .path(relative)
            .build()
    }

    /** 目标平台 API 19：写入公共 Downloads 需要 WRITE_EXTERNAL_STORAGE 已被授权。 */
    fun canWritePublicDownloads(context: Context): Boolean =
        context.checkPermission(
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Process.myPid(), Process.myUid()
        ) == PackageManager.PERMISSION_GRANTED

    /** 公共 Downloads 未授权时，导出报告前需要先申请存储权限。 */
    fun needsStoragePermission(context: Context): Boolean =
        !canWritePublicDownloads(context)

    @Suppress("DEPRECATION")
    fun publicDownloadsDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DiPlay")

    internal fun saveToPublicDownloads(context: Context, fileName: String, report: String): SavedReport {
        val directory = publicDownloadsDirectory()
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Downloads/DiPlay is unavailable")
        val base = fileName.removeSuffix(".txt")
        var file = File(directory, fileName)
        var copy = 1
        while (file.exists()) file = File(directory, "$base-${copy++}.txt")
        try {
            file.writeText(report, Charsets.UTF_8)
            if (file.length() == 0L && report.isNotEmpty()) throw IOException("Downloads/DiPlay did not keep the report")
        } catch (error: Exception) {
            file.delete()
            throw error
        }
        // The report is saved; nothing below may undo that. The user's Downloads folder is never pruned.
        runCatching {
            MediaScannerConnection.scanFile(context.applicationContext, arrayOf(file.absolutePath), arrayOf("text/plain"), null)
        }
        // 精简移植：公共 Downloads 不在 MiniFileProvider 的私有根内，无法通过它共享，
        // 统一改用私有副本的 Uri；Downloads/DiPlay 中的可见文件保留不动。
        val uri = privateShareCopy(context, fileName, report)
        return SavedReport(uri, savedPath = file.absolutePath)
    }

    private fun privateShareCopy(context: Context, fileName: String, report: String): Uri {
        val external = runCatching { context.getExternalFilesDir(null) }.getOrNull()
        if (external != null) {
            runCatching { return saveInDirectory(context, File(external, "diagnostic-reports"), fileName, report).uri }
        }
        return saveInDirectory(context, File(context.filesDir, "diagnostic-reports"), fileName, report, savedInApp = true).uri
    }

    fun write(resolver: ContentResolver, uri: Uri, report: String) {
        val stream = resolver.openOutputStream(uri, "wt")
            ?: throw IOException("Report destination is unavailable")
        stream.bufferedWriter(Charsets.UTF_8).use { it.write(report) }
    }
}
