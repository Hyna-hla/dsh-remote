package com.dsh.mobile.data

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSearchTest {

    private fun session(id: String, title: String? = null, preview: String? = null) = SessionSummary(
        sessionId = id,
        updatedAt = 1L,
        projections = buildJsonObject {
            put(
                "values",
                buildJsonObject {
                    title?.let { put("title", it) }
                    preview?.let { put("preview", it) }
                },
            )
        },
    )

    // ── filterSessions ──

    @Test
    fun titleHitMatches() {
        val list = listOf(session("a", title = "会议纪要"), session("b", title = "购物清单"))
        assertEquals(listOf("a"), filterSessions(list, "会议").map { it.sessionId })
    }

    @Test
    fun previewHitMatches() {
        val list = listOf(
            session("a", title = "会议纪要", preview = "讨论了 Q3 预算"),
            session("b", title = "购物清单", preview = "牛奶面包鸡蛋"),
        )
        assertEquals(listOf("a"), filterSessions(list, "预算").map { it.sessionId })
    }

    @Test
    fun matchingIsCaseInsensitive() {
        val list = listOf(session("a", title = "Meeting Notes", preview = "Q3 budget review"))
        assertEquals(listOf("a"), filterSessions(list, "meeting").map { it.sessionId })
        assertEquals(listOf("a"), filterSessions(list, "BUDGET").map { it.sessionId })
        assertEquals(listOf("a"), filterSessions(list, "NOTES").map { it.sessionId })
    }

    @Test
    fun blankQueryReturnsAll() {
        val list = listOf(session("a", title = "x"), session("b", title = "y"))
        assertSame(list, filterSessions(list, ""))
        assertEquals(list.map { it.sessionId }, filterSessions(list, "   ").map { it.sessionId })
    }

    @Test
    fun noHitReturnsEmpty() {
        val list = listOf(session("a", title = "会议纪要", preview = "Q3 预算"), session("b", title = "购物"))
        assertTrue(filterSessions(list, "不存在的关键词").isEmpty())
    }

    // ── 健壮性 ──

    @Test
    fun sessionsWithoutProjectionsDoNotCrash() {
        val plain = SessionSummary(sessionId = "p", updatedAt = 1L, projections = null)
        val list = listOf(plain, session("a", title = "匹配"))
        assertEquals(listOf("a"), filterSessions(list, "匹配").map { it.sessionId })
        assertTrue(filterSessions(list, "q").isEmpty())
    }

    @Test
    fun titleOrPreviewBothMatchStillListedOnce() {
        val list = listOf(session("a", title = "预算", preview = "预算讨论"))
        assertEquals(listOf("a"), filterSessions(list, "预算").map { it.sessionId })
    }
}
