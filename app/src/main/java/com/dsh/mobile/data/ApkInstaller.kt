package com.dsh.mobile.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

/**
 * APK 安装器：多策略兜底，解决部分 ROM / Android 15+ 上
 * 「点击安装无反应」的问题。
 *
 * 策略顺序：
 * 1. FileProvider + ACTION_INSTALL_PACKAGE（系统安装器，带确认界面）
 * 2. FileProvider + ACTION_VIEW（老设备/部分 ROM 兼容路径）
 * 3. PackageInstaller 会话安装（同签名自更新可直接装，无需"未知来源"授权）
 */
object ApkInstaller {

    /**
     * 安装 APK。返回 null = 已交给系统（安装中/等待用户确认）；
     * 返回非 null = 错误信息（全部策略失败）。
     */
    fun install(context: Context, apk: File): String? {
        if (!apk.exists() || apk.length() == 0L) return "安装包不存在或为空"

        // ── 策略 1/2：FileProvider → 系统安装器 ──
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", apk)
        }.getOrElse { return "无法访问安装包：${it.message}" }

        val providerIntents = listOf(
            Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setData(uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
        for (intent in providerIntents) {
            val resolve = runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull()
            if (resolve == null) continue
            val started = runCatching { context.startActivity(intent) }.isSuccess
            if (started) return null
        }

        // ── 策略 3：PackageInstaller 会话安装（同签名自更新兜底）──
        return installViaSession(context, apk)
    }

    /** PackageInstaller 会话安装；成功返回 null，失败返回错误信息 */
    private fun installViaSession(context: Context, apk: File): String? {
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppIcon(android.graphics.BitmapFactory.decodeFile(apk.absolutePath))
                setAppLabel("DSH Remote")
            }
            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apk.inputStream().use { input ->
                    val out = session.openWrite("package", 0, apk.length())
                    input.copyTo(out)
                    // Android 10+ 要求写入后 fsync
                    session.fsync(out)
                    out.close()
                }
                val sender = PendingIntent.getBroadcast(
                    context, sessionId,
                    Intent(context.applicationContext.packageName + ".INSTALL_RESULT"),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ).intentSender
                session.commit(sender)
                null
            } finally {
                session.close()
            }
        } catch (e: Exception) {
            "系统安装器不可用：${e.message}"
        }
    }

    /** 需要用户开启「安装未知应用」时调用（同签名自更新通常不需要） */
    fun openUnknownSourcesSetting(context: Context) {
        runCatching {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
