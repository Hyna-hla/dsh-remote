package com.dsh.mobile.data

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 会话搜索统一（T4）：标题 + 预览双字段过滤，忽略大小写；query 空白时原样返回。
 * 纯函数层，HomeScreen 各布局分支共用；排序（置顶）由调用方先行完成，过滤在排序后应用。
 * 不修改入参列表。
 */
fun filterSessions(sessions: List<SessionSummary>, query: String): List<SessionSummary> {
    if (query.isBlank()) return sessions
    return sessions.filter {
        sessionTitleOf(it)?.contains(query, ignoreCase = true) == true ||
            sessionPreviewOf(it)?.contains(query, ignoreCase = true) == true
    }
}

/** 会话预览：projections.values.preview；无则 null（SessionCard 同款提取） */
private fun sessionPreviewOf(session: SessionSummary): String? = runCatching {
    session.projections?.jsonObject?.get("values")?.jsonObject
        ?.get("preview")?.jsonPrimitive?.contentOrNull
}.getOrNull()
