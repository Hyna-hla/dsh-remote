package com.dsh.mobile.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.put
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
        assertEquals(5, c.items.value.size) // s2/s3/s4/s6 的 asked + s5 实时（s5 历史与实时同 key，只留实时）
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
    fun historyFailureIsSilent() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        h.sessions = {
            listOf(
                SessionSummary(sessionId = "s1", updatedAt = 1L),
                SessionSummary(sessionId = "s2", updatedAt = 2L),
            )
        }
        h.history = { sid ->
            if (sid == "s2") throw RuntimeException("boom")
            HistoryValue(events = listOf(HistoryEntry(event = SessionEventWire(
                "approval/asked", 1, 1,
                kotlinx.serialization.json.buildJsonObject { put("id", "a1"); put("toolName", "bash") },
            ))))
        }
        val c = center(h, backgroundScope)
        state.value = DshConnection.State.Connected("http://x")
        assertEquals(1, c.items.value.size) // s1 照常合并，s2 静默跳过
        assertEquals("s1", (c.items.value[0] as PendingItem.Approval).sessionId)
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
    fun resolvedThenFailureDoesNotResurrect() = runTest(UnconfinedTestDispatcher()) {
        val h = Harness()
        // 应答在乐观移除之后、回滚之前抛出；期间并发解决该项（墓碑生效）
        h.answerApproval = { _, _, _ ->
            events.emit(DshConnection.Event.ApprovalResolved("s1", "a1", "allowed-once"))
            throw ApiException("rpc down")
        }
        val c = center(h, backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", null, null))
        assertEquals(1, c.items.value.size)
        var failed = false
        try { c.allow("s1", "a1") } catch (e: Exception) { failed = true }
        assertTrue(failed)
        assertTrue(c.items.value.isEmpty()) // 墓碑阻止回滚复活
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

    @Test
    fun crossSessionSameApprovalIdIsolated() = runTest(UnconfinedTestDispatcher()) {
        val c = center(Harness(), backgroundScope)
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", null, null))
        events.emit(DshConnection.Event.ApprovalRequested("s2", "a1", "bash", null, null))
        assertEquals(2, c.items.value.size)
        // s1 重发同 approvalId 只替换 s1 的项，不影响 s2
        events.emit(DshConnection.Event.ApprovalRequested("s1", "a1", "bash", "r2", null))
        assertEquals(2, c.items.value.size)
        // 解决 s1 的 a1 只移除 s1，s2 保留
        events.emit(DshConnection.Event.ApprovalResolved("s1", "a1", "allowed-once"))
        assertEquals(1, c.items.value.size)
        assertEquals("s2", (c.items.value[0] as PendingItem.Approval).sessionId)
    }
}
