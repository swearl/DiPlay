package com.shilapi.xcertplay.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter

internal object UsbPermissionBroadcast {
    /** Signature permission declared in the manifest. Only this app can hold it. */
    fun senderPermission(context: Context): String = "${context.packageName}.USB_PERMISSION_RESULT"

    /**
     * A receiver registered at run time accepts broadcasts from any app, so require a permission
     * that only this app holds. The USB dialog result is sent through this app's own PendingIntent,
     * so it carries this app's identity.
     * 精简移植：目标平台 API 19 无 RECEIVER_NOT_EXPORTED，用自定义权限保护接收器。
     */
    fun register(context: Context, receiver: BroadcastReceiver, filter: IntentFilter) {
        context.registerReceiver(receiver, filter, senderPermission(context), null)
    }
}
