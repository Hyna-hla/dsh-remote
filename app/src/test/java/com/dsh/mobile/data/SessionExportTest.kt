package com.dsh.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S7 Task 6：日志本地导出纯函数测试。
 * - historyToMarkdown：标题行、用户/助手消息分节、工具调用缩进、空 events、chunk 跳过；
 * - historyToJson：合法序列化 + round-trip。
 */
class SessionExportTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── 构造 wire 事件（与 session.history events[] 同形：{type, seq, time, data}）──

    private fun event(type: String, data: JsonObject = JsonObject(emptyMap())): JsonElement =
        buildJsonObject {
            put("type", type)
            put("seq", 1L)
            put("time", 1700000000000L)
            put("data", data)
        }

    private fun userEvent(text: String) = event(
        DshEventTypes.USER_MESSAGE,
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", text) })
                },
            )
        },
    )

    private fun assistantEvent(text: String) = event(
        DshEventTypes.ASSISTANT_MESSAGE,
        buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(buildJsonObject { put("type", "text"); put("text", text) })
                        },
                    )
                },
            )
        },
    )

    private fun toolCallEvent(name: String, args: String) = event(
        DshEventTypes.TOOL_CALL,
        buildJsonObject {
            put("name", name)
            put("arguments", args)
        },
    )

    private fun toolResultEvent(text: String, isError: Boolean = false) = event(
        DshEventTypes.TOOL_RESULT,
        buildJsonObject {
            put(
                "message",
                buildJsonObject {
                    put(
                        "content",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "tool-result")
                                    put("isError", isError)
                                    put("content", text)
                                },
                            )
                        },
                    )
                },
            )
        },
    )

    // ── historyToMarkdown ──

    @Test
    fun markdown_titleLine() {
        val md = historyToMarkdown("我的会话", listOf(userEvent("你好")))
        assertTrue("应以标题行开头", md.startsWith("# 我的会话\n"))
    }

    @Test
    fun markdown_nullOrBlankTitleFallsBack() {
        val mdNull = historyToMarkdown(null, listOf(userEvent("你好")))
        assertTrue(mdNull.startsWith("# 会话日志\n"))
        val mdBlank = historyToMarkdown("   ", listOf(userEvent("你好")))
        assertTrue(mdBlank.startsWith("# 会话日志\n"))
    }

    @Test
    fun markdown_userAndAssistantSections() {
        val md = historyToMarkdown("t", listOf(userEvent("问题一"), assistantEvent("回答一")))
        assertTrue("应含用户分节", md.contains("\n## 👤 用户\n\n问题一"))
        assertTrue("应含助手分节", md.contains("\n## 🤖 助手\n\n回答一"))
    }

    @Test
    fun markdown_toolCallSection() {
        val md = historyToMarkdown("t", listOf(toolCallEvent("read_file", "{\"path\":\"/tmp/a\"}")))
        assertTrue("应含工具调用小节", md.contains("### 🔧 工具调用：read_file"))
        assertTrue("参数应进 json 代码块", md.contains("```json\n{\"path\":\"/tmp/a\"}\n```"))
    }

    @Test
    fun markdown_toolResultSection() {
        val md = historyToMarkdown("t", listOf(toolResultEvent("文件内容")))
        assertTrue("应含工具结果小节", md.contains("### 📤 工具结果"))
        assertTrue("结果应进 text 代码块", md.contains("```text\n文件内容\n```"))
    }

    @Test
    fun markdown_toolResultErrorMarked() {
        val md = historyToMarkdown("t", listOf(toolResultEvent("权限不足", isError = true)))
        assertTrue("失败结果应有标记", md.contains("### 📤 工具结果（失败）"))
    }

    @Test
    fun markdown_emptyEvents() {
        val md = historyToMarkdown("t", emptyList())
        assertTrue(md.contains("# t"))
        assertTrue("空日志应有占位", md.contains("（无内容）"))
    }

    @Test
    fun markdown_chunkAndTitleEventsSkipped() {
        val md = historyToMarkdown(
            "t",
            listOf(
                event(
                    DshEventTypes.ASSISTANT_CHUNK,
                    buildJsonObject {
                        put(
                            "chunk",
                            buildJsonObject { put("type", "text-delta"); put("text", "增量") },
                        )
                    },
                ),
                event(DshEventTypes.SESSION_TITLE, buildJsonObject { put("title", "t") }),
            ),
        )
        assertFalse("chunk 不应产生正文", md.contains("增量"))
        assertFalse("标题事件不应产生分节", md.contains("## "))
    }

    @Test
    fun markdown_multilineTextPreserved() {
        val md = historyToMarkdown("t", listOf(userEvent("第一行\n第二行")))
        assertTrue(md.contains("第一行\n第二行"))
    }

    @Test
    fun markdown_userOnlyImagesSkipped() {
        // 纯图片用户消息（无文本块）不产生空分节
        val md = historyToMarkdown(
            "t",
            listOf(
                event(
                    DshEventTypes.USER_MESSAGE,
                    buildJsonObject {
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "image"); put("data", "AAAA") })
                            },
                        )
                    },
                ),
            ),
        )
        assertFalse(md.contains("## "))
    }

    // ── historyToJson ──

    @Test
    fun json_roundTripPreservesEvents() {
        val events = listOf(
            userEvent("你好"),
            assistantEvent("回答"),
            toolCallEvent("read_file", "{}"),
        )
        val out = historyToJson(events)
        val parsed = json.parseToJsonElement(out)
        assertEquals("序列化后应能解析回相同结构", JsonArray(events), parsed)
    }

    @Test
    fun json_emptyEventsIsEmptyArray() {
        val out = historyToJson(emptyList())
        assertEquals(JsonArray(emptyList()), json.parseToJsonElement(out))
    }

    @Test
    fun json_outputIsValidJsonArray() {
        val out = historyToJson(listOf(userEvent("你好")))
        assertTrue(out.trimStart().startsWith("["))
        val parsed = json.parseToJsonElement(out)
        assertTrue(parsed is JsonArray)
        assertEquals(1, (parsed as JsonArray).size)
    }
}
