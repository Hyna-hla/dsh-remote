package com.dsh.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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
}

// ── Telegram / Twitter 风格常量 ─────────────────────────────────────────────
object DshShape {
    val bubble = RoundedCornerShape(18.dp)
    val userBubble = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    val assistantBubble = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    val pill = RoundedCornerShape(24.dp)
    val card = RoundedCornerShape(14.dp)
    val small = RoundedCornerShape(10.dp)
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
        typography = Typography(),
        content = content,
    )
}
