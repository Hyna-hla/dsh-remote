package com.dsh.mobile.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed class PendingItem {
    abstract val sessionId: String
    abstract val arrivedAt: Long
    abstract val fromHistory: Boolean

    data class Approval(
        override val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val reason: String?,
        val callId: String?,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()

    data class Question(
        override val sessionId: String,
        val questions: List<QuestionItem>,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()

    data class Error(
        override val sessionId: String,
        val message: String,
        val seq: Long,
        override val arrivedAt: Long,
        override val fromHistory: Boolean,
    ) : PendingItem()
}

/**
 * 从单会话的历史事件重建待办（spec §5.1）：
 * 按 seq 升序扫描——approval/asked 加入（重复 id 忽略）、approval/decided 移除同 id、
 * agent/error 每会话保留 seq 最大一条；data 解析失败跳过该事件。
 */
fun scanHistoryEvents(sessionId: String, entries: List<HistoryEntry>): List<PendingItem> {
    val approvals = LinkedHashMap<String, PendingItem.Approval>()
    var error: PendingItem.Error? = null
    for (entry in entries.sortedBy { it.event.seq }) {
        val ev = entry.event
        when (ev.type) {
            "approval/asked" -> {
                val d = ev.data.jsonObject
                val id = d["id"]?.jsonPrimitive?.contentOrNull ?: continue
                if (approvals.containsKey(id)) continue
                approvals[id] = PendingItem.Approval(
                    sessionId = sessionId,
                    approvalId = id,
                    toolName = d["toolName"]?.jsonPrimitive?.contentOrNull ?: "?",
                    reason = d["reason"]?.jsonPrimitive?.contentOrNull,
                    callId = d["callId"]?.jsonPrimitive?.contentOrNull,
                    arrivedAt = ev.time,
                    fromHistory = true,
                )
            }
            "approval/decided" -> {
                val id = ev.data.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: continue
                approvals.remove(id)
            }
            "agent/error" -> {
                if (error == null || ev.seq > error!!.seq) {
                    error = PendingItem.Error(sessionId, errorMessageOf(ev.data), ev.seq, ev.time, true)
                }
            }
        }
    }
    val out = mutableListOf<PendingItem>()
    out += approvals.values
    error?.let { out += it }
    return out
}

/** 异常消息宽松提取：message / error / name，全缺返回默认文案 */
internal fun errorMessageOf(data: JsonElement): String {
    val o = data.jsonObject
    return o["message"]?.jsonPrimitive?.contentOrNull
        ?: o["error"]?.jsonPrimitive?.contentOrNull
        ?: o["name"]?.jsonPrimitive?.contentOrNull
        ?: "智能体执行出错"
}

/** 三档顺序 + 档内排序（spec §5.1）：审批/问答 arrivedAt 升序，异常降序 */
fun sortPendingItems(items: List<PendingItem>): List<PendingItem> {
    fun rank(i: PendingItem) = when (i) {
        is PendingItem.Approval -> 0
        is PendingItem.Question -> 1
        is PendingItem.Error -> 2
    }
    return items.sortedWith(
        compareBy<PendingItem> { rank(it) }
            .thenBy { if (it is PendingItem.Error) Long.MAX_VALUE - it.arrivedAt else it.arrivedAt },
    )
}
