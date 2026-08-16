package com.dsh.mobile.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S5 MCP 服务列表（Task 7）：parseMcpList——
 * 解析插件 mcp/list 响应 {ok, servers:[{serverName, tools[], status}]} 为 McpServer 列表；
 * ok=false / 非法 → 空列表；tools 缺省 → 空列表；status 缺省 → "unknown"；
 * serverName 必填（缺失/非字符串 → 跳过该条目）。
 */
class McpListTest {

    @Test
    fun normalParsesServersWithTools() {
        val data = buildJsonObject {
            put("ok", true)
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("serverName", "github")
                    put("tools", buildJsonArray {
                        add(JsonPrimitive("mcp__github__search_repos"))
                        add(JsonPrimitive("mcp__github__list_issues"))
                    })
                    put("status", "unknown")
                })
                add(buildJsonObject {
                    put("serverName", "filesystem")
                    put("tools", buildJsonArray {
                        add(JsonPrimitive("mcp__filesystem__read_file"))
                    })
                    put("status", "unknown")
                })
            })
        }
        val servers = parseMcpList(data)
        assertEquals(2, servers.size)
        assertEquals(McpServer("github", listOf("mcp__github__search_repos", "mcp__github__list_issues"), "unknown"), servers[0])
        assertEquals(McpServer("filesystem", listOf("mcp__filesystem__read_file"), "unknown"), servers[1])
    }

    @Test
    fun emptyServersReturnsEmpty() {
        // 插件可用但无 mcp 工具 → 空 servers
        val data = buildJsonObject {
            put("ok", true)
            put("servers", buildJsonArray { })
        }
        assertTrue(parseMcpList(data).isEmpty())
    }

    @Test
    fun invalidInputReturnsEmpty() {
        assertTrue(parseMcpList(null).isEmpty())
        assertTrue(parseMcpList(JsonNull).isEmpty())
        assertTrue(parseMcpList(JsonPrimitive("not-an-object")).isEmpty())
        assertTrue(parseMcpList(buildJsonArray { add(JsonPrimitive(1)) }).isEmpty())
        // 缺 servers 字段 → 空
        assertTrue(parseMcpList(buildJsonObject { put("ok", true) }).isEmpty())
        // servers 非数组 → 空
        assertTrue(parseMcpList(buildJsonObject {
            put("ok", true)
            put("servers", "oops")
        }).isEmpty())
    }

    @Test
    fun okFalseReturnsEmpty() {
        val data = buildJsonObject {
            put("ok", false)
            put("error", "tools service unavailable")
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("serverName", "github")
                    put("tools", buildJsonArray { add(JsonPrimitive("mcp__github__search_repos")) })
                })
            })
        }
        assertTrue(parseMcpList(data).isEmpty())
    }

    @Test
    fun missingStatusDefaultsToUnknown() {
        val data = buildJsonObject {
            put("ok", true)
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("serverName", "github")
                    put("tools", buildJsonArray { add(JsonPrimitive("mcp__github__search_repos")) })
                })
                add(buildJsonObject {
                    put("serverName", "filesystem")
                    put("tools", buildJsonArray { add(JsonPrimitive("mcp__filesystem__read_file")) })
                    put("status", "connected") // 显式 status 原样保留
                })
            })
        }
        val servers = parseMcpList(data)
        assertEquals(2, servers.size)
        assertEquals("unknown", servers[0].status)
        assertEquals("connected", servers[1].status)
    }

    @Test
    fun missingFieldsTolerated() {
        // tools 缺失 → 空工具列表；status 缺失 → unknown
        val servers = parseMcpList(buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject { put("serverName", "minimal") })
            })
        })
        assertEquals(listOf(McpServer("minimal", emptyList(), "unknown")), servers)
        // tools 非数组 → 空工具列表
        val badTools = parseMcpList(buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("serverName", "bad-tools")
                    put("tools", "not-an-array")
                })
            })
        })
        assertEquals(listOf(McpServer("bad-tools", emptyList(), "unknown")), badTools)
    }

    @Test
    fun nonStringToolEntriesFiltered() {
        val servers = parseMcpList(buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("serverName", "github")
                    put("tools", buildJsonArray {
                        add(JsonPrimitive("mcp__github__search_repos"))
                        add(JsonPrimitive(42))       // 非字符串 → 过滤
                        add(JsonNull)                // null → 过滤
                        add(buildJsonObject { put("name", "x") }) // 非 primitive → 过滤
                    })
                })
            })
        })
        assertEquals(listOf(McpServer("github", listOf("mcp__github__search_repos"), "unknown")), servers)
    }

    @Test
    fun missingServerNameSkipped() {
        val servers = parseMcpList(buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("tools", buildJsonArray { add(JsonPrimitive("mcp__x__y")) }) // 缺 serverName → 跳过
                })
                add(buildJsonObject {
                    put("serverName", 42) // serverName 非字符串 → 跳过
                    put("tools", buildJsonArray { add(JsonPrimitive("mcp__x__y")) })
                })
                add(buildJsonObject { put("serverName", "ok-server") }) // 合法 → 保留
            })
        })
        assertEquals(listOf(McpServer("ok-server", emptyList(), "unknown")), servers)
    }
}
