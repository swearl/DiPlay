package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.orchestration.CarPlayController

/**
 * 方向盘通话按键在 CarPlay 通话期间的处理（精简移植：删除 BYD 挂断广播与 CarPlayCallKeyPolicy，
 * 仅保留通用 KEYCODE_CALL / KEYCODE_ENDCALL 转发给 CarPlay 控制器）。
 */
internal object CarPlayCallKeys {
    private const val TAG = "DiPlay-CallKeys"
    // 原系统窗口管理器会在接听键抬起后打开车机自带电话应用，之后需要回到 CarPlay
    private const val RETURN_DELAY_MILLIS = 700L

    private val handler = Handler(Looper.getMainLooper())

    /** 原用于监听 BYD 挂断广播；精简版无需安装任何监听，保留空实现以兼容调用点。 */
    fun install(context: Context) {
    }

    /** Returns true when the key belongs to a CarPlay call and must not reach the car. */
    fun onKey(context: Context, keyCode: Int, down: Boolean, controller: CarPlayController? = currentController()): Boolean {
        if (controller?.activeAirPlaySessionToken() == null) return false
        when (keyCode) {
            KeyEvent.KEYCODE_CALL -> if (!down) {
                val sent = controller.answerCall()
                Log.i(TAG, "call key $keyCode: answer CarPlay call sent=$sent")
                if (sent) returnToCarPlay(context.applicationContext)
                return true
            }
            KeyEvent.KEYCODE_ENDCALL -> if (!down) {
                val sent = controller.endCall()
                Log.i(TAG, "key $keyCode: end CarPlay call sent=$sent")
                return true
            }
        }
        // 按下时只拦截不动作，避免按键事件穿透到车机
        if (keyCode == KeyEvent.KEYCODE_CALL || keyCode == KeyEvent.KEYCODE_ENDCALL) return true
        return false
    }

    private fun returnToCarPlay(app: Context) {
        handler.postDelayed({
            val controller = currentController()
            if (controller?.activeAirPlaySessionToken() == null) return@postDelayed
            runCatching {
                app.startActivity(
                    CarPlayScreen.intent(app)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }.onFailure { Log.w(TAG, "could not bring CarPlay back after the call key", it) }
        }, RETURN_DELAY_MILLIS)
    }

    private fun currentController(): CarPlayController? = CarPlayBackgroundSession.snapshot()?.controller
}
