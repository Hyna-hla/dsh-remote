package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityPolicyTest {

    @Test
    fun highRiskKeywords() {
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("bash", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("pwsh", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("POWERSHELL", null)) // 大小写不敏感
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("fs-delete", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("rm", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("winget", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("git", "force push")) // reason 命中
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("something", "将执行 install 命令"))
    }

    @Test
    fun mediumRiskKeywords() {
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.level("write", null))
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.level("edit", null))
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.level("move", null))
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.level("create", "创建文件"))
    }

    @Test
    fun lowRiskDefault() {
        assertEquals(RiskLevel.LOW, RiskClassifier.level("read", null))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("list", "只读"))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("unknown-tool", null))
    }

    @Test
    fun shouldLockSemantics() {
        assertFalse(shouldLock(0L, 0L, enabled = false))
        assertTrue(shouldLock(0L, 0L, enabled = true))            // 首次
        assertFalse(shouldLock(1_000L, 40_000L, enabled = true))  // 宽限内
        assertTrue(shouldLock(1_000L, 70_000L, enabled = true))   // 超宽限
        assertFalse(shouldLock(1_000L, 70_000L, enabled = true, graceMs = 120_000L))
    }
}
