package com.dsh.mobile.data

import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S6 目录列举直连（Task 3）：parseDirectoryList——
 * 解析 host.listDirectory 响应 {path, home, crumbs[], entries[{name,path,hidden}], truncated} 为扁平模型；
 * crumbs 宽容解析（对象取 name / 纯字符串直接收）；缺字段容错；非法 → null；空 entries 合法。
 * deriveCrumbsFromPath：插件回退时从绝对路径推导面包屑层级标签。
 */
class DirectoryListTest {

    @Test
    fun normalListingParsesAllFields() {
        val data = buildJsonObject {
            put("path", "C:\\AI\\proj")
            put("home", "C:\\Users\\me")
            put("crumbs", buildJsonArray {
                add(entry("C:\\", "C:\\"))
                add(entry("AI", "C:\\AI"))
                add(entry("proj", "C:\\AI\\proj"))
            })
            put("entries", buildJsonArray {
                add(entry("src", "C:\\AI\\proj\\src"))
                add(entry(".git", "C:\\AI\\proj\\.git", hidden = true))
            })
            put("truncated", true)
        }
        val listing = parseDirectoryList(data)
        assertEquals("C:\\AI\\proj", listing?.path)
        assertEquals(listOf("C:\\", "AI", "proj"), listing?.crumbs)
        assertEquals(2, listing?.entries?.size)
        assertEquals(DirEntry("src", "C:\\AI\\proj\\src", false), listing?.entries?.get(0))
        assertEquals(DirEntry(".git", "C:\\AI\\proj\\.git", true), listing?.entries?.get(1))
        assertTrue(listing?.truncated == true)
    }

    @Test
    fun crumbsAsPlainStringsAccepted() {
        val data = buildJsonObject {
            put("path", "/home/me")
            put("crumbs", buildJsonArray {
                add(JsonPrimitive("/"))
                add(JsonPrimitive("/home"))
                add(JsonPrimitive("/home/me"))
            })
            put("entries", buildJsonArray { add(entry("docs", "/home/me/docs")) })
            put("truncated", false)
        }
        val listing = parseDirectoryList(data)
        assertEquals(listOf("/", "/home", "/home/me"), listing?.crumbs)
        assertEquals("/home/me", listing?.path)
        assertEquals(1, listing?.entries?.size)
        assertTrue(listing?.truncated == false)
    }

    @Test
    fun missingOptionalFieldsDefault() {
        // 只有 path：crumbs/entries 空、truncated=false
        val listing = parseDirectoryList(buildJsonObject { put("path", "C:\\only") })
        assertEquals("C:\\only", listing?.path)
        assertEquals(emptyList<String>(), listing?.crumbs)
        assertEquals(emptyList<DirEntry>(), listing?.entries)
        assertTrue(listing?.truncated == false)
    }

    @Test
    fun hiddenMissingDefaultsFalse() {
        val data = buildJsonObject {
            put("path", "C:\\x")
            put("entries", buildJsonArray {
                add(buildJsonObject {
                    put("name", "pub")
                    put("path", "C:\\x\\pub")
                    // 无 hidden 字段
                })
            })
        }
        val listing = parseDirectoryList(data)
        assertEquals(DirEntry("pub", "C:\\x\\pub", false), listing?.entries?.get(0))
    }

    @Test
    fun truncatedAsNumberOrStringTolerated() {
        // 数字形态的 truncated 不抛异常，按 false 处理
        val numeric = parseDirectoryList(buildJsonObject {
            put("path", "C:\\n")
            put("truncated", 1)
        })
        assertTrue(numeric?.truncated == false)
        // 字符串 "true" 宽容解析为 true
        val str = parseDirectoryList(buildJsonObject {
            put("path", "C:\\s")
            put("truncated", "true")
        })
        assertTrue(str?.truncated == true)
    }

    @Test
    fun emptyEntriesParses() {
        val listing = parseDirectoryList(buildJsonObject {
            put("path", "C:\\empty")
            put("entries", buildJsonArray { })
            put("truncated", false)
        })
        assertEquals("C:\\empty", listing?.path)
        assertEquals(emptyList<DirEntry>(), listing?.entries)
    }

    @Test
    fun malformedEntriesSkipped() {
        val data = buildJsonObject {
            put("path", "C:\\x")
            put("entries", buildJsonArray {
                add(buildJsonObject { put("path", "C:\\x\\noname") })            // 缺 name → 跳过
                add(buildJsonObject { put("name", "nopath") })                   // 缺 path → 跳过
                add(JsonPrimitive("plain string"))                               // 非对象 → 跳过
                add(entry("ok", "C:\\x\\ok"))                                    // 合法 → 保留
            })
        }
        val listing = parseDirectoryList(data)
        assertEquals(1, listing?.entries?.size)
        assertEquals(DirEntry("ok", "C:\\x\\ok", false), listing?.entries?.get(0))
    }

    @Test
    fun invalidInputReturnsNull() {
        assertNull(parseDirectoryList(null))
        assertNull(parseDirectoryList(JsonNull))
        assertNull(parseDirectoryList(JsonPrimitive("not-an-object")))
        assertNull(parseDirectoryList(buildJsonArray { add(JsonPrimitive(1)) }))
        // 缺 path → 非法
        assertNull(parseDirectoryList(buildJsonObject { put("entries", buildJsonArray { }) }))
        // path 非字符串 → 非法
        assertNull(parseDirectoryList(buildJsonObject { put("path", JsonPrimitive(42)) }))
    }

    // ── 插件回退辅助：从绝对路径推导面包屑层级标签 ──

    @Test
    fun deriveCrumbsFromWindowsPath() {
        assertEquals(
            listOf("C:\\", "C:\\AI", "C:\\AI\\proj"),
            deriveCrumbsFromPath("C:\\AI\\proj"),
        )
    }

    @Test
    fun deriveCrumbsFromWindowsRoot() {
        assertEquals(listOf("C:\\"), deriveCrumbsFromPath("C:\\"))
        assertEquals(listOf("C:\\"), deriveCrumbsFromPath("C:"))
    }

    @Test
    fun deriveCrumbsFromPosixPath() {
        assertEquals(listOf("/home", "/home/me"), deriveCrumbsFromPath("/home/me"))
    }

    @Test
    fun deriveCrumbsFromEmptyOrBlank() {
        assertEquals(emptyList<String>(), deriveCrumbsFromPath(""))
        assertEquals(emptyList<String>(), deriveCrumbsFromPath("   "))
    }

    private fun entry(name: String, path: String, hidden: Boolean = false) = buildJsonObject {
        put("name", name)
        put("path", path)
        put("hidden", hidden)
    }
}
