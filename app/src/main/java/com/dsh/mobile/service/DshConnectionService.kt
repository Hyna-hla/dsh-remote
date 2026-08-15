package com.dsh.mobile.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.dsh.mobile.DshApplication
import com.dsh.mobile.MainActivity
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务：持有一条只读连接监听审批/确认事件。
 * App 在后台时，桌面端一旦请求审批（approval/requested）或问答（question/requested），
 * 立即发一条高优先级横幅通知提醒用户；点击通知回到 App。
 * App 在前台时不重复通知（会话页已有审批横幅）。
 */
class DshConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: DshConnection? = null
    private var watchJob: Job? = null
    private var notificationSeq = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle("DSH Remote")
            .setContentText("后台连接已开启，审批与确认会横幅提醒")
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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        return START_STICKY
    }

    private fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            val settings = SettingsStore(this@DshConnectionService)
            val config = settings.connectionConfig.first()
            val notifyEnabled = settings.backgroundNotify.first()
            if (!notifyEnabled || config.serverUrl.isBlank()) {
                stopSelf()
                return@launch
            }
            val connection = DshConnection()
            watcher = connection
            // 事件监听
            launch { connection.events.collect { handle(it) } }
            // 断线自动重连（10s 退避）
            launch {
                connection.state.collect { st ->
                    if (st is DshConnection.State.Error) {
                        delay(10_000)
                        connection.connect(config.serverUrl)
                    }
                }
            }
            connection.connect(config.serverUrl)
        }
    }

    private suspend fun handle(event: DshConnection.Event) {
        if (DshApplication.isAppInForeground) return
        when (event) {
            is DshConnection.Event.ApprovalRequested -> {
                val reason = event.reason?.let { "：$it" } ?: ""
                postAlert("DSH 需要你的审批", "工具「${event.toolName}」请求执行权限$reason")
            }
            is DshConnection.Event.QuestionRequested -> {
                postAlert("DSH 需要你的确认", "有 ${event.questions.size} 个问题等待回答")
            }
            else -> {}
        }
    }

    private fun postAlert(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, DshApplication.CHANNEL_APPROVALS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        manager.notify(1000 + (notificationSeq++ % 100), notification)
    }

    override fun onDestroy() {
        watcher?.disconnect()
        watcher = null
        watchJob?.cancel()
        watchJob = null
        scope.cancel()
        super.onDestroy()
    }
}
