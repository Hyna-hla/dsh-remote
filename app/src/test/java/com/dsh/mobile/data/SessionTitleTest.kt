package com.dsh.mobile.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTitleTest {

    @Test
    fun normalTitleExtracted() {
        val session = SessionSummary(
            sessionId = "s1",
            updatedAt = 1L,
            projections = buildJsonObject {
                put("values", buildJsonObject { put("title", "我的会话") })
            },
        )
        assertEquals("我的会话", sessionTitleOf(session))
    }

    @Test
    fun nullProjectionsReturnsNull() {
        val session = SessionSummary(sessionId = "s1", updatedAt = 1L, projections = null)
        assertNull(sessionTitleOf(session))
    }

    @Test
    fun nonObjectProjectionsReturnsNullWithoutThrowing() {
        // projections 是畸形 wire 数据（非 object）：不得抛异常，应回退 null
        val session = SessionSummary(sessionId = "s1", updatedAt = 1L, projections = JsonPrimitive("x"))
        assertNull(sessionTitleOf(session))
    }

    @Test
    fun nonObjectValuesReturnsNullWithoutThrowing() {
        val session = SessionSummary(
            sessionId = "s1",
            updatedAt = 1L,
            projections = buildJsonObject { put("values", JsonPrimitive("x")) },
        )
        assertNull(sessionTitleOf(session))
    }

    @Test
    fun nonPrimitiveTitleReturnsNullWithoutThrowing() {
        val session = SessionSummary(
            sessionId = "s1",
            updatedAt = 1L,
            projections = buildJsonObject {
                put("values", buildJsonObject { put("title", buildJsonObject { put("x", 1) }) })
            },
        )
        assertNull(sessionTitleOf(session))
    }
}
