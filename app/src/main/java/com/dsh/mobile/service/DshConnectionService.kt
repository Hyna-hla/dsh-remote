package com.dsh.mobile.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R
import com.dsh.mobile.DshApplication

class DshConnectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = Notification.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle("DSH Remote")
            .setContentText("Connected to Harness")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        startForeground(1, notification)
        return START_STICKY
    }
}
