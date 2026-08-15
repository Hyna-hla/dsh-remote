package com.dsh.mobile.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 全局 token 用量监听（假 Pro 订阅扣费用）：
 * - 挂在连接事件流上（App 主连接 + 后台服务连接），PC 端任何会话的回合都会结算，
 *   不限于移动端打开着的会话
 * - 按 (sessionId, turnSeq) LRU 去重：主连接与后台服务连接都会收到同一事件流，
 *   同一回合只扣一次
 * - 服务端 usage 优先（inputTokens/outputTokens），缺失按本回合字符/4 估算
 */
object TokenUsageWatcher {

    private class TurnStat {
        var input = 0L
        var output = 0L
        var usage = 0L
    }

    /** sessionId → 本回合统计（TURN_START 建，TURN_END 结算删除） */
    private val stats = HashMap<String, TurnStat>()

    /** 已结算的 (sessionId, turnSeq) 去重表（LRU） */
    private val settled = object : LinkedHashMap<Pair<String, Long>, Boolean>(512, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<String, Long>, Boolean>,
        ): Boolean = size > 512
    }

    /** 输入内容块文字长度（user/message 的 text 块）+ 每图约 85 token */
    private fun inputLenOf(data: kotlinx.serialization.json.JsonElement): Long {
        val content = data.jsonObject["content"] as? JsonArray ?: return 0L
        var chars = 0L
        var images = 0L
        content.forEach { el ->
            val o = el.jsonObject
            when {
                o["type"]?.jsonPrimitive?.contentOrNull == "image" -> images++
                o["text"] is JsonPrimitive -> chars += (o["text"] as JsonPrimitive).content.length
            }
        }
        return chars + images * 340L
    }

    /** chunk 增量长度（reasoning-delta 不计） */
    private fun chunkLenOf(data: kotlinx.serialization.json.JsonElement): Long {
        val chunk = data.jsonObject["chunk"] ?: return 0L
        if (chunk is JsonPrimitive && chunk.isString) return chunk.content.length.toLong()
        val o = chunk as? kotlinx.serialization.json.JsonObject ?: return 0L
        val type = o["type"]?.jsonPrimitive?.contentOrNull
        if (type != null && type != "text-delta") return 0L
        return when {
            o["text"] is JsonPrimitive -> (o["text"] as JsonPrimitive).content.length.toLong()
            o["delta"] is JsonPrimitive -> (o["delta"] as JsonPrimitive).content.length.toLong()
            else -> 0L
        }
    }

    /** 助手完整消息文本长度 */
    private fun assistantLenOf(data: kotlinx.serialization.json.JsonElement): Long {
        val msg = data.jsonObject["message"]?.jsonObject ?: return 0L
        val content = msg["content"] as? JsonArray ?: return 0L
        var chars = 0L
        content.forEach { el ->
            val o = el.jsonObject
            if (o["type"]?.jsonPrimitive?.contentOrNull == "text" && o["text"] is JsonPrimitive) {
                chars += (o["text"] as JsonPrimitive).content.length
            }
        }
        return chars
    }

    /** usage 字段：inputTokens + outputTokens（兼容 data.usage 与 data.message.usage 两种位置） */
    private fun usageOf(data: kotlinx.serialization.json.JsonElement): Long {
        val u = data.jsonObject["usage"]?.jsonObject
            ?: data.jsonObject["message"]?.jsonObject?.get("usage")?.jsonObject
            ?: return 0L
        val input = u["inputTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        val output = u["outputTokens"]?.jsonPrimitive?.longOrNull ?: 0L
        return input + output
    }

    /** 结算并去重扣费 */
    private fun settle(sid: String, seq: Long, tokens: Long) {
        if (tokens <= 0) return
        // 双连接去重：同一回合（同 session 同 seq）只结算一次
        val key = sid to seq
        if (settled.containsKey(key)) return
        settled[key] = true
        ProTokenBank.consume(tokens)
    }

    /** 事件流入口（主线程/事件线程调用；内部同步） */
    @Synchronized
    fun onSessionEvent(sessionId: String, e: SessionEventWire) {
        val sid = sessionId
        when (e.type) {
            DshEventTypes.TURN_START -> stats[sid] = TurnStat()

            DshEventTypes.USER_MESSAGE -> {
                val s = stats.getOrPut(sid) { TurnStat() }
                s.input += inputLenOf(e.data)
            }

            DshEventTypes.ASSISTANT_CHUNK -> {
                val s = stats.getOrPut(sid) { TurnStat() }
                s.output += chunkLenOf(e.data)
            }

            DshEventTypes.ASSISTANT_MESSAGE -> {
                val s = stats.getOrPut(sid) { TurnStat() }
                s.output += assistantLenOf(e.data)
                val u = usageOf(e.data)
                if (u > s.usage) s.usage = u
            }

            DshEventTypes.TURN_END -> {
                val s = stats.remove(sid)
                val real = usageOf(e.data)
                if (s != null) {
                    // 常规路径：回合内累计 + 服务端 usage（二者取大）
                    val tokens = s.usage.coerceAtLeast(real)
                        .let { if (it > 0) it else (s.input + s.output) / 4 + 1 }
                    settle(sid, e.seq, tokens)
                } else if (real > 0) {
                    // 兜底路径：回合开始于 App 挂载监听之前（如 PC 端任务先跑起来），
                    // 没有累计统计，但服务端 usage 仍在——按 usage 结算
                    settle(sid, e.seq, real)
                }
            }
        }
    }
}
