package com.dsh.mobile.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * S5 thinkingText 落磁盘缓存（S8 Task 3）：
 * CachedItem.thinkingText 序列化/反序列化 round-trip + 旧缓存缺字段兼容（默认空串不崩）。
 * Json 配置与 HistoryCache 生产一致：ignoreUnknownKeys + encodeDefaults。
 */
class HistoryCacheTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ── 直接 Json 编解码 round-trip ──

    @Test
    fun thinkingTextRoundTripsThroughJson() {
        val item = CachedItem(
            kind = "assistant",
            text = "正文",
            thinkSeconds = 7,
            streaming = false,
            seq = 42,
            thinkingText = "先分析用户意图，再组织回答",
        )
        val decoded = json.decodeFromString<CachedItem>(json.encodeToString(item))
        assertEquals(item, decoded)
        assertEquals("先分析用户意图，再组织回答", decoded.thinkingText)
    }

    @Test
    fun emptyThinkingTextRoundTripsThroughJson() {
        val item = CachedItem(kind = "assistant", text = "无思考", thinkingText = "")
        val decoded = json.decodeFromString<CachedItem>(json.encodeToString(item))
        assertEquals(item, decoded)
        assertEquals("", decoded.thinkingText)
    }

    // ── 磁盘存储同款路径：CachedHistory 包一层 + gzip ──

    @Test
    fun thinkingTextSurvivesGzipCachedHistoryRoundTrip() {
        val history = CachedHistory(
            savedAt = 1_720_000_000_000,
            items = listOf(
                CachedItem(
                    kind = "assistant",
                    text = "缓存正文",
                    thinkSeconds = 3,
                    seq = 7,
                    thinkingText = "磁盘上的思考链",
                ),
                CachedItem(kind = "user", text = "问题"),
            ),
        )
        val restored = gzipRoundTrip(history)
        assertEquals(history, restored)
        assertEquals("磁盘上的思考链", restored.items[0].thinkingText)
        assertEquals("", restored.items[1].thinkingText)
    }

    // ── 旧缓存兼容：无 thinkingText 字段的旧 JSON 解码 → 默认空串不崩 ──

    @Test
    fun legacyJsonMissingThinkingTextDefaultsToEmpty() {
        // 旧格式磁盘 JSON（S8 之前无 thinkingText 字段）
        val legacy = """{"kind":"assistant","text":"旧消息","thinkSeconds":5,"seq":9}"""
        val decoded = json.decodeFromString<CachedItem>(legacy)
        assertEquals("旧消息", decoded.text)
        assertEquals(5L, decoded.thinkSeconds)
        assertEquals("", decoded.thinkingText)
    }

    @Test
    fun legacyHistoryJsonMissingThinkingTextDoesNotCrash() {
        // 旧 CachedHistory 整包：所有条目均无 thinkingText，解码后默认空串
        val legacy = """
            {"savedAt":1720000000000,"items":[
              {"kind":"assistant","text":"旧回复","thinkSeconds":2,"streaming":false,"seq":3},
              {"kind":"user","text":"旧提问","seq":2}
            ]}
        """.trimIndent()
        val decoded = json.decodeFromString<CachedHistory>(legacy)
        assertEquals(2, decoded.items.size)
        assertEquals("", decoded.items[0].thinkingText)
        assertEquals("", decoded.items[1].thinkingText)
    }

    // ── 辅助：HistoryCache.saveHistory/loadHistory 同款 gzip 编解码 ──

    private fun gzipRoundTrip(history: CachedHistory): CachedHistory {
        val payload = json.encodeToString(CachedHistory.serializer(), history)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        return GZIPInputStream(ByteArrayInputStream(bos.toByteArray())).use {
            json.decodeFromString<CachedHistory>(it.readBytes().toString(Charsets.UTF_8))
        }
    }
}
