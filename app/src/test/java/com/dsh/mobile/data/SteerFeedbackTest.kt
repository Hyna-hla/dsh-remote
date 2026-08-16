package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T6 打断（steer）动效反馈：steerFlashOn 状态机——
 * 触发 → 显示 2s「⚡ 已插话」banner → 自动消失；非 steer 发送不触发。
 */
class SteerFeedbackTest {

    // ── 触发瞬间 ──

    @Test
    fun steerSendTriggersVisibleImmediately() {
        val f = steerFlashOn(sendingSteer = true, prev = SteerFlash(false), now = 1_000L)
        assertTrue(f.visible)
        // 触发时刻被记录，作为超时计时的起点
        assertEquals(1_000L, f.at)
    }

    // ── duration 内保持 ──

    @Test
    fun staysVisibleWithinDuration() {
        val f = steerFlashOn(true, SteerFlash(false), now = 1_000L)
        // 距触发 1.9s（默认 2s 内）：仍可见，且不推进 at
        val mid = steerFlashOn(false, f, now = 1_000L + 1_900L)
        assertTrue(mid.visible)
        assertEquals(1_000L, mid.at)
    }

    // ── 超时消失 ──

    @Test
    fun hidesAfterDurationElapses() {
        val f = steerFlashOn(true, SteerFlash(false), now = 1_000L)
        // 恰好 2s：超时 → 隐藏
        val after = steerFlashOn(false, f, now = 1_000L + 2_000L)
        assertFalse(after.visible)
        // 更久之后依旧隐藏
        val muchLater = steerFlashOn(false, after, now = 1_000L + 60_000L)
        assertFalse(muchLater.visible)
    }

    // ── 非 steer 发送不触发 ──

    @Test
    fun nonSteerSendKeepsHiddenState() {
        val prev = SteerFlash(false)
        val f = steerFlashOn(sendingSteer = false, prev = prev, now = 5_000L)
        assertFalse(f.visible)
        // 完全透传原状态（不产生副作用）
        assertEquals(prev, f)
    }

    // ── 持续可见时重复触发：以新触发时刻重新计时 ──

    @Test
    fun retriggerWhileVisibleExtendsTimerFromNewMoment() {
        val f = steerFlashOn(true, SteerFlash(false), now = 1_000L)
        // 1.5s 后再次触发：可见，at 刷新为新触发时刻
        val again = steerFlashOn(true, f, now = 1_000L + 1_500L)
        assertTrue(again.visible)
        assertEquals(2_500L, again.at)
        // 以新 at 计时：距首次触发 3s（距再次触发 1.5s）仍未到 2s → 保持可见
        val later = steerFlashOn(false, again, now = 1_000L + 3_000L)
        assertTrue(later.visible)
        // 距再次触发满 2s → 隐藏
        assertFalse(steerFlashOn(false, again, now = 1_000L + 3_500L).visible)
    }

    // ── 自定义 duration ──

    @Test
    fun customDurationIsRespected() {
        val f = steerFlashOn(true, SteerFlash(false), now = 0L, durationMs = 500L)
        assertTrue(steerFlashOn(false, f, now = 499L, durationMs = 500L).visible)
        assertFalse(steerFlashOn(false, f, now = 500L, durationMs = 500L).visible)
    }

    // ── 默认 duration = 2000ms（与 UI 常量一致） ──

    @Test
    fun defaultDurationIsTwoSeconds() {
        val f = steerFlashOn(true, SteerFlash(false), now = 10_000L)
        assertTrue(steerFlashOn(false, f, now = 10_000L + 1_999L).visible)
        assertFalse(steerFlashOn(false, f, now = 10_000L + 2_000L).visible)
    }
}
