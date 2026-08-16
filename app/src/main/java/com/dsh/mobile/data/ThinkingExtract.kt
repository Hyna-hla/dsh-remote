package com.dsh.mobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * S5 思考链展示（Task 1）：从事件 data 提取思考文本的纯函数。
 * 事件流已下发两类思考载体，App 此前一律丢弃：
 * - assistant/chunk 的 `reasoning-delta` 增量（流式累积）；
 * - assistant/message 的 `reasoning` 内容块（完整消息兜底）。
 * 输入一律宽松处理（缺字段/形态异常 → null），不抛异常。
 */

/**
 * 从 assistant/chunk 事件 data 提取思考增量：data.chunk.type == "reasoning-delta" → chunk.text，否则 null。
 * 正文增量（text-delta / 直接文本）不属于思考，返回 null。
 */
internal fun reasoningDeltaOf(eventData: JsonElement?): String? {
    val chunk = (eventData as? JsonObject)?.get("chunk") as? JsonObject ?: return null
    if (chunk["type"]?.jsonPrimitive?.contentOrNull != "reasoning-delta") return null
    return chunk["text"]?.jsonPrimitive?.contentOrNull
}

/**
 * 从 assistant/message 事件 data 提取完整思考：data.message.content[] 中 type == "reasoning"
 * 的块文本按出现顺序拼接（\n 连接）；无 reasoning 块 / 缺 message / 缺 content → null。
 * 与 assistantTextOf 一致：无 message 时回退到根级 content（宽松容错）。
 */
internal fun reasoningBlockOf(eventData: JsonElement?): String? {
    val root = eventData as? JsonObject ?: return null
    val msg = root["message"] as? JsonObject ?: root
    val content = msg["content"] as? JsonArray ?: return null
    val sb = StringBuilder()
    var found = false
    for (el in content) {
        val o = el as? JsonObject ?: continue
        if (o["type"]?.jsonPrimitive?.contentOrNull != "reasoning") continue
        val text = o["text"]?.jsonPrimitive?.contentOrNull
            ?: o["value"]?.jsonPrimitive?.contentOrNull
        if (text != null) {
            if (found) sb.append("\n")
            sb.append(text)
            found = true
        }
    }
    return if (found) sb.toString() else null
}
