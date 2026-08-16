package com.dsh.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 统一待办状态层（spec §5.2）：唯一事实源。
 * 主构造全部依赖注入，纯逻辑可用假件单测；便利构造接 DshConnection。
 */
class ApprovalCenter(
    private val events: Flow<DshConnection.Event>,
    private val answerApprovalFn: suspend (sessionId: String, approvalId: String, outcome: String) -> Unit,
    private val answerQuestionsFn: suspend (sessionId: String, answers: List<DshConnection.QuestionAnswer>) -> Unit,
    private val state: Flow<DshConnection.State>,
    private val listSessionsFn: suspend () -> List<SessionSummary>,
    private val historyFn: suspend (sessionId: String) -> HistoryValue,
    private val scope: CoroutineScope,
) {
    companion object {
        /** 历史恢复窗口：最近 N 个活跃会话（spec §5.2） */
        const val RECOVERY_SESSION_LIMIT = 5
    }

    constructor(connection: DshConnection, scope: CoroutineScope) : this(
        events = connection.events,
        answerApprovalFn = connection::answerApproval,
        answerQuestionsFn = connection::answerQuestions,
        state = connection.state,
        listSessionsFn = connection::listSessions,
        historyFn = { connection.history(it) },
        scope = scope,
    )

    private val mutex = Mutex()
    private val _items = MutableStateFlow<List<PendingItem>>(emptyList())

    /** 已解决项的墓碑（键同 itemKey 格式），阻止乐观回滚「复活」并发已解决的项。 */
    private var resolvedTombstones: Set<String> = mutableSetOf()

    /** 已排序的待办列表（唯一事实源） */
    val items: StateFlow<List<PendingItem>> = _items
    val pendingCount: StateFlow<Int> =
        _items.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 0)

    init {
        scope.launch { events.collect { onEvent(it) } }
        scope.launch {
            state.distinctUntilChanged().collect { s ->
                if (s is DshConnection.State.Connected) recover()
            }
        }
    }

    private fun itemKey(i: PendingItem): String = when (i) {
        is PendingItem.Approval -> "a:${i.sessionId}:${i.approvalId}"
        is PendingItem.Question -> "q:${i.sessionId}"
        is PendingItem.Error -> "e:${i.sessionId}"
    }

    private suspend fun mutate(transform: (MutableList<PendingItem>) -> Unit) {
        mutex.withLock {
            val list = _items.value.toMutableList()
            transform(list)
            _items.value = sortPendingItems(list)
        }
    }

    private suspend fun onEvent(e: DshConnection.Event) {
        when (e) {
            is DshConnection.Event.ApprovalRequested -> mutate { l ->
                resolvedTombstones -= "a:${e.sessionId}:${e.approvalId}"
                l.removeAll { it is PendingItem.Approval && it.sessionId == e.sessionId && it.approvalId == e.approvalId }
                l += PendingItem.Approval(
                    e.sessionId, e.approvalId, e.toolName, e.reason, e.callId,
                    System.currentTimeMillis(), fromHistory = false,
                )
            }
            is DshConnection.Event.ApprovalResolved -> mutate { l ->
                resolvedTombstones += "a:${e.sessionId}:${e.approvalId}"
                if (resolvedTombstones.size > 200) resolvedTombstones = trimTombstones(resolvedTombstones)
                l.removeAll { it is PendingItem.Approval && it.sessionId == e.sessionId && it.approvalId == e.approvalId }
            }
            is DshConnection.Event.QuestionRequested -> mutate { l ->
                resolvedTombstones -= "q:${e.sessionId}"
                l.removeAll { it is PendingItem.Question && it.sessionId == e.sessionId }
                l += PendingItem.Question(e.sessionId, e.questions, System.currentTimeMillis(), false)
            }
            is DshConnection.Event.QuestionResolved -> mutate { l ->
                resolvedTombstones += "q:${e.sessionId}"
                if (resolvedTombstones.size > 200) resolvedTombstones = trimTombstones(resolvedTombstones)
                l.removeAll { it is PendingItem.Question && it.sessionId == e.sessionId }
            }
            is DshConnection.Event.SessionEvent ->
                if (e.event.type == "agent/error") mutate { l ->
                    val prev = l.filterIsInstance<PendingItem.Error>()
                        .firstOrNull { it.sessionId == e.sessionId }
                    if (prev == null || e.event.seq > prev.seq) {
                        l.removeAll { it is PendingItem.Error && it.sessionId == e.sessionId }
                        l += PendingItem.Error(
                            e.sessionId, errorMessageOf(e.event.data), e.event.seq,
                            System.currentTimeMillis(), false,
                        )
                    }
                }
            else -> {}
        }
    }

    /** 连接成功后扫描最近 5 个会话历史；实时优先，失败静默（spec §5.2/§7） */
    private suspend fun recover() {
        mutex.withLock {
            val summaries = runCatching { listSessionsFn() }.getOrElse { return }
            val recent = summaries.sortedByDescending { it.updatedAt }.take(RECOVERY_SESSION_LIMIT)
            val merged = _items.value.toMutableList()
            for (s in recent) {
                val hv = runCatching { historyFn(s.sessionId) }.getOrNull() ?: continue
                for (item in scanHistoryEvents(s.sessionId, hv.events)) {
                    if (itemKey(item) !in resolvedTombstones && merged.none { itemKey(it) == itemKey(item) }) merged += item
                }
            }
            _items.value = sortPendingItems(merged)
        }
    }

    // ── 应答（乐观更新 + 失败回滚，spec §5.2）──

    private suspend fun answerApprovalItem(sessionId: String, approvalId: String, outcome: String) {
        val item = items.value.filterIsInstance<PendingItem.Approval>()
            .firstOrNull { it.sessionId == sessionId && it.approvalId == approvalId } ?: return
        mutate { l -> l.removeAll { it is PendingItem.Approval && it.sessionId == sessionId && it.approvalId == approvalId } }
        try {
            answerApprovalFn(sessionId, approvalId, outcome)
        } catch (e: Exception) {
            mutate { l ->
                if (itemKey(item) !in resolvedTombstones && l.none { itemKey(it) == itemKey(item) }) l += item
            }
            throw e
        }
    }

    suspend fun allow(sessionId: String, approvalId: String) =
        answerApprovalItem(sessionId, approvalId, "allowed-once")

    suspend fun reject(sessionId: String, approvalId: String) =
        answerApprovalItem(sessionId, approvalId, "rejected")

    suspend fun answerQuestions(sessionId: String, answers: List<DshConnection.QuestionAnswer>) {
        val item = items.value.filterIsInstance<PendingItem.Question>()
            .firstOrNull { it.sessionId == sessionId } ?: return
        mutate { l -> l.removeAll { it is PendingItem.Question && it.sessionId == sessionId } }
        try {
            answerQuestionsFn(sessionId, answers)
        } catch (e: Exception) {
            mutate { l ->
                if (itemKey(item) !in resolvedTombstones && l.none { itemKey(it) == itemKey(item) }) l += item
            }
            throw e
        }
    }

    suspend fun skipQuestions(sessionId: String) = answerQuestions(sessionId, emptyList())

    suspend fun allowAllApprovals(): List<String> {
        val snaps = items.value.filterIsInstance<PendingItem.Approval>()
            .map { it.sessionId to it.approvalId }
        val failures = mutableListOf<String>()
        for ((sid, aid) in snaps) {
            try { allow(sid, aid) } catch (e: CancellationException) { throw e } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }

    suspend fun rejectAllApprovals(): List<String> {
        val snaps = items.value.filterIsInstance<PendingItem.Approval>()
            .map { it.sessionId to it.approvalId }
        val failures = mutableListOf<String>()
        for ((sid, aid) in snaps) {
            try { reject(sid, aid) } catch (e: CancellationException) { throw e } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }

    suspend fun skipAllQuestions(): List<String> {
        val sessions = items.value.filterIsInstance<PendingItem.Question>().map { it.sessionId }
        val failures = mutableListOf<String>()
        for (sid in sessions) {
            try { skipQuestions(sid) } catch (e: CancellationException) { throw e } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }
}

/**
 * 墓碑超限裁剪：保留最近 capacity 条（按加入顺序），丢弃最旧——与全清相比不丢近期已解决项。
 * 约定输入为插入有序集合（mutableSetOf 即 LinkedHashSet）；capacity <= 0 时清空。
 */
internal fun trimTombstones(current: Set<String>, capacity: Int = 200): Set<String> {
    if (capacity <= 0) return emptySet()
    if (current.size <= capacity) return current
    return LinkedHashSet(current).drop(current.size - capacity).toSet()
}
