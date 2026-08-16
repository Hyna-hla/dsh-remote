package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T5 快捷指令栏：encode/decode 纯函数 + 默认列表。
 * 存储格式：JSON 数组字符串（与 pinnedSessionIds 同套路）。
 */
class QuickPromptsTest {

    // ── encode ──

    @Test
    fun encodeEmptyListIsEmptyJsonArray() {
        assertEquals("[]", encodeQuickPrompts(emptyList()))
    }

    @Test
    fun encodeProducesJsonArrayOfStrings() {
        assertEquals(
            """["帮我总结当前会话","列出待办事项"]""",
            encodeQuickPrompts(listOf("帮我总结当前会话", "列出待办事项")),
        )
    }

    @Test
    fun encodeEscapesSpecialCharacters() {
        val encoded = encodeQuickPrompts(listOf("say \"hi\"", "a,b", "中文\n换行"))
        // 特殊字符（引号/逗号/换行）经 JSON 转义后应能无损往返
        assertEquals(listOf("say \"hi\"", "a,b", "中文\n换行"), decodeQuickPrompts(encoded))
    }

    // ── decode ──

    @Test
    fun encodeDecodeRoundTrip() {
        val items = listOf("帮我总结当前会话", "列出待办事项", "检查代码问题", "优化这段代码")
        assertEquals(items, decodeQuickPrompts(encodeQuickPrompts(items)))
    }

    @Test
    fun decodeNullAndBlankReturnsEmpty() {
        assertEquals(emptyList<String>(), decodeQuickPrompts(null))
        assertEquals(emptyList<String>(), decodeQuickPrompts(""))
        assertEquals(emptyList<String>(), decodeQuickPrompts("   "))
    }

    @Test
    fun decodeGarbageReturnsEmptyWithoutThrowing() {
        assertEquals(emptyList<String>(), decodeQuickPrompts("{{{not json"))
        assertEquals(emptyList<String>(), decodeQuickPrompts("12345"))
        assertEquals(emptyList<String>(), decodeQuickPrompts("""{"a":1}"""))
    }

    @Test
    fun decodePreservesOrderAndDuplicates() {
        // 列表语义：顺序与重复都保留（Set 去重不适用快捷指令）
        assertEquals(listOf("a", "b", "a"), decodeQuickPrompts("""["a","b","a"]"""))
    }

    // ── 默认列表 ──

    @Test
    fun defaultQuickPromptsHasFourBuiltinItemsInOrder() {
        assertEquals(
            listOf("帮我总结当前会话", "列出待办事项", "检查代码问题", "优化这段代码"),
            defaultQuickPrompts(),
        )
    }
}
