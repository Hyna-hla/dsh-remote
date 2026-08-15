package com.dsh.mobile.ui.screens

import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.components.MarkdownText
import com.dsh.mobile.ui.theme.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*

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

// ──────────────────────────── 聊天条目模型与状态机 ────────────────────────────

sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem
    data class Assistant(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val thinkSeconds: Long? = null,
    ) : ChatItem
    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val args: String,
        val status: String = "running",
        val result: String? = null,
        val isError: Boolean = false,
    ) : ChatItem

    data class Notice(override val key: String, val text: String, val isError: Boolean = false) : ChatItem
}

private fun textBlocks(content: JsonElement?): String {
    val arr = content as? JsonArray ?: return ""
    return arr.mapNotNull { el ->
        val o = el.jsonObject
        when {
            o["text"] is JsonPrimitive && (o["text"] as JsonPrimitive).isString ->
                (o["text"] as JsonPrimitive).content
            o["type"]?.jsonPrimitive?.contentOrNull == "text" && o["value"] is JsonPrimitive ->
                (o["value"] as JsonPrimitive).content
            else -> null
        }
    }.joinToString("\n")
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
            // 只接受正文增量；reasoning-delta（思考过程）一律丢弃，不上屏
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

class SessionChatState(
    private val scope: CoroutineScope,
    private val connection: DshConnection,
    val sessionId: String,
) {
    private val _items = MutableStateFlow<List<ChatItem>>(emptyList())
    val items: StateFlow<List<ChatItem>> = _items.asStateFlow()
    val title = MutableStateFlow<String?>(null)
    val running = MutableStateFlow(false)
    val currentMode = MutableStateFlow<String?>(null)
    val imageLimits = MutableStateFlow<JsonObject?>(null)
    var hasMore = false
    private var oldestSeq: Long? = null
    private var streamStart: Long? = null
    private var counter = 0L

    // 流式增量缓冲：chunk 先攒在这里，50ms 批量 flush 一次列表
    private val pendingChunk = StringBuilder()
    private var flushJob: Job? = null

    // load() 完成前到达的实时事件先入队，历史页落地后重放，
    // 防止网络加载期间 _items 被整页覆盖丢掉实时 chunk
    private var loaded = false
    private val preloadBuffer = mutableListOf<SessionEventWire>()

    private fun nextKey() = "k${counter++}"

    private fun flushPendingChunk() {
        flushJob?.cancel()
        flushJob = null
        if (pendingChunk.isEmpty()) return
        val delta = pendingChunk.toString()
        pendingChunk.clear()
        val cur = _items.value.toMutableList()
        val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
        if (idx >= 0) {
            val a = cur[idx] as ChatItem.Assistant
            cur[idx] = ChatItem.Assistant(a.key, a.text + delta, streaming = true)
        } else {
            cur.add(ChatItem.Assistant(nextKey(), delta, streaming = true))
        }
        _items.value = cur
    }

    private fun parseMeta(projections: JsonElement?) {
        try {
            val values = projections?.jsonObject?.get("values")?.jsonObject ?: return
            values["imageLimits"]?.jsonObject?.let { imageLimits.value = it }
        } catch (_: Exception) {}
    }

    suspend fun load() {
        try {
            // 首屏只取最近 3 条消息：服务端把 assistant/chunk 全量回传（约占 payload 98%），
            // maxMessages=10 时载荷可达 1MB+，手机端传输+解码+拼接就是"加载慢"的来源。
            val h = connection.history(sessionId, maxMessages = 3)
            oldestSeq = h.events.firstOrNull()?.event?.seq
            hasMore = h.hasMore
            parseMeta(h.projections)
            val list = mutableListOf<ChatItem>()
            // 历史 chunk 只保留"进行中回合"的尾部：其余 chunk 被 assistant/message 完整文本覆盖。
            // 用 StringBuilder 线性合并，避免逐条 a.text + d 的 O(n²) 拷贝。
            val chunkBuf = StringBuilder()
            var chunkStreamingIdx = -1
            fun flushChunks() {
                if (chunkBuf.isEmpty()) return
                if (chunkStreamingIdx >= 0 && chunkStreamingIdx < list.size) {
                    val a = list[chunkStreamingIdx] as ChatItem.Assistant
                    list[chunkStreamingIdx] = ChatItem.Assistant(a.key, a.text + chunkBuf.toString(), streaming = true)
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
                        val t = textBlocks(e.data.jsonObject["content"])
                        if (t.isNotBlank()) list.add(ChatItem.User(nextKey(), t))
                    }
                    DshEventTypes.ASSISTANT_MESSAGE -> {
                        // 完整消息到达：丢弃已收集的 chunk（其内容已包含在消息全文里）
                        chunkBuf.clear()
                        chunkStreamingIdx = -1
                        val t = assistantTextOf(e.data)
                        val idx = list.indexOfLast { it is ChatItem.Assistant && it.streaming }
                        if (idx >= 0) {
                            list[idx] = ChatItem.Assistant(list[idx].key, t)
                        } else if (t.isNotBlank()) {
                            list.add(ChatItem.Assistant(nextKey(), t))
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
                            )
                        )
                    }
                    DshEventTypes.TOOL_RESULT -> attachResult(list, e.data)
                    "permission/preset" -> {
                        e.data.jsonObject["preset"]?.jsonPrimitive?.contentOrNull?.let { currentMode.value = it }
                    }
                    DshEventTypes.SESSION_TITLE -> {
                        e.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { title.value = it }
                    }
                    DshEventTypes.AGENT_ERROR -> {
                        val msg = e.data.jsonObject["error"]?.toString() ?: "智能体出错"
                        list.add(ChatItem.Notice(nextKey(), msg, true))
                    }
                    else -> {}
                }
            }
            // 循环尾：flush 进行中回合残留的 chunk 尾部
            flushChunks()
            _items.value = list
        } catch (e: Exception) {
            _items.value = listOf(ChatItem.Notice(nextKey(), "加载历史失败：${e.message}", true))
        } finally {
            // 历史页落地后重放加载期间积压的实时事件，保证内容不丢
            loaded = true
            if (preloadBuffer.isNotEmpty()) {
                val pending = preloadBuffer.toList()
                preloadBuffer.clear()
                pending.forEach { processLiveEvent(it) }
            }
        }
    }

    /** 加载更早的消息（按 seq 前插） */
    suspend fun loadMore() {
        val before = oldestSeq ?: return
        try {
            val h = connection.history(sessionId, beforeSeq = before, maxMessages = 5)
            oldestSeq = h.events.firstOrNull()?.event?.seq
            hasMore = h.hasMore && h.events.isNotEmpty()
            val list = mutableListOf<ChatItem>()
            h.events.forEach { entry ->
                val e = entry.event
                when (e.type) {
                    DshEventTypes.USER_MESSAGE -> {
                        val t = textBlocks(e.data.jsonObject["content"])
                        if (t.isNotBlank()) list.add(ChatItem.User(nextKey(), t))
                    }
                    DshEventTypes.ASSISTANT_MESSAGE -> {
                        val t = assistantTextOf(e.data)
                        if (t.isNotBlank()) list.add(ChatItem.Assistant(nextKey(), t))
                    }
                    DshEventTypes.TOOL_CALL -> {
                        val d = e.data.jsonObject
                        list.add(
                            ChatItem.Tool(
                                key = nextKey(),
                                callId = d["callId"]?.jsonPrimitive?.contentOrNull ?: "",
                                name = d["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                                args = d["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                            )
                        )
                    }
                    DshEventTypes.TOOL_RESULT -> attachResult(list, e.data)
                    "permission/preset" -> {
                        e.data.jsonObject["preset"]?.jsonPrimitive?.contentOrNull?.let { currentMode.value = it }
                    }
                    DshEventTypes.SESSION_TITLE -> {
                        e.data.jsonObject["title"]?.jsonPrimitive?.contentOrNull?.let { title.value = it }
                    }
                    else -> {}
                }
            }
            if (list.isNotEmpty()) _items.value = list + _items.value
        } catch (_: Exception) {}
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
            if (delta.isNotEmpty()) {
                pendingChunk.append(delta)
                if (flushJob == null) {
                    flushJob = scope.launch {
                        delay(CHUNK_FLUSH_MS)
                        flushPendingChunk()
                    }
                }
            }
            return
        }
        // 其余事件：先落盘攒下的 chunk（保持时序），再一次性更新列表
        flushPendingChunk()
        val cur = _items.value.toMutableList()
        when (e.type) {
            DshEventTypes.USER_MESSAGE -> {
                val t = textBlocks(e.data.jsonObject["content"])
                if (t.isNotBlank()) cur.add(ChatItem.User(nextKey(), t))
            }

            DshEventTypes.ASSISTANT_MESSAGE -> {
                val t = assistantTextOf(e.data)
                val secs = streamStart?.let { (e.time - it) / 1000 }
                val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
                if (idx >= 0) {
                    cur[idx] = ChatItem.Assistant(cur[idx].key, t, thinkSeconds = secs)
                } else if (t.isNotBlank()) {
                    cur.add(ChatItem.Assistant(nextKey(), t, thinkSeconds = secs))
                }
                streamStart = null
            }

            DshEventTypes.TOOL_CALL -> {
                val d = e.data.jsonObject
                cur.add(
                    ChatItem.Tool(
                        key = nextKey(),
                        callId = d["callId"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = d["name"]?.jsonPrimitive?.contentOrNull ?: "tool",
                        args = d["arguments"]?.jsonPrimitive?.contentOrNull ?: "",
                    )
                )
            }

            DshEventTypes.TOOL_RESULT -> attachResult(cur, e.data)
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
                    )
                )
            }

            DshEventTypes.TURN_END -> {
                streamStart = null
                // 一轮结束：结束任何残留的流式占位
                val idx = cur.indexOfLast { it is ChatItem.Assistant && it.streaming }
                if (idx >= 0) {
                    val a = cur[idx] as ChatItem.Assistant
                    cur[idx] = ChatItem.Assistant(a.key, a.text, streaming = false)
                }
            }

            else -> {}
        }
        _items.value = cur
    }

    private fun attachResult(list: MutableList<ChatItem>, data: JsonElement) {
        val r = toolResultOf(data) ?: return
        val callId = (data.jsonObject["message"]?.jsonObject?.get("source") as? JsonObject)
            ?.get("callId")?.jsonPrimitive?.contentOrNull ?: ""
        val idx = list.indexOfLast {
            it is ChatItem.Tool && (it.callId == callId || (callId.isEmpty() && it.status == "running"))
        }
        if (idx >= 0) {
            val t = list[idx] as ChatItem.Tool
            list[idx] = t.copy(
                status = if (r.second) "error" else "done",
                result = r.first.take(4000),
                isError = r.second,
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
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = remember(sessionId) { SessionChatState(scope, connection, sessionId) }
    val items by state.items.collectAsState()
    val title by state.title.collectAsState()
    val running by state.running.collectAsState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var steerMode by remember { mutableStateOf(false) }
    var pendingImages by remember { mutableStateOf<List<DshConnection.ImagePart>>(emptyList()) }
    var modelDialog by remember { mutableStateOf(false) }
    var modeDialog by remember { mutableStateOf(false) }
    var modelInfo by remember { mutableStateOf<SessionModelsValue?>(null) }
    var skillsDialog by remember { mutableStateOf(false) }
    var skills by remember { mutableStateOf<List<DshConnection.SkillEntry>>(emptyList()) }
    var skillsLoading by remember { mutableStateOf(false) }
    var approval by remember { mutableStateOf<DshConnection.Event.ApprovalRequested?>(null) }
    var questions by remember { mutableStateOf<List<QuestionItem>?>(null) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var workspaceLabel by remember { mutableStateOf<String?>(null) }
    var autoModelEnabled by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    val context = LocalContext.current
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

    LaunchedEffect(sessionId) {
        state.load()
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

                is DshConnection.Event.ApprovalRequested ->
                    if (ev.sessionId == sessionId) approval = ev

                is DshConnection.Event.ApprovalResolved ->
                    if (ev.sessionId == sessionId && ev.approvalId == approval?.approvalId) approval = null

                is DshConnection.Event.QuestionRequested ->
                    if (ev.sessionId == sessionId) questions = ev.questions

                is DshConnection.Event.QuestionResolved ->
                    if (ev.sessionId == sessionId) questions = null

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

                else -> {}
            }
        }
    }

    // 自动滚动到底部：仅在用户本来就停在底部时跟随（不抢用户上翻阅读），
    // 用无动画 scrollToItem；流式时按最后一条文本长度跟随增量
    val tailSig = items.lastOrNull()?.let { if (it is ChatItem.Assistant) it.text.length else 0 } ?: 0
    LaunchedEffect(items.size, tailSig, approval, questions) {
        if (items.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        val atBottom = info.totalItemsCount <= 0 || lastVisible >= info.totalItemsCount - 1
        if (atBottom) listState.scrollToItem(items.size - 1)
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
        sending = true
        actionError = null
        scope.launch {
            try {
                // 自适应模型：按任务难度先切 Flash/Pro 再发送
                if (autoModelEnabled && text.isNotBlank()) {
                    runCatching { applyAutoModel(text) }
                }
                connection.prompt(
                    sessionId,
                    text,
                    if (steerMode) "steer" else "queue",
                    images = pendingImages,
                )
                input = ""
                pendingImages = emptyList()
            } catch (e: Exception) {
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

    fun answerApproval(outcome: String) {
        val a = approval ?: return
        scope.launch {
            try {
                connection.answerApproval(a.sessionId, a.approvalId, outcome)
                approval = null
            } catch (e: Exception) {
                actionError = e.message
            }
        }
    }

    fun answerQuestions(answers: List<DshConnection.QuestionAnswer>) {
        scope.launch {
            try {
                connection.answerQuestions(sessionId, answers)
                questions = null
            } catch (e: Exception) {
                actionError = e.message
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
                if (running) {
                    IconButton(onClick = { stopRun() }) {
                        Icon(Icons.Default.Stop, null, tint = DshError)
                    }
                }
            },
        )

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
                    TextButton(
                        onClick = { scope.launch { state.loadMore() } },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("加载更早的消息") }
                }
            }
            items(items, key = { it.key }) { item ->
                // 新条目渐入：新消息平滑出现，不跳变（Telegram 式轻量动效）
                androidx.compose.runtime.key(item.key) {
                    when (item) {
                        is ChatItem.User -> UserBubble(item.text, Modifier.animateItem())
                        is ChatItem.Assistant -> AssistantCard(item, Modifier.animateItem())
                        is ChatItem.Tool -> ToolCard(item, Modifier.animateItem())
                        is ChatItem.Notice -> NoticeRow(item, Modifier.animateItem())
                    }
                }
            }
        }

        actionError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        // 审批横幅
        approval?.let { a ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, DshWarn.copy(alpha = 0.5f)),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, null, Modifier.size(18.dp), tint = DshWarn)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "等待你的审批",
                            style = MaterialTheme.typography.titleSmall,
                            color = DshWarn,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        a.reason ?: "工具 ${a.toolName} 请求执行权限",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = { answerApproval("rejected") },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DshError),
                        ) {
                            Text("拒绝")
                        }
                        Button(
                            onClick = { answerApproval("allowed-once") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("允许一次")
                        }
                    }
                }
            }
        }

        // 问答题卡片
        questions?.let { qs ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "智能体需要你确认",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    QuestionCard(
                        questions = qs,
                        onSubmit = { answers -> answerQuestions(answers) },
                        onCancel = {
                            scope.launch {
                                try {
                                    connection.answerQuestions(sessionId, emptyList())
                                } catch (_: Exception) {}
                                questions = null
                            }
                        },
                    )
                }
            }
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

        // 输入栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .imePadding(),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconButton(onClick = { imagePicker.launch("image/*") }) {
                    Icon(Icons.Default.AttachFile, null)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("给智能体发消息…") },
                    maxLines = 4,
                    shape = RoundedCornerShape(22.dp),
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = steerMode,
                    onClick = { steerMode = !steerMode },
                    label = { Text("⚡ 插话") },
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                if (running) {
                    FilledIconButton(
                        onClick = { stopRun() },
                        modifier = Modifier.size(46.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Icon(Icons.Default.Stop, null, tint = MaterialTheme.colorScheme.onError)
                    }
                } else {
                    FilledIconButton(
                        onClick = { send() },
                        modifier = Modifier.size(46.dp),
                        enabled = (input.isNotBlank() || pendingImages.isNotEmpty()) && !sending,
                    ) {
                        if (sending) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
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
    }
}

// ──────────────────────────── 条目渲染 ────────────────────────────

@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(modifier),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            shape = DshShape.userBubble,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
private fun AssistantCard(item: ChatItem.Assistant, modifier: Modifier = Modifier) {
    Surface(
        shape = DshShape.assistantBubble,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            item.thinkSeconds?.let { secs ->
                Text(
                    "⚡ 已思考 $secs 秒",
                    style = MaterialTheme.typography.labelSmall,
                    color = DshWarn,
                )
                Spacer(Modifier.height(4.dp))
            }
            if (item.streaming) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "正在思考…",
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
                } else {
                    MarkdownText(item.text)
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
                    failed -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(16.dp), tint = DshError)
                    done -> Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp), tint = DshSuccess)
                    else -> CircularProgressIndicator(
                        Modifier.size(14.dp),
                        strokeWidth = 1.5.dp,
                        color = DshBrand,
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
                    item.args.take(160),
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

// ──────────────────────────── 问答题卡片 ────────────────────────────

@Composable
private fun QuestionCard(
    questions: List<QuestionItem>,
    onSubmit: (List<DshConnection.QuestionAnswer>) -> Unit,
    onCancel: () -> Unit,
) {
    var selections by remember(questions) {
        mutableStateOf(
            questions.associate { q -> q.id to mutableListOf<String>() }.toMutableMap()
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        questions.forEach { q ->
            Column {
                Text(
                    q.header ?: q.question,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (q.options.isEmpty()) {
                    var custom by remember(q.id) { mutableStateOf("") }
                    OutlinedTextField(
                        value = custom,
                        onValueChange = {
                            custom = it
                            selections[q.id] = mutableListOf(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入你的回答") },
                        maxLines = 3,
                    )
                } else {
                    q.options.forEach { opt ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                        ) {
                            val checked = selections[q.id]?.contains(opt.label) == true
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { on ->
                                    val cur = selections[q.id] ?: mutableListOf()
                                    if (q.multiSelect) {
                                        if (on) cur.add(opt.label) else cur.remove(opt.label)
                                    } else {
                                        cur.clear()
                                        if (on) cur.add(opt.label)
                                    }
                                    selections = selections.toMutableMap().apply { this[q.id] = cur }
                                },
                            )
                            Text(
                                opt.label,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text("取消")
            }
            Button(
                onClick = {
                    val answers = questions.map { q ->
                        DshConnection.QuestionAnswer(
                            id = q.id,
                            selected = selections[q.id] ?: emptyList(),
                        )
                    }
                    onSubmit(answers)
                },
                modifier = Modifier.weight(1f),
                enabled = questions.all { q -> (selections[q.id] ?: emptyList()).isNotEmpty() },
            ) {
                Text("提交")
            }
        }
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
