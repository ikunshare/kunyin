package com.ikunshare.sound.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 歌词颜色配置，通过 CompositionLocal 在歌词组件中获取。
 */
data class LyricColors(
    val playedInactive: Color,
    val unplayedInactive: Color,
    val playedActive: Color,
    val unplayedActive: Color,
    val lineHighlight: Color,        // 活跃歌词行背景高亮
    val inactiveAlpha: Float         // 非活跃行额外透明度（1.0 = 无额外透明，由 HEXA 控制）
)

val LocalLyricColors = staticCompositionLocalOf {
    // 默认锁定为白色，与 Apple Music 一致（实际值由 KunSoundTheme 提供）。
    LyricColors(
        playedInactive = Color.White,
        unplayedInactive = Color.White,
        playedActive = Color.White,
        unplayedActive = Color.White.copy(alpha = 0.4f),
        lineHighlight = Color.Transparent,
        inactiveAlpha = 0.4f
    )
}

/**
 * 应用级额外颜色配置（非 Material 标准色）。
 */
data class UiColors(
    val selectionHighlight: Color,  // 选择歌曲高亮遮罩
    val bottomBarColor: Color,      // 底栏/搜索栏背景色
    val songCardColor: Color        // 歌曲卡片背景色
)

val LocalUiColors = staticCompositionLocalOf {
    UiColors(
        selectionHighlight = Color(0xFF22C55E).copy(alpha = 0.12f),
        bottomBarColor = Color(0xFF1A1A2E),
        songCardColor = Color(0xFF1A1A2E)
    )
}
