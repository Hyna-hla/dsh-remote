package com.dsh.mobile.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 快捷指令（T5）：纯函数层，SessionScreen 快捷指令条与 SettingsStore 存储共用。
 * 存储格式：JSON 数组字符串（与 pinnedSessionIds 同套路）。
 */

private val quickPromptsJson = Json { ignoreUnknownKeys = true }

/** 编码：JSON 数组；空列表 → "[]" */
internal fun encodeQuickPrompts(items: List<String>): String =
    quickPromptsJson.encodeToString(items)

/** 解码：非法/null → 空列表（不抛）；列表语义——顺序与重复都保留 */
internal fun decodeQuickPrompts(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching { quickPromptsJson.decodeFromString<List<String>>(raw) }
        .getOrDefault(emptyList())
}

/** 内置默认 4 条（首次使用/用户清空后重置的兜底） */
internal fun defaultQuickPrompts(): List<String> = listOf(
    "帮我总结当前会话",
    "列出待办事项",
    "检查代码问题",
    "优化这段代码",
)
