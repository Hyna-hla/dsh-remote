package com.dsh.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.dsh.mobile.data.ApprovalCenter
import com.dsh.mobile.data.DshConnection
import com.dsh.mobile.data.HistoryCache
import com.dsh.mobile.data.ProTokenBank
import com.dsh.mobile.data.SecretCipher
import com.dsh.mobile.data.UpdateChecker
import kotlinx.coroutines.*

class DshApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connection = DshConnection(this)
    val approvalCenter by lazy { ApprovalCenter(connection, appScope) }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // 启动时清理过期会话缓存（7 天前历史、1 天前会话列表），防磁盘膨胀
        runCatching { HistoryCache(this).prune() }
        // 加密主密钥初始化（凭证密文存储，幂等）
        SecretCipher.init(this)
        // 假 Pro 订阅银行初始化（读持久化状态）
        ProTokenBank.init(this)
        // 更新镜像偏好初始化（记住上次成功的镜像，下次优先）
        UpdateChecker.init(this)
        // 注：资源更新检查在 MainActivity 的 UpdatePromptOverlay 里做（有更新弹窗询问，无更新静默）
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
