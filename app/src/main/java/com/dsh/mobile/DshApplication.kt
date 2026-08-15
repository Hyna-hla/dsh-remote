package com.dsh.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dsh.mobile.data.DshConnection

class DshApplication : Application() {

    val connection = DshConnection()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_desc)
        }
        manager.createNotificationChannel(channel)

        val approvals = NotificationChannel(
            CHANNEL_APPROVALS,
            "审批与确认提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "桌面端需要审批或确认时的高优先级横幅提醒"
        }
        manager.createNotificationChannel(approvals)
    }

    companion object {
        const val CHANNEL_ID = "dsh_task_updates"
        const val CHANNEL_APPROVALS = "dsh_approval_alerts"

        /** App 是否在前台（前台时审批横幅已可见，服务不再重复通知） */
        @Volatile
        var isAppInForeground = false
    }
}
