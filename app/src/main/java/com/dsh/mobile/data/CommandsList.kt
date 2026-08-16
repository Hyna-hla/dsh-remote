package com.dsh.mobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** commands/list 单个斜杠命令：name 必填；description / input.hint 可选（缺失 → null） */
data class SlashCommand(val name: String, val description: String?, val hint: String?)

/**
 * 解析 commands/list 响应 [{name, description, input:{hint}}] → SlashCommand 列表。
 * - 非法输入（null / JsonNull / 非数组 / 数组含非对象元素）→ 空列表；
 * - 条目缺 name 或 name 非字符串 → 跳过该条目；
 * - description / input.hint 缺失 → null（缺字段容错）。
 */
internal fun parseCommandsList(data: JsonElement?): List<SlashCommand> {
    if (data == null || data is JsonNull) return emptyList()
    val arr = data as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val name = o["name"]?.stringOrNull() ?: return@mapNotNull null
        val description = o["description"]?.stringOrNull()
        val hint = (o["input"] as? JsonObject)?.get("hint")?.stringOrNull()
        SlashCommand(name, description, hint)
    }
}

/** 宽松取字符串值：仅字符串 primitive 取内容；数字/布尔/JsonNull/非 primitive → null */
private fun JsonElement.stringOrNull(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else null
    else -> null
}
