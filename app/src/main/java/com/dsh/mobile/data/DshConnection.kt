package com.dsh.mobile.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

class ApiException(message: String, val code: String? = null) : Exception(message)

/**
 * DSH RPC 连接（真实协议，对应 dsh-client-connection）：
 * - 上行: POST /api/<method>  body = client-request {type, rpcId, method, payload}
 * - 下行: 响应 = server-response {type, rpcId, result: {ok, value|error}}
 * - 事件流: GET /api/events.mux (Accept: text/event-stream)，SSE data = server-request，
 *           payload = MuxFrame（session/event、approval/requested、question/requested…）
 * - 应答: POST /api/respond  body = client-response {type, rpcId, result}
 */
class DshConnection(private val appContext: Context? = null) {

    sealed class State {
        data object Disconnected : State()
        data class Connecting(val baseUrl: String) : State()
        data class Connected(val baseUrl: String, val hostVersion: String? = null) : State()
        data class Error(val message: String, val code: ConnectionErrorCode?, val profileId: String?) : State()
    }

    data class AttemptInfo(
        val profileId: String,
        val errorCode: ConnectionErrorCode?,
        val hostVersion: String?,
    )

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

    private var profileId: String? = null
    private var connectedProfile: HostProfile? = null
    private var unaryClient: OkHttpClient = OkHttpClient()
    private var streamClient: OkHttpClient = OkHttpClient()
    private var onAttempt: ((AttemptInfo) -> Unit)? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectJob: Job? = null

    private val _state = MutableStateFlow<State>(State.Disconnected)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var baseUrl = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** approvalId → server-request rpcId（应答时回显） */
    private val pendingApprovalRpc = mutableMapOf<String, String>()
    /** sessionId → question server-request rpcId */
    private val pendingQuestionRpc = mutableMapOf<String, String>()

    fun baseUrl(): String = baseUrl

    /** 当前连接使用的 profile（connect 时同步设置、disconnect 清空）；配对等按已连接主机取用 */
    fun currentProfile(): HostProfile? = connectedProfile

    // ────────────────────────── 连接管理 ──────────────────────────

    @Synchronized
    fun connect(profile: HostProfile, onAttempt: ((AttemptInfo) -> Unit)? = null) {
        val normalized = normalizeBaseUrl(profile.url)
        if (_state.value is State.Connected && baseUrl == normalized) return
        disconnectInternal()
        profileId = profile.id
        connectedProfile = profile
        this.onAttempt = onAttempt
        baseUrl = normalized
        val (unary, stream) = OkHttpClientFactory.build(profile)
        unaryClient = unary
        streamClient = stream
        _state.value = State.Connecting(normalized)
        registerNetworkCallback()
        connectJob = scope.launch {
            var attempt = 0
            while (isActive) {
                val result = try {
                    val value = call(DshEndpoints.HOST_DESCRIBE)
                    val version = runCatching {
                        value.jsonObject["version"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()
                    ConnectionResult.Ok(version)
                } catch (e: Exception) {
                    val code = classifyConnectError(e)
                    ConnectionResult.Fail(e, code)
                }
                if (!isActive) break
                when (result) {
                    is ConnectionResult.Ok -> {
                        val verdict = VersionPolicy.evaluate(result.version)
                        if (verdict == VersionVerdict.MISMATCH) {
                            failPermanently(ConnectionErrorCode.VERSION_MISMATCH, "版本不兼容（远端 ${result.version}）")
                            break
                        }
                        onAttempt?.invoke(AttemptInfo(profile.id, null, result.version))
                        // UNKNOWN（占位/缺失/无法解析）时 hostVersion 置 null → UI 显示「版本未知」
                        val hostVersion = if (verdict == VersionVerdict.OK) result.version else null
                        _state.value = State.Connected(normalized, hostVersion)
                        streamLoop("mux", "/api/events.mux")
                        streamLoop("host", "/api/events.host")
                        break
                    }
                    is ConnectionResult.Fail -> {
                        if (!RetryPolicy.isRecoverable(result.code)) {
                            failPermanently(result.code, result.e.message ?: result.code.name)
                            break
                        }
                        val backoff = RetryPolicy.nextBackoff(result.code, attempt) ?: break
                        onAttempt?.invoke(AttemptInfo(profile.id, result.code, null))
                        val msg = "连接失败（${result.code.name}），${backoff / 1000} 秒后自动重连"
                        _events.tryEmit(Event.StreamError(msg))
                        _state.value = State.Error(msg, result.code, profile.id)
                        delay(backoff)
                        if (!isActive) break
                        _state.value = State.Connecting(normalized)
                        attempt++
                    }
                }
            }
        }
    }

    private sealed class ConnectionResult {
        data class Ok(val version: String?) : ConnectionResult()
        data class Fail(val e: Exception, val code: ConnectionErrorCode) : ConnectionResult()
    }

    private fun classifyConnectError(e: Exception): ConnectionErrorCode {
        val code = when (e) {
            is ApiException -> e.code?.toIntOrNull()
                ?.let { ErrorClassifier.fromHttpStatus(it) }
                ?: ConnectionErrorCode.PROTOCOL_ERROR
            else -> ErrorClassifier.fromException(e, connectPhase = true, hasProxy = currentProfileHasProxy())
        }
        return code
    }

    private fun currentProfileHasProxy(): Boolean = connectedProfile?.proxy != null

    private fun failPermanently(code: ConnectionErrorCode, detail: String) {
        onAttempt?.invoke(AttemptInfo(profileId ?: "", code, null))
        _events.tryEmit(Event.StreamError("$detail（已停止自动重连）"))
        _state.value = State.Error("$detail（已停止自动重连）", code, profileId)
    }

    @Synchronized
    fun disconnect() = disconnectInternal()

    private fun disconnectInternal() {
        unregisterNetworkCallback()
        scope.coroutineContext.cancelChildren()
        _state.value = State.Disconnected
        connectedProfile = null
        profileId?.let { OkHttpClientFactory.release(it) }
        pendingApprovalRpc.clear()
        pendingQuestionRpc.clear()
    }

    private fun registerNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 新网络可用：若处于重连等待中，立即重置退避重试
                triggerImmediateRetry()
            }
        }
        networkCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unregisterNetworkCallback() {
        val context = appContext ?: return
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    @Volatile private var retryNowPending = false
    private fun triggerImmediateRetry() {
        if (retryNowPending) return
        retryNowPending = true
        retryScope.launch {
            try {
                val p = connectedProfile ?: return@launch
                if (_state.value !is State.Error) return@launch
                connectJob?.cancel()          // 取消正在等待退避的旧循环
                connect(p, onAttempt)         // 立即重建（内部会新起连接循环）
            } finally {
                retryNowPending = false
            }
        }
    }

    /** 事件流循环（WebSocket 优先，SSE 兜底，指数退避自动重连）
     *  连接成功后立即重置退避：3s → 6s → 12s → 24s → 30s（封顶）。 */
    private fun streamLoop(name: String, path: String) {
        scope.launch {
            // 事件流重连退避（指数退避 3→6→12→24→30s 封顶）
            var backoffMs = 3_000L
            while (isActive) {
                var lastError = "流结束"
                try {
                    openWsStream(path)
                    backoffMs = 3_000L
                } catch (e: Exception) {
                    lastError = e.message ?: "ws 失败"
                    // 回退：部分 dsh web 服务器用 SSE 承载该流
                    try {
                        readSse(path)
                        backoffMs = 3_000L
                    } catch (e2: Exception) {
                        lastError = "ws/see 均失败: ${e2.message}"
                    }
                }
                if (!isActive) break
                if (_state.value is State.Connected) {
                    _events.tryEmit(Event.StreamError("$name 流中断（$lastError），${backoffMs / 1000} 秒后重连"))
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
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

    /**
     * commands/list：当前会话可用的斜杠命令 [{name, description, input:{hint}}]。
     * 响应非法/连接失败 → 空列表（面板显示「没有可用命令」）。
     * 端点名内联（brief 提交文件集不含 DshProtocol.kt，未新增 DshEndpoints 常量）。
     */
    suspend fun commandsList(sessionId: String): List<SlashCommand> {
        return try {
            val value = call("commands/list", buildJsonObject {
                put("agentId", sessionId)
            })
            parseCommandsList(value)
        } catch (e: Exception) {
            emptyList()
        }
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

    /**
     * S7 导出：拉 session.history 的原始 events[]（JsonElement，wire 原貌，不做类型解码），
     * 供 historyToMarkdown/historyToJson 使用。与 history() 复用同一端点；
     * 网络失败抛 ApiException（UI 负责失败提示），响应缺 events 字段 → 空列表。
     */
    suspend fun historyRawEvents(sessionId: String): List<JsonElement> {
        val value = call(DshEndpoints.SESSION_HISTORY, buildJsonObject {
            put("sessionId", sessionId)
        })
        // 大 payload 提取在后台线程完成，避免主线程卡顿
        return withContext(Dispatchers.Default) {
            value.jsonObject["events"]?.jsonArray?.toList() ?: emptyList()
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

    /** 新建工作区：path 必须是 PC 端已存在的目录绝对路径（如 E:\AI搓的小东西） */
    suspend fun createWorkspace(path: String, title: String? = null) {
        call(DshEndpoints.WORKSPACE_CREATE, buildJsonObject {
            put("path", path)
            title?.let { put("title", it) }
        })
    }

    /**
     * 浏览 PC 端任意目录（插件 dsh-remote-access 的 fs/list 端点，listDirectory 的回退路径）：
     * 返回子目录名列表；插件未安装/不可用时返回空列表（UI 回退到手动输入路径）。
     * 保留不破坏：host.listDirectory RPC 不可用时由 listDirectory 转调此方法。
     */
    suspend fun listWorkspaceDirs(path: String): List<String> {
        return try {
            val text = withContext(Dispatchers.IO) {
                val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/api/remote-access/fs/list?path=$encoded")
                    .get()
                    .build()
                unaryClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ""
                    resp.body?.string().orEmpty()
                }
            }
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return emptyList()
            obj["dirs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 插件 fs/list（dsh-remote-access，Task 4 增强）：列举目录 + 文件。
     * 响应 {ok, path, dirs[], files[{name,path,size,hidden}]}；插件未安装/失败 → null。
     */
    suspend fun fsList(path: String): FsListing? {
        return try {
            val text = withContext(Dispatchers.IO) {
                val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/api/remote-access/fs/list?path=$encoded")
                    .get()
                    .build()
                unaryClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ""
                    resp.body?.string().orEmpty()
                }
            }
            val obj = json.parseToJsonElement(text).jsonObject
            if (obj["ok"]?.jsonPrimitive?.booleanOrNull != true) return null
            val dirs = obj["dirs"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
            val files = obj["files"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val path = o["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L
                val hidden = o["hidden"]?.jsonPrimitive?.booleanOrNull ?: false
                FileEntry(name, path, size, hidden)
            } ?: emptyList()
            FsListing(dirs, files)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 插件 fs/read（dsh-remote-access，Task 4）：文件内容只读预览（1MB 截断、二进制识别）。
     * 响应 {ok, path, size, truncated, isBinary, text|data}；失败/插件不可用 → null。
     */
    suspend fun fsRead(path: String): FilePreview? {
        return try {
            val text = withContext(Dispatchers.IO) {
                val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                val request = Request.Builder()
                    .url("$baseUrl/api/remote-access/fs/read?path=$encoded")
                    .get()
                    .build()
                unaryClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ""
                    resp.body?.string().orEmpty()
                }
            }
            if (text.isBlank()) return null
            parseFilePreview(json.parseToJsonElement(text))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 插件 mcp/list（dsh-remote-access，S5）：MCP 服务与工具枚举。
     * 响应 {ok, servers:[{serverName, tools[], status}]}；失败/插件不可用/非法 → 空列表
     * （UI 显示「无 MCP 服务」）。
     */
    suspend fun mcpList(): List<McpServer> {
        return try {
            val text = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/api/remote-access/mcp/list")
                    .get()
                    .build()
                unaryClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext ""
                    resp.body?.string().orEmpty()
                }
            }
            if (text.isBlank()) return emptyList()
            parseMcpList(json.parseToJsonElement(text))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** GET 封装：远端只读辅助路由 → 响应体或 ""（失败/非 200） */
    private suspend fun mcpGetText(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl$path").get().build()
        unaryClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
        }
    }

    /** 插件 mcp/resources/list（M3）：MCP 资源能力清册；失败/不可用 → 空列表 */
    suspend fun mcpResources(): List<McpResourceServer> {
        return try {
            val text = mcpGetText("/api/remote-access/mcp/resources/list")
            if (text.isBlank()) emptyList() else parseMcpResources(json.parseToJsonElement(text))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 插件 mcp/prompts/list（M3）：MCP 提示词能力清册；失败/不可用 → 空列表 */
    suspend fun mcpPrompts(): List<McpPromptServer> {
        return try {
            val text = mcpGetText("/api/remote-access/mcp/prompts/list")
            if (text.isBlank()) emptyList() else parseMcpPrompts(json.parseToJsonElement(text))
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 渲染 MCP 提示词（POST /mcp/prompts/render {name, arguments}）→ 可插入消息(best_effort)；失败 → null */
    suspend fun mcpPromptRender(name: String, arguments: Map<String, String>?): JsonArray? {
        return try {
            val body = JSONObject().put("name", name)
            arguments?.let { body.put("arguments", JSONObject(it)) }
            val text = withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url("$baseUrl/api/remote-access/mcp/prompts/render")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                unaryClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) "" else resp.body?.string().orEmpty()
                }
            }
            if (text.isBlank()) null
            else (json.parseToJsonElement(text) as? JsonObject)?.get("messages") as? JsonArray
        } catch (e: Exception) {
            null
        }
    }

    /**
     * dsh-encrypt 保险库状态（POST /api/credentials.status，与 web 端同一路由）。
     * 返回 null = 未连接 / PC 端未装 dsh-encrypt（404）/ 响应不可解析 → UI 显示「不可用」。
     */
    suspend fun vaultStatus(): VaultStatus? {
        return try {
            val text = postVaultRoute("/api/credentials.status", "{}")
            if (text == null) null else parseVaultStatus(text)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 解锁保险库（POST /api/credentials.unlock {digest}）。digest 为密码 SHA3-256 小写 hex
     * （明文密码不上行，与 web 端一致）；解锁为进程全局状态，PC web 端同步解锁。
     * 429 TOO_MANY_ATTEMPTS 不重试——由 UI 呈现倒计时（5 次失败锁 30s 起、指数到 15 分钟）。
     */
    suspend fun vaultUnlock(digest: String): VaultUnlockResult? {
        val text = try {
            postVaultRoute("/api/credentials.unlock", JSONObject().put("digest", digest).toString())
        } catch (e: Exception) {
            return null
        } ?: return null
        return parseVaultUnlock(text)
    }

    /** 保险库路由 POST（application/json 写栅栏）：非 2xx 返回原始响应体供错误码解析；404 → null。 */
    private suspend fun postVaultRoute(path: String, body: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        unaryClient.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            resp.body?.string().orEmpty()
        }
    }

    /**
     * 浏览 PC 端任意目录（直连 host.listDirectory RPC）：
     * 返回 {path, crumbs 面包屑层级, entries 子目录（含 hidden 隐藏标记）, truncated 截断标记, files 文件}。
     * - host RPC 可用：目录/面包屑/截断来自 host；文件来自插件 fs/list（host RPC 不含文件，插件不可用则空）；
     * - host RPC 不可用（老版本 host / 无 browse 能力 / 解析失败）时回退插件 fs/list
     *   （子目录名 + 文件，无面包屑——由 deriveCrumbsFromPath 推导；无截断标记），保证旧体验不回归。
     */
    suspend fun listDirectory(path: String?): DirListing {
        try {
            val value = call(DshEndpoints.HOST_LIST_DIRECTORY, buildJsonObject {
                path?.let { put("path", it) }
            })
            parseDirectoryList(value)?.let { host ->
                // host 不含文件：文件走插件 fs/list 增强（插件不可用 → 空文件列表）
                return host.copy(files = fsList(host.path)?.files ?: emptyList())
            }
        } catch (e: Exception) {
            // host RPC 失败/不支持 → 回退插件
        }
        val p = path ?: return DirListing("", emptyList(), emptyList(), false)
        val plugin = fsList(p)
        return DirListing(
            path = p,
            crumbs = deriveCrumbsFromPath(p),
            entries = (plugin?.dirs ?: emptyList()).map { DirEntry(it, joinChildPath(p, it), false) },
            files = plugin?.files ?: emptyList(),
            truncated = false,
        )
    }

    /** 子路径拼接：按父路径形态选择分隔符（Windows 反斜杠 / POSIX 斜杠） */
    private fun joinChildPath(parent: String, name: String): String {
        val sep = if (parent.contains('/')) '/' else '\\'
        return parent.trimEnd('\\').trimEnd('/') + sep + name
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
        retryScope.cancel()
    }
}
