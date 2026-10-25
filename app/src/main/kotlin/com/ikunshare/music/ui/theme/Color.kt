package com.ikunshare.music.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 默认配色。
 *
 * 中性近黑/暖白底 + 琥珀金强调色：底色不带色相，液态玻璃与封面流体背景不会被底色染色；
 * 强调色取自应用图标（金黄 + 篮球橙），用于播放控制、选中态与可点击文本。
 */
object KunyinColors {
    // 主色调 - 暖橙，用于环境光晕等次要强调
    val Primary = Color(0xFFFF8A4C)

    // 次要色调 - 柔金
    val Secondary = Color(0xFFFFC978)

    // 强调色 - 琥珀金，用于播放控制、选中态与 CTA（深底上对比度 ≈ 10.9:1）
    val Accent = Color(0xFFFFB437)

    // 背景色 - 中性近黑
    val Background = Color(0xFF0C0C0E)

    // 表面色 - 卡片和容器背景
    val Surface = Color(0xFF151518)

    // 主文字颜色 - 高对比度白色
    val TextPrimary = Color(0xFFF4F4F6)

    // 次要文字颜色 - 中性灰
    val TextSecondary = Color(0xFF9C9CA6)

    // 错误色
    val Error = Color(0xFFFF5A5F)

    // 表面变体色 - 用于分隔和边框
    val SurfaceVariant = Color(0xFF212126)

    // 轮廓色 - 用于边框和分隔线
    val Outline = Color(0xFF35353C)

    // 强调色上的前景
    val OnAccent = Color(0xFF1A1000)

    // 容器层级（弹窗、浮层）
    val SurfaceContainerLowest = Color(0xFF0C0C0E)
    val SurfaceContainerLow = Color(0xFF121215)
    val SurfaceContainer = Color(0xFF18181C)
    val SurfaceContainerHigh = Color(0xFF1F1F24)
    val SurfaceContainerHighest = Color(0xFF26262C)
}

object LightColors {
    val Primary = Color(0xFFE8701E)
    val Secondary = Color(0xFFC98A1E)
    // 浅底上压深的琥珀，作为小字文本时对比度 ≈ 4.6:1
    val Accent = Color(0xFFB35F00)
    val Background = Color(0xFFF7F6F3)
    val Surface = Color(0xFFFFFFFF)
    val TextPrimary = Color(0xFF17171B)
    val TextSecondary = Color(0xFF6B6B74)
    val Error = Color(0xFFD03A3F)
    val SurfaceVariant = Color(0xFFECEAE5)
    val Outline = Color(0xFFD3D0C9)
    val OnAccent = Color(0xFFFFFFFF)

    val SurfaceContainerLowest = Color(0xFFFFFFFF)
    val SurfaceContainerLow = Color(0xFFF2F1EE)
    val SurfaceContainer = Color(0xFFECEBE7)
    val SurfaceContainerHigh = Color(0xFFE6E5E0)
    val SurfaceContainerHighest = Color(0xFFE0DFDA)
}
