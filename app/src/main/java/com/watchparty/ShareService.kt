package com.watchparty

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Нужен Android 10+: захват экрана разрешён только пока работает foreground-сервис. */
class ShareService : Service() {
    companion object { var onStarted: (() -> Unit)? = null }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("share", "Эфир", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "share").setContentTitle("WatchParty: идёт трансляция экрана")
            .setSmallIcon(android.R.drawable.ic_media_play).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(1, n)
        onStarted?.invoke(); onStarted = null
        return START_NOT_STICKY
    }
}
