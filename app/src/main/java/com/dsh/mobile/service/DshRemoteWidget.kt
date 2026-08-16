package com.dsh.mobile.service

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dsh.mobile.MainActivity
import com.dsh.mobile.R

/**
 * 桌面小部件状态源 + 刷新入口:前台服务在连接状态变化时调用 push()。
 * 独立 object(进程级单例),部件进程内直接读——避免为小部件单独起持久化。
 */
object WidgetState {
    @Volatile
    var connectionText: String = "未连接"
        private set

    /** 活动待办数（审批/问答横幅计数），>0 时小部件状态行追加「N 待办」 */
    @Volatile
    var pendingCount: Int = 0
        private set

    fun push(context: Context, text: String) {
        connectionText = text
        renderAll(context)
    }

    fun pushPending(context: Context, count: Int) {
        pendingCount = count
        renderAll(context)
    }

    private fun renderAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, DshRemoteWidget::class.java))
        if (ids.isNotEmpty()) DshRemoteWidget.render(context, manager, ids)
    }

    /** 小部件状态行文案：连接状态 +（有待办时）待办数 */
    fun statusLine(): String =
        if (pendingCount > 0) "$connectionText · $pendingCount 待办" else connectionText
}

/**
 * 4x1 连接状态小部件:显示 DSH Remote 连接状态,点击拉起 App(主页)。
 * 状态由 DshConnectionService 经 WidgetState.push 驱动;无后台额外开销。
 */
class DshRemoteWidget : BroadcastReceiver() {

    companion object {
        fun render(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val views = RemoteViews(context.packageName, R.layout.dsh_widget).apply {
                setTextViewText(R.id.widget_status, WidgetState.statusLine())
            }
            val intent = Intent(context, MainActivity::class.java)
            val pending = android.app.PendingIntent.getActivity(
                context, 0, intent,
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: AppWidgetManager.getInstance(context)
                    .getAppWidgetIds(ComponentName(context, DshRemoteWidget::class.java))
            if (ids.isNotEmpty()) render(context, AppWidgetManager.getInstance(context), ids)
        }
    }
}
