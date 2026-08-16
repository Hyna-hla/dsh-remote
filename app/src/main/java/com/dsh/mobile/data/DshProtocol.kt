package com.dsh.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─────────────────────────────────────────────────────────────
// 四象限 RPC 信封（与 dsh-client-connection 的 wire 格式一致）
// 上行: POST /api/<method>  body = ClientRequest
// 下行: HTTP 200 body = ServerResponse
// 事件流: GET /api/events.mux (Accept: text/event-stream)
//        每个 SSE data = ServerRequest，其 payload 为 MuxFrame
// 应答: POST /api/respond  body = ClientResponse
// ─────────────────────────────────────────────────────────────

@Serializable
data class ClientRequest(
    @SerialName("type") val type: String = "client-request",
    @SerialName("rpcId") val rpcId: String,
    val method: String,
    val payload: JsonElement = JsonNull,
)

@Serializable
data class ServerResponse(
    @SerialName("type") val type: String = "server-response",
    @SerialName("rpcId") val rpcId: String,
    val result: RpcResult,
)

@Serializable
data class ServerRequest(
    @SerialName("type") val type: String = "server-request",
    @SerialName("rpcId") val rpcId: String,
    val method: String,
    val payload: JsonElement = JsonNull,
)

@Serializable
data class ClientResponse(
    @SerialName("type") val type: String = "client-response",
    @SerialName("rpcId") val rpcId: String,
    val result: RpcResult,
)

@Serializable
data class RpcResult(
    val ok: Boolean,
    val value: JsonElement? = null,
    val error: RpcError? = null,
)

@Serializable
data class RpcError(
    val code: String,
    val message: String,
    val details: JsonElement? = null,
)

// ── 会话事件（宽松解析：data 保持宽字段）──

@Serializable
data class SessionEventWire(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement = JsonNull,
    val sourceEventSeqs: List<Long> = emptyList(),
    val surfaceOp: JsonElement? = null,
    val ignorable: Boolean? = null,
)

@Serializable
data class HistoryEntry(
    val event: SessionEventWire,
    val view: JsonElement? = null,
)

// ── Mux 帧（单一宽松模型，覆盖 events.schema 全部变体）──

@Serializable
data class QuestionOption(
    val label: String,
    val description: String? = null,
)

@Serializable
data class QuestionItem(
    val id: String,
    val question: String,
    val header: String? = null,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
)

@Serializable
data class JobView(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String? = null,
    val startedAt: Long,
    val finishedAt: Long? = null,
)

@Serializable
data class MuxFrame(
    val type: String,
    val sessionId: String? = null,
    val event: SessionEventWire? = null,
    val view: JsonElement? = null,
    val lastSeq: Long? = null,
    val approvalId: String? = null,
    val toolName: String? = null,
    val callId: String? = null,
    val reason: String? = null,
    val outcome: String? = null,
    val questions: List<QuestionItem> = emptyList(),
    val questionRpcId: String? = null,
    val jobs: List<JobView> = emptyList(),
    val items: JsonElement? = null,
    val key: String? = null,
    val value: JsonElement? = null,
    val seq: Long? = null,
    val error: RpcError? = null,
    val blank: Boolean? = null,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val status: String? = null,
)

// ── 领域模型 ──

@Serializable
data class SessionSummary(
    val sessionId: String,
    val updatedAt: Long,
    val running: Boolean = false,
    val blank: Boolean = false,
    val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: JsonElement? = null,
)

/** 会话标题：projections.values.title；无则 null（HomeScreen/PendingScreen 共用） */
fun sessionTitleOf(session: SessionSummary): String? =
    session.projections?.jsonObject?.get("values")?.jsonObject
        ?.get("title")?.jsonPrimitive?.contentOrNull

@Serializable
data class SessionListValue(
    val items: List<SessionSummary> = emptyList(),
)

@Serializable
data class SessionCreateValue(
    val sessionId: String,
    val agentPreset: String? = null,
)

@Serializable
data class HistoryValue(
    val events: List<HistoryEntry> = emptyList(),
    val hasMore: Boolean = false,
    val projections: JsonElement? = null,
)

@Serializable
data class ModelSelection(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
)

@Serializable
data class SessionModelsValue(
    val current: ModelSelection,
    val routable: Boolean = false,
    val groups: JsonElement = JsonNull,
    val failures: JsonElement = JsonNull,
)

@Serializable
data class WorkspaceView(
    val workspaceId: String,
    val path: String,
    val title: String,
    val sessionIds: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class WorkspaceListValue(
    val items: List<WorkspaceView> = emptyList(),
    val archivedSessionIds: List<String> = emptyList(),
)

// ── 端点与事件类型常量（真实协议名）──

object DshEndpoints {
    const val SESSION_LIST = "session.list"
    const val SESSION_SEARCH = "session.search"
    const val SESSION_CREATE = "session.create"
    const val SESSION_HISTORY = "session.history"
    const val SESSION_MODELS = "session.models"
    const val SESSION_SELECT_MODEL = "session.selectModel"
    const val SESSION_PROMPT = "session.prompt"
    const val SESSION_CANCEL = "session.cancel"
    const val SESSION_RENAME = "session.rename"
    const val COMMANDS_EXECUTE = "commands/execute"
    const val SKILL_LIST = "skill.list"
    const val WORKSPACE_LIST = "workspace.list"
    const val WORKSPACE_CREATE = "workspace.create"
    const val WORKSPACE_ARCHIVE_SESSION = "workspace.archiveSession"
    const val WORKSPACE_INSERT_SESSION_BEFORE = "workspace.insertSessionBefore"
    const val AGENT_PRESET_LIST = "agentPreset.list"
    const val HOST_DESCRIBE = "host.describe"
}

object DshEventTypes {
    // 会话事件
    const val USER_MESSAGE = "user/message"
    const val ASSISTANT_MESSAGE = "assistant/message"
    const val ASSISTANT_CHUNK = "assistant/chunk"
    const val TOOL_CALL = "tool/call"
    const val TOOL_RESULT = "tool/result"
    const val TURN_START = "turn/start"
    const val TURN_END = "turn/end"
    const val SESSION_TITLE = "session/title"
    const val AGENT_ERROR = "agent/error"
    // Mux 帧
    const val FRAME_SESSION_EVENT = "session/event"
    const val FRAME_SUBSCRIBED = "session/subscribed"
    const val FRAME_APPROVAL_REQUESTED = "approval/requested"
    const val FRAME_APPROVAL_RESOLVED = "approval/resolved"
    const val FRAME_QUESTION_REQUESTED = "question/requested"
    const val FRAME_QUESTION_RESOLVED = "question/resolved"
    const val FRAME_JOBS = "session/jobs"
    const val FRAME_QUEUE = "session/queue"
    const val FRAME_PROJECTION = "session/projection"
    const val FRAME_STREAM_ERROR = "stream/error"
    // Host 帧
    const val HOST_SESSION_ADDED = "host/session-added"
    const val HOST_SESSION_REMOVED = "host/session-removed"
    const val HOST_SESSION_STATUS = "host/session-status"
    const val HOST_WORKSPACE_CHANGED = "host/workspace-changed"
}
