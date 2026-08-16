package com.dsh.mobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** host.listDirectory 单个条目（子目录或面包屑祖先；path 为宿主绝对路径，客户端不做路径拼接） */
data class DirEntry(val name: String, val path: String, val hidden: Boolean)

/** 面包屑层级元素：name=展示标签、path=宿主绝对路径（可跳转目标）；path=null 表示仅展示不可点（S8 Task 4） */
data class Crumb(val name: String, val path: String?)

/** 插件 fs/list 的 files[] 条目：{name, path, size, hidden}（size 字节数；隐藏 = 点前缀） */
data class FileEntry(val name: String, val path: String, val size: Long, val hidden: Boolean)

/** 插件 fs/list 响应模型（Task 4 增强）：子目录名 + 文件条目（dirs[] 兼容旧 App） */
data class FsListing(val dirs: List<String>, val files: List<FileEntry>)

/** host.listDirectory 响应扁平模型：当前目录 + 面包屑层级（Crumb） + 子目录 + 是否截断 + 文件（插件 fs/list 增强） */
data class DirListing(
    val path: String,
    val crumbs: List<Crumb>,
    val entries: List<DirEntry>,
    val truncated: Boolean,
    val files: List<FileEntry> = emptyList(),
)

/**
 * 解析 host.listDirectory 响应：{path, home, crumbs[], entries[{name,path,hidden}], truncated} → 扁平模型。
 * - path 缺失/非字符串 → 非法 → null；
 * - crumbs 宽容解析：元素为对象取 {name,path}（真实 wire 形状，每个 crumb 是 DirectoryEntry；
 *   path 缺失 → Crumb(name, null) 仅展示不可点；name 缺失 → 跳过），纯字符串 → Crumb(it, null)；
 * - entries 条目缺 name/path 跳过；hidden 缺省 false；crumbs/entries/truncated 缺省空/空/false。
 */
internal fun parseDirectoryList(data: JsonElement?): DirListing? {
    if (data == null || data is JsonNull) return null
    val obj = data as? JsonObject ?: return null
    val path = obj["path"]?.stringValue() ?: return null
    return DirListing(
        path = path,
        crumbs = obj["crumbs"]?.let(::parseCrumbs) ?: emptyList(),
        entries = obj["entries"]?.let(::parseEntries) ?: emptyList(),
        truncated = obj["truncated"]?.boolValue() ?: false,
        files = obj["files"]?.let(::parseFiles) ?: emptyList(),
    )
}

/**
 * 面包屑宽容解析：元素为对象取 {name,path}（真实 wire 形状；path 缺失 → Crumb(name, null) 仅展示不可点，
 * name 缺失/非字符串 → 跳过），纯字符串 → Crumb(it, null)（无跳转目标），非对象非字符串 → 跳过。
 */
private fun parseCrumbs(el: JsonElement): List<Crumb> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { c ->
        when (c) {
            is JsonObject -> c["name"]?.stringValue()?.let { name ->
                Crumb(name = name, path = c["path"]?.stringValue())
            }
            is JsonPrimitive -> c.stringValue()?.let { Crumb(it, null) }
            else -> null
        }
    }
}

private fun parseEntries(el: JsonElement): List<DirEntry> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o["name"]?.stringValue() ?: return@mapNotNull null
        val path = o["path"]?.stringValue() ?: return@mapNotNull null
        DirEntry(name, path, o["hidden"]?.boolValue() ?: false)
    }
}

private fun parseFiles(el: JsonElement): List<FileEntry> {
    val arr = el as? JsonArray ?: return emptyList()
    return arr.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val name = o["name"]?.stringValue() ?: return@mapNotNull null
        val path = o["path"]?.stringValue() ?: return@mapNotNull null
        FileEntry(name, path, o["size"]?.longValue() ?: 0L, o["hidden"]?.boolValue() ?: false)
    }
}

/**
 * 插件回退时 host 无 crumbs：从绝对路径推导面包屑层级 Crumb（如 C:\a\b →
 * [Crumb("C:\","C:\"), Crumb("C:\a","C:\a"), Crumb("C:\a\b","C:\a\b")]），
 * name=层级标签、path=推导路径，全部可点。兼容 Windows（盘符根 C:\）与 POSIX（/）两种绝对路径形态。
 */
internal fun deriveCrumbsFromPath(path: String): List<Crumb> {
    val raw = path.trim()
    if (raw.isEmpty()) return emptyList()
    // 分隔符须在 trimEnd 之前判定：盘符根 "C:\" 去掉尾分隔符后只剩 "C:"，无法再推断反斜杠
    val sep = when {
        raw.contains('\\') -> '\\'
        raw.contains('/') -> '/'
        raw.length == 2 && raw[1] == ':' -> '\\' // 裸盘符 C: 按 Windows 处理
        else -> '/'
    }
    val p = raw.trimEnd('\\').trimEnd('/')
    val parts = p.split('\\', '/').filter { it.isNotEmpty() }
    if (parts.isEmpty()) return emptyList()
    val out = mutableListOf<Crumb>()
    var cur = ""
    if (parts[0].length == 2 && parts[0][1] == ':') {
        // Windows 盘符根：C:\ → C:\foo → C:\foo\bar
        cur = parts[0] + sep
        out += Crumb(cur, cur)
        for (i in 1 until parts.size) {
            cur = cur.trimEnd(sep) + sep + parts[i]
            out += Crumb(cur, cur)
        }
    } else {
        // POSIX：/home → /home/me
        cur = "/" + parts[0]
        out += Crumb(cur, cur)
        for (i in 1 until parts.size) {
            cur += "/" + parts[i]
            out += Crumb(cur, cur)
        }
    }
    return out
}

/** 宽松取字符串值：仅字符串 primitive 取内容；数字/布尔/JsonNull/非 primitive → null */
private fun JsonElement.stringValue(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> if (isString) content else null
    else -> null
}

/** 宽松取长整型值：数字 primitive → 值；其他形态 → null（不外抛） */
private fun JsonElement.longValue(): Long? = (this as? JsonPrimitive)?.longOrNull

/** 宽松取布尔值：布尔 primitive → 值；字符串 "true"/"false" 亦接受；数字等异常形态 → null（不外抛） */
private fun JsonElement.boolValue(): Boolean? {
    val p = this as? JsonPrimitive ?: return null
    return runCatching { p.booleanOrNull }.getOrNull() ?: p.contentOrNull?.toBooleanStrictOrNull()
}
