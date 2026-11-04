package com.shilapi.xcertplay

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import android.graphics.Matrix
import android.view.TextureView

/**
 * Lets another app, such as a car launcher, show the live dashboard map (CarPlay stream 111)
 * inside its own screen. The app binds with [ACTION], sends [MSG_ATTACH] with its SurfaceView's
 * host token and size, and gets back a SurfaceControlViewHost.SurfacePackage to put into that
 * SurfaceView; the map then lives in the launcher's layout.
 * 精简移植：SurfaceControlViewHost/SurfacePackage 为 Android 11+（API 30），目标平台 API 19
 * 无法实现，本服务统一拒绝并保持协议兼容（launcher 收到 ERROR_UNSUPPORTED）。
 */
class MapEmbedService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { handle(it); true })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    private fun handle(message: Message) {
        val client = message.replyTo ?: return
        // API 19 无 Message.sendingUid（API 24+），用 Binder.getCallingUid 识别调用方
        val caller = packageManager.getNameForUid(Binder.getCallingUid()) ?: "uid ${Binder.getCallingUid()}"
        when (message.what) {
            MSG_ATTACH -> refuse(client, caller, ERROR_UNSUPPORTED)
            MSG_RESIZE, MSG_DETACH -> Unit // 无嵌入存在，无需处理
        }
    }

    private fun refuse(client: Messenger, caller: String, error: String) {
        Log.w(TAG, "$caller asked for the map: $error")
        send(client, MSG_ERROR, Bundle().apply { putString(KEY_ERROR, error) })
    }

    private fun send(client: Messenger, what: Int, data: Bundle) {
        try {
            client.send(Message.obtain(null, what).apply { this.data = data })
        } catch (_: RemoteException) {
            // 客户端已离开，无需处理
        }
    }

    companion object {
        private const val TAG = "DiPlay-MapEmbed"

        /** Bind to DiPlay's service with this action (the package differs between builds). */
        const val ACTION = "com.shihab.diplay.action.EMBED_MAP"

        // Launcher -> DiPlay. Every message needs replyTo.
        const val MSG_ATTACH = 1 // KEY_HOST_TOKEN, KEY_DISPLAY_ID, KEY_WIDTH, KEY_HEIGHT
        const val MSG_RESIZE = 2 // KEY_WIDTH, KEY_HEIGHT
        const val MSG_DETACH = 3

        // DiPlay -> launcher.
        const val MSG_ATTACHED = 101 // KEY_SURFACE_PACKAGE, KEY_STREAM_ACTIVE
        const val MSG_STREAM_STATE = 102 // KEY_STREAM_ACTIVE
        const val MSG_ERROR = 199 // KEY_ERROR

        const val KEY_HOST_TOKEN = "hostToken"
        const val KEY_DISPLAY_ID = "displayId"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_SURFACE_PACKAGE = "surfacePackage"
        const val KEY_STREAM_ACTIVE = "streamActive"
        const val KEY_ERROR = "error"

        const val ERROR_DISABLED = "disabled" // the driver has not allowed sharing in DiPlay
        const val ERROR_UNSUPPORTED = "unsupported" // Android 10 or older
        const val ERROR_BAD_REQUEST = "bad_request"
        const val ERROR_TOO_MANY = "too_many" // this app or all apps already show the most maps allowed

        /** Keeps the stream's shape (8:3 or 16:9) and fills the view, cutting the edges that do not fit. */
        internal fun cropToFill(view: TextureView, width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            val viewAspect = width.toFloat() / height
            val stream = MapMirrors.streamAspect.toFloat()
            val scaleX = if (viewAspect < stream) stream / viewAspect else 1f
            val scaleY = if (viewAspect > stream) viewAspect / stream else 1f
            view.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
        }
    }
}
