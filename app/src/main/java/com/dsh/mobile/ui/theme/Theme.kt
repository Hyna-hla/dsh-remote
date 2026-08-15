package com.dsh.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── 深色主题色板（对齐 Web 端 --dsw-alias-* 深色值）────────────────────────
val DshBackgroundDark = Color(0xFF0D1B2A)      // --dsw-alias-bg-base (dark)
val DshSurfaceDark = Color(0xFF1B2838)         // --dsw-alias-bg-layer-1 (dark)
val DshSurfaceHighDark = Color(0xFF243447)     // --dsw-alias-bg-layer-2 (dark)
val DshBrandDark = Color(0xFF4D7CFE)           // 品牌蓝
val DshBrandSoftDark = Color(0xFF64B5F6)
val DshTextPrimaryDark = Color(0xFFE6EDF7)
val DshTextSecondaryDark = Color(0xFF9DB2CE)
val DshBorderDark = Color(0xFF2C3E55)
val DshSuccessDark = Color(0xFF3FB68B)
val DshWarnDark = Color(0xFFE6B455)
val DshErrorDark = Color(0xFFE06C6C)

// ── 浅色主题色板（对齐 Web 端 --dsw-alias-* 浅色值）────────────────────────
val DshBackgroundLight = Color(0xFFF4F7FB)     // --dsw-alias-bg-base (light)
val DshSurfaceLight = Color(0xFFFFFFFF)        // --dsw-alias-bg-layer-1 (light)
val DshSurfaceHighLight = Color(0xFFEDF1F8)    // --dsw-alias-bg-layer-2 (light)
val DshBrandLight = Color(0xFF3D6BF2)          // 品牌蓝（浅色下略深保证对比度）
val DshBrandSoftLight = Color(0xFF2E7DD1)
val DshTextPrimaryLight = Color(0xFF17233B)
val DshTextSecondaryLight = Color(0xFF5A6B85)
val DshBorderLight = Color(0xFFD8E0EC)
val DshSuccessLight = Color(0xFF1F9D71)
val DshWarnLight = Color(0xFFB07C10)
val DshErrorLight = Color(0xFFC74F4F)

// ── 语义色（随主题切换，组件内直接用）───────────────────────────────────────
object DshPalette {
    var bg: Color = DshBackgroundDark
    var surface: Color = DshSurfaceDark
    var surfaceHigh: Color = DshSurfaceHighDark
    var brand: Color = DshBrandDark
    var brandSoft: Color = DshBrandSoftDark
    var border: Color = DshBorderDark
    var success: Color = DshSuccessDark
    var warn: Color = DshWarnDark
    var error: Color = DshErrorDark
}

// 兼容旧引用的顶层别名（当前主题值，Compose 重组时由 DshTheme 更新）
var DshBackground = DshBackgroundDark; private set
var DshSurface = DshSurfaceDark; private set
var DshSurfaceHigh = DshSurfaceHighDark; private set
var DshBrand = DshBrandDark; private set
var DshBrandSoft = DshBrandSoftDark; private set
var DshBorder = DshBorderDark; private set
var DshSuccess = DshSuccessDark; private set
var DshWarn = DshWarnDark; private set
var DshError = DshErrorDark; private set

fun applyPalette(dark: Boolean) {
    if (dark) {
        DshPalette.bg = DshBackgroundDark; DshPalette.surface = DshSurfaceDark
        DshPalette.surfaceHigh = DshSurfaceHighDark; DshPalette.brand = DshBrandDark
        DshPalette.brandSoft = DshBrandSoftDark; DshPalette.border = DshBorderDark
        DshPalette.success = DshSuccessDark; DshPalette.warn = DshWarnDark
        DshPalette.error = DshErrorDark
    } else {
        DshPalette.bg = DshBackgroundLight; DshPalette.surface = DshSurfaceLight
        DshPalette.surfaceHigh = DshSurfaceHighLight; DshPalette.brand = DshBrandLight
        DshPalette.brandSoft = DshBrandSoftLight; DshPalette.border = DshBorderLight
        DshPalette.success = DshSuccessLight; DshPalette.warn = DshWarnLight
        DshPalette.error = DshErrorLight
    }
    DshBackground = DshPalette.bg; DshSurface = DshPalette.surface
    DshSurfaceHigh = DshPalette.surfaceHigh; DshBrand = DshPalette.brand
    DshBrandSoft = DshPalette.brandSoft; DshBorder = DshPalette.border
    DshSuccess = DshPalette.success; DshWarn = DshPalette.warn
    DshError = DshPalette.error
}

// ── Telegram / Twitter 风格常量 ─────────────────────────────────────────────
// Telegram 感：大圆角气泡、胶囊输入栏、圆形头像；Twitter 感：细分割线、紧凑行高
object DshShape {
    /** 聊天气泡主圆角（Telegram 式大圆角） */
    val bubble = RoundedCornerShape(18.dp)
    /** 我方气泡：右上角小尾巴 */
    val userBubble = RoundedCornerShape(18.dp, 6.dp, 18.dp, 18.dp)
    /** 对方气泡：左上角小尾巴 */
    val assistantBubble = RoundedCornerShape(6.dp, 18.dp, 18.dp, 18.dp)
    /** 输入栏胶囊 */
    val pill = RoundedCornerShape(24.dp)
    /** 卡片/面板 */
    val card = RoundedCornerShape(14.dp)
    /** 小控件 */
    val small = RoundedCornerShape(10.dp)
}

private fun dshColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme(
    primary = DshBrandDark,
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFDCE7FB),
    secondary = DshBrandSoftDark,
    onSecondary = Color(0xFF0D1B2A),
    tertiary = DshWarnDark,
    onTertiary = Color(0xFF241A05),
    error = DshErrorDark,
    onError = Color(0xFF2A0B0B),
    errorContainer = Color(0xFF3A1D22),
    onErrorContainer = Color(0xFFFFD9DB),
    background = DshBackgroundDark,
    onBackground = DshTextPrimaryDark,
    surface = DshSurfaceDark,
    onSurface = DshTextPrimaryDark,
    surfaceVariant = DshSurfaceHighDark,
    onSurfaceVariant = DshTextSecondaryDark,
    outline = DshBorderDark,
    outlineVariant = Color(0xFF23354B),
) else lightColorScheme(
    primary = DshBrandLight,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE7FB),
    onPrimaryContainer = Color(0xFF12275C),
    secondary = DshBrandSoftLight,
    onSecondary = Color(0xFFFFFFFF),
    tertiary = DshWarnLight,
    onTertiary = Color(0xFF2B2003),
    error = DshErrorLight,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFBE0E0),
    onErrorContainer = Color(0xFF4A1515),
    background = DshBackgroundLight,
    onBackground = DshTextPrimaryLight,
    surface = DshSurfaceLight,
    onSurface = DshTextPrimaryLight,
    surfaceVariant = DshSurfaceHighLight,
    onSurfaceVariant = DshTextSecondaryLight,
    outline = DshBorderLight,
    outlineVariant = Color(0xFFE4EAF3),
)

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    applyPalette(darkTheme)
    MaterialTheme(
        colorScheme = dshColorScheme(darkTheme),
        typography = Typography(),
        content = content,
    )
}
