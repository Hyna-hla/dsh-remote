package com.dsh.mobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** 插件 fs/read 响应模型：文件内容只读预览（文本在 text；二进制时 text=null，data 不进入模型） */
data class FilePreview(
    val path: String,
    val size: Long,
    val truncated: Boolean,
    val isBinary: Boolean,
    val text: String?,
)

/**
 * 解析插件 fs/read 响应 {ok, path, size, truncated, isBinary, text|data}：
 * - null / 非对象 / ok=false / 缺 path 或 path 非字符串 → null；
 * - size/truncated/isBinary 缺省 0/false/false；
 * - text 仅接受字符串（二进制时服务端返回 data 字段，text 缺失/null → null）；
 * - ok 缺失按成功宽容处理（与其余解析器一致）。
 */
internal fun parseFilePreview(data: JsonElement?): FilePreview? {
    if (data == null || data is JsonNull) return null
    val obj = data as? JsonObject ?: return null
    if (obj["ok"]?.jsonPrimitiveOrNull()?.booleanOrNull == false) return null
    val path = obj["path"]?.jsonPrimitiveStringOrNull() ?: return null
    val size = obj["size"]?.jsonPrimitiveOrNull()?.longOrNull ?: 0L
    val truncated = obj["truncated"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
    val isBinary = obj["isBinary"]?.jsonPrimitiveOrNull()?.booleanOrNull ?: false
    val text = obj["text"]?.jsonPrimitiveStringOrNull()
    return FilePreview(path, size, truncated, isBinary, text)
}

/** 仅字符串 primitive 取内容；JsonNull/数字/布尔/非 primitive → null */
private fun JsonElement.jsonPrimitiveStringOrNull(): String? {
    val p = this as? JsonPrimitive ?: return null
    return if (p.isString) p.content else null
}

/** 宽松转 JsonPrimitive：对象/数组等非 primitive → null（不外抛） */
private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive
