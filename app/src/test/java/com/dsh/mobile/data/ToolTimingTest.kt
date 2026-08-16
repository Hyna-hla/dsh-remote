package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S5 工具调用耗时（Task 2）：ToolTimingTracker——
 * 维护 callId→startTime 映射，配对 tool/call 与 tool/result 的 time（Unix ms）计算耗时；
 * 无配对 / 负值 / cleanup 后 → null；重复 call 覆盖旧值；result 配对后即移除条目。
 */
class ToolTimingTest {

    @Test
    fun normalPairingComputesResultMinusCall() {
        val tracker = ToolTimingTracker()
        tracker.onCall("call_1", 1_000)
        assertEquals(2_500L, tracker.onResult("call_1", 3_500))
    }

    @Test
    fun resultWithoutCallReturnsNull() {
        val tracker = ToolTimingTracker()
        assertNull(tracker.onResult("ghost", 1_000))
        // 连续多次无配对 result 也不崩
        assertNull(tracker.onResult("ghost", 2_000))
    }

    @Test
    fun duplicateCallOverwritesStart() {
        val tracker = ToolTimingTracker()
        tracker.onCall("call_1", 1_000)
        tracker.onCall("call_1", 2_000)
        // 以最后一次 call 时间为起点：3000 - 2000 = 1000
        assertEquals(1_000L, tracker.onResult("call_1", 3_000))
    }

    @Test
    fun duplicateResultReturnsNull() {
        val tracker = ToolTimingTracker()
        tracker.onCall("call_1", 1_000)
        assertEquals(1_000L, tracker.onResult("call_1", 2_000))
        // 条目已移除：第二次 result 无配对 → null
        assertNull(tracker.onResult("call_1", 2_500))
    }

    @Test
    fun negativeElapsedReturnsNull() {
        val tracker = ToolTimingTracker()
        tracker.onCall("call_1", 5_000)
        assertNull(tracker.onResult("call_1", 3_000))
        // 负值同样消费条目：后续 result 不再配对
        assertNull(tracker.onResult("call_1", 4_000))
    }

    @Test
    fun cleanupClearsAllPairs() {
        val tracker = ToolTimingTracker()
        tracker.onCall("call_1", 1_000)
        tracker.onCall("call_2", 2_000)
        tracker.cleanup()
        assertNull(tracker.onResult("call_1", 3_000))
        assertNull(tracker.onResult("call_2", 3_000))
    }

    @Test
    fun blankCallIdIgnored() {
        val tracker = ToolTimingTracker()
        tracker.onCall("", 1_000)
        tracker.onCall("   ", 2_000)
        assertNull(tracker.onResult("", 3_000))
        assertNull(tracker.onResult("   ", 3_000))
    }
}
