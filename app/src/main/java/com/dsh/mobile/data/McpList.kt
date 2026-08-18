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

// ---------- M3：MCP 资源/提示词能力清册（插件 mcp/resources|prompts list） ----------

/** 插件 mcp/resources/list 单个资源能力：uri 主键 */
data class McpResource(val uri: String, val name: String, val mimeType: String, val description: String)

/** 插件 mcp/resources/list 单个 MCP 服务 */
data class McpResourceServer(val serverName: String, val resources: List<McpResource>)

/** 插件 mcp/prompts/list 单条提示词（argumentsSchema 为可选 JSON schema 原始元素） */
data class McpPrompt(val name: String, val description: String, val argumentsSchema: JsonElement?)

/** 插件 mcp/prompts/list 单个 MCP 服务 */
data class McpPromptServer(val serverName: String, val prompts: List<McpPrompt>)

/** 解析 {ok, servers:[{serverName, resources:[{uri,name,mimeType,description}]}]} → 资源能力清册 */
internal fun parseMcpResources(data: JsonElement?): List<McpResourceServer> {
    if (data == null || data is JsonNull) return emptyList()
    val obj = data as? JsonObject ?: return emptyList()
    if (obj["ok"]?.jsonPrimitiveOrNull()?.booleanOrNull == false) return emptyList()
    val arr = obj["servers"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val serverName = o["serverName"]?.stringOrNull() ?: return@mapNotNull null
        val resources = (o["resources"] as? JsonArray)?.mapNotNull { re ->
            val ro = re as? JsonObject ?: return@mapNotNull null
            McpResource(
                uri = ro["uri"]?.stringOrNull() ?: "",
                name = ro["name"]?.stringOrNull() ?: "",
                mimeType = ro["mimeType"]?.stringOrNull() ?: "text/plain",
                description = ro["description"]?.stringOrNull() ?: "",
            )
        } ?: emptyList()
        McpResourceServer(serverName, resources)
    }
}

/** 解析 {ok, servers:[{serverName, prompts:[{name,description,argumentsSchema}]}]} → 提示词能力清册 */
internal fun parseMcpPrompts(data: JsonElement?): List<McpPromptServer> {
    if (data == null || data is JsonNull) return emptyList()
    val obj = data as? JsonObject ?: return emptyList()
    if (obj["ok"]?.jsonPrimitiveOrNull()?.booleanOrNull == false) return emptyList()
    val arr = obj["servers"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val serverName = o["serverName"]?.stringOrNull() ?: return@mapNotNull null
        val prompts = (o["prompts"] as? JsonArray)?.mapNotNull { pe ->
            val po = pe as? JsonObject ?: return@mapNotNull null
            McpPrompt(
                name = po["name"]?.stringOrNull() ?: "",
                description = po["description"]?.stringOrNull() ?: "",
                argumentsSchema = po["argumentsSchema"],
            )
        } ?: emptyList()
        McpPromptServer(serverName, prompts)
    }
}
