package com.dsh.mobile.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── 语义色（随主题切换，组件内直接用）───────────────────────────────────────
object DshPalette {
    var bg: Color = ThemeRegistry.blue.colors.background
    var surface: Color = ThemeRegistry.blue.colors.surface
    var surfaceHigh: Color = ThemeRegistry.blue.colors.surfaceHigh
    var brand: Color = ThemeRegistry.blue.colors.brand
    var brandSoft: Color = ThemeRegistry.blue.colors.brandSoft
    var border: Color = ThemeRegistry.blue.colors.border
    var success: Color = ThemeRegistry.blue.colors.success
    var warn: Color = ThemeRegistry.blue.colors.warn
    var error: Color = ThemeRegistry.blue.colors.error
}

var DshBackground = ThemeRegistry.blue.colors.background; private set
var DshSurface = ThemeRegistry.blue.colors.surface; private set
var DshSurfaceHigh = ThemeRegistry.blue.colors.surfaceHigh; private set
var DshBrand = ThemeRegistry.blue.colors.brand; private set
var DshBrandSoft = ThemeRegistry.blue.colors.brandSoft; private set
var DshBorder = ThemeRegistry.blue.colors.border; private set
var DshSuccess = ThemeRegistry.blue.colors.success; private set
var DshWarn = ThemeRegistry.blue.colors.warn; private set
var DshError = ThemeRegistry.blue.colors.error; private set

/** 主题是否浅色（状态栏图标对比与蒙层颜色选择） */
fun isLightMode(theme: ThemeDef): Boolean = theme.light

/**
 * 面板通透系数：glass 0–100 → 各层级表面不透明度。
 * 对齐桌面端 dsh-beautify 的 GLASS_TOKENS 映射。
 */
fun surfaceAlphaFor(glass: Float, isBg: Boolean = true): Triple<Float, Float, Float> {
    if (!isBg) return Triple(1f, 1f, 1f)
    val g = glass.coerceIn(0f, 100f) / 100f
    val bgAlpha = (1f - 0.6f * g).coerceAtLeast(0.3f)
    val l1Alpha = (1f - 0.35f * g).coerceAtLeast(0.45f)
    val l2Alpha = (1f - 0.25f * g).coerceAtLeast(0.55f)
    return Triple(bgAlpha, l1Alpha, l2Alpha)
}

fun applyPalette(theme: ThemeDef, bgActive: Boolean = false, glass: Float = 0f) {
    val (bgA, l1A, l2A) = surfaceAlphaFor(glass, bgActive)
    val c = theme.colors
    DshPalette.bg = c.background.copy(alpha = bgA)
    DshPalette.surface = c.surface.copy(alpha = l1A)
    DshPalette.surfaceHigh = c.surfaceHigh.copy(alpha = l2A)
    DshPalette.brand = c.brand; DshPalette.brandSoft = c.brandSoft
    DshPalette.border = c.border; DshPalette.success = c.success
    DshPalette.warn = c.warn; DshPalette.error = c.error
    DshBackground = DshPalette.bg; DshSurface = DshPalette.surface
    DshSurfaceHigh = DshPalette.surfaceHigh; DshBrand = DshPalette.brand
    DshBrandSoft = DshPalette.brandSoft; DshBorder = DshPalette.border
    DshSuccess = DshPalette.success; DshWarn = DshPalette.warn
    DshError = DshPalette.error
    DshThemeStyle = theme.style
    DshThemeId = theme.id
    applyShapeStyle(theme.style)
}

// ── Telegram / Twitter 风格常量（随主题风格切换：STANDARD 圆润 / CODEX 方角 / CYBERPUNK 切角）──
object DshShape {
    var bubble: Shape = RoundedCornerShape(18.dp)
    var userBubble: Shape = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    var assistantBubble: Shape = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    var pill: Shape = RoundedCornerShape(24.dp)
    var card: Shape = RoundedCornerShape(14.dp)
    var small: Shape = RoundedCornerShape(10.dp)
}

/** 当前主题风格（组件装饰按它分支：等宽/方角/终端细节） */
var DshThemeStyle = ThemeStyle.STANDARD; private set

/** 当前主题 id（如 blue/codex/cyberpunk；按 id 启用专属装扮） */
var DshThemeId = "blue"; private set

/** 品牌渐变（主按钮/强调装饰用）：brand → brandSoft，随主题色走 */
fun brandGradient(): Brush = Brush.linearGradient(listOf(DshPalette.brand, DshPalette.brandSoft))

private fun applyShapeStyle(style: ThemeStyle) {
    when (style) {
        ThemeStyle.STANDARD -> {
            DshShape.bubble = RoundedCornerShape(18.dp)
            DshShape.userBubble = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
            DshShape.assistantBubble = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
            DshShape.pill = RoundedCornerShape(24.dp)
            DshShape.card = RoundedCornerShape(14.dp)
            DshShape.small = RoundedCornerShape(10.dp)
        }
        ThemeStyle.CODEX -> {
            // 终端风：方角 + 极小小圆角
            val sq = RoundedCornerShape(3.dp)
            DshShape.bubble = sq
            DshShape.userBubble = sq
            DshShape.assistantBubble = sq
            DshShape.pill = RoundedCornerShape(6.dp)
            DshShape.card = RoundedCornerShape(6.dp)
            DshShape.small = RoundedCornerShape(4.dp)
        }
        ThemeStyle.CYBERPUNK -> {
            // 夜之城：45° 切角（chamfer），工业感
            val cut = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
            val cutSm = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
            DshShape.bubble = cutSm
            DshShape.userBubble = cutSm
            DshShape.assistantBubble = cutSm
            DshShape.pill = cut
            DshShape.card = cut
            DshShape.small = cutSm
        }
    }
}

/** 主题字体设置（对齐 dsh-font 插件概念：界面字体 + 代码字体分离） */
data class FontConfig(
    /** 界面字体（Android 系统字体族名，null = 主题默认） */
    val ui: String? = null,
    /** 代码字体（等宽族名，null = Monospace） */
    val code: String? = null,
)

private fun familyOf(name: String?, fallback: FontFamily): FontFamily =
    if (name.isNullOrBlank()) fallback
    else runCatching {
        // 系统字体族名 → Typeface（未安装的族名由系统回退，不抛错）
        FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL))
    }.getOrDefault(fallback)

/**
 * 排版体系（不再用默认 Typography()）：
 * - STANDARD：官方 DSH 风格——标题收紧字距加粗、正文 15sp/22sp 行高、标签小字加字距
 * - CODEX：全等宽终端排版——字号整体偏小、行高紧凑、代码感
 * - CYBERPUNK：无衬线大字重（MiSans 风格）——标题更粗更紧，正文稍大
 * fonts.ui / fonts.code 覆盖字体族（设置页可选）
 */
private fun dshTypography(style: ThemeStyle, fonts: FontConfig = FontConfig()): Typography {
    val base = familyOf(
        fonts.ui,
        when (style) {
            ThemeStyle.CODEX -> FontFamily.Monospace
            else -> FontFamily.Default
        },
    )
    val code = familyOf(fonts.code, FontFamily.Monospace)
    val titleWeight = if (style == ThemeStyle.CYBERPUNK) FontWeight.Black else FontWeight.Bold
    val titleSpacing = if (style == ThemeStyle.CYBERPUNK) (-0.6).sp else (-0.3).sp
    val bodySize = if (style == ThemeStyle.CYBERPUNK) 15.5.sp else 15.sp
    val bodyLine = if (style == ThemeStyle.CYBERPUNK) 23.sp else 22.sp
    return Typography(
        displayLarge = TextStyle(fontFamily = base, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
        headlineLarge = TextStyle(fontFamily = base, fontSize = 27.sp, fontWeight = titleWeight, lineHeight = 33.sp, letterSpacing = titleSpacing),
        headlineMedium = TextStyle(fontFamily = base, fontSize = 23.sp, fontWeight = titleWeight, lineHeight = 29.sp, letterSpacing = (-0.2).sp),
        headlineSmall = TextStyle(fontFamily = base, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
        titleLarge = TextStyle(fontFamily = base, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = base, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = base, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = base, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = base, fontSize = bodySize, fontWeight = FontWeight.Normal, lineHeight = bodyLine),
        bodySmall = TextStyle(fontFamily = base, fontSize = 12.5.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = base, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = base, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontFamily = base, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp, letterSpacing = 0.4.sp),
    ).also { _ -> codeFamily = code }
}

/** 当前代码字体（Typography 没有 code 槽位，单独记录供代码块/参数/终端文本使用） */
private var codeFamily: FontFamily = FontFamily.Monospace

/** 代码字体（等宽），供 MarkdownText/工具参数/终端装饰统一使用 */
fun dshCodeFont(): FontFamily = codeFamily

private fun dshShapes(style: ThemeStyle): Shapes = when (style) {
    ThemeStyle.STANDARD -> Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
    ThemeStyle.CODEX -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
        extraLarge = RoundedCornerShape(10.dp),
    )
    ThemeStyle.CYBERPUNK -> Shapes(
        extraSmall = CutCornerShape(topEnd = 4.dp, bottomStart = 4.dp),
        small = CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp),
        medium = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp),
        large = CutCornerShape(topEnd = 14.dp, bottomStart = 14.dp),
        extraLarge = CutCornerShape(topEnd = 16.dp, bottomStart = 16.dp),
    )
}

private fun dshColorScheme(theme: ThemeDef, bgActive: Boolean = false, glass: Float = 0f): ColorScheme {
    val (bgA, l1A, l2A) = surfaceAlphaFor(glass, bgActive)
    val c = theme.colors
    val background = c.background.copy(alpha = bgA)
    val surface = c.surface.copy(alpha = l1A)
    val surfaceVariant = c.surfaceHigh.copy(alpha = l2A)
    return if (theme.light) lightColorScheme(
        primary = c.brand, onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE7FB), onPrimaryContainer = Color(0xFF12275C),
        secondary = c.brandSoft, onSecondary = Color(0xFFFFFFFF),
        tertiary = c.warn, onTertiary = Color(0xFF2B2003),
        error = c.error, onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFBE0E0), onErrorContainer = Color(0xFF4A1515),
        background = background, onBackground = c.textPrimary,
        surface = surface, onSurface = c.textPrimary,
        surfaceVariant = surfaceVariant, onSurfaceVariant = c.textSecondary,
        outline = c.border, outlineVariant = Color(0xFFE4EAF3),
    ) else darkColorScheme(
        primary = c.brand, onPrimary = Color(0xFF0D1B2A),
        primaryContainer = Color(0xFF1B3A6B), onPrimaryContainer = Color(0xFFDCE7FB),
        secondary = c.brandSoft, onSecondary = Color(0xFF0D1B2A),
        tertiary = c.warn, onTertiary = Color(0xFF241A05),
        error = c.error, onError = Color(0xFF2A0B0B),
        errorContainer = Color(0xFF3A1D22), onErrorContainer = Color(0xFFFFD9DB),
        background = background, onBackground = c.textPrimary,
        surface = surface, onSurface = c.textPrimary,
        surfaceVariant = surfaceVariant, onSurfaceVariant = c.textSecondary,
        outline = c.border, outlineVariant = Color(0xFF23354B),
    )
}

@Composable
fun DshTheme(
    theme: ThemeDef = ThemeRegistry.blue,
    bgActive: Boolean = false,
    glass: Float = 0f,
    fonts: FontConfig = FontConfig(),
    content: @Composable () -> Unit,
) {
    applyPalette(theme, bgActive, glass)
    MaterialTheme(
        colorScheme = dshColorScheme(theme, bgActive, glass),
        typography = dshTypography(theme.style, fonts),
        shapes = dshShapes(theme.style),
        content = content,
    )
}
