package com.dsh.mobile.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 可拓展主题：内置三套（深蓝/纯黑/暖白）+ 用户导入的自定义 JSON 主题。
 * 主题文件格式（UTF-8 JSON）：
 * {
 *   "id": "aurora", "name": "极光", "light": false,
 *   "colors": { "background": "#0D1B2A", "surface": "#1B2838", "surfaceHigh": "#243447",
 *               "brand": "#4D7CFE", "brandSoft": "#64B5F6", "textPrimary": "#E6EDF7",
 *               "textSecondary": "#9DB2CE", "border": "#2C3E55",
 *               "success": "#3FB68B", "warn": "#E6B455", "error": "#E06C6C" }
 * }
 */
data class ThemeColors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val brand: Color,
    val brandSoft: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val border: Color,
    val success: Color,
    val warn: Color,
    val error: Color,
)

/**
 * 主题风格：决定排版与装饰语言，而不只是颜色。
 * - STANDARD：官方 DSH 风格排版（精调字号/字重/行高/字距）+ 圆润卡片
 * - CODEX：Codex CLI 终端风格（全局等宽字体 + 方角 + 终端装饰）
 * - CYBERPUNK：夜之城风格（切角按钮 + 霓虹强调 + HUD 装饰）
 * - CHATGPT：ChatGPT 移动端深色风格（纯黑扁平 + 胶囊输入栏 + 侧边抽屉）
 * - CLAUDE：Claude 移动端暖调深色风格（衬线大标题 + 28px 超大圆角输入容器 + 陶土橙）
 * - DEEPLOOK：DeepSeek 移动端风格（iOS 分组列表 + 品牌蓝 #4D6BFE + 深蓝黑选中态，浅色为主含深色变体）
 */
enum class ThemeStyle { STANDARD, CODEX, CYBERPUNK, CHATGPT, CLAUDE, DEEPLOOK }

data class ThemeDef(
    val id: String,
    val name: String,
    val light: Boolean,
    val colors: ThemeColors,
    /** 主题包版本（可选，用于热更新识别） */
    val version: String? = null,
    /** 排版/装饰风格（默认 STANDARD；自定义 JSON 主题不受影响） */
    val style: ThemeStyle = ThemeStyle.STANDARD,
)

@Serializable
private data class ThemeFile(
    val id: String = "",
    val name: String = "",
    val light: Boolean = false,
    val version: String? = null,
    val style: String? = null,
    val colors: Map<String, String> = emptyMap(),
)

object ThemeRegistry {

    val blue = ThemeDef(
        "blue", "深蓝", light = false,
        ThemeColors(
            // DeepSeek Chat 蓝鲸蓝 #4D6BFE（对齐 chat.deepseek.com 官方蓝鲸配色）
            background = Color(0xFF0D1B2A), surface = Color(0xFF1B2838), surfaceHigh = Color(0xFF243447),
            brand = Color(0xFF4D6BFE), brandSoft = Color(0xFF6D8BFF),
            textPrimary = Color(0xFFE6EDF7), textSecondary = Color(0xFF9DB2CE), border = Color(0xFF2C3E55),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
    )
    val warm = ThemeDef(
        "warm", "暖白", light = true,
        ThemeColors(
            background = Color(0xFFFAF6EF), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF1EAE0),
            brand = Color(0xFF3D5AF1), brandSoft = Color(0xFF2E7DD1),
            textPrimary = Color(0xFF2E2A24), textSecondary = Color(0xFF7B7265), border = Color(0xFFE6DCC9),
            success = Color(0xFF1F9D71), warn = Color(0xFFB07C10), error = Color(0xFFC74F4F),
        ),
    )

    /** 内置仅深蓝/暖白两款底色，其余观感全部走主题市场（dsh-theme-market）按需安装 */
    private val builtins = listOf(blue, warm)
    val builtinIds: Set<String> = builtins.map { it.id }.toSet()

    fun available(custom: List<ThemeDef>): List<ThemeDef> = builtins + custom

    /** 按 id 解析；未知 id（含已删除的自定义主题）回落到深蓝 */
    fun resolve(id: String, custom: List<ThemeDef>): ThemeDef =
        available(custom).firstOrNull { it.id == id } ?: blue

    /** 解析用户主题 JSON；非法返回 null */
    fun parseJson(text: String): ThemeDef? = runCatching {
        val file = Json { ignoreUnknownKeys = true }.decodeFromString<ThemeFile>(text)
        if (file.id.isBlank() || file.name.isBlank() || file.id in builtinIds) return null
        if (!file.id.matches(Regex("[A-Za-z0-9_-]{1,48}"))) return null
        fun c(key: String): Color? = file.colors[key]?.let {
            runCatching { Color(AndroidColor.parseColor(it)) }.getOrNull()
        }
        val colors = ThemeColors(
            background = c("background") ?: return null,
            surface = c("surface") ?: return null,
            surfaceHigh = c("surfaceHigh") ?: return null,
            brand = c("brand") ?: return null,
            brandSoft = c("brandSoft") ?: return null,
            textPrimary = c("textPrimary") ?: return null,
            textSecondary = c("textSecondary") ?: return null,
            border = c("border") ?: return null,
            success = c("success") ?: return null,
            warn = c("warn") ?: return null,
            error = c("error") ?: return null,
        )
        ThemeDef(
            file.id, file.name, file.light, colors,
            file.version?.takeIf { it.isNotBlank() },
            style = file.style
                ?.let { s -> runCatching { ThemeStyle.valueOf(s.trim().uppercase()) }.getOrNull() }
                ?: ThemeStyle.STANDARD,
        )
    }.getOrNull()
}
