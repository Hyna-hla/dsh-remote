package com.dsh.mobile.data

/**
 * S4 T1 通知渠道精细化：分渠道放行策略（纯函数，供单测与服务接线复用）。
 *
 * 语义：总开关 && 分渠道开关 && 不在前台 → 放行，其余一律不放行。
 */

/** 审批/问答横幅是否放行：总开关 && 分渠道开关 && 不在前台 */
internal fun shouldNotifyApproval(
    backgroundNotify: Boolean,
    notifyApprovals: Boolean,
    appInForeground: Boolean,
): Boolean = backgroundNotify && notifyApprovals && !appInForeground

/** 完成提醒是否放行：总开关 && 分渠道开关 && 不在前台 */
internal fun shouldNotifyCompletion(
    backgroundNotify: Boolean,
    notifyCompletion: Boolean,
    appInForeground: Boolean,
): Boolean = backgroundNotify && notifyCompletion && !appInForeground
