# S2 统一待办审批中心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 DSH Remote 的审批/问答/报错处理收编到一个集中式待办中心：实时事件 + 历史恢复的状态层、三档待办页（批量操作）、会话页收编为轻提示、点击跳转会话定位。

**Architecture:** 纯函数层（PendingItem 扫描/排序）与注入式 `ApprovalCenter` 状态层（唯一事实源）先行并单测覆盖；UI 三层消费同一状态流；会话页横幅收编；导航加 Pending 路由与 Session focusSeq 定位。

**Tech Stack:** Kotlin 2.1.21 / Compose BOM 2025.06 / kotlinx-coroutines（新增 test 依赖 kotlinx-coroutines-test 1.10.2）/ JUnit 4.13.2（S1 已建）。

**Spec:** `docs/superpowers/specs/2026-08-16-s2-approval-center-design.md`（spec 为准绳；执行者两个都读）

## Global Constraints

- 状态唯一事实源 = `ApprovalCenter.items`（Sorted）；UI 不保留第二份待办状态。
- 三档顺序：审批 → 问答 → 异常；审批/问答按 arrivedAt 升序，异常按降序（spec §5.1）。
- 历史恢复：连接成功（State.Connected）后扫描最近 `RECOVERY_SESSION_LIMIT = 5` 个会话；合并规则「实时优先」（同 key 历史项不覆盖实时项）；任一步失败静默降级（spec §5.2/§7）。
- 问答无持久化：`Question.fromHistory` 恒 false，恢复不产生 Question（spec §3 协议事实）。
- 批量 = 分区级（全部允许/全部拒绝/全部跳过），顺序执行、逐项容错、返回失败列表（spec §5.2）。
- 应答乐观更新：先本地移除，失败回滚并抛异常（spec §5.2）。
- 跳转定位：仅 `Error` 项带 `focusSeq`；Approval/Question 不带（spec §5.3/§9）。
- 会话内轻提示条：数量 N>0 时显示「本会话有 N 条待办（审批/问答）· 去处理」；异常不进提示条（spec §5.4）。
- 测试命令统一在 `C:\hremote`（ASCII junction → E:\AI搓的小东西\harness-remote）下执行：`.\gradlew.bat :app:testDebugUnitTest --tests "<FQCN>"`。
- 每个任务 `git add` 精确文件后单独 commit；**禁止** `git add -A`/`git commit -a`（仓库可能有并行 agent 活动）。

---

### Task 1: PendingItem 模型 + 纯函数（scanHistoryEvents / sortPendingItems）

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/data/PendingItem.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/PendingItemTest.kt`

**Interfaces:**
- Consumes: `DshProtocol.kt` 现有 `QuestionItem`/`HistoryEntry`/`SessionEventWire`/`JsonElement` 处理。
- Produces:
```kotlin
sealed class PendingItem {
    abstract val sessionId: String
    abstract val arrivedAt: Long
    abstract val fromHistory: Boolean
    data class Approval(sessionId, approvalId, toolName, reason: String?, callId: String?,
        arrivedAt, fromHistory) : PendingItem()
    data class Question(sessionId, questions: List<QuestionItem>, arrivedAt, fromHistory) : PendingItem()
    data class Error(sessionId, message, seq: Long, arrivedAt, fromHistory) : PendingItem()
}
fun scanHistoryEvents(sessionId: String, entries: List<HistoryEntry>): List<PendingItem>
fun sortPendingItems(items: List<PendingItem>): List<PendingItem>
internal fun errorMessageOf(data: JsonElement): String
```
（注：spec §5.1 的 `scanHistoryEvents(entries)` 省略了 sessionId 参数——调用方按会话扫描，本计划补上 sessionId 参数，属对 spec 的澄清性补全。）

- [ ] **Step 1: 写失败测试**

`app/src/test/java/com/dsh/mobile/data/PendingItemTest.kt`：
```kotlin
package com.dsh.mobile.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingItemTest {

    private fun ev(type: String, seq: Long, time: Long = seq, data: kotlinx.serialization.json.JsonObject = buildJsonObject { }) =
        SessionEventWire(type = type, seq = seq, time = time, data = data)

    private fun entry(e: SessionEventWire) = HistoryEntry(event = e)

    private fun asked(id: String, seq: Long) = ev("approval/asked", seq, data = buildJsonObject {
        put("id", id); put("toolName", "bash"); put("reason", "run rm")
    })

    @Test
    fun pairedAskedDecidedYieldsEmpty() {
        val out = scanHistoryEvents("s1", listOf(
            entry(asked("a1", 1)),
            entry(ev("approval/decided", 2, data = buildJsonObject { put("id", "a1") })),
        ))
        assertTrue(out.isEmpty())
    }

    @Test
    fun askedWithoutDecidedStaysPending() {
        val out = scanHistoryEvents("s1", listOf(entry(asked("a1", 1))))
        assertEquals(1, out.size)
        val a = out[0] as PendingItem.Approval
        assertEquals("a1", a.approvalId)
        assertEquals("bash", a.toolName)
        assertEquals("run rm", a.reason)
        assertTrue(a.fromHistory)
    }

    @Test
    fun duplicateAskedIgnored() {
        val out = scanHistoryEvents("s1", listOf(entry(asked("a1", 1)), entry(asked("a1", 3))))
        assertEquals(1, out.size)
    }

    @Test
    fun decidedWithoutAskedIgnored() {
        val out = scanHistoryEvents("s1", listOf(
            entry(ev("approval/decided", 1, data = buildJsonObject { put("id", "x") })),
            entry(asked("a1", 2)),
        ))
        assertEquals(1, out.size)
    }

    @Test
    fun latestErrorPerSessionWins() {
        val e1 = ev("agent/error", 1, data = buildJsonObject { put("message", "first") })
        val e2 = ev("agent/error", 5, data = buildJsonObject { put("message", "second") })
        val out = scanHistoryEvents("s1", listOf(entry(e1), entry(e2)))
        assertEquals(1, out.size)
        assertEquals("second", (out[0] as PendingItem.Error).message)
        assertEquals(5L, (out[0] as PendingItem.Error).seq)
    }

    @Test
    fun badDataSkipped() {
        val out = scanHistoryEvents("s1", listOf(entry(ev("approval/asked", 1, data = buildJsonObject { put("nope", 1) }))))
        assertTrue(out.isEmpty())
    }

    @Test
    fun sortOrderApprovalQuestionError() {
        val a = PendingItem.Approval("s1", "a1", "bash", null, null, 100L, false)
        val q = PendingItem.Question("s2", emptyList(), 50L, false)
        val e = PendingItem.Error("s3", "err", 9L, 200L, false)
        val sorted = sortPendingItems(listOf(e, q, a))
        assertEquals(listOf<PendingItem>(a, q, e), sorted)
    }

    @Test
    fun sortApprovalOldestFirstErrorNewestFirst() {
        val a1 = PendingItem.Approval("s1", "a1", "t", null, null, 100L, false)
        val a2 = PendingItem.Approval("s1", "a2", "t", null, null, 50L, false)
        val e1 = PendingItem.Error("s1", "e", 1L, 100L, false)
        val e2 = PendingItem.Error("s1", "e", 2L, 300L, false)
        val sorted = sortPendingItems(listOf(a1, e1, e2, a2))
        assertEquals(listOf<PendingItem>(a2, a1, e2, e1), sorted)
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.PendingItemTest"`
Expected: 编译失败（PendingItem 不存在）。

- [ ] **Step 3: 实现**

`app/src/main/java/com/dsh/mobile/data/PendingItem.kt`：
```kotlin
package com.dsh.mobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class PendingItem {
    abstract val sessionId: String
    abstract val arrivedAt: Long
    abstract val fromHistory: Boolean

    data class Approval(
        override val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
        val callId: String?,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()

    data class Question(
        override val sessionId: String,
        val questions: List<QuestionItem>,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()

    data class Error(
        override val sessionId: String,
        val message: String,
        val seq: Long,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()
}

/**
 * 从单会话的历史事件重建待办（spec §5.1）：
 * 按 seq 升序扫描——approval/asked 加入（重复 id 忽略）、approval/decided 移除同 id、
 * agent/error 每会话保留 seq 最大一条；data 解析失败跳过该事件。
 */
fun scanHistoryEvents(sessionId: String, entries: List<HistoryEntry>): List<PendingItem> {
    val approvals = LinkedHashMap<String, PendingItem.Approval>()
    var error: PendingItem.Error? = null
    for (entry in entries.sortedBy { it.event.seq }) {
        val ev = entry.event
        when (ev.type) {
            "approval/asked" -> {
                val d = ev.data.jsonObject
                val id = d["id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (approvals.containsKey(id)) continue
                approvals[id] = PendingItem.Approval(
                    sessionId = sessionId,
                    approvalId = id,
                    toolName = d["toolName"]?.jsonPrimitive?.contentOrNull ?: "?",
                    reason = d["reason"]?.jsonPrimitive?.contentOrNull,
                    callId = d["callId"]?.jsonPrimitive?.contentOrNull,
                    arrivedAt = ev.time,
                    fromHistory = true,
                )
            }
            "approval/decided" -> {
                val id = ev.data.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
                approvals.remove(id)
            }
            "agent/error" -> {
                if (error == null || ev.seq > error!!.seq) {
                    error = PendingItem.Error(sessionId, errorMessageOf(ev.data), ev.seq, ev.time, true)
                }
            }
        }
    }
    val out = mutableListOf<PendingItem>()
    out += approvals.values
    error?.let { out += it }
    return out
}

/** 异常消息宽松提取：message / error / name，全缺返回默认文案 */
internal fun errorMessageOf(data: JsonElement): String {
    val o = data.jsonObject
    return o["message"]?.jsonPrimitive?.contentOrNull
        ?: o["error"]?.jsonPrimitive?.contentOrNull
        ?: o["name"]?.jsonPrimitive?.contentOrNull
        ?: "智能体执行出错"
}

/** 三档顺序 + 档内排序（spec §5.1）：审批/问答 arrivedAt 升序，异常降序 */
fun sortPendingItems(items: List<PendingItem>): List<PendingItem> {
    fun rank(i: PendingItem) = when (i) {
        is PendingItem.Approval -> 0
        is PendingItem.Question -> 1
        is PendingItem.Error -> 2
    }
    return items.sortedWith(
        compareBy<PendingItem> { rank(it) }
            .thenBy { if (it is PendingItem.Error) Long.MAX_VALUE - it.arrivedAt else it.arrivedAt },
    )
}
```

- [ ] **Step 4: 运行确认通过**

Run: `cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.PendingItemTest"`
Expected: 8/8 PASS。再跑全量 `.\gradlew.bat :app:testDebugUnitTest` 无回归。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/data/PendingItem.kt app/src/test/java/com/dsh/mobile/data/PendingItemTest.kt
git commit -m "feat(s2): PendingItem 模型 + 历史扫描与排序纯函数"
```

---

### Task 2: ApprovalCenter 状态层（注入式）+ 单测

**Files:**
- Modify: `gradle/libs.versions.toml`（加 kotlinx-coroutines-test）
- Modify: `app/build.gradle.kts`（testImplementation）
- Create: `app/src/main/java/com/dsh/mobile/data/ApprovalCenter.kt`
- Test: `app/src/test/java/com/dsh/mobile/data/ApprovalCenterTest.kt`

**Interfaces:**
- Consumes: Task 1 `PendingItem`/`scanHistoryEvents`/`sortPendingItems`；`DshConnection.Event`/`State`、`DshConnection.QuestionAnswer`、`SessionSummary`/`HistoryValue`。
- Produces:
```kotlin
class ApprovalCenter(
    events: Flow<DshConnection.Event>,
    answerApprovalFn: suspend (String, String, String) -> Unit,
    answerQuestionsFn: suspend (String, List<DshConnection.QuestionAnswer>) -> Unit,
    state: Flow<DshConnection.State>,
    listSessionsFn: suspend () -> List<SessionSummary>,
    historyFn: suspend (String) -> HistoryValue,
    scope: CoroutineScope,
) {
    constructor(connection: DshConnection, scope: CoroutineScope)  // 便利构造
    companion object { const val RECOVERY_SESSION_LIMIT = 5 }
    val items: StateFlow<List<PendingItem>>
    val pendingCount: StateFlow<Int>
    suspend fun allow(sessionId, approvalId)
    suspend fun reject(sessionId, approvalId)
    suspend fun answerQuestions(sessionId, answers: List<DshConnection.QuestionAnswer>)
    suspend fun skipQuestions(sessionId)
    suspend fun allowAllApprovals(): List<String>
    suspend fun rejectAllApprovals(): List<String>
    suspend fun skipAllQuestions(): List<String>
}
```

- [ ] **Step 1: 加 coroutines-test 依赖**

`gradle/libs.versions.toml`：
```toml
[libraries]
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "kotlinxCoroutinesAndroid" }
```
`app/build.gradle.kts` dependencies 块：
```kotlin
    testImplementation(libs.kotlinx.coroutines.test)
```

- [ ] **Step 2: 写失败测试**

`app/src/test/java/com/dsh/mobile/data/ApprovalCenterTest.kt`：
```kotlin
package com.dsh.mobile.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalCenterTest {

    private val events = MutableSharedFlow<DshConnection.Event>(extraBufferCapacity = 16)
    private val state = MutableStateFlow<DshConnection.State>(DshConnection.State.Disconnected)

    private class Harness {
        var sessions: suspend () -> List<SessionSummary> = { emptyList() }
        var history: suspend (String) -> HistoryValue = { HistoryValue() }
        var answerApproval: suspend (String, String, String) -> Unit = { _, _, _ -> }
        var answerQuestions: suspend (String, List<DshConnection.QuestionAnswer>) -> Unit = { _, _ -> }
    }

    private fun center(h: Harness, scope: kotlinx.coroutines.CoroutineScope) = ApprovalCenter(
        events = events, state = state,
        answerApprovalFn = h.answerApproval, answerQuestionsFn = h.answerQuestions,
        listSessionsFn = h.sessions, historyFn = h.history, scope = scope,
    )

    @Test
    fun liveApprovalAddAndResolve() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        val c = center(h, backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", "r", null))
        assertEquals(1, c.items.value.size)
        events.emit(DshConnection.Event.ApprovalResolved("s1", "a1", "allowed-once"))
        assertTrue(c.items.value.isEmpty())
    }

    @Test
    fun questionReplacesPerSession() = runTest(UnconfinedTestDispatcher()) {
        val c = center(Harness(), backgroundScope)
        events.emit(DshConnection.Event.QuestionRequested("s1", listOf(QuestionItem("q1", "Q"))))
        events.emit(DshConnection.Event.QuestionRequested("s1", listOf(QuestionItem("q2", "Q2"))))
        assertEquals(1, c.items.value.size)
        assertEquals("Q2", (c.items.value[0] as PendingItem.Question).questions[0].question)
    }

    @Test
    fun errorKeepsLatestSeqPerSession() = runTest(UnconfinedTestDispatcher()) {
        val c = center(Harness(), backgroundScope)
        fun err(seq: Long, msg: String) = DshConnection.Event.SessionEvent(
            "s1", SessionEventWire("agent/error", seq, seq,
                kotlinx.serialization.json.buildJsonObject { put("message", msg) }),
        )
        events.emit(err(1, "one"))
        events.emit(err(3, "three"))
        assertEquals("three", (c.items.value[0] as PendingItem.Error).message)
        events.emit(err(2, "two"))  // 旧 seq 不覆盖
        assertEquals("three", (c.items.value[0] as PendingItem.Error).message)
    }

    @Test
    fun recoveryScansTop5AndLiveWins() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.sessions = {
            (1L..6L).map { i -> SessionSummary(sessionId = "s$i", updatedAt = i) }
        }
        h.history = { sid ->
            val seq = sid.removePrefix("s").toLong()
            HistoryValue(events = listOf(HistoryEntry(event = SessionEventWire(
                "approval/asked", seq, seq,
                kotlinx.serialization.json.buildJsonObject { put("id", "a$sid"); put("toolName", "bash") },
            ))))
        }
        val c = center(h, backgroundScope)
        // 实时项同 key 优先（s5 实时到达）
        events.emit(DshConnection.Event.ApprovalRequested("s5", "as5", "bash", null, null))
        state.value = DshConnection.State.Connected("http://x")
        // 恢复扫描 s6..s2（updatedAt 前 5），s1 在窗口外
        assertEquals(6, c.items.value.size) // s2..s6 的 asked + s5 实时（同 key 只留实时）
        assertTrue(c.items.value.none { it.sessionId == "s1" })
    }

    @Test
    fun recoveryFailureIsSilent() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.sessions = { throw RuntimeException("boom") }
        val c = center(h, backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", null, null))
        state.value = DshConnection.State.Connected("http://x")
        assertEquals(1, c.items.value.size)
    }

    @Test
    fun optimisticRemoveRollsBackOnFailure() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.answerApproval = { _, _, _ -> throw ApiException("rpc down") }
        val c = center(h, backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", null, null))
        var failed = false
        try { c.allow("s1", "a1") } catch (e: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(1, c.items.value.size) // 回滚
    }

    @Test
    fun batchCollectsFailures() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.answerApproval = { _, aid, _ -> if (aid == "a2") throw ApiException("nope") }
        val c = center(h, backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "t", null, null))
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a2", "t", null, null))
        events.emit(DshConnection.Event.ApprovalRequested("s2", "a3", "t", null, null))
        val failures = c.allowAllApprovals()
        assertEquals(1, failures.size)
        assertTrue(failures[0].contains("nope"))
        assertEquals(1, c.items.value.size) // 失败项回滚保留
    }
}
```
（`put` 需要 `import kotlinx.serialization.json.put`，在文件 import 区补上。）

- [ ] **Step 3: 运行确认失败**

Run: `cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ApprovalCenterTest"`
Expected: 编译失败（ApprovalCenter 不存在）。

- [ ] **Step 4: 实现 ApprovalCenter**

`app/src/main/java/com/dsh/mobile/data/ApprovalCenter.kt`：
```kotlin
package com.dsh.mobile.data

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
                l.removeAll { it is PendingItem.Approval && it.approvalId == e.approvalId }
                l += PendingItem.Approval(
                    e.sessionId, e.approvalId, e.toolName, e.reason, e.callId,
                    System.currentTimeMillis(), fromHistory = false,
                )
            }
            is DshConnection.Event.ApprovalResolved -> mutate { l ->
                l.removeAll { it is PendingItem.Approval && it.approvalId == e.approvalId }
            }
            is DshConnection.Event.QuestionRequested -> mutate { l ->
                l.removeAll { it is PendingItem.Question && it.sessionId == e.sessionId }
                l += PendingItem.Question(e.sessionId, e.questions, System.currentTimeMillis(), false)
            }
            is DshConnection.Event.QuestionResolved -> mutate { l ->
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
                val hv = runCatching { historyFn(s.sessionId) }.getOrElse { continue }
                for (item in scanHistoryEvents(s.sessionId, hv.events)) {
                    if (merged.none { itemKey(it) == itemKey(item) }) merged += item
                }
            }
            _items.value = sortPendingItems(merged)
        }
    }

    // ── 应答（乐观更新 + 失败回滚，spec §5.2）──

    private suspend fun answerApprovalItem(sessionId: String, approvalId: String, outcome: String) {
        val item = items.value.filterIsInstance<PendingItem.Approval>()
            .firstOrNull { it.sessionId == sessionId && it.approvalId == approvalId } ?: return
        mutate { l -> l.removeAll { it is PendingItem.Approval && it.approvalId == approvalId } }
        try {
            answerApprovalFn(sessionId, approvalId, outcome)
        } catch (e: Exception) {
            mutate { l ->
                if (l.none { it is PendingItem.Approval && it.approvalId == approvalId }) l += item
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
                if (l.none { it is PendingItem.Question && it.sessionId == sessionId }) l += item
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
            try { allow(sid, aid) } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }

    suspend fun rejectAllApprovals(): List<String> {
        val snaps = items.value.filterIsInstance<PendingItem.Approval>()
            .map { it.sessionId to it.approvalId }
        val failures = mutableListOf<String>()
        for ((sid, aid) in snaps) {
            try { reject(sid, aid) } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }

    suspend fun skipAllQuestions(): List<String> {
        val sessions = items.value.filterIsInstance<PendingItem.Question>().map { it.sessionId }
        val failures = mutableListOf<String>()
        for (sid in sessions) {
            try { skipQuestions(sid) } catch (e: Exception) { failures += e.message ?: "unknown" }
        }
        return failures
    }
}
```

- [ ] **Step 5: 运行确认通过**

Run: `cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest --tests "com.dsh.mobile.data.ApprovalCenterTest"`
Expected: 7/7 PASS。全量 `.\gradlew.bat :app:testDebugUnitTest` 无回归。

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/dsh/mobile/data/ApprovalCenter.kt app/src/test/java/com/dsh/mobile/data/ApprovalCenterTest.kt
git commit -m "feat(s2): ApprovalCenter 待办状态层（实时事件+历史恢复+批量应答）"
```

---

### Task 3: 共享抽取 —— QuestionCard 组件 + sessionTitleOf

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/ui/components/QuestionCard.kt`
- Modify: `app/src/main/java/com/dsh/mobile/data/DshProtocol.kt`（加 sessionTitleOf）
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`（私有 QuestionCard 迁移后改 import；本任务后 SessionScreen 仍调用它，行为不变）
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt`（chatGptTitleOf 委托）

**Interfaces:**
- Consumes: `QuestionItem`/`DshConnection.QuestionAnswer`/`SessionSummary`。
- Produces:
```kotlin
// ui/components/QuestionCard.kt
@Composable
fun QuestionCard(
    questions: List<QuestionItem>,
    onSubmit: (List<DshConnection.QuestionAnswer>) -> Unit,
    onSkip: () -> Unit,
)
// data/DshProtocol.kt
fun sessionTitleOf(session: SessionSummary): String?
```

- [ ] **Step 1: sessionTitleOf 共享函数**

`DshProtocol.kt`（SessionSummary 定义之后）加：
```kotlin
/** 会话标题：projections.values.title；无则 null（HomeScreen/PendingScreen 共用） */
fun sessionTitleOf(session: SessionSummary): String? =
    session.projections?.jsonObject?.get("values")?.jsonObject
        ?.get("title")?.jsonPrimitive?.contentOrNull
```

- [ ] **Step 2: HomeScreen 委托**

`HomeScreen.kt` 的 `chatGptTitleOf`（约 2320 行）函数体替换为：
```kotlin
private fun chatGptTitleOf(s: SessionSummary): String = sessionTitleOf(s)
    ?: s.sessionId.take(8)
```
（先读原函数确认回退逻辑一致——原实现 title 为 null 时的回退文案保持不变；若有差异以原实现为准。）

- [ ] **Step 3: 迁移 QuestionCard**

把 `SessionScreen.kt` 的私有 `QuestionCard` composable（1667 行起，含其内部 selections 状态与 UI）整段移到新文件 `ui/components/QuestionCard.kt`：
- 包名改 `com.dsh.mobile.ui.components`；import 补齐（原 SessionScreen 文件头的相关 import 复制所需项）。
- 签名增加 `onSkip: () -> Unit`，组件内跳过按钮（原组件若无跳过按钮，在提交按钮旁加 `TextButton(onClick = onSkip) { Text("跳过") }`）。
- SessionScreen 中调用点改 import `com.dsh.mobile.ui.components.QuestionCard` 并补 `onSkip = { ... }`（本任务行为等价：`onSkip` 里执行原「跳过」逻辑，即 `scope.launch { connection.answerQuestions(sessionId, emptyList()); questions = null }`——参照 SessionScreen 1058-1061 行现有逻辑）。若原 QuestionCard 已有跳过按钮语义，保留并接到 onSkip。

- [ ] **Step 4: 编译验证**

Run: `cd C:\hremote; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL（SessionScreen 仍用迁移后的组件，无行为变化）。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/ui/components/QuestionCard.kt app/src/main/java/com/dsh/mobile/data/DshProtocol.kt app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt
git commit -m "feat(s2): 抽取共享 QuestionCard 与 sessionTitleOf"
```

---

### Task 4: PendingScreen 待办页

**Files:**
- Create: `app/src/main/java/com/dsh/mobile/ui/screens/PendingScreen.kt`

**Interfaces:**
- Consumes: Task 1/2 `PendingItem`/`ApprovalCenter`、Task 3 `QuestionCard`/`sessionTitleOf`、`DshConnection.listSessions`。
- Produces:
```kotlin
@Composable
fun PendingScreen(
    center: ApprovalCenter,
    connection: DshConnection,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, focusSeq: Long?) -> Unit,
)
```
（本任务无人调用，Task 5 接线。）

- [ ] **Step 1: 实现 PendingScreen**

`app/src/main/java/com/dsh/mobile/ui/screens/PendingScreen.kt`（结构完整代码）：

```kotlin
package com.dsh.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.mobile.data.*
import com.dsh.mobile.ui.components.QuestionCard
import com.dsh.mobile.ui.theme.DshShape
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingScreen(
    center: ApprovalCenter,
    connection: DshConnection,
    onBack: () -> Unit,
    onOpenSession: (sessionId: String, focusSeq: Long?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by center.items.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var busy by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }

    // 会话标题映射（一次拉取；失败回退 sessionId 前 8 位）
    LaunchedEffect(Unit) {
        titles = runCatching { connection.listSessions() }.getOrDefault(emptyList())
            .associate { it.sessionId to (sessionTitleOf(it) ?: it.sessionId.take(8)) }
    }

    suspend fun runBatch(label: String, block: suspend () -> List<String>) {
        busy = true
        val failures = runCatching { block() }.getOrElse { listOf(it.message ?: "unknown") }
        busy = false
        if (failures.isNotEmpty()) {
            snackbar.showSnackbar("$label：${failures.size} 条失败 —— ${failures.first().take(80)}")
        }
    }

    val approvals = items.filterIsInstance<PendingItem.Approval>()
    val questions = items.filterIsInstance<PendingItem.Question>()
    val errors = items.filterIsInstance<PendingItem.Error>()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("待办中心") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("暂无待办", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    "审批、问答与智能体报错会收在这里",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                if (approvals.isNotEmpty()) {
                    item(key = "ap-h") { SectionHeader("审批（${approvals.size}）") }
                    item(key = "ap-b") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(
                                onClick = { scope.launch { runBatch("全部允许") { center.allowAllApprovals() } } },
                                enabled = !busy, modifier = Modifier.weight(1f),
                            ) { Text("全部允许") }
                            OutlinedButton(
                                onClick = { scope.launch { runBatch("全部拒绝") { center.rejectAllApprovals() } } },
                                enabled = !busy, modifier = Modifier.weight(1f),
                            ) { Text("全部拒绝") }
                        }
                    }
                    items(approvals, key = { "ap-" + it.approvalId }) { a ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = DshShape.card) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        titles[a.sessionId] ?: a.sessionId.take(8),
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        "工具 · ${a.toolName}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                a.reason?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "原因：$it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2,
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                runCatching { center.reject(a.sessionId, a.approvalId) }
                                                    .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("拒绝") }
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                runCatching { center.allow(a.sessionId, a.approvalId) }
                                                    .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                            }
                                        },
                                        enabled = !busy,
                                    ) { Text("允许一次") }
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onOpenSession(a.sessionId, null) }) { Text("去会话") }
                                }
                            }
                        }
                    }
                }

                if (questions.isNotEmpty()) {
                    item(key = "q-h") { SectionHeader("问答（${questions.size}）") }
                    item(key = "q-b") {
                        OutlinedButton(
                            onClick = { scope.launch { runBatch("全部跳过") { center.skipAllQuestions() } } },
                            enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        ) { Text("全部跳过") }
                    }
                    items(questions, key = { "q-" + it.sessionId }) { q ->
                        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp), shape = DshShape.card) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    titles[q.sessionId] ?: q.sessionId.take(8),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(8.dp))
                                QuestionCard(
                                    questions = q.questions,
                                    onSubmit = { answers ->
                                        scope.launch {
                                            runCatching { center.answerQuestions(q.sessionId, answers) }
                                                .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                        }
                                    },
                                    onSkip = {
                                        scope.launch {
                                            runCatching { center.skipQuestions(q.sessionId) }
                                                .onFailure { e -> scope.launch { snackbar.showSnackbar(e.message ?: "失败") } }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                if (errors.isNotEmpty()) {
                    item(key = "e-h") { SectionHeader("异常（${errors.size}）") }
                    items(errors, key = { "e-" + it.sessionId + "-" + it.seq }) { err ->
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = DshShape.card,
                            onClick = { onOpenSession(err.sessionId, err.seq) },
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    titles[err.sessionId] ?: err.sessionId.take(8),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    err.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    maxLines = 2,
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
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
}
```
（`Card(onClick=...)` 需要 material3 的可点击 Card 重载——`ExperimentalMaterial3Api` 已 OptIn；若该版本无 onClick 重载，改用 `Card { }` 包 `Modifier.clickable { }`。import `androidx.compose.foundation.clickable` 备选。）

- [ ] **Step 2: 编译验证**

Run: `cd C:\hremote; .\gradlew.bat :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/ui/screens/PendingScreen.kt
git commit -m "feat(s2): 待办中心页面（三档/批量/内联问答/异常跳转）"
```

---

### Task 5: 接线 —— DshApplication 单例 + AppNavigation + HomeScreen 铃铛

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/DshApplication.kt`
- Modify: `app/src/main/java/com/dsh/mobile/MainActivity.kt`
- Modify: `app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt`
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt`

**Interfaces:**
- Consumes: Task 2 `ApprovalCenter`、Task 4 `PendingScreen`。
- Produces: `AppNavigation(navController, connection, approvalCenter)`；`HomeScreen(…, approvalCenter: ApprovalCenter, onPending: () -> Unit, …)`；`Screen.Pending`。

- [ ] **Step 1: DshApplication 单例**

`DshApplication.kt`：
```kotlin
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val connection = DshConnection(this)
    val approvalCenter by lazy { ApprovalCenter(connection, appScope) }
```
（import 补 `com.dsh.mobile.data.ApprovalCenter`、`kotlinx.coroutines.*`。）

- [ ] **Step 2: MainActivity 传参**

`MainActivity.kt` 的 AppNavigation 调用处（约 222-225 行）：
```kotlin
                        AppNavigation(
                            navController = navController,
                            connection = app.connection,
                            approvalCenter = app.approvalCenter,
                        )
```

- [ ] **Step 3: AppNavigation 路由与注入**

`AppNavigation.kt`：
```kotlin
sealed class Screen(val route: String) {
    data object Connect : Screen("connect")
    data object Home : Screen("home")
    data object Session : Screen("session/{sessionId}?focusSeq={focusSeq}") {
        fun createRoute(sessionId: String, focusSeq: Long? = null) =
            "session/$sessionId" + (focusSeq?.let { "?focusSeq=$it" } ?: "")
    }
    data object Settings : Screen("settings")
    data object Pending : Screen("pending")
    // Screen.Pro 保留不动
}
```
`AppNavigation` 签名加 `approvalCenter: ApprovalCenter`；NavHost 中：
```kotlin
        composable(Screen.Connect.route) { ConnectScreen(connection = connection, onEditHost = { … }) }  // 现状保留
        composable(Screen.Home.route) {
            HomeScreen(
                connection = connection,
                approvalCenter = approvalCenter,
                onPending = { navController.navigate(Screen.Pending.route) },
                onSessionClick = { id -> navController.navigate(Screen.Session.createRoute(id)) },
                onSettings = { navController.navigate(Screen.Settings.route) },
                onUpgrade = { navController.navigate(Screen.Pro.route) },
                onDisconnect = { onUserDisconnect() },
            )
        }
        composable(
            route = Screen.Session.route,
            arguments = listOf(
                navArgument("sessionId") { type = NavType.StringType },
                navArgument("focusSeq") { type = NavType.StringType; defaultValue = "" },
            ),
        ) {
            SessionScreen(
                sessionId = it.arguments?.getString("sessionId") ?: "",
                connection = connection,
                approvalCenter = approvalCenter,
                focusSeq = it.arguments?.getString("focusSeq")?.toLongOrNull(),
                onBack = { navController.popBackStack() },
                onPending = { navController.navigate(Screen.Pending.route) },
            )
        }
        composable(Screen.Pending.route) {
            PendingScreen(
                center = approvalCenter,
                connection = connection,
                onBack = { navController.popBackStack() },
                onOpenSession = { sid, seq -> navController.navigate(Screen.Session.createRoute(sid, seq)) },
            )
        }
```
（SessionScreen 的 `approvalCenter`/`focusSeq`/`onPending` 新参数在 Task 6 才添加——本任务先加路由参数并传参会导致编译失败。**处理**：本任务 Step 3 同时给 SessionScreen 加"空实现"参数（`approvalCenter: ApprovalCenter`、`focusSeq: Long?`、`onPending: () -> Unit`，函数体暂不引用或引用 approvalCenter 为空实现），Task 6 再填充语义。若实现者认为两次改 SessionScreen 签名低效，可将 Step 3 的 Session 部分推迟到 Task 6 一并做，但本任务必须先保证 Home/Pending 两路由编译通过——以编译通过为准选择。）

- [ ] **Step 4: HomeScreen 铃铛徽章**

`HomeScreen.kt`：
- 签名加 `approvalCenter: ApprovalCenter` 与 `onPending: () -> Unit`。
- 顶栏（设置齿轮同排）加：
```kotlin
        val pendingCount by approvalCenter.pendingCount.collectAsState()
        // 顶栏行内（设置按钮旁）：
        IconButton(onClick = onPending) {
            BadgedBox(badge = {
                if (pendingCount > 0) Badge { Text(pendingCount.toString()) }
            }) {
                Icon(Icons.Default.Notifications, contentDescription = "待办中心")
            }
        }
```
（import：`androidx.compose.material.icons.filled.Notifications`、`androidx.compose.material3.Badge`/`BadgedBox`。顶栏具体位置：HomeScreen 现有顶栏/品牌行——若 DeepLook 样式下无独立顶栏，把铃铛放在设置入口同层级最右侧；实现时以现有设置按钮位置为锚。）

- [ ] **Step 5: 编译 + 单测**

Run: `cd C:\hremote; .\gradlew.bat :app:compileDebugKotlin` 与 `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL、单测无回归。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/DshApplication.kt app/src/main/java/com/dsh/mobile/MainActivity.kt app/src/main/java/com/dsh/mobile/ui/navigation/AppNavigation.kt app/src/main/java/com/dsh/mobile/ui/screens/HomeScreen.kt
git commit -m "feat(s2): 接线——ApprovalCenter 单例、Pending 路由、首页铃铛徽章"
```

---

### Task 6: SessionScreen 收编 + focusSeq 定位

**Files:**
- Modify: `app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt`

**Interfaces:**
- Consumes: Task 2/3/5 的 `ApprovalCenter`、共享 `QuestionCard`、路由参数。
- Produces: SessionScreen 新签名 `SessionScreen(sessionId, connection, approvalCenter, focusSeq: Long?, onBack, onPending)`；轻提示条；focusSeq 滚动定位。

- [ ] **Step 1: 移除旧横幅与问答处理**

按 spec §5.4：删除 `SessionScreen.kt` 的
- `var approval`/`var questions` 局部状态（641-642 行附近）及 `LaunchedEffect(…)` 键中的 `approval, questions` 依赖（747 行）；
- `answerApproval`/`answerQuestions` 本地函数（813-830 行附近）；
- 审批横幅块（991-1040 行附近）与内联 `questions?.let { … }` 块（1040-1061 行附近）；
- 私有 `QuestionCard` 定义（Task 3 已移走）与事件收集里对 approval/questions 状态的赋值（698-708 行附近——保留事件收集中与本任务无关的分支）。

- [ ] **Step 2: 轻提示条**

顶部（消息列表上方，与现有顶部工具栏同级）加：
```kotlin
    val centerItems by approvalCenter.items.collectAsState()
    val myPending = centerItems.count {
        it.sessionId == sessionId && (it is PendingItem.Approval || it is PendingItem.Question)
    }
    if (myPending > 0) {
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
```

- [ ] **Step 3: focusSeq 定位**

`SessionScreen` 签名加 `focusSeq: Long?`；在历史/消息加载完成的现有滚动逻辑之后追加：
```kotlin
    var highlightedSeq by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(items, focusSeq) {
        val target = focusSeq ?: return@LaunchedEffect
        if (items.none { /* 该项承载事件 seq == target */ it.seq == target }) {
            if (items.isNotEmpty()) { /* 停在顶部：滚动到 index 0 */ }
            return@LaunchedEffect
        }
        // 找到包含该 seq 的 item index → 滚动定位 + 高亮 1.5s
        highlightedSeq = target
        delay(1500)
        highlightedSeq = null
    }
```
（具体 item 结构与滚动 API 以 SessionScreen 现状为准：items 是会话消息/事件列表，`focusSeq` 匹配方式——若列表项直接暴露事件 seq 则直接比对；否则遍历 `history` 源数据把 seq 映射到 index。实现者读现有代码后落地，必要时在报告里说明采用的映射方式。未命中降级：停在顶部 + snackbar「目标位置不在已加载窗口」。）

- [ ] **Step 4: 编译 + 单测**

Run: `cd C:\hremote; .\gradlew.bat :app:compileDebugKotlin` 与 `.\gradlew.bat :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL、无回归。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/dsh/mobile/ui/screens/SessionScreen.kt
git commit -m "feat(s2): 会话页收编为轻提示 + focusSeq 定位"
```

---

### Task 7: 全量构建 + 验收清单 + 收尾

**Files:**
- Modify: `README.md`（更新日志加 S2 条目）

- [ ] **Step 1: 全量验证**

Run: `cd C:\hremote; .\gradlew.bat :app:testDebugUnitTest` 与 `.\gradlew.bat :app:assembleDebug`
Expected: 全部单测（现有 30 + S2 新增 15 = 45 项左右）PASS；APK 产出。

- [ ] **Step 2: 真机验收清单（记录进报告，不需真机执行）**

按 spec §8 抄录验收清单：徽章计数、单项/批量应答、问答内联与跳过、异常跳转定位、重启恢复（最近 5 会话、问答不恢复为预期）、轻提示条、后台通知联动回归、空态。

- [ ] **Step 3: README 更新日志**

`README.md` 顶部更新日志加一条（版本号 +1 由你按仓库惯例处理，或先不加版本只加条目）：
```
- **v?.?.?**：统一待办审批中心（S2）——首页铃铛待办徽章、三档待办页（审批/问答/异常）、批量允许/拒绝/跳过、点击跳转会话定位（异常带 seq）、会话内收编为轻提示、重启后扫描最近 5 会话恢复未决审批与报错
```

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs(s2): README 更新日志——统一待办审批中心"
```

---

## Self-Review 记录（计划作者自查）

- **Spec 覆盖**：§5.1→Task 1；§5.2→Task 2；§5.3→Task 4；§5.4→Task 6 Step 1-2；§5.5→Task 5 + Task 6 Step 3；§5.6→Task 3；§6 事件形状→Task 1 扫描实现；§7 降级→Task 2 recover/批量容错；§8→Task 7；§9 风险表处置在 Task 2/4/6 落地。无缺口。
- **占位符扫描**：无 TBD/TODO；Task 6 Step 3 的 item 结构以现状为准的表述属实现指引（附映射方式说明要求），非占位。
- **类型一致性**：`PendingItem.Approval/Question/Error` 字段跨任务一致；`ApprovalCenter` 主构造签名 Task 2 定义、Task 4/5/6 引用一致；`QuestionCard(questions, onSubmit, onSkip)` Task 3 定义、Task 4 使用一致；`sessionTitleOf` Task 3 定义、Task 4/5 使用一致；`Screen.Session.createRoute(sessionId, focusSeq)` Task 5 定义、Task 4（回调签名 `(String, Long?) -> Unit`）一致。
- **执行注意**：Task 5 的 SessionScreen 新参数与 Task 6 的填充存在先后依赖（Task 5 Step 3 已注明两种处理方式，以编译通过为准）；所有任务 commit 精确 add；并行 agent 活动风险沿用 S1 的暂停协议。
