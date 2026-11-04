package com.shilapi.xcertplay.compat

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * 精简移植：替代 androidx.core.content.FileProvider 的极简文件提供器。
 * 仅支持以 authority + 相对路径的形式共享应用私有目录中的文件（供系统查看/安装）。
 * 用法：Uri = Uri.parse("content://$authority/<相对路径>")
 */
// 精简移植：open 以便各 provider（诊断报告 / 更新包）以独立 authority 继承复用。
open class MiniFileProvider : ContentProvider() {

    // authority 由 manifest 提供，系统在 attachInfo 时写入。
    private var providerAuthority: String? = null

    override fun attachInfo(context: Context, info: ProviderInfo) {
        super.attachInfo(context, info)
        providerAuthority = info.authority
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File {
        val authority = checkNotNull(providerAuthority) { "provider authority is null" }
        require(uri.authority == authority) { "unknown authority ${uri.authority}" }
        val relative = uri.path?.trimStart('/') ?: throw IllegalArgumentException("empty path")
        require(!relative.contains("..")) { "path traversal is not allowed" }
        return File(context!!.filesDir.parentFile, relative).canonicalFile
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = fileFor(uri)
        require(file.exists()) { "file does not exist: ${file.path}" }
        val fileMode = when (mode) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_WRITE_ONLY
            "wa" -> ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND or
                ParcelFileDescriptor.MODE_WRITE_ONLY
            "rw" -> ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE
            "rwt" -> ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE or
                ParcelFileDescriptor.MODE_READ_WRITE
            else -> throw IllegalArgumentException("unsupported mode $mode")
        }
        return ParcelFileDescriptor.open(file, fileMode)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = fileFor(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns)
        cursor.addRow(columns.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun getType(uri: Uri): String {
        val name = fileFor(uri).name
        val dot = name.lastIndexOf('.')
        val extension = if (dot >= 0) name.substring(dot + 1) else ""
        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
        return mime ?: "application/octet-stream"
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
