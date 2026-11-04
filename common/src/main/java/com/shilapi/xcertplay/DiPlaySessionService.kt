package com.shilapi.xcertplay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.shilapi.xcertplay.host.R

/** Keeps an explicitly started connection alive when another car app is in the foreground. */
class DiPlaySessionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            CarPlayBackgroundSession.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        // 精简移植：API 19 无通知渠道，用优先级代替渠道重要度。
        val builder = @Suppress("DEPRECATION")
        Notification.Builder(this).setPriority(Notification.PRIORITY_LOW)
        val open = PendingIntent.getActivity(this, 0, CarPlayScreen.intent(this), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DiPlaySessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = builder
            .setSmallIcon(R.drawable.ic_diplay_notification)
            .setContentTitle("DiPlay")
            .setContentText("CarPlay connection running")
            .setContentIntent(open).setOngoing(true)
            // 精简移植：addAction(Notification.Action) 为 API 23+，改用经典三参重载（API 16）。
            .addAction(0, "Disconnect", stop).build()
        startForeground(1, notification)
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        CarPlayBackgroundSession.stop()
        stopSelf()
    }
    companion object {
        const val ACTION_STOP = "com.shihab.diplay.DISCONNECT"
    }
}
