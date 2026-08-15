package com.dsh.mobile.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 会话历史缓存的单条条目（与 UI 的 ChatItem 互转） */
@Serializable
data class CachedItem(
    val kind: String,
    val text: String = "",
    val name: String = "",
    val args: String = "",
    val status: String = "",
    val result: String? = null,
    val isError: Boolean = false,
    val thinkSeconds: Long? = null,
    val streaming: Boolean = false,
)

@Serializable
data class CachedHistory(val savedAt: Long, val items: List<CachedItem>)

/**
 * 冷热分离缓存层：
 * - 热：内存中最近条目（UI 状态流）
 * - 温：本文件 —— gzip 压缩的磁盘缓存（历史按会话、列表全局），秒开秒显
 * - 冷：服务器全量历史（loadMore 分页）
 * gzip 同时服务于"传输减少"：缓存命中时零网络；未命中时 OkHttp 已对响应启用透明 gzip。
 */
class HistoryCache(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val historyDir = File(context.cacheDir, "history")
    private val listFile = File(context.cacheDir, "session-list.json.gz")

    // ── 会话历史 ──

    fun loadHistory(sessionId: String): List<CachedItem>? {
        val f = historyFile(sessionId)
        if (!f.exists() || f.length() > 4 * 1024 * 1024) return null
        return runCatching {
            GZIPInputStream(f.inputStream()).use {
                json.decodeFromString<CachedHistory>(it.readBytes().toString(Charsets.UTF_8)).items
            }
        }.getOrNull()
    }

    fun saveHistory(sessionId: String, items: List<CachedItem>) {
        if (items.isEmpty()) return
        // 温缓存只留最近 120 条，避免膨胀
        val capped = items.takeLast(120)
        runCatching {
            historyDir.mkdirs()
            val payload = json.encodeToString(
                CachedHistory.serializer(),
                CachedHistory(System.currentTimeMillis(), capped),
            )
            GZIPOutputStream(historyFile(sessionId).outputStream()).use {
                it.write(payload.toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun clearHistory(sessionId: String) {
        historyFile(sessionId).delete()
    }

    // ── 会话列表（全局，短 TTL 语义由调用方控制） ──

    fun loadSessionList(): List<SessionSummary>? {
        if (!listFile.exists() || listFile.length() > 1 * 1024 * 1024) return null
        return runCatching {
            GZIPInputStream(listFile.inputStream()).use {
                json.decodeFromString<SessionListValue>(it.readBytes().toString(Charsets.UTF_8)).items
            }
        }.getOrNull()
    }

    fun saveSessionList(items: List<SessionSummary>) {
        runCatching {
            val payload = json.encodeToString(SessionListValue.serializer(), SessionListValue(items))
            GZIPOutputStream(listFile.outputStream()).use {
                it.write(payload.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun historyFile(sessionId: String): File =
        File(historyDir, sessionId.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json.gz")
}
