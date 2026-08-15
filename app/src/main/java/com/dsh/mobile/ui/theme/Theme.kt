package com.dsh.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    applyShapeStyle(theme.style)
}

// ── Telegram / Twitter 风格常量（随主题风格切换：STANDARD 圆润 / CODEX 方角）──
object DshShape {
    var bubble = RoundedCornerShape(18.dp)
    var userBubble = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    var assistantBubble = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    var pill = RoundedCornerShape(24.dp)
    var card = RoundedCornerShape(14.dp)
    var small = RoundedCornerShape(10.dp)
}

/** 当前主题风格（组件装饰按它分支：等宽/方角/终端细节） */
var DshThemeStyle = ThemeStyle.STANDARD; private set

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
    }
}

/**
 * 排版体系（不再用默认 Typography()）：
 * - STANDARD：官方 DSH 风格——标题收紧字距加粗、正文 15sp/22sp 行高、标签小字加字距
 * - CODEX：全等宽终端排版——字号整体偏小、行高紧凑、代码感
 */
private fun dshTypography(style: ThemeStyle): Typography {
    val base = if (style == ThemeStyle.CODEX) FontFamily.Monospace else FontFamily.Default
    return Typography(
        displayLarge = TextStyle(fontFamily = base, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 40.sp, letterSpacing = (-0.5).sp),
        headlineLarge = TextStyle(fontFamily = base, fontSize = 27.sp, fontWeight = FontWeight.Bold, lineHeight = 33.sp, letterSpacing = (-0.3).sp),
        headlineMedium = TextStyle(fontFamily = base, fontSize = 23.sp, fontWeight = FontWeight.Bold, lineHeight = 29.sp, letterSpacing = (-0.2).sp),
        headlineSmall = TextStyle(fontFamily = base, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, lineHeight = 25.sp),
        titleLarge = TextStyle(fontFamily = base, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
        titleMedium = TextStyle(fontFamily = base, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
        titleSmall = TextStyle(fontFamily = base, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, letterSpacing = 0.1.sp),
        bodyLarge = TextStyle(fontFamily = base, fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 24.sp),
        bodyMedium = TextStyle(fontFamily = base, fontSize = 15.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp),
        bodySmall = TextStyle(fontFamily = base, fontSize = 12.5.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
        labelLarge = TextStyle(fontFamily = base, fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
        labelMedium = TextStyle(fontFamily = base, fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, letterSpacing = 0.3.sp),
        labelSmall = TextStyle(fontFamily = base, fontSize = 11.sp, fontWeight = FontWeight.Medium, lineHeight = 15.sp, letterSpacing = 0.4.sp),
    )
}

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
    content: @Composable () -> Unit,
) {
    applyPalette(theme, bgActive, glass)
    MaterialTheme(
        colorScheme = dshColorScheme(theme, bgActive, glass),
        typography = dshTypography(theme.style),
        shapes = dshShapes(theme.style),
        content = content,
    )
}
