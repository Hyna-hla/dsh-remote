package com.dsh.mobile.data

/**
 * 生物锁宽限判断（spec §5.3）：启用且（从未解锁 或 距上次解锁超过宽限）→ 需锁。
 * lastUnlockedAt == 0 表示从未解锁（首次启动需锁）。
 */
fun shouldLock(
    lastUnlockedAt: Long,
    now: Long,
    enabled: Boolean,
    graceMs: Long = 60_000L,
): Boolean = enabled && (lastUnlockedAt == 0L || (now - lastUnlockedAt) > graceMs)
