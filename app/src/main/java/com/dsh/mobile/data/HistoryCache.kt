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
    /** 服务端消息序号：缓存与网络首屏合并去重用（旧缓存无此字段，默认为 null） */
    val seq: Long? = null,
)

@Serializable
data class CachedHistory(val savedAt: Long, val items: List<CachedItem>)

/**
 * 进程级内存热缓存：解码结果 LRU，避免返回会话/首页时重复读盘+解压+JSON 解码。
 * - 历史：最近 8 个会话的解码列表（LRU）
 * - 会话列表：最近一次解码结果
 * - 预取槽：Home 后台预取的会话首屏（HistoryValue 原始对象，点开即消费）
 */
object HistoryMemoryCache {
    private const val MAX_HISTORIES = 8

    private val histories = object : LinkedHashMap<String, List<CachedItem>>(MAX_HISTORIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<CachedItem>>): Boolean =
            size > MAX_HISTORIES
    }
    private val prefetched = HashMap<String, HistoryValue>()
    private var listCache: List<SessionSummary>? = null

    @Synchronized fun history(id: String): List<CachedItem>? = histories[id]
    @Synchronized fun putHistory(id: String, items: List<CachedItem>) {
        if (items.isNotEmpty()) histories[id] = items
    }
    @Synchronized fun sessionList(): List<SessionSummary>? = listCache
    @Synchronized fun putSessionList(items: List<SessionSummary>?) { listCache = items }
    /** 取走预取（一次性消费）；无则返回 null */
    @Synchronized fun takePrefetch(id: String): HistoryValue? = prefetched.remove(id)
    @Synchronized fun putPrefetch(id: String, value: HistoryValue) {
        if (value.events.isEmpty()) return
        if (prefetched.size >= 16) prefetched.clear()
        prefetched[id] = value
    }
}

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
        // 内存热缓存优先（零解码）；未命中才读盘解压
        HistoryMemoryCache.history(sessionId)?.let { return it }
        val f = historyFile(sessionId)
        if (!f.exists() || f.length() > 4 * 1024 * 1024) return null
        val items = runCatching {
            GZIPInputStream(f.inputStream()).use {
                json.decodeFromString<CachedHistory>(it.readBytes().toString(Charsets.UTF_8)).items
            }
        }.getOrNull()
        if (items != null) HistoryMemoryCache.putHistory(sessionId, items)
        return items
    }

    fun saveHistory(sessionId: String, items: List<CachedItem>) {
        if (items.isEmpty()) return
        // 内存即时生效（返回再进零解码），磁盘延迟由调用方防抖控制
        HistoryMemoryCache.putHistory(sessionId, items)
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
        // 内存热缓存优先
        HistoryMemoryCache.sessionList()?.let { return it }
        if (!listFile.exists() || listFile.length() > 1 * 1024 * 1024) return null
        val items = runCatching {
            GZIPInputStream(listFile.inputStream()).use {
                json.decodeFromString<SessionListValue>(it.readBytes().toString(Charsets.UTF_8)).items
            }
        }.getOrNull()
        if (items != null) HistoryMemoryCache.putSessionList(items)
        return items
    }

    fun saveSessionList(items: List<SessionSummary>) {
        HistoryMemoryCache.putSessionList(items)
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
