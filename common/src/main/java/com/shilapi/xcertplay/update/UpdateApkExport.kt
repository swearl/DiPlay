package com.shilapi.xcertplay.update

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/** Copies a verified APK where a file manager can reach it, for head units that cannot use the system installer. */
internal object UpdateApkExport {

    /** [path] is shown to the driver. [uri] is no longer set（精简移植：删除了 MediaStore Downloads 路径）. */
    class Saved(val path: String, val uri: Uri?)

    /** Returns null when no public copy could be made. */
    fun copy(context: Context, apk: File): Saved? {
        return try {
            val directory = File(context.getExternalFilesDir(null) ?: return null, "update")
            Saved(copyToDirectory(directory, apk).absolutePath, null)
        } catch (_: Exception) {
            null
        }
    }

    private fun copyToDirectory(directory: File, apk: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        // Keep only the newest APK so a long-lived head unit does not collect old releases.
        directory.listFiles()?.filter { it.isFile && it.name != apk.name }?.forEach { it.delete() }
        return apk.copyTo(File(directory, apk.name), overwrite = true)
    }
}
