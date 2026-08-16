package com.dsh.mobile.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.components.MarkdownText
import com.dsh.mobile.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.Locale

/** 判定「复杂任务」的关键词（命中即用 Pro） */
private val COMPLEX_TASK_KEYWORDS = listOf(
    "分析", "审查", "评审", "优化", "重构", "架构", "方案", "设计", "实现",
    "排查", "修复", "翻译", "总结", "规划", "复杂", "深度", "代码", "算法",
    "论文", "报告", "长文", "测试", "对比", "评估", "调研", "梳理",
)

// 流式 chunk 合并落列表的节流间隔：攒 50ms 的增量一次性更新，
// 避免每个 token 都做一次全列表拷贝 + 重组（快流式下主线程被打满 → 卡死）
private const val CHUNK_FLUSH_MS = 50L

// 流式期间 Markdown 只渲染尾部上限，防止长回复每个增量都全量重解析（O(n²)）
private const val STREAM_TAIL_CHARS = 8000

// T6 插话反馈 banner 展示时长：与 data/SteerFeedback.kt 的 steerFlashOn 默认 durationMs 保持一致
private const val STEER_FLASH_MS = 2000L

// 非流式超长消息折叠阈值：超过则默认只显示前 N 字 + 「展开全文」（省渲染/观感提速）
private const val COLLAPSE_CHARS = 3000

// ──────────────────────────── 聊天条目模型与状态机 ────────────────────────────

/** 用户消息里的图片内容块（mediaType + base64 data，DSH 协议 image 块） */
data class UserImage(val mediaType: String, val base64: String)

sealed interface ChatItem {
    val key: String
    /** 服务端消息序号：缓存/网络首屏合并去重、排序用 */
    val seq: Long?

    data class User(
        override val key: String,
        val text: String,
        override val seq: Long? = null,
        val images: List<UserImage> = emptyList(),
        /** T6 插话乐观标记：本条消息以 steer 模式发送（纯乐观，服务端无回执，不落缓存） */
        val steerSent: Boolean = false,
    ) : ChatItem
    data class Assistant(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val thinkSeconds: Long? = null,
        /** S5：思考链文本——reasoning-delta 流式累积；assistant/message 的 reasoning 块兜底（不落磁盘缓存） */
        val thinkingText: String = "",
        override val seq: Long? = null,
        /** T6 插话乐观标记：本条回复由插话触发（纯乐观，不落缓存） */
        val steerSent: Boolean = false,
    ) : ChatItem
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val args: String,
        val status: String = "running",
        val result: String? = null,
        val isError: Boolean = false,
        override val seq: Long? = null,
        /** S5：工具耗时 ms——tool/call ↔ tool/result 的 time 差值（实时配对，历史重放为 null） */
        val elapsedMs: Long? = null,
    ) : ChatItem

    data class Notice(override val key: String, val text: String, val isError: Boolean = false, override val seq: Long? = null) : ChatItem
}

/** 缓存互转：UI 条目 ↔ gzip 磁盘条目（磁盘只存 hasImages 占位，不存 base64） */
private fun ChatItem.toCached(): CachedItem = when (this) {
    is ChatItem.User -> CachedItem(
        kind = "user", text = text, seq = seq,
        images = images.map { CachedImage(it.mediaType, it.base64) },
        hasImages = images.isNotEmpty(),
    )
    is ChatItem.Assistant -> CachedItem(
        kind = "assistant", text = text, thinkSeconds = thinkSeconds, thinkingText = thinkingText, streaming = streaming, seq = seq,
    )
    is ChatItem.Tool -> CachedItem(
        kind = "tool", name = name, args = args, status = status, result = result, isError = isError, seq = seq,
    )
    is ChatItem.Notice -> CachedItem(kind = "notice", text = text, isError = isError, seq = seq)
}

private fun CachedItem.toChatItem(key: String): ChatItem = when (kind) {
    "user" -> ChatItem.User(
        key, text, seq = seq,
        images = images.map { UserImage(it.mediaType, it.base64) },
    )
    "assistant" -> ChatItem.Assistant(key, text, streaming = streaming, thinkSeconds = thinkSeconds, thinkingText = thinkingText, seq = seq)
    "tool" -> ChatItem.Tool(key, callId = key, name = name, args = args, status = status, result = result, isError = isError, seq = seq)
    else -> ChatItem.Notice(key, text, isError = isError, seq = seq)
}

private data class UserContent(val text: String, val images: List<UserImage>)

/** 解析用户消息内容块：text 块拼文字，image 块取 mediaType + base64（供 UI 渲染） */
private fun userContentOf(content: JsonElement?): UserContent {
    val arr = content as? JsonArray ?: return UserContent("", emptyList())
    val text = StringBuilder()
    val images = mutableListOf<UserImage>()
    arr.forEach { el ->
        val o = el.jsonObject
        when {
            o["type"]?.jsonPrimitive?.contentOrNull == "image" -> {
                val mediaType = o["mediaType"]?.jsonPrimitive?.contentOrNull ?: "image/png"
                val data = o["data"]?.jsonPrimitive?.contentOrNull
                    ?: o["base64"]?.jsonPrimitive?.contentOrNull
                if (!data.isNullOrBlank()) images.add(UserImage(mediaType, data))
            }
            o["text"] is JsonPrimitive && (o["text"] as JsonPrimitive).isString -> {
                if (text.isNotEmpty()) text.append("\n")
                text.append((o["text"] as JsonPrimitive).content)
            }
            o["type"]?.jsonPrimitive?.contentOrNull == "text" && o["value"] is JsonPrimitive -> {
                if (text.isNotEmpty()) text.append("\n")
                text.append((o["value"] as JsonPrimitive).content)
            }
        }
    }
    return UserContent(text.toString(), images)
}

private fun assistantTextOf(data: JsonElement): String {
    val msg = data.jsonObject["message"]?.jsonObject ?: data.jsonObject
    val content = msg["content"]
    return if (content is JsonArray) {
        content.mapNotNull { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull == "text" && o["text"] is JsonPrimitive) {
                (o["text"] as JsonPrimitive).content
            } else null
        }.joinToString("\n")
    } else ""
}

private fun chunkTextOf(data: JsonElement): String {
    val chunk = data.jsonObject["chunk"]
    return when {
        chunk is JsonPrimitive && chunk.isString -> chunk.content
        chunk is JsonObject -> {
            // 只接受正文增量；reasoning-delta（思考过程）由 reasoningDeltaOf 单独提取进 thinkingText，不进正文
            val type = chunk["type"]?.jsonPrimitive?.contentOrNull
            if (type != null && type != "text-delta") return ""
            when {
                chunk["text"] is JsonPrimitive && (chunk["text"] as JsonPrimitive).isString ->
                    (chunk["text"] as JsonPrimitive).content
                chunk["delta"] is JsonPrimitive && (chunk["delta"] as JsonPrimitive).isString ->
                    (chunk["delta"] as JsonPrimitive).content
                else -> ""
            }
        }
        else -> ""
    }
}

private fun toolResultOf(data: JsonElement): Pair<String, Boolean>? {
    val msg = data.jsonObject["message"]?.jsonObject ?: return null
    val content = msg["content"] as? JsonArray ?: return null
    for (el in content) {
        val o = el.jsonObject
        if (o["type"]?.jsonPrimitive?.contentOrNull == "tool-result") {
            val inner = o["content"]
            val text = when (inner) {
                is JsonArray -> inner.mapNotNull { x ->
                    when {
                        x is JsonPrimitive && x.isString -> x.content
                        x is JsonObject && x["text"] is JsonPrimitive -> (x["text"] as JsonPrimitive).content
                        else -> null
                    }
                }.joinToString("\n")
                is JsonPrimitive -> inner.content
                else -> ""
            }
            val isError = o["isError"]?.jsonPrimitive?.booleanOrNull ?: false
            return text to isError
        }
    }
    return null
}

/**
 * S5：从 tool/result 事件 data 取配对 callId——优先 brief 指定位置
 * `message.content[0].toolCallId`（tool-result 块），兜底既有 `message.source.callId`（同值）。
 * 取不到 → ""（沿用旧语义：空 id 按「唯一 running 工具」匹配）。
 */
private fun toolResultCallIdOf(data: JsonElement): String {
    val msg = data.jsonObject["message"]?.jsonObject ?: return ""
    val content = msg["content"] as? JsonArray
    if (content != null) {
        for (el in content) {
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull != "tool-result") continue
            return o["toolCallId"]?.jsonPrimitive?.contentOrNull ?: ""
        }
    }
    return (msg["source"] as? JsonObject)?.get("callId")?.jsonPrimitive?.contentOrNull ?: ""
}

class SessionChatState(
    private val scope: CoroutineScope,
    private val connection: DshConnection,
    val sessionId: String,
    private val cache: HistoryCache,
) {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()
    /** 首屏加载中（构造起 → 首次 load() 结束）：空列表 + 加载中 → UI 显示骨架屏气泡 */
    val initialLoading = MutableStateFlow(true)
    val title = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val currentMode = MutableStateFlow<String?>(null)
    val imageLimits = MutableStateFlow<JsonObject?>(null)
    var hasMore = false
    private var oldestSeq: Long? = null
    private var streamStart: Long? = null
    /** key 生成线程安全：load() 解析在 Default 线程、实时事件在主线程，并发自增不得冲突 */
    private val counter = java.util.concurrent.atomic.AtomicLong(0)

    /** S5：工具耗时配对——tool/call 登记时间，tool/result 配对计算并移除 */
    private val toolTiming = ToolTimingTracker()

    /** S5：断线/换会话时清空在途配对（时间戳已失效，避免跨连接错配） */
    fun clearToolTiming() = toolTiming.cleanup()

    // T6 插话乐观标记：steer 发送时登记的文本，按服务端回显 USER_MESSAGE 的文本匹配消费。
    // 列表语义——同文本连发两次可分别消费（UI 单飞发送，实际最多一条在途）。
    private val steerPending = mutableListOf<String>()
    /** steer 回显命中后置 true：下一条 ASSISTANT_MESSAGE（插话触发的回复）标记「插话」后消费 */
    private var steerReplyArmed = false

    /** T6：steer 发送时登记本次文本；回显 USER_MESSAGE 按文本匹配标记「插话」。
     *  纯文本以外（纯图片发送）不登记——无法稳定匹配回显，消息级徽章跳过，banner 反馈不受影响。 */
    fun markSteerPending(text: String) {
        if (text.isNotBlank()) steerPending.add(text)
    }

    /** T6：发送失败时撤回登记，避免后续无关回显被误标 */
    fun clearSteerPending(text: String) {
        if (text.isNotBlank()) steerPending.removeAll { it == text }
    }

    // 流式增量缓冲：chunk 先攒在这里，50ms 批量 flush 一次列表
    private val pendingChunk = StringBuilder()
    /** S5：思考增量缓冲——reasoning-delta 与正文 chunk 同批次 flush，保证时序一致 */
    private val pendingThinking = StringBuilder()
    private var flushJob: Job? = null
    private var cacheJob: Job? = null
    // 注：token 用量统计已上移至全局 TokenUsageWatcher（挂在连接事件流上，PC 端会话也计数），
    // 会话页不再本地计数，避免与全局重复扣费。

    /** 温缓存延迟写盘（3s 防抖，gzip 压缩；快照拷贝在 Default 线程） */
    private fun scheduleCacheSave() {
        cacheJob?.cancel()
        cacheJob = scope.launch {
            delay(3_000)
            val snapshot = withContext(Dispatchers.Default) { _items.value.map { it.toCached() } }
            cache.saveHistory(sessionId, snapshot)
        }
    }

    // load() 完成前到达的实时事件先入队，历史页落地后重放，
    // 防止网络加载期间 _items 被整页覆盖丢掉实时 chunk
    private var loaded = false
    private val preloadBuffer = mutableListOf<SessionEventWire>()

    private fun nextKey() = "k${counter.getAndIncrement()}"

    /** S5：完整消息 reasoning 块与流式已累积思考合并——取长（块为完整版，流式增量可能被服务端截断） */
    private fun mergeThinking(accumulated: String, block: String?): String = when {
        block.isNullOrBlank() -> accumulated
        accumulated.isBlank() -> block
        block.length > accumulated.length -> block
        else -> accumulated
    }

    private fun flushPendingChunk() {
        flushJob?.cancel()
        flushJob = null
        if (pendingChunk.isEmpty() && pendingThinking.isEmpty()) return
        val delta = pendingChunk.toString()
        val thinkingDelta = pendingThinking.toString()
        pendingChunk.clear()
        pendingThinking.clear()
        val cur = _items.value.toMutableList()
        val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
        if (idx >= 0) {
            val a = cur[idx] as ChatItem.Assistant
            cur[idx] = ChatItem.Assistant(
                a.key, a.text + delta,
                thinkingText = a.thinkingText + thinkingDelta,
                streaming = true,
            )
        } else {
            cur.add(ChatItem.Assistant(nextKey(), delta, thinkingText = thinkingDelta, streaming = true))
        }
        _items.value = cur
        scheduleCacheSave()
    }

    private fun parseMeta(projections: JsonElement?) {
        try {
            val values = projections?.jsonObject?.get("values")?.jsonObject ?: return
            values["imageLimits"]?.jsonObject?.let { imageLimits.value = it }
        } catch (_: Exception) {}
    }

    suspend fun load() {
        // 冷热分离：先渲染本地温缓存（秒开；gzip 解压+解码移出主线程），再刷网络冷数据
        val cachedItems = withContext(Dispatchers.Default) {
            cache.loadHistory(sessionId)?.mapIndexed { i, c -> c.toChatItem(nextKey()) }
        }
        if (!cachedItems.isNullOrEmpty()) {
            _items.value = cachedItems
            hasMore = true
        }
        try {
            // 首屏只取最近 3 条消息：服务端把 assistant/chunk 全量回传（约占 payload 98%），
            // maxMessages=10 时载荷可达 1MB+，手机端传输+解码+拼接就是"加载慢"的来源。
            // Home 后台预取命中时零网络直接消费。
            val h = HistoryMemoryCache.takePrefetch(sessionId)
                ?: connection.history(sessionId, maxMessages = 3)
            oldestSeq = h.events.firstOrNull()?.event?.seq
            hasMore = h.hasMore
            parseMeta(h.projections)
            // 历史事件解析与拼接在 Default 线程完成（JSON 遍历 + StringBuilder 线性合并）
            val net = withContext(Dispatchers.Default) {
                val list = mutableListOf<ChatItem>()
                // 历史 chunk 只保留"进行中回合"的尾部：其余 chunk 被 assistant/message 完整文本覆盖。
                // 用 StringBuilder 线性合并，避免逐条 a.text + d 的 O(n²) 拷贝。
                val chunkBuf = StringBuilder()
                var chunkStreamingIdx = -1
                fun flushChunks() {
                    if (chunkBuf.isEmpty()) return
                    if (chunkStreamingIdx >= 0 && chunkStreamingIdx < list.size) {
                        val a = list[chunkStreamingIdx] as ChatItem.Assistant
                        list[chunkStreamingIdx] = ChatItem.Assistant(a.key, a.text + chunkBuf.toString(), streaming = true, seq = a.seq)
                    } else {
                        list.add(ChatItem.Assistant(nextKey(), chunkBuf.toString(), streaming = true))
                    }
                    chunkBuf.clear()
                    chunkStreamingIdx = -1
                }
                h.events.forEach { entry ->
                    val e = entry.event
                    when (e.type) {
                        DshEventTypes.USER_MESSAGE -> {
                            flushChunks()
                            val uc = userContentOf(e.data.jsonObject["content"])
                            if (uc.text.isNotBlank() || uc.images.isNotEmpty()) {
                                list.add(ChatItem.User(nextKey(), uc.text, seq = e.seq, images = uc.images))
                            }
                        }
                        DshEventTypes.ASSISTANT_MESSAGE -> {
                            // 完整消息到达：丢弃已收集的 chunk（其内容已包含在消息全文里）
                            chunkBuf.clear()
                            chunkStreamingIdx = -1
                            val t = assistantTextOf(e.data)
                            val think = reasoningBlockOf(e.data)
                            val idx = list.indexOfLast { it is ChatItem.Assistant && it.streaming }
                            if (idx >= 0) {
                                list[idx] = ChatItem.Assistant(list[idx].key, t, thinkingText = think ?: "", seq = e.seq)
                            } else if (t.isNotBlank() || think != null) {
                                list.add(ChatItem.Assistant(nextKey(), t, thinkingText = think ?: "", seq = e.seq))
                            }
                        }
                        DshEventTypes.ASSISTANT_CHUNK -> {
                            val d = chunkTextOf(e.data)
                            if (d.isNotEmpty()) {
                                val idx = list.indexOfLast { it is ChatItem.Assistant && it.streaming }
                                if (idx >= 0 && chunkStreamingIdx < 0) chunkStreamingIdx = idx
                                chunkBuf.append(d)
                            }
                        }
                        DshEventTypes.TOOL_CALL -> {
                            val d = e.data.jsonObject
                            list.add(
                                ChatItem.Tool(
                                    key = nextKey(),
                                    callId = d["callId"]?.jsonPrimitive?.contentOrNull ?: "",
                                    name = d["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                                    args = d["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                                    seq = e.seq,
                                )
                            )
                        }
                        DshEventTypes.TOOL_RESULT -> attachResult(list, e.data, e.time)
                        "permission/preset" -> {
                            e.data.jsonObject["preset"]?.jsonPrimitive?.contentOrNull?.let { currentMode.value = it }
                        }
                        DshEventTypes.SESSION_TITLE -> {
                            e.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { title.value = it }
                        }
                        DshEventTypes.AGENT_ERROR -> {
                            val msg = e.data.jsonObject["error"]?.toString() ?: "智能体出错"
                            list.add(ChatItem.Notice(nextKey(), msg, true, seq = e.seq))
                        }
                        else -> {}
                    }
                }
                // 循环尾：flush 进行中回合残留的 chunk 尾部
                flushChunks()
                list
            }
            // 缓存 + 网络合并：按 seq 拼接，避免网络 3 条到达后整页替换导致内容闪跳丢失
            val merged = withContext(Dispatchers.Default) { mergeCachedWithNet(cachedItems, net) }
            _items.value = merged
            withContext(Dispatchers.Default) { cache.saveHistory(sessionId, merged.map { it.toCached() }) }
        } catch (e: Exception) {
            if (cachedItems.isNullOrEmpty()) {
                _items.value = listOf(ChatItem.Notice(nextKey(), "加载历史失败：" + e.message, true))
            } else {
                _items.value = _items.value + ChatItem.Notice(nextKey(), "网络失败，显示本地缓存（重进可重试）", false)
            }
        } finally {
            initialLoading.value = false
            // 历史页落地后重放加载期间积压的实时事件，保证内容不丢
            loaded = true
            if (preloadBuffer.isNotEmpty()) {
                val pending = preloadBuffer.toList()
                preloadBuffer.clear()
                pending.forEach { processLiveEvent(it) }
            }
        }
    }

    /**
     * 缓存与网络首屏合并：新格式缓存（带 seq）按序号去重拼接——
     * 缓存中早于网络首条的条目保留，网络条目覆盖尾部；旧格式缓存（seq 全 null，
     * 一次性过渡）直接追加，尾部可能重复一次，随后写盘升级为新格式。
     */
    private fun mergeCachedWithNet(cached: List<ChatItem>?, net: List<ChatItem>): List<ChatItem> {
        if (cached.isNullOrEmpty()) return net
        val cacheHasSeq = cached.any { it.seq != null }
        if (!cacheHasSeq) return cached + net
        val netMinSeq = net.mapNotNull { it.seq }.minOrNull() ?: return cached + net
        return cached.filter { item -> val s = item.seq; s == null || s < netMinSeq } + net
    }

    /** 解析历史页（loadMore/loadAll 用：更早历史为完整消息，无需 chunk 合并） */
    private fun parseHistoryPage(h: HistoryValue): List<ChatItem> {
        val out = mutableListOf<ChatItem>()
        h.events.forEach { entry ->
            val e = entry.event
            when (e.type) {
                DshEventTypes.USER_MESSAGE -> {
                    val uc = userContentOf(e.data.jsonObject["content"])
                    if (uc.text.isNotBlank() || uc.images.isNotEmpty()) {
                        out.add(ChatItem.User(nextKey(), uc.text, seq = e.seq, images = uc.images))
                    }
                }
                DshEventTypes.ASSISTANT_MESSAGE -> {
                    val t = assistantTextOf(e.data)
                    val think = reasoningBlockOf(e.data)
                    if (t.isNotBlank() || think != null) {
                        out.add(ChatItem.Assistant(nextKey(), t, thinkingText = think ?: "", seq = e.seq))
                    }
                }
                // 更早历史中的 chunk 已被完整 assistant/message 覆盖，忽略
                DshEventTypes.TOOL_CALL -> {
                    val d = e.data.jsonObject
                    out.add(
                        ChatItem.Tool(
                            key = nextKey(),
                            callId = d["callId"]?.jsonPrimitive?.contentOrNull ?: "",
                            name = d["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                            args = d["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                            seq = e.seq,
                        )
                    )
                }
                DshEventTypes.TOOL_RESULT -> attachResult(out, e.data, e.time)
                "permission/preset" -> {
                    e.data.jsonObject["preset"]?.jsonPrimitive?.contentOrNull?.let { currentMode.value = it }
                }
                DshEventTypes.SESSION_TITLE -> {
                    e.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { title.value = it }
                }
                DshEventTypes.AGENT_ERROR -> {
                    val msg = e.data.jsonObject["error"]?.toString() ?: "智能体出错"
                    out.add(ChatItem.Notice(nextKey(), msg, true, seq = e.seq))
                }
                else -> {}
            }
        }
        return out
    }

    /** 加载更早的消息（按 seq 前插；每页 15 条，减少点击次数） */
    suspend fun loadMore() {
        val before = oldestSeq ?: return
        try {
            val h = connection.history(sessionId, beforeSeq = before, maxMessages = 15)
            oldestSeq = h.events.firstOrNull()?.event?.seq
            hasMore = h.hasMore && h.events.isNotEmpty()
            val list = withContext(Dispatchers.Default) { parseHistoryPage(h) }
            if (list.isNotEmpty()) _items.value = list + _items.value
            withContext(Dispatchers.Default) { cache.saveHistory(sessionId, _items.value.map { it.toCached() }) }
        } catch (_: Exception) {}
    }

    /** 加载全部历史：循环翻页直到服务端 hasMore=false（每页 50 条） */
    suspend fun loadAll() {
        var guard = 0
        while (hasMore && oldestSeq != null && guard < 500) {
            val before = oldestSeq
            try {
                val h = connection.history(sessionId, beforeSeq = before, maxMessages = 50)
                if (h.events.isEmpty()) { hasMore = false; break }
                oldestSeq = h.events.firstOrNull()?.event?.seq
                // 防呆：序号没前进说明服务端重复返回同一页，停止避免死循环
                if (oldestSeq != null && oldestSeq == before) { hasMore = false; break }
                hasMore = h.hasMore
                val list = withContext(Dispatchers.Default) { parseHistoryPage(h) }
                if (list.isNotEmpty()) _items.value = list + _items.value
                guard++
            } catch (_: Exception) { break }
        }
        withContext(Dispatchers.Default) { cache.saveHistory(sessionId, _items.value.map { it.toCached() }) }
    }

    fun onSessionEvent(e: SessionEventWire) {
        // load() 尚未完成：先入队，历史页落地后重放，避免被整页覆盖丢内容
        if (!loaded) {
            preloadBuffer.add(e)
            return
        }
        // 实时事件可能形态异常（data 非对象等），异常一律跳过，绝不能让会话页崩溃
        try {
            processLiveEvent(e)
        } catch (_: Exception) {}
    }

    private fun processLiveEvent(e: SessionEventWire) {
        // chunk 走批量缓冲路径：攒 CHUNK_FLUSH_MS 的增量一次性 flush，
        // 不做每 token 一次的全列表拷贝 + 重组（快流式下主线程被打满 → 卡死）
        if (e.type == DshEventTypes.ASSISTANT_CHUNK) {
            if (streamStart == null) streamStart = e.time
            val delta = chunkTextOf(e.data)
            val thinkingDelta = reasoningDeltaOf(e.data)
            if (delta.isNotEmpty()) pendingChunk.append(delta)
            if (!thinkingDelta.isNullOrEmpty()) pendingThinking.append(thinkingDelta)
            if ((pendingChunk.isNotEmpty() || pendingThinking.isNotEmpty()) && flushJob == null) {
                flushJob = scope.launch {
                    delay(CHUNK_FLUSH_MS)
                    flushPendingChunk()
                }
            }
            return
        }
        // 其余事件：先落盘攒下的 chunk（保持时序），再一次性更新列表
        flushPendingChunk()
        val cur = _items.value.toMutableList()
        when (e.type) {
            DshEventTypes.USER_MESSAGE -> {
                val uc = userContentOf(e.data.jsonObject["content"])
                if (uc.text.isNotBlank() || uc.images.isNotEmpty()) {
                    // T6：按文本匹配本次 steer 发送（乐观标记；匹配即登记「插话」回复）
                    val steer = if (uc.text.isNotBlank()) steerPending.remove(uc.text) else false
                    if (steer) steerReplyArmed = true
                    cur.add(ChatItem.User(nextKey(), uc.text, seq = e.seq, images = uc.images, steerSent = steer))
                }
            }

            DshEventTypes.ASSISTANT_MESSAGE -> {
                val t = assistantTextOf(e.data)
                // S5：完整消息兜底——reasoning 块作为思考全文；流式已累积时取长合并（块为完整版，流式可能截断）
                val block = reasoningBlockOf(e.data)
                val secs = streamStart?.let { (e.time - it) / 1000 }
                val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
                // T6：插话回显命中后，本条（或其覆盖的流式条目）即插话触发的回复
                val steer = steerReplyArmed.also { steerReplyArmed = false }
                if (idx >= 0) {
                    val a = cur[idx] as ChatItem.Assistant
                    cur[idx] = ChatItem.Assistant(
                        a.key, t,
                        thinkingText = mergeThinking(a.thinkingText, block),
                        thinkSeconds = secs, seq = e.seq, steerSent = steer,
                    )
                } else if (t.isNotBlank() || block != null) {
                    cur.add(
                        ChatItem.Assistant(
                            nextKey(), t,
                            thinkingText = block ?: "",
                            thinkSeconds = secs, seq = e.seq, steerSent = steer,
                        )
                    )
                }
                streamStart = null
            }

            DshEventTypes.TOOL_CALL -> {
                val d = e.data.jsonObject
                val callId = d["callId"]?.jsonPrimitive?.contentOrNull ?: ""
                toolTiming.onCall(callId, e.time)
                cur.add(
                    ChatItem.Tool(
                        key = nextKey(),
                        callId = callId,
                        name = d["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                        args = d["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                        seq = e.seq,
                    )
                )
            }

            DshEventTypes.TOOL_RESULT -> attachResult(cur, e.data, e.time)
            DshEventTypes.SESSION_TITLE -> {
                e.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { title.value = it }
            }

            "permission/preset" -> {
                e.data.jsonObject["preset"]?.jsonPrimitive?.contentOrNull?.let { currentMode.value = it }
            }

            DshEventTypes.AGENT_ERROR -> {
                cur.add(
                    ChatItem.Notice(
                        nextKey(),
                        e.data.jsonObject["error"]?.toString() ?: "智能体出错",
                        true,
                        seq = e.seq,
                    )
                )
            }

            DshEventTypes.TURN_END -> {
                streamStart = null
                // 一轮结束：结束任何残留的流式占位
                val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
                if (idx >= 0) {
                    val a = cur[idx] as ChatItem.Assistant
                    cur[idx] = ChatItem.Assistant(
                        a.key, a.text,
                        thinkingText = a.thinkingText,
                        streaming = false, seq = a.seq,
                    )
                }
            }

            else -> {}
        }
        _items.value = cur
    }

    private fun attachResult(list: MutableList<ChatItem>, data: JsonElement, timeMs: Long) {
        val r = toolResultOf(data) ?: return
        val callId = toolResultCallIdOf(data)
        // S5：配对计算工具耗时（无配对/负值 → null；历史重放路径 tracker 为空同样得 null）
        val elapsed = if (callId.isEmpty()) null else toolTiming.onResult(callId, timeMs)
        val idx = list.indexOfLast {
            it is ChatItem.Tool && (it.callId == callId || (callId.isEmpty() && it.status == "running"))
        }
        if (idx >= 0) {
            val t = list[idx] as ChatItem.Tool
            list[idx] = t.copy(
                status = if (r.second) "error" else "done",
                result = r.first.take(4000),
                isError = r.second,
                elapsedMs = elapsed,
            )
        }
    }
}

// ──────────────────────────── 会话屏 ────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SessionScreen(
    sessionId: String,
    connection: DshConnection,
    approvalCenter: ApprovalCenter,
    focusSeq: Long?,
    onBack: () -> Unit,
    onPending: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val state = remember(sessionId) { SessionChatState(scope, connection, sessionId, HistoryCache(context)) }
    val items by state.items.collectAsState()
    val initialLoading by state.initialLoading.collectAsState()
    val title by state.title.collectAsState()
    val running by state.running.collectAsState()

    // 本会话待办数量（审批 + 问答，异常不计入轻提示条）
    val centerItems by approvalCenter.items.collectAsState()
    val myPending = centerItems.count {
        it.sessionId == sessionId && (it is PendingItem.Approval || it is PendingItem.Question)
    }

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var steerMode by remember { mutableStateOf(false) }
    // T6 插话反馈 banner：steer 发送瞬间显示 2s「⚡ 已插话」后自动消失（纯乐观，无服务端回执）
    var steerFlash by remember { mutableStateOf(SteerFlash(false)) }
    var pendingImages by remember { mutableStateOf<List<DshConnection.ImagePart>>(emptyList()) }
    // 快捷指令条（T5）：SettingsStore 持久化，未配置时注入内置默认 4 条
    val settingsStore = remember { SettingsStore(context) }
    val quickPrompts by settingsStore.quickPrompts.collectAsState(initial = defaultQuickPrompts())
    var quickEditDialog by remember { mutableStateOf(false) }
    var quickEditText by remember { mutableStateOf("") }
    var modelDialog by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    var modelInfo by remember { mutableStateOf<SessionModelsValue?>(null) }
    var skillsDialog by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf<List<DshConnection.SkillEntry>>(emptyList()) }
    var skillsLoading by remember { mutableStateOf(false) }
    // S7 slash 命令面板：commands/list + 执行反馈
    var commandsDialog by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf<List<SlashCommand>>(emptyList()) }
    var commandsLoading by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var workspaceLabel by remember { mutableStateOf<String?>(null) }
    var autoModelEnabled by remember { mutableStateOf(true) }
    var streamNotice by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var loadingAll by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    // S7 导出会话：顶栏菜单 → history 拉取 → Markdown → cacheDir/exports → ACTION_SEND
    var exportMenu by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    // focusSeq 定位：历史加载完成标志 + 短暂高亮的目标 seq
    var historyReady by remember { mutableStateOf(false) }
    var highlightedSeq by remember { mutableStateOf<Long?>(null) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@launch
                if (bytes.size > 4 * 1024 * 1024) {
                    actionError = "图片超过 4MB"
                    return@launch
                }
                val type = context.contentResolver.getType(uri) ?: "image/png"
                if (type !in listOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                    actionError = "不支持的图片格式: $type"
                    return@launch
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                pendingImages = pendingImages + DshConnection.ImagePart(type, b64)
            } catch (e: Exception) {
                actionError = e.message
            }
        }
    }

    // 语音输入（T2）：麦克风按钮 → RECORD_AUDIO 权限（未授权先申请，拒绝 Toast 提示）
    // → RecognizerIntent 系统语音识别 → EXTRA_RESULTS 首条文本回填输入栏（末尾补空格拼接）
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val norm = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let { normalizeVoiceResult(it) }
            if (norm != null) {
                // 既有输入拼接：末尾补空格，避免两段文本粘连
                input = if (input.isBlank()) norm else input.trimEnd() + " " + norm
            }
        }
    }
    val startVoiceRecognition: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "对着手机说话")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            // 设备无语音识别服务（如部分定制 ROM / 模拟器）
            Toast.makeText(context, "此设备不支持语音输入", Toast.LENGTH_SHORT).show()
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted
        if (granted) startVoiceRecognition()
        else Toast.makeText(context, "需要麦克风权限", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(sessionId) {
        state.load()
        historyReady = true
        // 会话所属工作区（只读展示，切换入口在首页新对话）
        runCatching {
            val ws = connection.workspaceList().items
                .firstOrNull { it.sessionIds.contains(sessionId) }
            workspaceLabel = ws?.let { it.title.ifBlank { it.path } }
        }
        // 自适应模型开关（全局设置，可在此会话发送时生效）
        runCatching {
            autoModelEnabled = SettingsStore(context).autoModel.first()
        }
    }

    // 实时事件
    LaunchedEffect(sessionId) {
        connection.events.collect { ev ->
            when (ev) {
                is DshConnection.Event.SessionEvent ->
                    if (ev.sessionId == sessionId) state.onSessionEvent(ev.event)

                is DshConnection.Event.SessionStatus ->
                    if (ev.sessionId == sessionId) state.running.value = ev.status == "running"

                is DshConnection.Event.Jobs ->
                    if (ev.sessionId == sessionId) {
                        state.running.value = ev.jobs.any { it.status == "running" || it.status == "stopping" }
                    }

                is DshConnection.Event.Projection ->
                    if (ev.sessionId == sessionId && ev.key == "imageLimits") {
                        runCatching { ev.value.jsonObject }.getOrNull()?.let { state.imageLimits.value = it }
                    }

                is DshConnection.Event.StreamError -> {
                    streamNotice = ev.message
                    // S5：流中断，在途工具配对的时间戳可能已失效，清空防错配
                    state.clearToolTiming()
                }

                is DshConnection.Event.Reconnected -> {
                    streamNotice = null
                    // S5：重连后时间线重新开始，清空旧配对
                    state.clearToolTiming()
                }

                else -> {}
            }
        }
    }

    // T6：插话反馈 banner 自动消失——触发后 STEER_FLASH_MS 置回不可见；
    // at 变化（持续可见时再次触发）会重启计时，顺延展示时长
    LaunchedEffect(steerFlash.visible, steerFlash.at) {
        if (steerFlash.visible) {
            delay(STEER_FLASH_MS)
            steerFlash = steerFlashOn(sendingSteer = false, prev = steerFlash, now = System.currentTimeMillis())
        }
    }

    // 滚动到顶自动加载更早的消息（每加载一页顶部索引后移，需再次滚到顶才继续；
    // 与「加载更早」按钮并存，按钮兜底手动加载）
    LaunchedEffect(listState.firstVisibleItemIndex) {
        if (state.hasMore && listState.firstVisibleItemIndex <= 0 && items.isNotEmpty() && !loadingMore) {
            loadingMore = true
            scope.launch {
                state.loadMore()
                loadingMore = false
            }
        }
    }

    // 自动滚动到底部：仅在用户本来就停在底部时跟随（不抢用户上翻阅读），
    // 用无动画 scrollToItem；流式时按最后一条文本长度跟随增量
    val tailSig = items.lastOrNull()?.let { if (it is ChatItem.Assistant) it.text.length else 0 } ?: 0
    LaunchedEffect(items.size, tailSig) {
        if (items.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = info.totalItemsCount <= 0 || lastVisible >= info.totalItemsCount - 1
        if (atBottom) listState.scrollToItem(items.size - 1)
    }

    // focusSeq 定位：历史加载完成后，按事件 seq 直接映射到消息列表项，
    // 找到 → 滚动定位 + 短暂高亮；未找到 → 停在顶部 + snackbar 提示。
    LaunchedEffect(historyReady, focusSeq) {
        val target = focusSeq ?: return@LaunchedEffect
        if (!historyReady) return@LaunchedEffect
        val idx = items.indexOfFirst { it.seq == target }
        if (idx < 0) {
            if (items.isNotEmpty()) listState.scrollToItem(0)
            snackbarHostState.showSnackbar("目标位置不在已加载窗口")
            return@LaunchedEffect
        }
        // LazyColumn 首项可能是「加载更早/全部」头部，消息列表下标需顺延一位
        val listIdx = idx + (if (state.hasMore) 1 else 0)
        listState.scrollToItem(listIdx)
        highlightedSeq = target
        delay(1500)
        highlightedSeq = null
    }

    /** 自适应模型：短问答→flash，复杂/长文本→pro；模型列表不含对应档位时保持现状 */
    suspend fun applyAutoModel(text: String) {
        val info = modelInfo ?: connection.sessionModels(sessionId)?.also { modelInfo = it }
        if (info == null) return
        val cur = info.current
        val groups = runCatching { info.groups?.jsonArray }.getOrNull() ?: return
        val group = groups.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.contentOrNull == cur.provider
        } ?: return
        val models = runCatching { group.jsonObject.get("models")?.jsonArray }.getOrNull() ?: return
        val ids = models.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
        val complex = text.length > 200 || COMPLEX_TASK_KEYWORDS.any { text.contains(it) }
        val target = if (complex) {
            ids.firstOrNull { it.contains("pro", ignoreCase = true) } ?: return
        } else {
            ids.firstOrNull { it.contains("flash", ignoreCase = true) } ?: return
        }
        if (target != cur.model) {
            connection.selectModel(sessionId, cur.provider, target, null)
        }
    }

    fun send() {
        val text = input.trim()
        if ((text.isBlank() && pendingImages.isEmpty()) || sending) return
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        sending = true
        actionError = null
        // 捕获本次发送模式：await 期间用户切走开关不影响已发起请求的语义
        val isSteer = steerMode
        if (isSteer) {
            // T6：插话发送瞬间 → 「⚡ 已插话」banner + 登记消息级乐观标记
            steerFlash = steerFlashOn(sendingSteer = true, prev = steerFlash, now = System.currentTimeMillis())
            state.markSteerPending(text)
        }
        scope.launch {
            try {
                // 自适应模型：按任务难度先切 Flash/Pro 再发送
                if (autoModelEnabled && text.isNotBlank()) {
                    runCatching { applyAutoModel(text) }
                }
                connection.prompt(
                    sessionId,
                    text,
                    if (isSteer) "steer" else "queue",
                    images = pendingImages,
                )
                input = ""
                pendingImages = emptyList()
            } catch (e: Exception) {
                state.clearSteerPending(text)
                actionError = e.message
            } finally {
                sending = false
            }
        }
    }

    fun stopRun() {
        scope.launch {
            try {
                connection.cancel(sessionId)
            } catch (_: Exception) {}
            state.running.value = false
        }
    }

    /**
     * S7：导出会话——拉 session.history 原始 events → historyToMarkdown →
     * 写 cacheDir/exports/<sessionId>.md → ACTION_SEND（FileProvider URI）分享。
     * 文件保留供多次分享；失败 Toast。
     */
    fun exportSession() {
        if (exporting) return
        exporting = true
        scope.launch {
            try {
                val raw = connection.historyRawEvents(sessionId)
                if (raw.isEmpty()) {
                    Toast.makeText(context, "无内容可导出", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val md = withContext(Dispatchers.Default) { historyToMarkdown(title, raw) }
                val dir = File(context.cacheDir, "exports").apply { mkdirs() }
                val file = File(dir, "$sessionId.md")
                withContext(Dispatchers.IO) { file.writeText(md, Charsets.UTF_8) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/markdown"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TEXT, "DSH 会话日志：${title ?: sessionId}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(share, "导出会话日志"))
            } catch (e: Exception) {
                Toast.makeText(context, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                exporting = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        title ?: "会话 ${sessionId.take(8)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            renameText = title ?: ""
                            renameDialog = true
                        },
                    )
                    Text(
                        if (running) "运行中…" else "空闲",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running) DshBrand else MaterialTheme.colorScheme.outline,
                    )
                    workspaceLabel?.let { ws ->
                        Text(
                            ws,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                }
            },
            actions = {
                IconButton(onClick = {
                    skillsDialog = true
                    skillsLoading = true
                    scope.launch {
                        skills = connection.skillsList(sessionId)
                        skillsLoading = false
                    }
                }) {
                    Icon(Icons.Default.MenuBook, null)
                }
                IconButton(onClick = {
                    modelDialog = true
                    scope.launch { modelInfo = connection.sessionModels(sessionId) }
                }) {
                    Icon(Icons.Default.SmartToy, null)
                }
                IconButton(onClick = { modeDialog = true }) {
                    Icon(Icons.Default.Security, null)
                }
                IconButton(onClick = {
                    commandsDialog = true
                    commandsLoading = true
                    scope.launch {
                        commands = connection.commandsList(sessionId)
                        commandsLoading = false
                    }
                }) {
                    Icon(Icons.Default.Terminal, "斜杠命令")
                }
                // S7：导出会话（顶栏菜单）
                Box {
                    IconButton(onClick = { exportMenu = true }) {
                        Icon(Icons.Default.MoreVert, "更多")
                    }
                    DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(if (exporting) "导出中…" else "导出会话") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            enabled = !exporting,
                            onClick = {
                                exportMenu = false
                                exportSession()
                            },
                        )
                    }
                }
                if (running) {
                    IconButton(onClick = { stopRun() }) {
                        Icon(Icons.Default.Stop, null, tint = DshError)
                    }
                }
            },
        )

        // 连接中断提示条（事件流断开/首连失败时显示，重连成功后消失）
        streamNotice?.let { notice ->
            // 断线诊断：探测根路径分辨「服务在线通道抖动」与「服务器不可达（隧道断/电脑关机）」
            var diag by remember(notice) { mutableStateOf<String?>(null) }
            LaunchedEffect(notice) {
                diag = withContext(Dispatchers.IO) {
                    val code = runCatching {
                        val req = okhttp3.Request.Builder().url(connection.baseUrl()).head().build()
                        okhttp3.OkHttpClient.Builder()
                            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                            .newCall(req).execute().use { it.code }
                    }.getOrNull()
                    if (code != null) "服务在线（HTTP $code），通道恢复中…" to true
                    else null to false
                }.first
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (DshThemeStyle == ThemeStyle.CYBERPUNK) Modifier.ncHatch() else Modifier),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.WifiOff,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (diag != null) "$notice\n$diag" else notice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // 本会话待办轻提示条：审批/问答数量，异常不计入
        // 待办轻提示条：出现/消失 slide+fade（从顶部滑入，避免生硬闪现）
        androidx.compose.animation.AnimatedVisibility(
            visible = myPending > 0,
            enter = androidx.compose.animation.slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(220),
            ) + androidx.compose.animation.fadeIn(animationSpec = tween(220)),
            exit = androidx.compose.animation.slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(180),
            ) + androidx.compose.animation.fadeOut(animationSpec = tween(180)),
        ) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "本会话有 $myPending 条待办（审批/问答）",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onPending) { Text("去处理") }
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.hasMore) {
                item(key = "load_more") {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        TextButton(
                            onClick = { scope.launch { state.loadMore() } },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("加载更早的消息") }
                        TextButton(
                            onClick = {
                                loadingAll = true
                                scope.launch {
                                    state.loadAll()
                                    loadingAll = false
                                }
                            },
                            enabled = !loadingAll,
                        ) {
                            if (loadingAll) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("正在加载全部历史…")
                            } else {
                                Text("加载全部历史")
                            }
                        }
                    }
                }
            }
            // 首屏骨架：空列表 + 加载中 → 3 条呼吸气泡占位（对齐真实消息的宽度分布，感知更快）
            if (initialLoading && items.isEmpty()) {
                items(3, key = { "skeleton_$it" }) { index ->
                    SkeletonMessageBubble(index)
                }
            }
            items(items, key = { it.key }) { item ->
                // 新条目渐入：新消息平滑出现，不跳变（Telegram 式轻量动效）
                androidx.compose.runtime.key(item.key) {
                    val highlighted = highlightedSeq != null && item.seq == highlightedSeq
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (highlighted) {
                                    Modifier
                                        .background(DshWarn.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
                                        .padding(6.dp)
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        when (item) {
                            is ChatItem.User -> UserBubble(item, Modifier.animateItem())
                            is ChatItem.Assistant -> AssistantCard(item, Modifier.animateItem())
                            is ChatItem.Tool -> ToolCard(item, Modifier.animateItem())
                            is ChatItem.Notice -> NoticeRow(item, Modifier.animateItem())
                        }
                    }
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.fillMaxWidth())

        actionError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // 待发送图片
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pendingImages.forEachIndexed { i, _ ->
                    AssistChip(
                        onClick = { pendingImages = pendingImages.filterIndexed { j, _ -> j != i } },
                        label = { Text("图片 ${i + 1}") },
                        trailingIcon = {
                            Icon(Icons.Default.Close, null, Modifier.size(14.dp))
                        },
                    )
                }
            }
        }

        // 深蓝主题专属：输入栏上方底部纹章（dsh-deep-whale 皮肤装饰，低调居中）
        if (DshThemeId == "blue") {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(com.dsh.mobile.R.drawable.maid_crest),
                    contentDescription = null,
                    modifier = Modifier
                        .width(88.dp)
                        .graphicsLayer { alpha = 0.55f },
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        // 快捷指令条（T5）：输入栏上方横向 chip，点击追加 prompt 到输入框；条尾「编辑」打开编辑对话框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            quickPrompts.forEach { prompt ->
                AssistChip(
                    onClick = {
                        // 复用技能选择拼接模式（:1308）：追加 prompt 并补空格，避免与后续输入粘连
                        input = if (input.isBlank()) prompt + " " else input.trimEnd() + " " + prompt + " "
                    },
                    label = {
                        Text(prompt, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
            IconButton(
                onClick = {
                    quickEditText = quickPrompts.joinToString("\n")
                    quickEditDialog = true
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(Icons.Rounded.Edit, "编辑快捷指令", Modifier.size(18.dp))
            }
        }

        // 插话反馈 banner（T6）：steer 发送瞬间显示 2s「⚡ 已插话」（快捷指令条之下、输入栏之上），
        // 纯乐观反馈——服务端无回执事件（recon §7.5），可见性与时长由 SteerFlash 状态机驱动
        if (steerFlash.visible) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text("⚡ 已插话") },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = DshBrandSoft,
                        labelColor = DshBrand,
                    ),
                )
            }
        }

        // 输入栏（ChatGPT/Claude 风格胶囊化；其余风格保持原样）
        val modernInput = DshThemeStyle == ThemeStyle.CHATGPT || DshThemeStyle == ThemeStyle.CLAUDE
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = if (modernInput) 0.dp else 3.dp,
            shadowElevation = if (modernInput) 0.dp else 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (modernInput) {
                            if (DshThemeStyle == ThemeStyle.CHATGPT) {
                                Modifier
                                    .navigationBarsPadding()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)
                                    .height(52.dp)
                                    .clip(RoundedCornerShape(26.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 12.dp)
                            } else {
                                // Claude：28px 超大圆角，自适应高度
                                Modifier
                                    .navigationBarsPadding()
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 4.dp)
                                    .clip(RoundedCornerShape(28.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            }
                        } else {
                            Modifier
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .imePadding()
                        },
                    ),
                verticalAlignment = if (modernInput) Alignment.CenterVertically else Alignment.Bottom,
            ) {
                IconButton(onClick = { imagePicker.launch("image/*") }) {
                    Icon(Icons.Default.AttachFile, null)
                }
                // 语音输入：已授权直接拉起 RecognizerIntent；未授权先申请 RECORD_AUDIO
                IconButton(onClick = {
                    if (micGranted) startVoiceRecognition()
                    else micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }) {
                    Icon(Icons.Rounded.Mic, "语音输入")
                }
                if (DshThemeStyle == ThemeStyle.CODEX) {
                    Text(
                        "❯",
                        style = MaterialTheme.typography.titleMedium,
                        color = DshBrand,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    // width(0)+weight(1f)：消除 OutlinedTextField 默认最小宽度（约 280dp）对
                    // 右侧按钮的挤压——长文本/窄屏/大字体下发送键不再被推出屏幕
                    modifier = Modifier
                        .width(0.dp)
                        .weight(1f),
                    placeholder = {
                        Text(
                            when {
                                DshThemeStyle == ThemeStyle.CODEX -> "> 消息…"
                                DshThemeStyle == ThemeStyle.CHATGPT -> "询问 DSH…"
                                DshThemeStyle == ThemeStyle.CLAUDE -> "给 DSH 发消息…"
                                else -> "给智能体发消息…"
                            },
                        )
                    },
                    maxLines = when {
                        DshThemeStyle == ThemeStyle.CHATGPT -> 1
                        DshThemeStyle == ThemeStyle.CLAUDE -> 3
                        else -> 4
                    },
                    shape = RoundedCornerShape(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (DshThemeStyle == ThemeStyle.CHATGPT || DshThemeStyle == ThemeStyle.CLAUDE) {
                    // 麦克风位置映射为 ⚡ 插话图标（24px）
                    Icon(
                        Icons.Default.Bolt,
                        "插话",
                        Modifier
                            .size(24.dp)
                            .clickable { steerMode = !steerMode },
                        tint = if (steerMode) DshBrand else MaterialTheme.colorScheme.onSurface,
                    )
                } else {
                    FilterChip(
                        selected = steerMode,
                        onClick = { steerMode = !steerMode },
                        label = {
                            Text(
                                "⚡ 插话",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .widthIn(max = 92.dp),
                    )
                }
                if (running) {
                    FilledIconButton(
                        onClick = { stopRun() },
                        modifier = Modifier.size(
                            when (DshThemeStyle) {
                                ThemeStyle.CHATGPT, ThemeStyle.CLAUDE -> 40.dp
                                else -> 46.dp
                            },
                        ),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    FilledIconButton(
                        onClick = { send() },
                        modifier = when (DshThemeStyle) {
                            ThemeStyle.CHATGPT -> Modifier
                                .size(40.dp)
                                .background(DshBrand, CircleShape)
                            ThemeStyle.CLAUDE -> Modifier
                                .size(44.dp)
                                .background(DshBrand, CircleShape)
                            else -> Modifier
                                .size(46.dp)
                                .background(brandGradient(), CircleShape)
                        },
                        enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                        } else if (DshThemeStyle == ThemeStyle.CLAUDE) {
                            // Claude 规格：向上箭头
                            Icon(Icons.Default.ArrowUpward, null, Modifier.size(20.dp))
                        } else {
                            Icon(Icons.Default.Send, null, Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        // 模型选择
        if (modelDialog) {
            ModelPickerDialog(
                info = modelInfo,
                onDismiss = { modelDialog = false },
                onApply = { provider, model, effort ->
                    scope.launch {
                        try {
                            connection.selectModel(sessionId, provider, model, effort)
                            modelDialog = false
                        } catch (e: Exception) {
                            actionError = e.message
                        }
                    }
                },
            )
        }

        // 审查严格度
        if (modeDialog) {
            AccessModeDialog(
                current = state.currentMode.value,
                onDismiss = { modeDialog = false },
                onSelect = { value ->
                    scope.launch {
                        try {
                            connection.executeCommand(sessionId, "/permission $value")
                            modeDialog = false
                        } catch (e: Exception) {
                            actionError = e.message
                        }
                    }
                },
            )
        }

        // 技能选择
        if (skillsDialog) {
            SkillsDialog(
                skills = skills,
                loading = skillsLoading,
                onDismiss = { skillsDialog = false },
                onPick = { name ->
                    input = input + "请使用 $name 技能："
                    skillsDialog = false
                },
            )
        }

        // 斜杠命令面板（S7）：点击命令 → commands/execute 直接执行；
        // 响应 {commandId, result:{kind, text}}——kind=error 红字轻提示，其余 Toast 展示输出
        if (commandsDialog) {
            CommandsDialog(
                commands = commands,
                loading = commandsLoading,
                onDismiss = { commandsDialog = false },
                onPick = { cmd ->
                    commandsDialog = false
                    scope.launch {
                        try {
                            val result = connection.executeCommand(sessionId, cmd.name)
                            val r = runCatching { result.jsonObject["result"]?.jsonObject }.getOrNull()
                            val kind = r?.get("kind")?.jsonPrimitive?.contentOrNull
                            val text = r?.get("text")?.jsonPrimitive?.contentOrNull
                            if (kind == "error") {
                                actionError = text ?: "命令执行失败"
                            } else {
                                Toast.makeText(context, text ?: "命令已执行", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            actionError = e.message
                        }
                    }
                },
            )
        }

        // 快捷指令编辑（T5）：每行一条，保存写入 SettingsStore
        if (quickEditDialog) {
            AlertDialog(
                onDismissRequest = { quickEditDialog = false },
                title = { Text("编辑快捷指令") },
                text = {
                    OutlinedTextField(
                        value = quickEditText,
                        onValueChange = { quickEditText = it },
                        label = { Text("每行一条") },
                        minLines = 4,
                        maxLines = 8,
                        shape = DshShape.small,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            quickEditDialog = false
                            val items = quickEditText.lines().map { it.trim() }.filter { it.isNotEmpty() }
                            scope.launch { settingsStore.setQuickPrompts(items) }
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { quickEditDialog = false }) { Text("取消") }
                },
            )
        }

        // 重命名会话（点标题触发）
        if (renameDialog) {
            AlertDialog(
                onDismissRequest = { renameDialog = false },
                title = { Text("重命名会话") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("会话标题") },
                        shape = DshShape.small,
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val name = renameText.trim()
                            renameDialog = false
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    runCatching { connection.rename(sessionId, name) }
                                        .onSuccess { state.title.value = name }
                                }
                            }
                        },
                    ) { Text("保存") }
                },
                dismissButton = {
                    TextButton(onClick = { renameDialog = false }) { Text("取消") }
                },
            )
        }
    }
}

// ──────────────────────────── 条目渲染 ────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UserBubble(item: ChatItem.User, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .then(modifier),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            if (item.steerSent) {
                // T6：插话乐观徽章——本条消息以 steer 模式发送（纯乐观，无服务端确认）
                Text(
                    "⚡ 插话",
                    style = MaterialTheme.typography.labelSmall,
                    color = DshBrand,
                    modifier = Modifier.padding(end = 6.dp, bottom = 2.dp),
                )
            }
            Surface(
                shape = DshShape.userBubble,
                color = if (DshThemeStyle == ThemeStyle.CHATGPT) Color(0xFF1C1C1E)
                else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            if (item.text.isNotBlank()) {
                                clipboard.setText(AnnotatedString(item.text))
                                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                            }
                        },
                    ),
            ) {
                Column(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item.images.forEach { img ->
                        val bitmap = remember(img.base64) { decodeUserImage(context, img) }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "图片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.FillWidth,
                            )
                        } else {
                            Text(
                                "（图片）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                    if (item.text.isNotBlank()) {
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (DshThemeStyle == ThemeStyle.CHATGPT) Color.White
                            else MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

/** base64 图片按屏幕宽度采样解码（大图不爆内存；GIF 取首帧） */
private fun decodeUserImage(context: android.content.Context, img: UserImage): Bitmap? {
    return try {
        val bytes = Base64.decode(img.base64, Base64.NO_WRAP)
        if (bytes.isEmpty()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val maxW = context.resources.displayMetrics.widthPixels
        var sample = 1
        while (bounds.outWidth / sample > maxW || bounds.outHeight / sample > maxW * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    } catch (_: Exception) {
        null
    }
}

/**
 * S5：思考链折叠区——「💭 已思考」标题行点击展开/收起思考文本。
 * 纯文本（bodySmall + 弱色），不渲染 Markdown；流式进行中标题显示「思考中…」。
 * 与既有「已思考 x 秒」占位（thinkSeconds 徽章）并存不冲突：前者是思考内容，后者是耗时。
 */
@Composable
private fun ThinkingFold(streaming: Boolean, text: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
        ) {
            Text(
                if (streaming) "💭 思考中…" else "💭 已思考",
                style = MaterialTheme.typography.labelSmall,
                color = if (streaming) MaterialTheme.colorScheme.onSurfaceVariant else DshWarn,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssistantCard(item: ChatItem.Assistant, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // 超长消息折叠状态
    var expanded by remember(item.key) { mutableStateOf(false) }
    Surface(
        shape = DshShape.assistantBubble,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    if (item.text.isNotBlank()) {
                        clipboard.setText(AnnotatedString(item.text))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                },
            )
            .then(modifier),
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // 品牌左侧色条：官方 Web 助手消息的视觉锚点（流式时用品牌软色）
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(if (item.streaming) DshBrandSoft else DshBrand),
            )
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (item.steerSent) {
                    // T6：插话乐观徽章——本条回复由插话（steer）触发（纯乐观，无服务端确认）
                    Text(
                        "⚡ 插话",
                        style = MaterialTheme.typography.labelSmall,
                        color = DshBrand,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                item.thinkSeconds?.let { secs ->
                    Text(
                        if (DshThemeStyle == ThemeStyle.CODEX) "⚡ think ${secs}s" else "⚡ 已思考 $secs 秒",
                        style = MaterialTheme.typography.labelSmall,
                        color = DshWarn,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (item.streaming) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (DshThemeStyle == ThemeStyle.CODEX) "thinking…" else "正在思考…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(8.dp))
                        CircularProgressIndicator(
                            Modifier.size(12.dp),
                            strokeWidth = 1.5.dp,
                            color = DshBrand,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // S5 思考链：内容上方渲染「💭 已思考」折叠区（点击展开/收起思考文本；流式进行中显示「思考中…」）
                if (item.thinkingText.isNotBlank()) {
                    ThinkingFold(streaming = item.streaming, text = item.thinkingText)
                    Spacer(Modifier.height(6.dp))
                }
                if (item.text.isNotBlank()) {
                    // 流式期间只渲染尾部：Markdown 解析/排版随文本增长是 O(n²)，
                    // 长回复每个增量都全量重解析会把主线程拖死
                    if (item.streaming && item.text.length > STREAM_TAIL_CHARS) {
                        Text(
                            "…（流式输出中，仅显示最新部分）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        MarkdownText(item.text.takeLast(STREAM_TAIL_CHARS))
                    } else if (!item.streaming && item.text.length > COLLAPSE_CHARS && !expanded) {
                        // 非流式超长消息折叠：默认只渲染前段，点击展开全文（省渲染，弱网更顺滑）
                        MarkdownText(item.text.take(COLLAPSE_CHARS))
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "展开全文（共 ${item.text.length} 字）",
                                style = MaterialTheme.typography.labelMedium,
                                color = DshBrand,
                            )
                        }
                    } else {
                        MarkdownText(item.text)
                        if (!item.streaming && item.text.length > COLLAPSE_CHARS && expanded) {
                            TextButton(
                                onClick = { expanded = false },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "收起",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = DshBrand,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolCard(item: ChatItem.Tool, modifier: Modifier = Modifier) {
    var expanded by remember(item.key) { mutableStateOf(false) }
    val done = item.status == "done"
    val failed = item.status == "error"

    Surface(
        shape = DshShape.small,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        onClick = { if (item.result != null) expanded = !expanded },
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        item.name.contains("bash", true) || item.name.contains("pwsh", true) ->
                            Icons.Default.Terminal
                        item.name.contains("read", true) || item.name.contains("write", true) ||
                            item.name.contains("edit", true) || item.name.contains("file", true) ->
                            Icons.Default.EditNote
                        item.name.contains("search", true) || item.name.contains("grep", true) ->
                            Icons.Default.Search
                        item.name.contains("web", true) || item.name.contains("browse", true) ->
                            Icons.Default.Language
                        item.name.contains("browser", true) -> Icons.Default.Public
                        else -> Icons.Default.Build
                    },
                    null,
                    Modifier.size(18.dp),
                    tint = if (failed) DshError else (if (done) DshSuccess else DshBrand),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    item.name,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                when {
                    failed -> ToolStatusBadge("失败", DshError)
                    done -> ToolStatusBadge("完成", DshSuccess)
                    else -> CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = DshBrand,
                    )
                }
                // S5：工具耗时徽章（实时配对；1s 以下显示 ms，以上 %.1f s）
                item.elapsedMs?.let { elapsed ->
                    Spacer(Modifier.width(6.dp))
                    ToolStatusBadge(
                        if (elapsed >= 1000) {
                            String.format(Locale.ROOT, "耗时 %.1f s", elapsed / 1000.0)
                        } else {
                            "耗时 $elapsed ms"
                        },
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (item.result != null) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null,
                        Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (item.args.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    (if (DshThemeStyle == ThemeStyle.CODEX) "$ " else "") + item.args.take(160),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded && item.result != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        item.result,
                        modifier = Modifier.padding(10.dp),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        ),
                        color = if (item.isError) DshError else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 20,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 工具状态文字徽章（对齐 Web 端状态 chip 观感） */
@Composable
private fun ToolStatusBadge(text: String, color: Color) {
    Surface(
        shape = if (DshThemeStyle == ThemeStyle.CODEX) RoundedCornerShape(2.dp) else RoundedCornerShape(5.dp),
        color = color.copy(alpha = 0.14f),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/** 夜之城警示纹：红底 45° 斜纹（错误横幅/提示条用，对齐 dsh-theme-cyberpunk2077） */
private fun Modifier.ncHatch(color: Color = Color.Red): Modifier = this.drawBehind {
    val step = 14f
    var x = -size.height
    while (x < size.width) {
        drawLine(
            color = color.copy(alpha = 0.28f),
            start = androidx.compose.ui.geometry.Offset(x, size.height),
            end = androidx.compose.ui.geometry.Offset(x + size.height, 0f),
            strokeWidth = 5f,
        )
        x += step
    }
}

@Composable
private fun NoticeRow(item: ChatItem.Notice, modifier: Modifier = Modifier) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier)
            .background(
                if (item.isError) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(10.dp),
            )
            .then(if (item.isError && DshThemeStyle == ThemeStyle.CYBERPUNK) Modifier.ncHatch() else Modifier)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (item.isError) Icons.Default.ErrorOutline else Icons.Default.Info,
            null,
            Modifier.size(16.dp),
            tint = if (item.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (item.isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ──────────────────────────── 模型选择对话框 ────────────────────────────

@Composable
private fun ModelPickerDialog(
    info: SessionModelsValue?,
    onDismiss: () -> Unit,
    onApply: (String, String, String?) -> Unit,
) {
    var provider by remember(info) { mutableStateOf(info?.current?.provider ?: "") }
    var model by remember(info) { mutableStateOf(info?.current?.model ?: "") }
    var effort by remember(info) { mutableStateOf(info?.current?.reasoningEffort) }

    val groups = remember(info) {
        runCatching { info?.groups?.jsonArray }.getOrNull()?.mapNotNull { it.jsonObject } ?: emptyList()
    }
    val currentGroup = groups.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == provider }
    val models = remember(groups, provider) {
        runCatching { currentGroup?.get("models")?.jsonArray }.getOrNull()
            ?.mapNotNull { it.jsonObject } ?: emptyList()
    }
    val currentModel = models.firstOrNull { it["id"]?.jsonPrimitive?.contentOrNull == model }
    val efforts = remember(currentModel) {
        runCatching {
            currentModel?.get("reasoning")?.jsonObject?.get("efforts")?.jsonArray
        }.getOrNull()?.mapNotNull { el ->
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
            id to name
        } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("切换模型") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "提供商",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (groups.isEmpty()) {
                    Text("无法获取模型列表", style = MaterialTheme.typography.bodySmall)
                } else {
                    groups.forEach { g ->
                        val gid = g["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                        val gname = g["name"]?.jsonPrimitive?.contentOrNull ?: gid
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = provider == gid,
                                onClick = {
                                    provider = gid
                                    model = ""
                                    effort = null
                                },
                            )
                            Text(gname, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(
                    "模型",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                models.forEach { m ->
                    val mid = m["id"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                    val mname = m["name"]?.jsonPrimitive?.contentOrNull ?: mid
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == mid, onClick = { model = mid })
                        Text(mname, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (efforts.isNotEmpty()) {
                    Text(
                        "思考程度",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    efforts.forEach { (id, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = effort == id, onClick = { effort = id })
                            Text(name, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(provider, model, effort) },
                enabled = provider.isNotBlank() && model.isNotBlank(),
            ) { Text("切换") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ──────────────────────────── 审查严格度对话框 ────────────────────────────

@Composable
private fun AccessModeDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val options = listOf(
        Triple("read-only", "只读", "只能读取文件，不能修改"),
        Triple("workspace-write", "工作区可写", "可修改工作区内的文件"),
        Triple("danger-full-access", "完全访问", "可执行任意操作（含敏感操作，谨慎使用）"),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("审查严格度（访问模式）") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                options.forEach { (value, label, desc) ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = current == value, onClick = { onSelect(value) })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            desc,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ──────────────────────────── 技能选择对话框 ────────────────────────────

@Composable
private fun SkillsDialog(
    skills: List<DshConnection.SkillEntry>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择技能") },
        text = {
            if (loading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else if (skills.isEmpty()) {
                Text("没有可用技能", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    skills.forEach { s ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(s.name) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                s.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            if (s.description.isNotBlank()) {
                                Text(
                                    s.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ──────────────────────────── 斜杠命令面板（S7） ────────────────────────────

@Composable
private fun CommandsDialog(
    commands: List<SlashCommand>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPick: (SlashCommand) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("斜杠命令") },
        text = {
            if (loading) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            } else if (commands.isEmpty()) {
                Text("没有可用命令", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    commands.forEach { c ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(c) }
                                .padding(vertical = 6.dp),
                        ) {
                            Text(
                                c.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            c.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    desc,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // hint 作占位提示（斜体低强调；无 description 时兜底说明）
                            c.hint?.takeIf { it.isNotBlank() }?.let { hint ->
                                Text(
                                    hint,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontStyle = FontStyle.Italic,
                                    color = MaterialTheme.colorScheme.tertiary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ──────────────────────────── 语音输入（T2） ────────────────────────────

/** 语音识别结果回填输入栏前的最小清洗：null/空白/空串 → null（不回填）；有效文本 → trim 后返回 */
internal fun normalizeVoiceResult(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() }

/** 首屏骨架气泡：呼吸透明度动画；index 决定伪随机宽度/对齐，避免三行等宽的死板感 */
@Composable
private fun SkeletonMessageBubble(index: Int) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeletonAlpha",
    )
    val widths = listOf(0.72f, 0.55f, 0.82f)
    val alignEnd = index == 1
    Box(
        modifier = if (alignEnd) Modifier.fillMaxWidth() else Modifier,
        contentAlignment = if (alignEnd) Alignment.BottomEnd else Alignment.BottomStart,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth(widths[index % widths.size])
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                    RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), RoundedCornerShape(4.dp))
            )
            Box(
                Modifier
                    .fillMaxWidth(0.68f)
                    .height(12.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = alpha), RoundedCornerShape(4.dp))
            )
        }
    }
}
