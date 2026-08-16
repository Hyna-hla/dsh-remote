package com.dsh.mobile.data

enum class RiskLevel { LOW, MEDIUM, HIGH }

/**
 * 工具审批风险分级（spec §5.4）：HIGH = 命令执行/删除/安装类；MEDIUM = 写类；其余 LOW。
 * 匹配 toolName + reason 的合并小写文本，关键词子串命中即生效。
 */
object RiskClassifier {

    private val HIGH = listOf(
        "bash", "pwsh", "powershell", "terminal", "shell", "cmd",
        "delete", "remove", "rm", "unlink", "install", "winget", "choco",
        "scoop", "apt", "pip", "npm", "git push", "force", "format", "drop",
    )

    private val MEDIUM = listOf(
        "write", "edit", "move", "rename", "create", "mkdir",
        "upload", "replace", "truncate",
    )

    fun level(toolName: String, reason: String?): RiskLevel {
        val text = (toolName + " " + (reason ?: "")).lowercase()
        if (HIGH.any { text.contains(it) }) return RiskLevel.HIGH
        if (MEDIUM.any { text.contains(it) }) return RiskLevel.MEDIUM
        return RiskLevel.LOW
    }
}
