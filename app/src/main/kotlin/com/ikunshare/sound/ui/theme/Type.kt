package com.ikunshare.sound.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** 全局字重偏移：将基础字重按 delta 叠加并夹取到 100~900 */
private fun adjustWeight(base: FontWeight, delta: Int): FontWeight =
    if (delta == 0) base else FontWeight((base.weight + delta).coerceIn(100, 900))

/**
 * 构建应用级 Typography，支持字体自定义。
 *
 * @param scale 全局字号缩放（建议 0.8~1.4）。
 * @param weightAdjust 全局字重偏移（-100 细体 / 0 标准 / +100 加粗），逐样式叠加并夹取到 100~900。
 */
fun buildAppTypography(
    scale: Float = 1f,
    weightAdjust: Int = 0
): Typography {
    val s = scale.coerceIn(0.7f, 1.6f)

    fun style(size: Float, line: Float, weight: FontWeight) = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = adjustWeight(weight, weightAdjust),
        fontSize = (size * s).sp,
        lineHeight = (line * s).sp,
        letterSpacing = 0.sp
    )

    return Typography(
        displayLarge = style(57f, 64f, FontWeight.Bold),
        displayMedium = style(45f, 52f, FontWeight.Bold),
        displaySmall = style(36f, 44f, FontWeight.Bold),
        headlineLarge = style(32f, 40f, FontWeight.SemiBold),
        headlineMedium = style(28f, 36f, FontWeight.SemiBold),
        headlineSmall = style(24f, 32f, FontWeight.SemiBold),
        titleLarge = style(22f, 28f, FontWeight.Medium),
        titleMedium = style(16f, 24f, FontWeight.Medium),
        titleSmall = style(14f, 20f, FontWeight.Medium),
        bodyLarge = style(16f, 24f, FontWeight.Normal),
        bodyMedium = style(14f, 20f, FontWeight.Normal),
        bodySmall = style(12f, 16f, FontWeight.Normal),
        labelLarge = style(14f, 20f, FontWeight.Medium),
        labelMedium = style(12f, 16f, FontWeight.Medium),
        labelSmall = style(11f, 16f, FontWeight.Medium)
    )
}

/** 默认 Typography（缩放 1.0 / 标准字重 / 系统字体）。 */
val Typography = buildAppTypography()
