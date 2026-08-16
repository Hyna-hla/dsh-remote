package com.dsh.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 文件内容只读预览（Task 4）：parseFilePreview——
 * 解析插件 fs/read 响应 {ok, path, size, truncated, isBinary, text|data} 为 FilePreview；
 * ok=false / 非法 → null；size/truncated/isBinary 缺省 0/false/false；二进制时 text=null（data 不进入模型）。
 */
class FilePreviewTest {

    @Test
    fun normalTextParsesAllFields() {
        val data = buildJsonObject {
            put("ok", true)
            put("path", "C:\\proj\\notes.md")
            put("size", 12345)
            put("truncated", false)
            put("isBinary", false)
            put("text", "hello 世界\nline2")
        }
        val pv = parseFilePreview(data)
        assertEquals("C:\\proj\\notes.md", pv?.path)
        assertEquals(12345L, pv?.size)
        assertTrue(pv?.truncated == false)
        assertTrue(pv?.isBinary == false)
        assertEquals("hello 世界\nline2", pv?.text)
    }

    @Test
    fun binaryBranchTextNull() {
        // 二进制：服务端返回 data（base64），无 text 字段 → text=null、isBinary=true
        val data = buildJsonObject {
            put("ok", true)
            put("path", "C:\\proj\\logo.png")
            put("size", 2048)
            put("truncated", false)
            put("isBinary", true)
            put("data", "iVBORw0KGgoAAAANSUhEUg==")
        }
        val pv = parseFilePreview(data)
        assertEquals("C:\\proj\\logo.png", pv?.path)
        assertEquals(2048L, pv?.size)
        assertTrue(pv?.isBinary == true)
        assertNull(pv?.text)
    }

    @Test
    fun explicitNullTextTreatedAsNoText() {
        val data = buildJsonObject {
            put("ok", true)
            put("path", "/tmp/x.bin")
            put("size", 99)
            put("isBinary", true)
            put("text", JsonNull)
        }
        val pv = parseFilePreview(data)
        assertTrue(pv?.isBinary == true)
        assertNull(pv?.text)
    }

    @Test
    fun truncatedFlagParsed() {
        val data = buildJsonObject {
            put("ok", true)
            put("path", "C:\\big.log")
            put("size", 3 * 1024 * 1024)
            put("truncated", true)
            put("isBinary", false)
            put("text", "head...")
        }
        val pv = parseFilePreview(data)
        assertTrue(pv?.truncated == true)
        assertEquals(3L * 1024 * 1024, pv?.size)
    }

    @Test
    fun missingOptionalFieldsDefault() {
        // 只有 path：size=0、truncated=false、isBinary=false、text=null
        val pv = parseFilePreview(buildJsonObject { put("ok", true); put("path", "C:\\only") })
        assertEquals("C:\\only", pv?.path)
        assertEquals(0L, pv?.size)
        assertTrue(pv?.truncated == false)
        assertTrue(pv?.isBinary == false)
        assertNull(pv?.text)
    }

    @Test
    fun okFalseReturnsNull() {
        val data = buildJsonObject {
            put("ok", false)
            put("error", "no such file")
            put("path", "C:\\missing.txt")
        }
        assertNull(parseFilePreview(data))
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(parseFilePreview(null))
        assertNull(parseFilePreview(JsonNull))
        assertNull(parseFilePreview(JsonPrimitive("not-an-object")))
        assertNull(parseFilePreview(buildJsonArray { add(JsonPrimitive(1)) }))
        // 缺 path → 非法
        assertNull(parseFilePreview(buildJsonObject {
            put("ok", true)
            put("size", 10)
        }))
        // path 非字符串 → 非法
        assertNull(parseFilePreview(buildJsonObject {
            put("ok", true)
            put("path", JsonPrimitive(42))
        }))
    }

    @Test
    fun missingOkTolerated() {
        // 宽容：ok 缺失按成功处理（路径与内容合法时仍可解析）
        val pv = parseFilePreview(buildJsonObject {
            put("path", "C:\\t.txt")
            put("size", 7)
            put("text", "content")
        })
        assertEquals("C:\\t.txt", pv?.path)
        assertEquals("content", pv?.text)
    }

    @Test
    fun nonStringTextIgnored() {
        // text 为数字等非字符串形态 → 按 null 处理（不抛异常）
        val pv = parseFilePreview(buildJsonObject {
            put("ok", true)
            put("path", "C:\\x")
            put("text", JsonPrimitive(123))
        })
        assertEquals("C:\\x", pv?.path)
        assertNull(pv?.text)
    }

    @Test
    fun largeSizeFitsLong() {
        val data = buildJsonObject {
            put("ok", true)
            put("path", "C:\\huge.bin")
            put("size", 5_000_000_000L)
            put("isBinary", true)
        }
        val pv = parseFilePreview(data)
        assertEquals(5_000_000_000L, pv?.size)
    }
}
