package com.dsh.mobile.data

/**
 * S5 工具调用耗时（Task 2）：从 wire 事件信封取 time（Unix ms），
 * 维护 callId→startTime 映射，配对 tool/call 与 tool/result 计算单次工具耗时。
 * 语义：
 * - onCall 登记开始时间，重复 call 覆盖旧值；
 * - onResult 配对成功后立即移除条目（消耗型），差值 = result.time - call.time；
 *   无配对 / 差值为负（时钟回拨或异常时序）→ null；
 * - cleanup 清空全部在途配对（断线 / 换会话）。
 */
internal class ToolTimingTracker {

    private val starts = HashMap<String, Long>()

    /** tool/call：登记本次调用开始时间；空 callId 无法配对，直接忽略。 */
    fun onCall(callId: String, timeMs: Long) {
        if (callId.isBlank()) return
        starts[callId] = timeMs
    }

    /** tool/result：配对计算耗时并移除条目；无配对 / 负值 → null。 */
    fun onResult(toolCallId: String, timeMs: Long): Long? {
        if (toolCallId.isBlank()) return null
        val startMs = starts.remove(toolCallId) ?: return null
        val elapsed = timeMs - startMs
        return if (elapsed >= 0) elapsed else null
    }

    /** 断线 / 换会话：清空所有在途配对。 */
    fun cleanup() {
        starts.clear()
    }
}
