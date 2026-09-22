package com.ikunshare.sound.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.ikunshare.sound.common.CustomThemeEntry
import kotlin.math.pow

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF6366F1),
    onPrimary = KunyinColors.TextPrimary,
    primaryContainer = KunyinColors.Primary,
    onPrimaryContainer = KunyinColors.TextPrimary,
    secondary = KunyinColors.Secondary,
    onSecondary = KunyinColors.TextPrimary,
    secondaryContainer = KunyinColors.Secondary,
    onSecondaryContainer = KunyinColors.TextPrimary,
    tertiary = KunyinColors.Accent,
    onTertiary = KunyinColors.Background,
    tertiaryContainer = KunyinColors.Accent,
    onTertiaryContainer = KunyinColors.Background,
    background = KunyinColors.Background,
    onBackground = KunyinColors.TextPrimary,
    surface = KunyinColors.Surface,
    onSurface = KunyinColors.TextPrimary,
    surfaceVariant = KunyinColors.SurfaceVariant,
    onSurfaceVariant = KunyinColors.TextSecondary,
    error = KunyinColors.Error,
    onError = KunyinColors.TextPrimary,
    errorContainer = KunyinColors.Error,
    onErrorContainer = KunyinColors.TextPrimary,
    outline = KunyinColors.Outline,
    outlineVariant = KunyinColors.SurfaceVariant,
    inverseSurface = KunyinColors.TextPrimary,
    inverseOnSurface = KunyinColors.Background,
    inversePrimary = KunyinColors.Secondary,
    scrim = KunyinColors.Background
)

private val LightColorScheme = lightColorScheme(
    primary = LightColors.Primary,
    onPrimary = LightColors.Background,
    primaryContainer = LightColors.Primary,
    onPrimaryContainer = LightColors.Background,
    secondary = LightColors.Secondary,
    onSecondary = LightColors.Background,
    secondaryContainer = LightColors.Secondary,
    onSecondaryContainer = LightColors.Background,
    tertiary = LightColors.Accent,
    onTertiary = LightColors.Surface,
    tertiaryContainer = LightColors.Accent,
    onTertiaryContainer = LightColors.Surface,
    background = LightColors.Background,
    onBackground = LightColors.TextPrimary,
    surface = LightColors.Surface,
    onSurface = LightColors.TextPrimary,
    surfaceVariant = LightColors.SurfaceVariant,
    onSurfaceVariant = LightColors.TextSecondary,
    error = LightColors.Error,
    onError = LightColors.Surface,
    errorContainer = LightColors.Error,
    onErrorContainer = LightColors.Surface,
    outline = LightColors.Outline,
    outlineVariant = LightColors.SurfaceVariant,
    inverseSurface = LightColors.TextPrimary,
    inverseOnSurface = LightColors.Background,
    inversePrimary = LightColors.Secondary,
    scrim = LightColors.TextPrimary
)

/**
 * 计算颜色的相对亮度
 */
private fun luminance(color: Color): Float {
    fun linearize(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055) / 1.055).pow(2.4).toFloat()
    return 0.2126f * linearize(color.red) + 0.7152f * linearize(color.green) + 0.0722f * linearize(
        color.blue
    )
}

/**
 * 根据背景亮度返回黑或白前景色
 */
private fun contrastColor(bg: Color): Color =
    if (luminance(bg) > 0.5f) Color.Black else Color.White

/**
 * 从 CustomThemeColors 构建 Material3 ColorScheme
 */
private fun buildCustomColorScheme(config: CustomThemeColors): ColorScheme {
    val primary = Color(config.primary)
    val secondary = Color(config.secondary)
    val tertiary = Color(config.tertiary)
    val background = Color(config.background)
    val surface = Color(config.surface)
    val surfaceVariant = Color(config.surfaceVariant)
    val onBackground = Color(config.onBackground)
    val onSurfaceVariant = Color(config.onSurfaceVariant)
    val error = Color(config.error)
    val outline = Color(config.outline)
    val dialogBg = Color(config.dialogBackground)

    val onPrimary = contrastColor(primary)
    val onSecondary = contrastColor(secondary)
    val onTertiary = contrastColor(tertiary)
    val onError = contrastColor(error)

    return darkColorScheme(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primary,
        onPrimaryContainer = onPrimary,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondary,
        onSecondaryContainer = onSecondary,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiary,
        onTertiaryContainer = onTertiary,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onBackground,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = dialogBg,
        surfaceContainerHigh = dialogBg,
        surfaceContainerHighest = dialogBg,
        error = error,
        onError = onError,
        errorContainer = error,
        onErrorContainer = onError,
        outline = outline,
        outlineVariant = surfaceVariant,
        inverseSurface = onBackground,
        inverseOnSurface = background,
        inversePrimary = secondary,
        scrim = background
    )
}

/**
 * 根据 themeMode 和系统状态解析出实际生效的主题 ID
 * 返回值: "dark" | "light" | "custom_<id>"
 */
fun resolveActiveThemeId(
    themeMode: String,
    systemLightTheme: String,
    systemDarkTheme: String,
    isSystemDark: Boolean
): String = when {
    themeMode == "system" -> if (isSystemDark) systemDarkTheme else systemLightTheme
    themeMode == "dark" || themeMode == "light" || themeMode.startsWith("custom_") -> themeMode
    else -> if (isSystemDark) "dark" else "light"
}

@Composable
fun KunSoundTheme(
    themeMode: String = "system",
    customThemes: List<CustomThemeEntry> = emptyList(),
    systemLightTheme: String = "light",
    systemDarkTheme: String = "dark",
    fontScale: Float = 1.0f,
    fontWeightAdjust: Int = 0,
    content: @Composable () -> Unit
) {
    val isSystemDark = isSystemInDarkTheme()
    val activeId = resolveActiveThemeId(themeMode, systemLightTheme, systemDarkTheme, isSystemDark)

    val isCustom = activeId.startsWith("custom_")
    val customConfig = if (isCustom) {
        val id = activeId.removePrefix("custom_")
        customThemes.firstOrNull { it.id == id }?.let { CustomThemeColors.fromJson(it.colorsJson) }
    } else null

    val isDark = when (activeId) {
        "light" -> false
        "dark" -> true
        else -> true // 自定义主题基于深色方案
    }

    val colorScheme = when {
        isCustom && customConfig != null -> buildCustomColorScheme(customConfig)
        isDark -> DarkColorScheme
        else -> LightColorScheme
    }

    // 歌词颜色锁定为白色，与 Apple Music 一致（不随主题色变化，也不支持自定义）。
    // 当前行纯白，非活跃行整体降到 40% 透明度；活跃行未唱字为半透明白，逐字唱到时变纯白。
    val lyricColors = LyricColors(
        playedInactive = Color.White,
        unplayedInactive = Color.White,
        playedActive = Color.White,
        unplayedActive = Color.White.copy(alpha = 0.4f),
        lineHighlight = Color.Transparent,
        inactiveAlpha = 0.4f
    )

    // 构建应用级 UI 颜色
    val uiColors = if (isCustom && customConfig != null) {
        UiColors(
            selectionHighlight = Color(customConfig.selectionHighlight),
            bottomBarColor = Color(customConfig.bottomBarColor),
            songCardColor = Color(customConfig.songCardColor)
        )
    } else {
        UiColors(
            selectionHighlight = colorScheme.tertiary.copy(alpha = 0.12f),
            bottomBarColor = colorScheme.surface,
            songCardColor = colorScheme.surface
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 系统栏颜色由 enableEdgeToEdge 控制为透明；这里只更新文字图标深浅
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    // 全局字体自定义：缩放 / 字重偏移
    val typography = remember(fontScale, fontWeightAdjust) {
        buildAppTypography(fontScale, fontWeightAdjust)
    }

    CompositionLocalProvider(
        LocalLyricColors provides lyricColors,
        LocalUiColors provides uiColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}
