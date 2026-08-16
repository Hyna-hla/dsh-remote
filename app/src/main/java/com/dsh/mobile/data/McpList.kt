package com.dsh.mobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** 插件 mcp/list 单个 MCP 服务：serverName 必填；tools[] 缺省空；status 缺省 "unknown" */
data class McpServer(val serverName: String, val tools: List<String>, val status: String)

/**
 * 解析插件 mcp/list 响应 {ok, servers:[{serverName, tools[], status}]} → McpServer 列表。
 * - null / JsonNull / 非对象 / ok=false / 缺 servers 或 servers 非数组 → 空列表（ok 缺失宽容）；
 * - 条目缺 serverName 或 serverName 非字符串 → 跳过该条目；
 * - tools 缺失/非数组 → 空工具列表；tools 中非字符串元素过滤；
 * - status 缺失 → "unknown"（上游无连接态 API，侦察 §2.2 恒为 unknown）。
 */
internal fun parseMcpList(data: JsonElement?): List<McpServer> {
    if (data == null || data is JsonNull) return emptyList()
    val obj = data as? JsonObject ?: return emptyList()
    if (obj["ok"]?.jsonPrimitiveOrNull()?.booleanOrNull == false) return emptyList()
    val arr = obj["servers"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val serverName = o["serverName"]?.stringOrNull() ?: return@mapNotNull null
        val tools = (o["tools"] as? JsonArray)
            ?.mapNotNull { it.stringOrNull() }
            ?: emptyList()
        val status = o["status"]?.stringOrNull() ?: "unknown"
        McpServer(serverName, tools, status)
    }
}

/** 宽松转 JsonPrimitive：对象/数组等非 primitive → null（不外抛） */
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

/** 宽松取字符串值：仅字符串 primitive 取内容；数字/布尔/JsonNull/非 primitive → null */
private fun JsonElement.stringOrNull(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else null
    else -> null
}
