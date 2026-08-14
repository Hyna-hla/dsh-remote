package com.dsh.mobile.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DSH 深色主题色板（对齐 Web 端 --dsw-alias-* 的深色值）
val DshBackground = Color(0xFF0D1B2A)      // --dsw-alias-bg-base (dark)
val DshSurface = Color(0xFF1B2838)         // --dsw-alias-bg-layer-1 (dark)
val DshSurfaceHigh = Color(0xFF243447)     // --dsw-alias-bg-layer-2 (dark)
val DshBrand = Color(0xFF4D7CFE)           // 品牌蓝
val DshBrandSoft = Color(0xFF64B5F6)
val DshTextPrimary = Color(0xFFE6EDF7)
val DshTextSecondary = Color(0xFF9DB2CE)
val DshBorder = Color(0xFF2C3E55)
val DshSuccess = Color(0xFF3FB68B)
val DshWarn = Color(0xFFE6B455)
val DshError = Color(0xFFE06C6C)

private val DshColors = darkColorScheme(
    primary = DshBrand,
    onPrimary = Color(0xFF0D1B2A),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFDCE7FB),
    secondary = DshBrandSoft,
    onSecondary = Color(0xFF0D1B2A),
    tertiary = DshWarn,
    onTertiary = Color(0xFF241A05),
    error = DshError,
    onError = Color(0xFF2A0B0B),
    errorContainer = Color(0xFF3A1D22),
    onErrorContainer = Color(0xFFFFD9DB),
    background = DshBackground,
    onBackground = DshTextPrimary,
    surface = DshSurface,
    onSurface = DshTextPrimary,
    surfaceVariant = DshSurfaceHigh,
    onSurfaceVariant = DshTextSecondary,
    outline = DshBorder,
    outlineVariant = Color(0xFF23354B),
)

@Composable
fun DshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DshColors,
        typography = Typography(),
        content = content,
    )
}
