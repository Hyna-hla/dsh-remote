package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S4 T1 通知渠道精细化：分渠道放行策略的纯函数测试。
 *
 * 两个函数语义相同：总开关 && 分渠道开关 && 不在前台 → 放行，其余一律不放行。
 * 这里对每个函数做：分语义组覆盖 + 全组合真值表穷举。
 */
class NotificationPolicyTest {

    // ---- shouldNotifyApproval：总开关关 → 恒 false ----

    @Test
    fun approval_masterSwitchOff_neverNotifies() {
        listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, false),
            Triple(false, true, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyApproval(bg, ch, fg))
        }
    }

    // ---- shouldNotifyApproval：分渠道关 → 恒 false ----

    @Test
    fun approval_channelOff_neverNotifies() {
        listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, false, true),
            Triple(true, false, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyApproval(bg, ch, fg))
        }
    }

    // ---- shouldNotifyApproval：前台 → 恒 false ----

    @Test
    fun approval_foreground_neverNotifies() {
        listOf(
            Triple(false, false, true),
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyApproval(bg, ch, fg))
        }
    }

    // ---- shouldNotifyApproval：全开且不在前台 → true ----

    @Test
    fun approval_allOn_background_notifies() {
        assertTrue(shouldNotifyApproval(true, true, false))
    }

    // ---- shouldNotifyApproval：全组合真值表穷举 ----

    @Test
    fun approval_fullTruthTable() {
        val cases = listOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to false,
            Triple(false, true, true) to false,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to true,
            Triple(true, true, true) to false,
        )
        cases.forEach { (input, expected) ->
            val (bg, ch, fg) = input
            assertEquals("bg=$bg ch=$ch fg=$fg", expected, shouldNotifyApproval(bg, ch, fg))
        }
    }

    // ---- shouldNotifyCompletion：总开关关 → 恒 false ----

    @Test
    fun completion_masterSwitchOff_neverNotifies() {
        listOf(
            Triple(false, false, false),
            Triple(false, false, true),
            Triple(false, true, false),
            Triple(false, true, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyCompletion(bg, ch, fg))
        }
    }

    // ---- shouldNotifyCompletion：分渠道关 → 恒 false ----

    @Test
    fun completion_channelOff_neverNotifies() {
        listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(false, false, true),
            Triple(true, false, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyCompletion(bg, ch, fg))
        }
    }

    // ---- shouldNotifyCompletion：前台 → 恒 false ----

    @Test
    fun completion_foreground_neverNotifies() {
        listOf(
            Triple(false, false, true),
            Triple(false, true, true),
            Triple(true, false, true),
            Triple(true, true, true),
        ).forEach { (bg, ch, fg) ->
            assertFalse("bg=$bg ch=$ch fg=$fg", shouldNotifyCompletion(bg, ch, fg))
        }
    }

    // ---- shouldNotifyCompletion：全开且不在前台 → true ----

    @Test
    fun completion_allOn_background_notifies() {
        assertTrue(shouldNotifyCompletion(true, true, false))
    }

    // ---- shouldNotifyCompletion：全组合真值表穷举 ----

    @Test
    fun completion_fullTruthTable() {
        val cases = listOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to false,
            Triple(false, true, true) to false,
            Triple(true, false, false) to false,
            Triple(true, false, true) to false,
            Triple(true, true, false) to true,
            Triple(true, true, true) to false,
        )
        cases.forEach { (input, expected) ->
            val (bg, ch, fg) = input
            assertEquals("bg=$bg ch=$ch fg=$fg", expected, shouldNotifyCompletion(bg, ch, fg))
        }
    }
}
