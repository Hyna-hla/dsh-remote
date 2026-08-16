package com.dsh.mobile.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 会话置顶（本地 pin）：纯函数层，HomeScreen 排序与 SettingsStore 存储共用。
 * 置顶集合存 sessionId；排序只在渲染层应用，HistoryCache 仍存原始 updatedAt 序。
 */

private val pinnedJson = Json { ignoreUnknownKeys = true }

/** 置顶优先排序：pinned 的会话按 updatedAt 倒序在前，其余按 updatedAt 倒序在后；不修改入参列表 */
fun sortSessionsWithPinned(sessions: List<SessionSummary>, pinnedIds: Set<String>): List<SessionSummary> {
    val pinned = sessions.filter { it.sessionId in pinnedIds }.sortedByDescending { it.updatedAt }
    val rest = sessions.filterNot { it.sessionId in pinnedIds }.sortedByDescending { it.updatedAt }
    return pinned + rest
}

/** 翻转置顶状态：已在集合 → 移除；不在 → 加入（连调两次恢复原状） */
fun togglePinned(pinnedIds: Set<String>, id: String): Set<String> =
    if (id in pinnedIds) pinnedIds - id else pinnedIds + id

/** 存储格式：JSON 数组（排序后编码，输出确定） */
fun encodePinnedIds(ids: Set<String>): String =
    pinnedJson.encodeToString(ids.toList().sorted())

/** 解码存储值；null/空白/非法 → 空集（不抛），重复元素去重 */
fun decodePinnedIds(raw: String?): Set<String> {
    if (raw.isNullOrBlank()) return emptySet()
    return runCatching { pinnedJson.decodeFromString<List<String>>(raw).toSet() }
        .getOrDefault(emptySet())
}
