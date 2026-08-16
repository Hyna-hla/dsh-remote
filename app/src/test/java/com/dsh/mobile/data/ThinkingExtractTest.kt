package com.dsh.mobile.data

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S5 思考链展示（Task 1）：从事件 data 提取思考文本的纯函数——
 * reasoningDeltaOf（assistant/chunk 流式增量）+ reasoningBlockOf（assistant/message 完整兜底）。
 */
class ThinkingExtractTest {

    // ── reasoningDeltaOf：data.chunk.type == "reasoning-delta" → chunk.text，否则 null ──

    @Test
    fun reasoningDeltaExtractsChunkText() {
        val data = buildJsonObject {
            put("chunk", buildJsonObject {
                put("type", "reasoning-delta")
                put("text", "先分析用户意图")
            })
        }
        assertEquals("先分析用户意图", reasoningDeltaOf(data))
    }

    @Test
    fun emptyTextDeltaStillReturnsDelta() {
        val data = buildJsonObject {
            put("chunk", buildJsonObject {
                put("type", "reasoning-delta")
                put("text", "")
            })
        }
        assertEquals("", reasoningDeltaOf(data))
    }

    @Test
    fun textDeltaReturnsNull() {
        val data = buildJsonObject {
            put("chunk", buildJsonObject {
                put("type", "text-delta")
                put("text", "正文增量")
            })
        }
        assertNull(reasoningDeltaOf(data))
    }

    @Test
    fun plainStringChunkReturnsNull() {
        val data = buildJsonObject { put("chunk", "直接文本") }
        assertNull(reasoningDeltaOf(data))
    }

    @Test
    fun missingChunkReturnsNull() {
        val data = buildJsonObject { put("other", 1) }
        assertNull(reasoningDeltaOf(data))
    }

    @Test
    fun missingTypeReturnsNull() {
        val data = buildJsonObject {
            put("chunk", buildJsonObject { put("text", "无类型") })
        }
        assertNull(reasoningDeltaOf(data))
    }

    @Test
    fun missingTextReturnsNull() {
        val data = buildJsonObject {
            put("chunk", buildJsonObject { put("type", "reasoning-delta") })
        }
        assertNull(reasoningDeltaOf(data))
    }

    @Test
    fun nullDataReturnsNull() {
        assertNull(reasoningDeltaOf(null))
        assertNull(reasoningDeltaOf(JsonNull))
    }

    // ── reasoningBlockOf：data.message.content[] 中 type == "reasoning" 的块文本按序拼接；无则 null ──

    @Test
    fun reasoningBlocksJoinedInOrder() {
        val data = assistantMessage(
            block("text", "你好"),
            block("reasoning", "第一段思考"),
            block("reasoning", "第二段思考"),
            block("text", "再见"),
        )
        assertEquals("第一段思考\n第二段思考", reasoningBlockOf(data))
    }

    @Test
    fun singleReasoningBlockExtracted() {
        val data = assistantMessage(block("reasoning", "唯一思考"))
        assertEquals("唯一思考", reasoningBlockOf(data))
    }

    @Test
    fun reasoningBlockValueFieldFallback() {
        val data = buildJsonObject {
            put("message", buildJsonObject {
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "reasoning")
                                put("value", "value 字段的思考")
                            },
                        )
                    },
                )
            })
        }
        assertEquals("value 字段的思考", reasoningBlockOf(data))
    }

    @Test
    fun noReasoningBlockReturnsNull() {
        val data = assistantMessage(block("text", "无思考"))
        assertNull(reasoningBlockOf(data))
    }

    @Test
    fun emptyContentReturnsNull() {
        val data = assistantMessage()
        assertNull(reasoningBlockOf(data))
    }

    @Test
    fun missingMessageReturnsNull() {
        val data = buildJsonObject { put("other", 1) }
        assertNull(reasoningBlockOf(data))
    }

    @Test
    fun contentWithoutMessageFallsBackToRoot() {
        val data = buildJsonObject {
            put("content", buildJsonArray { add(block("reasoning", "根级思考")) })
        }
        assertEquals("根级思考", reasoningBlockOf(data))
    }

    @Test
    fun nullDataReturnsNullForBlock() {
        assertNull(reasoningBlockOf(null))
        assertNull(reasoningBlockOf(JsonNull))
    }

    // ── 辅助 ──

    /** 构造 assistant/message 事件 data：{message: {content: [...]}} */
    private fun assistantMessage(vararg blocks: JsonObject) = buildJsonObject {
        put("message", buildJsonObject {
            put("content", buildJsonArray { blocks.forEach { add(it) } })
        })
    }

    /** 单内容块 {type, text} */
    private fun block(type: String, text: String) = buildJsonObject {
        put("type", type)
        put("text", text)
    }
}
