package com.dsh.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S8 Task 2：风险关键词词边界匹配——消除 rm/apt/pip 子串误伤，
 * 并验证 "git push" 空格关键词及 snake/camel 变体生效。
 */
class RiskClassifierTest {

    // ---- containsWord：词边界 ----

    @Test
    fun rmRequiresStandaloneWord() {
        assertFalse("confirm 中的 rm 不应命中", containsWord("confirm delete", "rm"))
        assertTrue("rm -rf 中的 rm 应命中", containsWord("rm -rf", "rm"))
    }

    @Test
    fun aptRequiresStandaloneWord() {
        assertFalse("capture 中的 apt 不应命中", containsWord("capture", "apt"))
        assertTrue("apt install 中的 apt 应命中", containsWord("apt install", "apt"))
    }

    @Test
    fun pipRequiresStandaloneWord() {
        assertFalse("pipeline 中的 pip 不应命中", containsWord("pipeline", "pip"))
        assertTrue("pip install 中的 pip 应命中", containsWord("pip install", "pip"))
    }

    // ---- containsWord：含空格关键词按整体词匹配 ----

    @Test
    fun multiWordKeywordMatchesAsWhole() {
        assertTrue("git push origin 应命中 git push 整体", containsWord("git push origin", "git push"))
        assertFalse("单独 git 不应命中 git push", containsWord("git", "git push"))
        assertFalse("单独 push 不应命中 git push", containsWord("push", "git push"))
        assertFalse("git_push 不含空格，不命中 git push 整体", containsWord("git_push", "git push"))
    }

    // ---- level：子串误伤回归 ----

    @Test
    fun substringFalsePositivesAreLow() {
        assertEquals(RiskLevel.LOW, RiskClassifier.level("confirm", null))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("capture", null))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("pipeline", null))
    }

    @Test
    fun standaloneKeywordsStillHigh() {
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("rm", "删除文件"))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("apt", "安装"))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("pip", "install"))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("fs-delete", null))
    }

    // ---- level：git push 空格关键词及变体 ----

    @Test
    fun gitPushSpaceKeywordAndVariants() {
        // 空格关键词（reason 里出现完整短语）
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("git", "push origin master"))
        // snake_case / camelCase 工具名变体（S3 记录「git push 实际不生效」的修复）
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("git_push", null))
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("gitpush", null))
        // 单独的 git / push 不因空格关键词误判
        assertEquals(RiskLevel.LOW, RiskClassifier.level("git", null))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("push", null))
    }

    // ---- 既有语义保持 ----

    @Test
    fun existingSemanticsPreserved() {
        assertEquals(RiskLevel.HIGH, RiskClassifier.level("bash", null))
        assertEquals(RiskLevel.MEDIUM, RiskClassifier.level("write_file", null))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("read", "只读"))
        assertEquals(RiskLevel.LOW, RiskClassifier.level("unknown-tool", null))
    }
}
