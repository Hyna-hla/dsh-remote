package com.dsh.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * S7 Task 6：日志本地导出纯函数。
 * 输入为 session.history 的原始 events[]（wire 原貌的 {type, seq, time, data} 对象，
 * 与 SessionEventWire 宽松兼容）；一律宽松解析，形态异常的事件跳过，绝不抛异常。
 */

/** 把 session.history 的原始 events[]（List<JsonElement>）转成可读 Markdown 文本 */
fun historyToMarkdown(sessionTitle: String?, events: List<JsonElement>): String {
    val sb = StringBuilder()
    sb.append("# ").append(sessionTitle?.takeIf { it.isNotBlank() } ?: "会话日志").append('\n')
    if (events.isEmpty()) {
        sb.append("\n（无内容）\n")
        return sb.toString()
    }
    for (raw in events) appendEventSection(sb, raw)
    return sb.toString()
}

/** 把 session.history 的原始 events[]（List<JsonElement>）序列化为 JSON 数组文本（pretty print） */
fun historyToJson(events: List<JsonElement>): String =
    Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), JsonArray(events))

private fun appendEventSection(sb: StringBuilder, raw: JsonElement) {
    val obj = raw as? JsonObject ?: return
    val type = obj["type"]?.jsonPrimitive?.contentOrNull ?: return
    val data = obj["data"] as? JsonObject ?: JsonObject(emptyMap())
    when (type) {
        DshEventTypes.USER_MESSAGE -> appendUserSection(sb, data)
        DshEventTypes.ASSISTANT_MESSAGE -> appendAssistantSection(sb, data)
        DshEventTypes.TOOL_CALL -> appendToolCallSection(sb, data)
        DshEventTypes.TOOL_RESULT -> appendToolResultSection(sb, data)
        // assistant/chunk（增量，被 assistant/message 全文覆盖）、session/title 及其它辅助事件不展示
        else -> {}
    }
}

/** 提取内容块数组（content[]）里的文本：text 块；type=="text" 且带 value 的块 */
private fun textBlocksOf(content: JsonElement?): String {
    val arr = content as? JsonArray ?: return ""
    val sb = StringBuilder()
    for (el in arr) {
        val o = el as? JsonObject ?: continue
        val text = when {
            o["text"] is JsonPrimitive && (o["text"] as JsonPrimitive).isString ->
                (o["text"] as JsonPrimitive).content
            o["type"]?.jsonPrimitive?.contentOrNull == "text" && o["value"] is JsonPrimitive &&
                (o["value"] as JsonPrimitive).isString ->
                (o["value"] as JsonPrimitive).content
            else -> null
        }
        if (!text.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
        }
    }
    return sb.toString()
}

private fun appendUserSection(sb: StringBuilder, data: JsonObject) {
    val text = textBlocksOf(data["content"])
    if (text.isBlank()) return // 纯图片等无文本的用户消息不产生空分节
    sb.append("\n## 👤 用户\n\n").append(text).append('\n')
}

private fun appendAssistantSection(sb: StringBuilder, data: JsonObject) {
    val msg = data["message"] as? JsonObject ?: data
    val text = textBlocksOf(msg["content"])
    val think = reasoningBlockOf(data)
    if (text.isBlank() && think.isNullOrBlank()) return
    sb.append("\n## 🤖 助手\n\n")
    if (!think.isNullOrBlank()) {
        sb.append("> 💭 ").append(think.replace("\n", "\n> ")).append("\n\n")
    }
    if (text.isNotBlank()) sb.append(text).append('\n')
}

private fun appendToolCallSection(sb: StringBuilder, data: JsonObject) {
    val name = data["name"]?.jsonPrimitive?.contentOrNull ?: "tool"
    val args = data["arguments"]?.jsonPrimitive?.contentOrNull
    sb.append("\n### 🔧 工具调用：").append(name).append('\n')
    if (!args.isNullOrBlank()) {
        sb.append("```json\n").append(args).append("\n```\n")
    }
}

private fun appendToolResultSection(sb: StringBuilder, data: JsonObject) {
    val msg = data["message"] as? JsonObject ?: return
    val content = msg["content"] as? JsonArray ?: return
    for (el in content) {
        val o = el as? JsonObject ?: continue
        if (o["type"]?.jsonPrimitive?.contentOrNull != "tool-result") continue
        val isError = o["isError"]?.jsonPrimitive?.booleanOrNull ?: false
        val text = resultTextOf(o["content"])
        sb.append("\n### 📤 工具结果").append(if (isError) "（失败）" else "").append('\n')
        if (text.isNotBlank()) {
            sb.append("```text\n").append(text).append("\n```\n")
        }
        return
    }
}

/** 提取 tool-result 块的文本：直接字符串 / 文本块数组 */
private fun resultTextOf(content: JsonElement?): String = when (content) {
    is JsonArray -> content.mapNotNull { x ->
        when {
            x is JsonPrimitive && x.isString -> x.content
            x is JsonObject && x["text"] is JsonPrimitive && (x["text"] as JsonPrimitive).isString ->
                (x["text"] as JsonPrimitive).content
            else -> null
        }
    }.joinToString("\n")
    is JsonPrimitive -> content.contentOrNull ?: ""
    else -> ""
}
