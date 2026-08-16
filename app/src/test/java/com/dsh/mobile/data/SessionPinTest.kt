package com.dsh.mobile.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPinTest {

    private fun session(id: String, updatedAt: Long) = SessionSummary(
        sessionId = id,
        updatedAt = updatedAt,
        // 排序只读 sessionId/updatedAt，projections 不影响结果
        projections = JsonPrimitive("{}"),
    )

    // ── sortSessionsWithPinned ──

    @Test
    fun pinnedSessionsComeFirst() {
        val list = listOf(session("a", 100), session("b", 200), session("c", 300))
        val sorted = sortSessionsWithPinned(list, setOf("b"))
        assertEquals(listOf("b", "c", "a"), sorted.map { it.sessionId })
    }

    @Test
    fun pinnedGroupSortedDescByUpdatedAt() {
        val list = listOf(
            session("p1", 100), session("p2", 300), session("p3", 200),
            session("n1", 500), session("n2", 400),
        )
        val sorted = sortSessionsWithPinned(list, setOf("p1", "p2", "p3"))
        assertEquals(listOf("p2", "p3", "p1", "n1", "n2"), sorted.map { it.sessionId })
    }

    @Test
    fun emptyPinnedIdsEqualsPlainUpdatedAtSort() {
        val list = listOf(session("a", 100), session("b", 300), session("c", 200))
        val sorted = sortSessionsWithPinned(list, emptySet())
        assertEquals(
            list.sortedByDescending { it.updatedAt }.map { it.sessionId },
            sorted.map { it.sessionId },
        )
    }

    @Test
    fun unknownPinnedIdIgnored() {
        val list = listOf(session("a", 100), session("b", 200))
        val sorted = sortSessionsWithPinned(list, setOf("ghost"))
        assertEquals(listOf("b", "a"), sorted.map { it.sessionId })
    }

    @Test
    fun originalListNotMutated() {
        val list = listOf(session("a", 100), session("b", 200))
        sortSessionsWithPinned(list, setOf("a"))
        assertEquals(listOf("a", "b"), list.map { it.sessionId })
    }

    // ── togglePinned ──

    @Test
    fun toggleAddsWhenAbsent() {
        assertEquals(setOf("a"), togglePinned(emptySet(), "a"))
    }

    @Test
    fun toggleRemovesWhenPresent() {
        assertEquals(setOf("b"), togglePinned(setOf("a", "b"), "a"))
    }

    @Test
    fun toggleTwiceRestoresOriginal() {
        val ids = setOf("a", "b", "c")
        assertEquals(ids, togglePinned(togglePinned(ids, "a"), "a"))
    }

    // ── encode/decode（SettingsStore 存储格式：JSON 数组）──

    @Test
    fun encodeDecodeRoundTrip() {
        val ids = setOf("s1", "s2")
        assertEquals(ids, decodePinnedIds(encodePinnedIds(ids)))
    }

    @Test
    fun decodeBlankReturnsEmpty() {
        assertEquals(emptySet<String>(), decodePinnedIds(null))
        assertEquals(emptySet<String>(), decodePinnedIds(""))
        assertEquals(emptySet<String>(), decodePinnedIds("   "))
    }

    @Test
    fun decodeGarbageReturnsEmptyWithoutThrowing() {
        assertEquals(emptySet<String>(), decodePinnedIds("{{{not json"))
        assertEquals(emptySet<String>(), decodePinnedIds("12345"))
    }

    @Test
    fun decodeDedupes() {
        assertEquals(setOf("a", "b"), decodePinnedIds("""["a","b","a"]"""))
    }
}
