package com.dsh.mobile

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.HistoryCache
import com.dsh.mobile.data.UpdateChecker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DshApplication : Application() {

    val connection = DshConnection()
    private val updateScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // 启动时清理过期会话缓存（7 天前历史、1 天前会话列表），防磁盘膨胀
        runCatching { HistoryCache(this).prune() }
        // 启动自动检查更新（每天最多一次，发现新版发通知）
        scheduleAutoUpdateCheck()
    }

    /** 启动自动检查更新：发现新版本时发一条通知，点击进 App 去设置页手动更新 */
    private fun scheduleAutoUpdateCheck() {
        updateScope.launch {
            val latest = UpdateChecker.autoCheck(this@DshApplication) ?: return@launch
            val current = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: return@launch
            if (!UpdateChecker.isNewer(latest.tagName, current)) return@launch
            notifyUpdateAvailable(latest)
        }
    }

    private fun notifyUpdateAvailable(latest: com.dsh.mobile.data.ReleaseInfo) {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("DSH Remote 有新版本")
            .setContentText("v${latest.tagName.removePrefix("v")} 已发布，去 设置 → 关于 下载安装")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        manager.notify(7777, notification)
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

        val completion = NotificationChannel(
            CHANNEL_COMPLETION,
            "任务完成提醒",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "会话任务完成时的提醒（独立渠道，可与审批提醒分别设置铃声/勿扰）"
        }
        manager.createNotificationChannel(completion)
    }

    companion object {
        const val CHANNEL_ID = "dsh_task_updates"
        const val CHANNEL_APPROVALS = "dsh_approval_alerts"
        const val CHANNEL_COMPLETION = "dsh_completion_alerts"

        /** App 是否在前台（前台时审批横幅已可见，服务不再重复通知） */
        @Volatile
        var isAppInForeground = false

        /** 通知点击后要打开的会话（由 MainActivity/AppNavigation 消费） */
        @Volatile
        var pendingOpenSessionId: String? = null
    }
}
