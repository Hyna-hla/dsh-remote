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
