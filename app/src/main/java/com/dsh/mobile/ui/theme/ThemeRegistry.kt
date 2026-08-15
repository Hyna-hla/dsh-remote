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
 */
enum class ThemeStyle { STANDARD, CODEX }

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
    val colors: Map<String, String> = emptyMap(),
)

object ThemeRegistry {

    val blue = ThemeDef(
        "blue", "深蓝", light = false,
        ThemeColors(
            background = Color(0xFF0D1B2A), surface = Color(0xFF1B2838), surfaceHigh = Color(0xFF243447),
            brand = Color(0xFF4D7CFE), brandSoft = Color(0xFF64B5F6),
            textPrimary = Color(0xFFE6EDF7), textSecondary = Color(0xFF9DB2CE), border = Color(0xFF2C3E55),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
    )
    val black = ThemeDef(
        "black", "纯黑", light = false,
        ThemeColors(
            background = Color(0xFF000000), surface = Color(0xFF121418), surfaceHigh = Color(0xFF1B1E24),
            brand = Color(0xFF4D7CFE), brandSoft = Color(0xFF64B5F6),
            textPrimary = Color(0xFFE8EDF5), textSecondary = Color(0xFF9AA6B8), border = Color(0xFF2A2F3A),
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

    /** Codex CLI 终端风格：黑底 + 橙棕主色 + 全局等宽字体 + 方角（模拟 OpenAI Codex CLI 终端观感） */
    val codex = ThemeDef(
        "codex", "Codex CLI", light = false,
        ThemeColors(
            background = Color(0xFF0B0D0E), surface = Color(0xFF151718), surfaceHigh = Color(0xFF1D2021),
            brand = Color(0xFFCC7B5B), brandSoft = Color(0xFFE09A7C),
            textPrimary = Color(0xFFF0F0EC), textSecondary = Color(0xFFA9A9A3), border = Color(0xFF2B2E2F),
            success = Color(0xFF6FBF8F), warn = Color(0xFFD8B46E), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
        style = ThemeStyle.CODEX,
    )


    /** Built-in skin themes adapted from the SKIN pack collection (v1.0.19). */
    val skinThemes: List<ThemeDef> = listOf(
    ThemeDef(
        id = "aurora", name = "极光紫", light = false,
        colors = ThemeColors(
            background = Color(0xFF0B0E1A), surface = Color(0xFF12162A), surfaceHigh = Color(0xFF1A1F3D),
            brand = Color(0xFF6D7CFF), brandSoft = Color(0xFF8A97FF),
            textPrimary = Color(0xFFEEF0FB), textSecondary = Color(0xFFA9B1D4), border = Color(0xFF2F3543),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "codex-warm", name = "Codex 暖", light = false,
        colors = ThemeColors(
            background = Color(0xFF2D2D2B), surface = Color(0xFF363633), surfaceHigh = Color(0xFF3F3F3B),
            brand = Color(0xFFCC7D5E), brandSoft = Color(0xFFDB916F),
            textPrimary = Color(0xFFF9F9F7), textSecondary = Color(0xFFC9C9C4), border = Color(0xFF464644),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "coffee", name = "暖咖", light = true,
        colors = ThemeColors(
            background = Color(0xFFF7F3EC), surface = Color(0xFFFFFDF8), surfaceHigh = Color(0xFFF0E8DB),
            brand = Color(0xFF8B5E34), brandSoft = Color(0xFFA3744A),
            textPrimary = Color(0xFF2F2921), textSecondary = Color(0xFF776A58), border = Color(0xFFDBD4C8),
            success = Color(0xFF1F9D71), warn = Color(0xFFB07C10), error = Color(0xFFC74F4F),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "forest", name = "森林绿", light = false,
        colors = ThemeColors(
            background = Color(0xFF0A120D), surface = Color(0xFF101A13), surfaceHigh = Color(0xFF17241A),
            brand = Color(0xFF34D37B), brandSoft = Color(0xFF5AE295),
            textPrimary = Color(0xFFE7F5EB), textSecondary = Color(0xFF9DC4A9), border = Color(0xFF233E2D),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "graphite", name = "石墨灰", light = false,
        colors = ThemeColors(
            background = Color(0xFF0F0F11), surface = Color(0xFF17171A), surfaceHigh = Color(0xFF1E1E22),
            brand = Color(0xFFB9BDC8), brandSoft = Color(0xFFD2D5DE),
            textPrimary = Color(0xFFEDEDF0), textSecondary = Color(0xFFA2A2AB), border = Color(0xFF313132),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "maid-navy", name = "深海蕾丝", light = false,
        colors = ThemeColors(
            background = Color(0xFF0B193F), surface = Color(0xFF10204D), surfaceHigh = Color(0xFF1C326B),
            brand = Color(0xFF526AA8), brandSoft = Color(0xFF6D84C4),
            textPrimary = Color(0xFFEEF2FF), textSecondary = Color(0xFFA9BCE8), border = Color(0xFF2C3D69),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "matrix", name = "代码绿", light = false,
        colors = ThemeColors(
            background = Color(0xFF07120A), surface = Color(0xFF0C1C10), surfaceHigh = Color(0xFF122715),
            brand = Color(0xFF22C55E), brandSoft = Color(0xFF4ADE80),
            textPrimary = Color(0xFFE4F7E9), textSecondary = Color(0xFF93C9A0), border = Color(0xFF163F24),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "midnight", name = "午夜黑", light = false,
        colors = ThemeColors(
            background = Color(0xFF000000), surface = Color(0xFF0B0B0F), surfaceHigh = Color(0xFF141419),
            brand = Color(0xFF7C8CFF), brandSoft = Color(0xFF9AA7FF),
            textPrimary = Color(0xFFE8E8EE), textSecondary = Color(0xFF9D9DAA), border = Color(0xFF1F1F1F),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "night-city", name = "夜之城", light = false,
        colors = ThemeColors(
            background = Color(0xFF05070D), surface = Color(0xFF0A0E18), surfaceHigh = Color(0xFF0D1322),
            brand = Color(0xFFFCE300), brandSoft = Color(0xFF00F0FF),
            textPrimary = Color(0xFFE8F1FF), textSecondary = Color(0xFF9FB2D6), border = Color(0xFF043A42),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "nord", name = "Nord 北境", light = false,
        colors = ThemeColors(
            background = Color(0xFF2E3440), surface = Color(0xFF3B4252), surfaceHigh = Color(0xFF434C5E),
            brand = Color(0xFF88C0D0), brandSoft = Color(0xFF8FBCBB),
            textPrimary = Color(0xFFECEFF4), textSecondary = Color(0xFFD8DEE9), border = Color(0xFF494F5B),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "ocean", name = "海洋蓝", light = false,
        colors = ThemeColors(
            background = Color(0xFF0A101F), surface = Color(0xFF101A30), surfaceHigh = Color(0xFF16233E),
            brand = Color(0xFF4D86F8), brandSoft = Color(0xFF6D9DFA),
            textPrimary = Color(0xFFE9EEF9), textSecondary = Color(0xFFA5B3CC), border = Color(0xFF2E3647),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "paper", name = "纸张米黄", light = true,
        colors = ThemeColors(
            background = Color(0xFFFAF7F1), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF4EFE5),
            brand = Color(0xFFB45309), brandSoft = Color(0xFFD97706),
            textPrimary = Color(0xFF2E2A22), textSecondary = Color(0xFF6F675A), border = Color(0xFFE3DCCE),
            success = Color(0xFF1F9D71), warn = Color(0xFFB07C10), error = Color(0xFFC74F4F),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "sakura", name = "樱花粉", light = true,
        colors = ThemeColors(
            background = Color(0xFFFDF5F7), surface = Color(0xFFFFFFFF), surfaceHigh = Color(0xFFF9E8EE),
            brand = Color(0xFFDB2777), brandSoft = Color(0xFFEC4899),
            textPrimary = Color(0xFF3B2530), textSecondary = Color(0xFF8B6576), border = Color(0xFFF2D7E0),
            success = Color(0xFF1F9D71), warn = Color(0xFFB07C10), error = Color(0xFFC74F4F),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "solarized", name = "Solarized 日光", light = false,
        colors = ThemeColors(
            background = Color(0xFF002B36), surface = Color(0xFF073642), surfaceHigh = Color(0xFF0D4250),
            brand = Color(0xFF268BD2), brandSoft = Color(0xFF2AA1E0),
            textPrimary = Color(0xFF839496), textSecondary = Color(0xFF93A1A1), border = Color(0xFF1F444D),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "sunset", name = "日落紫", light = false,
        colors = ThemeColors(
            background = Color(0xFF150F1F), surface = Color(0xFF1D152B), surfaceHigh = Color(0xFF261C38),
            brand = Color(0xFFC084FC), brandSoft = Color(0xFFD4A4FD),
            textPrimary = Color(0xFFF4EDFC), textSecondary = Color(0xFFC2AEE0), border = Color(0xFF3F374C),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    ThemeDef(
        id = "whale", name = "蓝鲸", light = false,
        colors = ThemeColors(
            background = Color(0xFF0D1B2A), surface = Color(0xFF1B2838), surfaceHigh = Color(0xFF243447),
            brand = Color(0xFF4D6BFE), brandSoft = Color(0xFF5D7BFE),
            textPrimary = Color(0xFFE6EDF7), textSecondary = Color(0xFF9DB2CE), border = Color(0xFF243853),
            success = Color(0xFF3FB68B), warn = Color(0xFFE6B455), error = Color(0xFFE06C6C),
        ),
        version = "1.0",
    ),
    )

    private val builtins = listOf(blue, black, warm) + skinThemes
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
        ThemeDef(file.id, file.name, file.light, colors, file.version?.takeIf { it.isNotBlank() })
    }.getOrNull()
}
