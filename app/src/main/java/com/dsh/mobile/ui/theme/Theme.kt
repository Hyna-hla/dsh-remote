package com.dsh.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ── DeepSeek 经典蓝（深蓝主题，对齐 Web 端 --dsw-alias-* 深色值）────────────
val DshBackgroundBlue = Color(0xFF0D1B2A)
val DshSurfaceBlue = Color(0xFF1B2838)
val DshSurfaceHighBlue = Color(0xFF243447)
val DshBrandBlue = Color(0xFF4D7CFE)
val DshBrandSoftBlue = Color(0xFF64B5F6)
val DshTextPrimaryBlue = Color(0xFFE6EDF7)
val DshTextSecondaryBlue = Color(0xFF9DB2CE)
val DshBorderBlue = Color(0xFF2C3E55)
val DshSuccessBlue = Color(0xFF3FB68B)
val DshWarnBlue = Color(0xFFE6B455)
val DshErrorBlue = Color(0xFFE06C6C)

// ── 纯黑（AMOLED 黑）───────────────────────────────────────────────────────
val DshBackgroundBlack = Color(0xFF000000)
val DshSurfaceBlack = Color(0xFF121418)
val DshSurfaceHighBlack = Color(0xFF1B1E24)
val DshBrandBlack = Color(0xFF4D7CFE)
val DshBrandSoftBlack = Color(0xFF64B5F6)
val DshTextPrimaryBlack = Color(0xFFE8EDF5)
val DshTextSecondaryBlack = Color(0xFF9AA6B8)
val DshBorderBlack = Color(0xFF2A2F3A)
val DshSuccessBlack = Color(0xFF3FB68B)
val DshWarnBlack = Color(0xFFE6B455)
val DshErrorBlack = Color(0xFFE06C6C)

// ── 暖白（护眼米白）────────────────────────────────────────────────────────
val DshBackgroundWarm = Color(0xFFFAF6EF)
val DshSurfaceWarm = Color(0xFFFFFFFF)
val DshSurfaceHighWarm = Color(0xFFF1EAE0)
val DshBrandWarm = Color(0xFF3D5AF1)
val DshBrandSoftWarm = Color(0xFF2E7DD1)
val DshTextPrimaryWarm = Color(0xFF2E2A24)
val DshTextSecondaryWarm = Color(0xFF7B7265)
val DshBorderWarm = Color(0xFFE6DCC9)
val DshSuccessWarm = Color(0xFF1F9D71)
val DshWarnWarm = Color(0xFFB07C10)
val DshErrorWarm = Color(0xFFC74F4F)

// ── 语义色（随主题切换，组件内直接用）───────────────────────────────────────
object DshPalette {
    var bg: Color = DshBackgroundBlue
    var surface: Color = DshSurfaceBlue
    var surfaceHigh: Color = DshSurfaceHighBlue
    var brand: Color = DshBrandBlue
    var brandSoft: Color = DshBrandSoftBlue
    var border: Color = DshBorderBlue
    var success: Color = DshSuccessBlue
    var warn: Color = DshWarnBlue
    var error: Color = DshErrorBlue
}

var DshBackground = DshBackgroundBlue; private set
var DshSurface = DshSurfaceBlue; private set
var DshSurfaceHigh = DshSurfaceHighBlue; private set
var DshBrand = DshBrandBlue; private set
var DshBrandSoft = DshBrandSoftBlue; private set
var DshBorder = DshBorderBlue; private set
var DshSuccess = DshSuccessBlue; private set
var DshWarn = DshWarnBlue; private set
var DshError = DshErrorBlue; private set

/** 主题是否浅色（用于状态栏图标对比与蒙层颜色选择） */
fun isLightMode(mode: String): Boolean = mode == "warm"

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

fun applyPalette(mode: String, bgActive: Boolean = false, glass: Float = 0f) {
    val (bgA, l1A, l2A) = surfaceAlphaFor(glass, bgActive)
    val warm = mode == "warm"
    val black = mode == "black"
    val p: List<Color> = when {
        warm -> listOf(
            DshBackgroundWarm, DshSurfaceWarm, DshSurfaceHighWarm, DshBrandWarm, DshBrandSoftWarm,
            DshTextPrimaryWarm, DshTextSecondaryWarm, DshBorderWarm, DshSuccessWarm, DshWarnWarm, DshErrorWarm,
        )
        black -> listOf(
            DshBackgroundBlack, DshSurfaceBlack, DshSurfaceHighBlack, DshBrandBlack, DshBrandSoftBlack,
            DshTextPrimaryBlack, DshTextSecondaryBlack, DshBorderBlack, DshSuccessBlack, DshWarnBlack, DshErrorBlack,
        )
        else -> listOf(
            DshBackgroundBlue, DshSurfaceBlue, DshSurfaceHighBlue, DshBrandBlue, DshBrandSoftBlue,
            DshTextPrimaryBlue, DshTextSecondaryBlue, DshBorderBlue, DshSuccessBlue, DshWarnBlue, DshErrorBlue,
        )
    }
    DshPalette.bg = p[0].copy(alpha = bgA)
    DshPalette.surface = p[1].copy(alpha = l1A)
    DshPalette.surfaceHigh = p[2].copy(alpha = l2A)
    DshPalette.brand = p[3]; DshPalette.brandSoft = p[4]
    DshPalette.border = p[7]; DshPalette.success = p[8]
    DshPalette.warn = p[9]; DshPalette.error = p[10]
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

private fun dshColorScheme(mode: String, bgActive: Boolean = false, glass: Float = 0f): ColorScheme {
    val (bgA, l1A, l2A) = surfaceAlphaFor(glass, bgActive)
    val light = isLightMode(mode)
    val palette = if (light) arrayOf(
        DshBackgroundWarm, DshSurfaceWarm, DshSurfaceHighWarm, DshBrandWarm, DshBrandSoftWarm,
        DshTextPrimaryWarm, DshTextSecondaryWarm, DshBorderWarm, DshWarnWarm, DshErrorWarm,
    ) else arrayOf(
        DshBackgroundBlue, DshSurfaceBlue, DshSurfaceHighBlue, DshBrandBlue, DshBrandSoftBlue,
        DshTextPrimaryBlue, DshTextSecondaryBlue, DshBorderBlue, DshWarnBlue, DshErrorBlue,
    )
    val background = (palette[0] as Color).copy(alpha = bgA)
    val surface = (palette[1] as Color).copy(alpha = l1A)
    val surfaceVariant = (palette[2] as Color).copy(alpha = l2A)
    val brand = palette[3] as Color
    val brandSoft = palette[4] as Color
    val textPrimary = palette[5] as Color
    val textSecondary = palette[6] as Color
    val border = palette[7] as Color
    val warn = palette[8] as Color
    val error = palette[9] as Color
    return if (light) lightColorScheme(
        primary = brand, onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE7FB), onPrimaryContainer = Color(0xFF12275C),
        secondary = brandSoft, onSecondary = Color(0xFFFFFFFF),
        tertiary = warn, onTertiary = Color(0xFF2B2003),
        error = error, onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFBE0E0), onErrorContainer = Color(0xFF4A1515),
        background = background, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceVariant, onSurfaceVariant = textSecondary,
        outline = border, outlineVariant = Color(0xFFE4EAF3),
    ) else darkColorScheme(
        primary = brand, onPrimary = Color(0xFF0D1B2A),
        primaryContainer = Color(0xFF1B3A6B), onPrimaryContainer = Color(0xFFDCE7FB),
        secondary = brandSoft, onSecondary = Color(0xFF0D1B2A),
        tertiary = warn, onTertiary = Color(0xFF241A05),
        error = error, onError = Color(0xFF2A0B0B),
        errorContainer = Color(0xFF3A1D22), onErrorContainer = Color(0xFFFFD9DB),
        background = background, onBackground = textPrimary,
        surface = surface, onSurface = textPrimary,
        surfaceVariant = surfaceVariant, onSurfaceVariant = textSecondary,
        outline = border, outlineVariant = Color(0xFF23354B),
    )
}

@Composable
fun DshTheme(
    mode: String = "blue",
    bgActive: Boolean = false,
    glass: Float = 0f,
    content: @Composable () -> Unit,
) {
    applyPalette(mode, bgActive, glass)
    MaterialTheme(
        colorScheme = dshColorScheme(mode, bgActive, glass),
        typography = Typography(),
        content = content,
    )
}
