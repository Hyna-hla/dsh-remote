package com.dsh.mobile.data

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** keyword → 预编译词边界正则缓存（避免每次匹配重复编译）。 */
private val wordBoundaryRegexCache = HashMap<String, Regex>()

/**
 * 词边界匹配：text 中 keyword 作为独立词出现（前后非字母数字）才命中；含空格的关键词按整体词匹配。
 * 调用方保证 text 已小写、keyword 为小写。
 */
internal fun containsWord(text: String, keyword: String): Boolean {
    if (keyword.isEmpty()) return false
    val regex = wordBoundaryRegexCache.getOrPut(keyword) {
        Regex("(?<![a-z0-9])" + Regex.escape(keyword) + "(?![a-z0-9])")
    }
    return regex.containsMatchIn(text)
}

/**
 * 工具审批风险分级（spec §5.4）：HIGH = 命令执行/删除/安装类；MEDIUM = 写类；其余 LOW。
 * 匹配 toolName + reason 的合并小写文本，关键词词边界命中即生效（消除 rm/apt/pip 子串误伤）。
 */
object RiskClassifier {

    private val HIGH = listOf(
        "bash", "pwsh", "powershell", "terminal", "shell", "cmd",
        "delete", "remove", "rm", "unlink", "install", "winget", "choco",
        "scoop", "apt", "pip", "npm", "git push", "git_push", "gitpush",
        "force", "format", "drop",
    )

    private val MEDIUM = listOf(
        "write", "edit", "move", "rename", "create", "mkdir",
        "upload", "replace", "truncate",
    )

    fun level(toolName: String, reason: String?): RiskLevel {
        val text = (toolName + " " + (reason ?: "")).lowercase()
        if (HIGH.any { containsWord(text, it) }) return RiskLevel.HIGH
        if (MEDIUM.any { containsWord(text, it) }) return RiskLevel.MEDIUM
        return RiskLevel.LOW
    }
}
