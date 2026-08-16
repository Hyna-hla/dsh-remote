package com.dsh.mobile.data

import com.dsh.mobile.ui.screens.normalizeVoiceResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 语音识别结果回填输入栏前的最小清洗（T2）。
 * 约定：null/空白/空串 → null（不回填）；有效文本 → trim 后返回（内部空白保留）。
 */
class VoiceIntentTest {

    @Test
    fun nullRawReturnsNull() {
        assertNull(normalizeVoiceResult(null))
    }

    @Test
    fun emptyRawReturnsNull() {
        assertNull(normalizeVoiceResult(""))
    }

    @Test
    fun blankRawReturnsNull() {
        assertNull(normalizeVoiceResult("   "))
        assertNull(normalizeVoiceResult("\t\n  "))
    }

    @Test
    fun textIsTrimmed() {
        assertEquals("你好世界", normalizeVoiceResult("  你好世界  "))
    }

    @Test
    fun innerWhitespaceIsPreserved() {
        assertEquals("你好 世界", normalizeVoiceResult("你好 世界"))
        // trim 只去首尾空白，内部连续空格原样保留
        assertEquals("hello  world", normalizeVoiceResult(" hello  world "))
    }
}
