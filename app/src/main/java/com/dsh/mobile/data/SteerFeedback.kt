package com.dsh.mobile.data

/**
 * 插话（steer）发送瞬间的反馈状态机（T6）：触发 → 显示 N 秒「⚡ 已插话」banner → 自动消失。
 * 纯乐观反馈：服务端无回执事件（recon §7.5），可见性与时长完全由本状态机驱动。
 */

/** 反馈状态：visible=是否显示 banner；at=最近一次触发的时刻（超时计时起点，ms） */
data class SteerFlash(val visible: Boolean, val at: Long = 0L)

/**
 * 状态机推进（单次调用只做一步，UI 侧用 LaunchedEffect 按 duration 定时推进）：
 * - 触发（sendingSteer=true）：立即可见，并以 now 作为新的计时起点（持续可见时重复触发会顺延）；
 * - 计时中（visible 且未超时）：原样保持（不推进 at）；
 * - 超时（visible 且 now - at >= durationMs）：隐藏；
 * - 非 steer 发送（sendingSteer=false 且当前不可见）：原样透传，绝不误触发。
 *
 * @param sendingSteer 本次发送是否为插话（steer）模式
 * @param prev 上一次状态
 * @param now 当前时刻（ms，与 at 同钟）
 * @param durationMs banner 展示时长（默认 2000ms，与 SessionScreen 的 STEER_FLASH_MS 保持一致）
 */
fun steerFlashOn(sendingSteer: Boolean, prev: SteerFlash, now: Long, durationMs: Long = 2000L): SteerFlash {
    if (sendingSteer) return SteerFlash(visible = true, at = now)
    if (!prev.visible) return prev
    return if (now - prev.at >= durationMs) SteerFlash(visible = false, at = prev.at) else prev
}
