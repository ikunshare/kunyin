package com.ikunshare.music.ui.screens.lyrics.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Compose 端歌词引擎配置，逐项对应桌面端 vendored `music-lyric-player` 的
 * `dom/config/root.ts` DEFAULT + `composables/useLyricPlayer.ts` 里宿主的覆盖值。
 *
 * 字号相关的量在桌面端是 px（基准主词 28px），这里全部换算成「主词字号的倍数」(em)，
 * 这样跟随用户设置的字号缩放时比例关系与桌面端一致。
 */

/** 三态不透明度（normal / active / played）。 */
internal data class LyricStateOpacity(val normal: Float, val active: Float, val played: Float) {
    fun resolve(active: Boolean, played: Boolean): Float = when {
        active -> this.active
        played -> this.played
        else -> normal
    }
}

/** 行滚动过渡模式，对应 `scroll.animation.mode`。 */
internal enum class LyricScrollMode { Smooth, Ripple, Directional, Stagger }

internal data class LyricScrollAnimation(
    val mode: LyricScrollMode = LyricScrollMode.Smooth,
    /** 单行过渡时长 ms（桌面 500）。 */
    val durationMs: Int = 500,
    /** CSS `ease`。 */
    val easing: Easing = CssEase,
    val smoothDelayMs: Int = 0,
    val rippleRange: Int = 5,
    val rippleStepMs: Int = 40,
    val directionalRange: Int = 5,
    val directionalStepMs: Int = 40,
    val staggerRange: Int = 4,
    val staggerStepMs: Int = 50,
)

/** 距离激活行越远越缩小/越模糊（高斯分布 σ=2.2）。桌面主窗口两者均关闭。 */
internal data class LyricDistanceEffect(val enabled: Boolean, val min: Float, val max: Float)

internal data class LyricFloatConfig(
    val enabled: Boolean = true,
    /** 词开始时 translateY（px → em）。 */
    val fromEm: Float = 0f,
    /** 词结束时 translateY，桌面 -2px。 */
    val toEm: Float = -2f / 28f,
    /** 时长下限 ms（桌面 `Math.max(1000, wordDuration)`）。 */
    val minDurationMs: Long = 1000L,
)

internal data class LyricMaskConfig(
    val enabled: Boolean = true,
    /** 擦除软边宽度 = 字高 × normal。 */
    val featherNormal: Float = 0.5f,
    val featherFirst: Float = 1.5f,
    val featherLast: Float = 0.5f,
)

internal data class LyricEmphasizeFloat(
    val enabled: Boolean = true,
    /** 时长 = 长音时长（不短于 minDurationMs）× scale。 */
    val durationScale: Float = 1.4f,
    /** 比该字的起始时间提前多少 ms 开始。 */
    val leadMs: Long = 400L,
    val amplitudeEm: Float = 2f / 28f,
    val easing: Easing = EmphasizeFloatEase,
    val backgroundEnabled: Boolean = true,
    val backgroundScale: Float = 2f,
)

/** 长音强调：只保留逐字错峰上浮；桌面的逐字放大扇开与发光已移除。 */
internal data class LyricEmphasizeConfig(
    val enabled: Boolean = true,
    val minDurationMs: Long = 1000L,
    /** 换行时未播完的动画快进倍率。 */
    val disablePlaybackRate: Float = 4f,
    val float: LyricEmphasizeFloat = LyricEmphasizeFloat(),
) {
    val anyEffect: Boolean get() = enabled && float.enabled
}

internal data class LyricInterludeConfig(
    /** 圆点直径（桌面 16px / 28px）。 */
    val sizeEm: Float = 16f / 28f,
    /** 圆点间距（scss gap 12px）。 */
    val gapEm: Float = 12f / 28f,
    val normalOpacity: Float = 0.2f,
    val activeOpacity: Float = 0.8f,
    /** 非激活时整行折叠（`normal.hide`）。 */
    val hideWhenInactive: Boolean = true,
    /** 浏览（手动滚动）态圆点不透明度（scss `[scrolling] .dot`）。 */
    val scrollingOpacity: Float = 0.6f,
)

internal data class LyricPlayerConfig(
    /** 行间距（桌面宿主 gap 36px / 28px）。 */
    val lineGapEm: Float = 36f / 28f,
    /** 行内主词/翻译/音译之间的间距（宿主 row-gap 14px）。 */
    val rowGapEm: Float = 14f / 28f,
    /** 激活行在视口中的锚点（0..1，桌面 anchor 50%）。 */
    val anchor: Float = 0.5f,
    val scroll: LyricScrollAnimation = LyricScrollAnimation(),
    val scale: LyricDistanceEffect = LyricDistanceEffect(enabled = false, min = 0.65f, max = 1f),
    val blur: LyricDistanceEffect = LyricDistanceEffect(enabled = false, min = 0.4f, max = 4.5f),
    /** 激活行 ± 多少行保留逐字动画 / 图层提升。 */
    val animationWindow: Int = 2,
    /** 激活行 ± 多少行保留已构建内容（窗口化下限，实际按视口高度扩大）。 */
    val contentWindow: Int = 6,
    /** 行级（`.normal` 容器）不透明度：0.6 / 1 / 0.4，过渡 0.6s ease。 */
    val lineOpacity: LyricStateOpacity = LyricStateOpacity(0.6f, 1f, 0.4f),
    val lineOpacityTransitionMs: Int = 600,
    /** 主词（`.word` / `.plain`）不透明度：继承 base → 0.6 / 1 / 0.4，过渡 0.8s（激活瞬时）。 */
    val mainOpacity: LyricStateOpacity = LyricStateOpacity(0.6f, 1f, 0.4f),
    val mainOpacityTransitionMs: Int = 800,
    /** 翻译 / 行级音译不透明度（宿主 0.42 / 0.62 / 0.32）。 */
    val annotationOpacity: LyricStateOpacity = LyricStateOpacity(0.42f, 0.62f, 0.32f),
    val annotationOpacityTransitionMs: Int = 800,
    /** 背景人声子行：字号 0.62em、字重 500、不透明度 0.72。 */
    val backgroundFontScale: Float = 0.62f,
    val backgroundOpacity: Float = 0.72f,
    /** 逐字音译：字号 0.45em、与主词的间隙 gap 4px。 */
    val wordRomanFontScale: Float = 0.45f,
    val wordRomanGapEm: Float = 4f / 28f,
    /** 多 token 或时间窗不同的逐字音译自走擦除；单 token 同窗随主词（宿主 forceOwnWipe=false）。 */
    val forceOwnWipe: Boolean = false,
    val mask: LyricMaskConfig = LyricMaskConfig(),
    val float: LyricFloatConfig = LyricFloatConfig(),
    val emphasize: LyricEmphasizeConfig = LyricEmphasizeConfig(),
    val interlude: LyricInterludeConfig = LyricInterludeConfig(),
    /** 手动滚动后多久回到自动跟随（桌面滚轮 3s；触屏给 5s）。 */
    val manualScrollReturnMs: Long = 5000L,
    /** 超长行自动缩字：超过此行数就逐步缩小字号。 */
    val fitMaxLines: Int = 2,
    val fitMinScale: Float = 0.62f,
) {
    companion object {
        val Default = LyricPlayerConfig()
    }
}

// ==================== 缓动 ====================

/** CSS `ease`。 */
internal val CssEase: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** CSS `ease-out`。 */
internal val CssEaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

internal val EmphasizeFloatEase: Easing = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/**
 * WAAPI 三关键帧「0 → 峰值(offset 0.5) → 0」的采样：前半段用 [rise]，后半段用 [fall]。
 * 返回 0..1 的峰值系数。
 */
internal fun pulse(progress: Float, rise: Easing, fall: Easing): Float = when {
    progress <= 0f || progress >= 1f -> 0f
    progress < 0.5f -> rise.transform(progress * 2f)
    else -> 1f - fall.transform((progress - 0.5f) * 2f)
}

internal fun clamp01(value: Float): Float = if (value < 0f) 0f else if (value > 1f) 1f else value
