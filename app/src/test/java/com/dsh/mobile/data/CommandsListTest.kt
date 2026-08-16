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
 * S7 slash 命令面板（Task 5）：parseCommandsList——
 * 解析 commands/list 响应 [{name, description, input:{hint}}] 为 SlashCommand 列表；
 * name 必填（缺失/非字符串 → 跳过该条目）；description / input.hint 可选（缺失 → null）；
 * 非法输入（null / 非数组 / 数组含非对象元素）→ 空列表。
 */
class CommandsListTest {

    @Test
    fun fullFieldsParsed() {
        val data = buildJsonArray {
            add(buildJsonObject {
                put("name", "permission")
                put("description", "切换审查严格度（访问模式）")
                put("input", buildJsonObject {
                    put("hint", "read-only | workspace-write | danger-full-access")
                })
            })
        }
        val commands = parseCommandsList(data)
        assertEquals(1, commands.size)
        assertEquals(
            SlashCommand(
                "permission",
                "切换审查严格度（访问模式）",
                "read-only | workspace-write | danger-full-access",
            ),
            commands[0],
        )
    }

    @Test
    fun multipleCommandsKeepOrder() {
        val data = buildJsonArray {
            add(buildJsonObject { put("name", "/permission") })
            add(buildJsonObject {
                put("name", "/export")
                put("description", "导出会话日志")
                put("input", buildJsonObject { put("hint", "保存到本地") })
            })
        }
        val commands = parseCommandsList(data)
        assertEquals(2, commands.size)
        assertEquals(SlashCommand("/permission", null, null), commands[0])
        assertEquals(SlashCommand("/export", "导出会话日志", "保存到本地"), commands[1])
    }

    @Test
    fun emptyArrayReturnsEmpty() {
        assertTrue(parseCommandsList(buildJsonArray { }).isEmpty())
    }

    @Test
    fun invalidInputReturnsEmpty() {
        assertTrue(parseCommandsList(null).isEmpty())
        assertTrue(parseCommandsList(JsonNull).isEmpty())
        assertTrue(parseCommandsList(JsonPrimitive("not-an-array")).isEmpty())
        assertTrue(parseCommandsList(buildJsonObject { put("name", "x") }).isEmpty())
        // 数组内含非对象元素 → 整体仍为空列表
        assertTrue(parseCommandsList(buildJsonArray { add(JsonPrimitive(1)) }).isEmpty())
    }

    @Test
    fun missingFieldsTolerated() {
        // 只有 name：description / hint 均为 null
        val minimal = parseCommandsList(buildJsonArray {
            add(buildJsonObject { put("name", "/retry") })
        })
        assertEquals(listOf(SlashCommand("/retry", null, null)), minimal)
        // 有 description 无 input
        val noInput = parseCommandsList(buildJsonArray {
            add(buildJsonObject {
                put("name", "/cancel")
                put("description", "取消当前运行")
            })
        })
        assertEquals(listOf(SlashCommand("/cancel", "取消当前运行", null)), noInput)
        // input 非对象 → hint null
        val badInput = parseCommandsList(buildJsonArray {
            add(buildJsonObject {
                put("name", "/x")
                put("input", "not-an-object")
            })
        })
        assertEquals(listOf(SlashCommand("/x", null, null)), badInput)
        // input 对象缺 hint → hint null
        val emptyInput = parseCommandsList(buildJsonArray {
            add(buildJsonObject {
                put("name", "/y")
                put("input", buildJsonObject { put("other", "1") })
            })
        })
        assertEquals(listOf(SlashCommand("/y", null, null)), emptyInput)
    }

    @Test
    fun missingNameSkipped() {
        val data = buildJsonArray {
            add(buildJsonObject { put("description", "无 name") })  // 缺 name → 跳过
            add(buildJsonObject { put("name", 42) })                // name 非字符串 → 跳过
            add(JsonPrimitive("plain string"))                      // 非对象 → 跳过
            add(buildJsonObject { put("name", "/ok") })             // 合法 → 保留
        }
        val commands = parseCommandsList(data)
        assertEquals(listOf(SlashCommand("/ok", null, null)), commands)
    }
}
