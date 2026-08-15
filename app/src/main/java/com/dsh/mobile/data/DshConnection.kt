package com.dsh.mobile.data

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

class ApiException(message: String, val code: String? = null) : Exception(message)

/**
 * DSH RPC 连接（真实协议，对应 dsh-client-connection）：
 * - 上行: POST /api/<method>  body = client-request {type, rpcId, method, payload}
 * - 下行: 响应 = server-response {type, rpcId, result: {ok, value|error}}
 * - 事件流: GET /api/events.mux (Accept: text/event-stream)，SSE data = server-request，
 *           payload = MuxFrame（session/event、approval/requested、question/requested…）
 * - 应答: POST /api/respond  body = client-response {type, rpcId, result}
 */
class DshConnection {

    sealed class State {
        data object Disconnected : State()
        data class Connecting(val baseUrl: String) : State()
        data class Connected(val baseUrl: String) : State()
        data class Error(val message: String) : State()
    }

    /** 领域事件（解析后的 mux/host 帧） */
    sealed class Event {
        data class SessionEvent(val sessionId: String, val event: SessionEventWire) : Event()
        data class ApprovalRequested(
            val sessionId: String,
            val approvalId: String,
            val toolName: String,
            val callId: String?,
            val reason: String?,
        ) : Event()
        data class ApprovalResolved(val sessionId: String, val approvalId: String, val outcome: String) : Event()
        data class QuestionRequested(val sessionId: String, val questions: List<QuestionItem>) : Event()
        data class QuestionResolved(val sessionId: String, val questionRpcId: String, val outcome: String) : Event()
        data class Jobs(val sessionId: String, val jobs: List<JobView>) : Event()
        data class SessionAdded(val sessionId: String) : Event()
        data class SessionRemoved(val sessionId: String) : Event()
        data class SessionStatus(val sessionId: String, val status: String) : Event()
        data class Projection(val sessionId: String, val key: String, val value: JsonElement) : Event()
        data class StreamError(val message: String) : Event()
        data class Reconnected(val baseUrl: String) : Event()
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        // 关键：信封的 type 字段带默认值（"client-request"等），必须强制编码，
        // 否则发出的 JSON 缺 type 会被服务端拒收（invalid client-request message）
        encodeDefaults = true
    }

    private val unaryClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // SSE 流用独立 client：readTimeout=0（无超时），靠服务端 keepalive/断线触发重连
    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var baseUrl = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** approvalId → server-request rpcId（应答时回显） */
    private val pendingApprovalRpc = mutableMapOf<String, String>()
    /** sessionId → question server-request rpcId */
    private val pendingQuestionRpc = mutableMapOf<String, String>()

    fun baseUrl(): String = baseUrl

    // ────────────────────────── 连接管理 ──────────────────────────

    @Synchronized
    fun connect(url: String) {
        val normalized = normalizeBaseUrl(url)
        if (_state.value is State.Connected && baseUrl == normalized) return
        disconnectInternal()
        baseUrl = normalized
        _state.value = State.Connecting(normalized)
        scope.launch {
            // 先探测连通性（host.describe）
            val ok = try {
                call(DshEndpoints.HOST_DESCRIBE)
                true
            } catch (e: Exception) {
                _state.value = State.Error(e.message ?: "连接失败")
                false
            }
            if (ok) {
                _state.value = State.Connected(normalized)
                streamLoop("mux", "/api/events.mux")
                streamLoop("host", "/api/events.host")
            }
        }
    }

    private fun normalizeBaseUrl(raw: String): String {
        var u = raw.trim()
        if (u.isEmpty()) return u
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        u = u.trimEnd('/')
        return u
    }

    @Synchronized
    fun disconnect() = disconnectInternal()

    private fun disconnectInternal() {
        scope.coroutineContext.cancelChildren()
        _state.value = State.Disconnected
        pendingApprovalRpc.clear()
        pendingQuestionRpc.clear()
    }

    /** 事件流循环（WebSocket 优先，SSE 兜底，自动重连） */
    private fun streamLoop(name: String, path: String) {
        scope.launch {
            while (isActive) {
                var lastError = "流结束"
                try {
                    openWsStream(path)
                } catch (e: Exception) {
                    lastError = e.message ?: "ws 失败"
                    // 回退：部分 dsh web 服务器用 SSE 承载该流
                    try {
                        readSse(path)
                    } catch (e2: Exception) {
                        lastError = "ws/see 均失败: ${e2.message}"
                    }
                }
                if (!isActive) break
                if (_state.value is State.Connected) {
                    _events.tryEmit(Event.StreamError("$name 流中断（$lastError），3 秒后重连"))
                    delay(3000)
                } else break
            }
        }
    }

    /** WebSocket 方式读取事件流：每条文本消息 = server-request 信封 JSON。
     *  协程保持挂起直到 socket 关闭/失败，关闭后由外层循环触发重连。 */
    private suspend fun openWsStream(path: String) = suspendCancellableCoroutine { cont ->
        val wsUrl = if (baseUrl.startsWith("https://")) "wss://" + baseUrl.removePrefix("https://") + path
        else "ws://" + baseUrl.removePrefix("http://") + path
        val request = Request.Builder().url(wsUrl).build()
        val ws = streamClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // 保持挂起：等 socket 关闭或失败才返回，交给外层重连
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleStreamData(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (cont.isActive) cont.resume(Unit) {}
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (cont.isActive) {
                    cont.resumeWith(Result.failure(t))
                }
            }
        })
        cont.invokeOnCancellation { ws.close(1000, "cancelled") }
    }

    /** SSE 方式读取事件流（dsh web CLI 服务器兼容路径） */
    private suspend fun readSse(path: String) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")
            .build()
        val response = streamClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw ApiException("HTTP ${response.code}")
        }
        val source = response.body?.source() ?: run {
            response.close()
            throw ApiException("空响应体")
        }
        try {
            val dataLines = mutableListOf<String>()
            while (currentCoroutineContext().isActive) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty()) {
                    if (dataLines.isNotEmpty()) {
                        val data = dataLines.joinToString("\n")
                        dataLines.clear()
                        handleStreamData(data)
                    }
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.removePrefix("data:").trim())
                }
            }
        } finally {
            response.close()
        }
        if (_state.value is State.Connected) throw ApiException("SSE 流结束")
    }

    private fun handleStreamData(data: String) {
        if (data.isBlank()) return
        val parsed = try {
            json.decodeFromString(ServerRequest.serializer(), data)
        } catch (e: Exception) {
            return
        }
        val frame = try {
            json.decodeFromJsonElement(MuxFrame.serializer(), parsed.payload)
        } catch (e: Exception) {
            return
        }
        dispatchFrame(parsed.rpcId, frame)
    }

    private fun dispatchFrame(rpcId: String, f: MuxFrame) {
        when (f.type) {
            DshEventTypes.FRAME_SESSION_EVENT -> {
                val ev = f.event ?: return
                f.sessionId?.let { _events.tryEmit(Event.SessionEvent(it, ev)) }
            }
            DshEventTypes.FRAME_SUBSCRIBED -> {
                f.sessionId?.let { _events.tryEmit(Event.Reconnected(baseUrl)) }
            }
            DshEventTypes.FRAME_APPROVAL_REQUESTED -> {
                val sid = f.sessionId ?: return
                val aid = f.approvalId ?: return
                pendingApprovalRpc[aid] = rpcId
                _events.tryEmit(Event.ApprovalRequested(sid, aid, f.toolName ?: "?", f.callId, f.reason))
            }
            DshEventTypes.FRAME_APPROVAL_RESOLVED -> {
                val aid = f.approvalId ?: return
                pendingApprovalRpc.remove(aid)
                f.sessionId?.let { _events.tryEmit(Event.ApprovalResolved(it, aid, f.outcome ?: "resolved")) }
            }
            DshEventTypes.FRAME_QUESTION_REQUESTED -> {
                val sid = f.sessionId ?: return
                if (f.questions.isEmpty()) return
                pendingQuestionRpc[sid] = rpcId
                _events.tryEmit(Event.QuestionRequested(sid, f.questions))
            }
            DshEventTypes.FRAME_QUESTION_RESOLVED -> {
                val sid = f.sessionId ?: return
                pendingQuestionRpc.remove(sid)
                _events.tryEmit(Event.QuestionResolved(sid, f.questionRpcId ?: "", f.outcome ?: "answered"))
            }
            DshEventTypes.FRAME_JOBS -> {
                f.sessionId?.let { _events.tryEmit(Event.Jobs(it, f.jobs)) }
            }
            DshEventTypes.FRAME_PROJECTION -> {
                val sid = f.sessionId ?: return
                val key = f.key ?: return
                _events.tryEmit(Event.Projection(sid, key, f.value ?: JsonNull))
            }
            DshEventTypes.FRAME_STREAM_ERROR -> {
                _events.tryEmit(Event.StreamError(f.error?.message ?: "stream error"))
            }
            DshEventTypes.HOST_SESSION_ADDED -> {
                f.sessionId?.let { _events.tryEmit(Event.SessionAdded(it)) }
            }
            DshEventTypes.HOST_SESSION_REMOVED -> {
                f.sessionId?.let { _events.tryEmit(Event.SessionRemoved(it)) }
            }
            DshEventTypes.HOST_SESSION_STATUS -> {
                f.sessionId?.let { _events.tryEmit(Event.SessionStatus(it, f.status ?: "unknown")) }
            }
            else -> { /* 未知帧忽略 */ }
        }
    }

    // ────────────────────────── RPC 基础 ──────────────────────────

    suspend fun call(method: String, payload: JsonElement = buildJsonObject { }): JsonElement {
        val rpcId = "m-" + UUID.randomUUID().toString()
        val envelope = ClientRequest(rpcId = rpcId, method = method, payload = payload)
        val body = json.encodeToString(ClientRequest.serializer(), envelope)
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$baseUrl/api/$method")
            .post(body)
            .build()

        // 响应体读取（阻塞）与 JSON 解析（大 payload 如 session.history 可达数 MB）
        // 整体放在 IO 线程完成：调用方常在主线程（LaunchedEffect），否则打开会话卡数秒
        return withContext(Dispatchers.IO) {
            val response = unaryClient.newCall(request).execute()
            response.use { resp ->
                val text = resp.body?.string() ?: throw ApiException("空响应")
                if (!resp.isSuccessful) {
                    throw ApiException("HTTP ${resp.code}", resp.code.toString())
                }
                val parsed = try {
                    json.decodeFromString(ServerResponse.serializer(), text)
                } catch (e: Exception) {
                    throw ApiException("响应解析失败: ${e.message}")
                }
                if (!parsed.result.ok) {
                    val err = parsed.result.error
                    throw ApiException(err?.message ?: "RPC 失败", err?.code)
                }
                parsed.result.value ?: JsonNull
            }
        }
    }

    /** POST /api/respond —— 应答 server-request（审批/问答） */
    suspend fun respond(rpcId: String, resultValue: JsonObject) {
        val envelope = ClientResponse(
            rpcId = rpcId,
            result = RpcResult(ok = true, value = resultValue),
        )
        val body = json.encodeToString(ClientResponse.serializer(), envelope)
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/api/respond")
            .post(body)
            .build()
        val response = withContext(Dispatchers.IO) { unaryClient.newCall(request).execute() }
        response.use { resp ->
            if (!resp.isSuccessful) {
                val text = resp.body?.string().orEmpty()
                throw ApiException("respond HTTP ${resp.code}: $text")
            }
        }
    }

    suspend fun answerApproval(sessionId: String, approvalId: String, outcome: String) {
        val rpcId = pendingApprovalRpc.remove(approvalId)
            ?: throw ApiException("approvalId 无对应待应答请求（可能已过期）")
        respond(rpcId, buildJsonObject {
            put("sessionId", sessionId)
            put("approvalId", approvalId)
            put("outcome", outcome)
        })
    }

    suspend fun answerQuestions(sessionId: String, answers: List<QuestionAnswer>) {
        val rpcId = pendingQuestionRpc.remove(sessionId)
            ?: throw ApiException("问答无对应待应答请求（可能已过期）")
        respond(rpcId, buildJsonObject {
            put("sessionId", sessionId)
            put("answer", buildJsonObject {
                put("answers", buildJsonArray {
                    answers.forEach { a ->
                        add(buildJsonObject {
                            put("id", a.id)
                            put("selected", buildJsonArray { a.selected.forEach { add(it) } })
                            a.custom?.let { put("custom", it) }
                        })
                    }
                })
            })
        })
    }

    data class QuestionAnswer(val id: String, val selected: List<String>, val custom: String? = null)

    // ────────────────────────── 业务便捷方法 ──────────────────────────

    suspend fun listSessions(): List<SessionSummary> {
        val value = call(DshEndpoints.SESSION_LIST)
        return try {
            json.decodeFromJsonElement(SessionListValue.serializer(), value).items
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun createSession(agentPreset: String? = null, workspaceId: String? = null): String {
        val value = call(DshEndpoints.SESSION_CREATE, buildJsonObject {
            agentPreset?.let { put("agentPreset", it) }
            workspaceId?.let { put("workspaceId", it) }
        })
        return json.decodeFromJsonElement(SessionCreateValue.serializer(), value).sessionId
    }

    /** 图片附件（内嵌 base64 的 prompt 内容块） */
    data class ImagePart(val mediaType: String, val base64Data: String, val name: String? = null)

    suspend fun prompt(
        sessionId: String,
        text: String,
        mode: String = "queue",
        images: List<ImagePart> = emptyList(),
    ) {
        call(DshEndpoints.SESSION_PROMPT, buildJsonObject {
            put("sessionId", sessionId)
            put("mode", mode)
            put("content", buildJsonArray {
                if (text.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    })
                }
                images.forEach { img ->
                    add(buildJsonObject {
                        put("type", "image")
                        put("mediaType", img.mediaType)
                        put("data", img.base64Data)
                        img.name?.let { put("name", it) }
                    })
                }
            })
        })
    }

    /** 执行会话命令（如 /permission read-only 切换审查严格度） */
    suspend fun executeCommand(sessionId: String, line: String): JsonElement {
        return call(DshEndpoints.COMMANDS_EXECUTE, buildJsonObject {
            put("agentId", sessionId)
            put("line", line)
        })
    }

    suspend fun history(sessionId: String, beforeSeq: Long? = null, maxMessages: Int? = null): HistoryValue {
        val value = call(DshEndpoints.SESSION_HISTORY, buildJsonObject {
            put("sessionId", sessionId)
            beforeSeq?.let { put("beforeSeq", it) }
            maxMessages?.let { put("maxMessages", it) }
        })
        // 解码可能达数万条事件（assistant/chunk 占绝大多数），必须在后台线程做，
        // 否则主线程卡死 → ANR/闪退
        return withContext(Dispatchers.Default) {
            try {
                json.decodeFromJsonElement(HistoryValue.serializer(), value)
            } catch (e: Exception) {
                HistoryValue()
            }
        }
    }

    suspend fun cancel(sessionId: String) {
        call(DshEndpoints.SESSION_CANCEL, buildJsonObject { put("sessionId", sessionId) })
    }

    suspend fun rename(sessionId: String, title: String) {
        call(DshEndpoints.SESSION_RENAME, buildJsonObject {
            put("sessionId", sessionId)
            put("title", title)
        })
    }

    suspend fun sessionModels(sessionId: String): SessionModelsValue? {
        return try {
            val value = call(DshEndpoints.SESSION_MODELS, buildJsonObject { put("sessionId", sessionId) })
            json.decodeFromJsonElement(SessionModelsValue.serializer(), value)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun selectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null) {
        call(DshEndpoints.SESSION_SELECT_MODEL, buildJsonObject {
            put("sessionId", sessionId)
            put("provider", provider)
            put("model", model)
            reasoningEffort?.let { put("reasoningEffort", it) }
        })
    }

    suspend fun workspaceList(): WorkspaceListValue {
        return try {
            val value = call(DshEndpoints.WORKSPACE_LIST)
            json.decodeFromJsonElement(WorkspaceListValue.serializer(), value)
        } catch (e: Exception) {
            WorkspaceListValue()
        }
    }

    /** 归档会话（从会话列表移除，进入已归档区） */
    suspend fun archiveSession(sessionId: String) {
        call(DshEndpoints.WORKSPACE_ARCHIVE_SESSION, buildJsonObject { put("sessionId", sessionId) })
    }

    /** 把已归档会话恢复到指定工作区 */
    suspend fun restoreSession(workspaceId: String, sessionId: String) {
        call(DshEndpoints.WORKSPACE_INSERT_SESSION_BEFORE, buildJsonObject {
            put("workspaceId", workspaceId)
            put("sessionId", sessionId)
        })
    }

    data class SkillEntry(val name: String, val description: String)

    /** skill.list：当前会话可用的技能列表（响应 {skills:[{name, description, whenToUse, modelInvocable}]}） */
    suspend fun skillsList(sessionId: String): List<SkillEntry> {
        return try {
            val value = call(DshEndpoints.SKILL_LIST, buildJsonObject { put("sessionId", sessionId) })
            val arr = value.jsonObject["skills"]?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val desc = o["description"]?.jsonPrimitive?.contentOrNull ?: ""
                SkillEntry(name, desc)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** agentPreset.list 响应：{presets:[{id, name, trust, isDefault, description}]} */
    suspend fun agentPresets(): List<Pair<String, String>> {
        return try {
            val value = call(DshEndpoints.AGENT_PRESET_LIST)
            val arr = value.jsonObject["presets"]?.jsonArray
                ?: value.jsonObject["items"]?.jsonArray
                ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: id
                id to name
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun destroy() {
        disconnectInternal()
        scope.cancel()
    }
}
