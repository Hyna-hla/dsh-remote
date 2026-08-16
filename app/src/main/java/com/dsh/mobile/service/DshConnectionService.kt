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
import com.dsh.mobile.data.HostProfile
import com.dsh.mobile.data.SettingsStore
import com.dsh.mobile.data.TokenUsageWatcher
import com.dsh.mobile.data.shouldNotifyApproval
import com.dsh.mobile.data.shouldNotifyCompletion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 前台服务：持有一条只读连接监听事件。
 * - 审批/问答请求 → 高优先级横幅通知（去重，点击直达对应会话）
 * - 任务完成（会话从运行态转空闲，8 秒防抖确认）→ 普通通知
 * App 在前台时不重复通知（会话页已有横幅）。
 */
class DshConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watcher: DshConnection? = null
    private var watchJob: Job? = null
    private var eventsJob: Job? = null
    private var stateJob: Job? = null
    private var notificationSeq = 0

    /** 会话活跃状态（运行中→空闲迁移触发完成通知） */
    private val sessionActive = mutableMapOf<String, Boolean>()
    private val completionJobs = mutableMapOf<String, Job>()
    /** 审批/问答去重 */
    private val seenApprovals = mutableSetOf<String>()
    private val seenQuestions = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    private fun startAsForeground() {
        val notification = Notification.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle("DSH Remote")
            .setContentText("后台连接已开启，审批/确认/完成会横幅提醒")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
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

    /** 常驻通知同步当前连接状态（已连接 / 连接中 / 错误并自动重连） */
    private fun updateForegroundText(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = Notification.Builder(this, DshApplication.CHANNEL_ID)
            .setContentTitle("DSH Remote")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        manager.notify(1, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWatching()
        return START_STICKY
    }

    private fun startWatching() {
        if (watchJob != null) return
        watchJob = scope.launch {
            val settings = SettingsStore(this@DshConnectionService)
            settings.ensureMigrated()
            if (!settings.backgroundNotify.first()) {
                stopSelf()
                return@launch
            }
            // 跟随活跃主机：切换即重启 watcher（单活跃语义）
            settings.activeProfileId
                .distinctUntilChanged()
                .collect { activeId ->
                    eventsJob?.cancel()
                    stateJob?.cancel()
                    watcher?.disconnect()
                    watcher = null
                    seenApprovals.clear()
                    seenQuestions.clear()
                    sessionActive.clear()
                    completionJobs.values.forEach { it.cancel() }
                    completionJobs.clear()

                    val profile = if (activeId == null) null else
                        settings.profiles.first().firstOrNull { it.id == activeId }
                    if (profile == null || profile.url.isBlank()) {
                        updateForegroundText("未选择活跃主机")
                        return@collect
                    }
                    val connection = DshConnection(this@DshConnectionService)
                    watcher = connection
                    eventsJob = launch { connection.events.collect { handle(it) } }
                    stateJob = launch {
                        connection.state.collect { st ->
                            val text = when (st) {
                                is DshConnection.State.Connected -> "已连接 " + st.baseUrl
                                is DshConnection.State.Connecting -> "连接中…"
                                // st.message 已含「N 秒后自动重连」或「（已停止自动重连）」，直接透传
                                is DshConnection.State.Error -> st.message
                                else -> "后台连接已开启"
                            }
                            updateForegroundText(text)
                        }
                    }
                    connection.connect(profile) { info ->
                        if (info.errorCode != null) {
                            scope.launch {
                                settings.markAttempt(info.profileId, info.errorCode, info.hostVersion)
                            }
                        }
                    }
                }
        }
    }

    private suspend fun handle(event: DshConnection.Event) {
        if (DshApplication.isAppInForeground) return
        when (event) {
            // 全局 token 用量监听：App 被杀后前台服务仍活着，PC 端回合消耗继续计费（假 Pro）
            is DshConnection.Event.SessionEvent ->
                TokenUsageWatcher.onSessionEvent(event.sessionId, event.event)

            is DshConnection.Event.ApprovalRequested -> {
                if (!seenApprovals.add(event.approvalId)) return
                // 分渠道开关：审批/问答渠道关闭时不弹横幅（去重照常消费，避免重复请求刷屏）
                val settings = SettingsStore(this@DshConnectionService)
                if (!shouldNotifyApproval(
                        settings.backgroundNotify.first(),
                        settings.notifyApprovals.first(),
                        DshApplication.isAppInForeground,
                    )
                ) return
                val reason = event.reason?.let { "：" + it } ?: ""
                postAlert(
                    "DSH 需要你的审批",
                    "工具「" + event.toolName + "」请求执行权限" + reason,
                    event.sessionId,
                )
            }
            is DshConnection.Event.ApprovalResolved -> seenApprovals.remove(event.approvalId)

            is DshConnection.Event.QuestionRequested -> {
                if (!seenQuestions.add(event.sessionId)) return
                // 分渠道开关：问答归入「审批与确认提醒」渠道，同审批一样受 notifyApprovals 控制
                val settings = SettingsStore(this@DshConnectionService)
                if (!shouldNotifyApproval(
                        settings.backgroundNotify.first(),
                        settings.notifyApprovals.first(),
                        DshApplication.isAppInForeground,
                    )
                ) return
                val first = event.questions.firstOrNull()?.question?.take(60)
                postAlert(
                    "DSH 需要你的确认",
                    if (first != null) "「" + first + "」等 " + event.questions.size + " 个问题等待回答"
                    else "有 " + event.questions.size + " 个问题等待回答",
                    event.sessionId,
                )
            }
            is DshConnection.Event.QuestionResolved -> seenQuestions.remove(event.sessionId)

            is DshConnection.Event.SessionStatus ->
                updateActivity(event.sessionId, event.status == "running")

            is DshConnection.Event.Jobs ->
                updateActivity(event.sessionId, event.jobs.any { it.status == "running" || it.status == "stopping" })

            else -> {}
        }
    }

    /** 运行态跟踪：进入运行清掉完成防抖；转空闲后 8 秒确认（避免任务间空隙误报）再通知完成 */
    private fun updateActivity(sessionId: String, active: Boolean) {
        val prev = sessionActive[sessionId] ?: false
        sessionActive[sessionId] = active
        if (active) {
            completionJobs.remove(sessionId)?.cancel()
        } else if (prev) {
            val job = scope.launch {
                delay(8_000)
                if (sessionActive[sessionId] != true) {
                    // 分渠道开关 + 前台判定在防抖到期瞬间读取（期间用户可能已打开 App）
                    val settings = SettingsStore(this@DshConnectionService)
                    if (!shouldNotifyCompletion(
                            settings.backgroundNotify.first(),
                            settings.notifyCompletion.first(),
                            DshApplication.isAppInForeground,
                        )
                    ) return@launch
                    postAlert(
                        "DSH 任务完成",
                        "会话 " + sessionId.take(8) + " 的执行已结束",
                        sessionId,
                        channelId = DshApplication.CHANNEL_COMPLETION,
                    )
                }
            }
            completionJobs[sessionId] = job
        }
    }

    private fun postAlert(
        title: String,
        text: String,
        sessionId: String? = null,
        channelId: String = DshApplication.CHANNEL_APPROVALS,
    ) {
        val manager = getSystemService(NotificationManager::class.java)
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sessionId != null) putExtra("open_session", sessionId)
        }
        val notification = Notification.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, (1000 + sessionId.hashCode()).coerceAtLeast(0),
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .build()
        manager.notify(1000 + (notificationSeq++ % 100), notification)
    }

    override fun onDestroy() {
        eventsJob?.cancel()
        stateJob?.cancel()
        watcher?.disconnect()
        watcher = null
        watchJob?.cancel()
        watchJob = null
        scope.cancel()
        super.onDestroy()
    }
}
