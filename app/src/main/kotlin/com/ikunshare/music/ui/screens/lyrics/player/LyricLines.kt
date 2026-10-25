package com.ikunshare.music.ui.screens.lyrics.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalGraphicsContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikunshare.music.model.DuetSide
import com.ikunshare.music.model.LyricBackground
import com.ikunshare.music.model.LyricChar
import com.ikunshare.music.model.LyricLine

/** 由用户设置派生的字体样式。 */
internal class LyricTextStyles(
    val main: TextStyle,
    /** 翻译 / 行级音译。 */
    val annotation: TextStyle,
)

/** 每帧共享的时钟：插帧后的播放位置与墙钟（ms）。只在 draw 阶段读取。 */
internal class LyricFrameClock(val position: State<Long>, val wallMs: State<Long>)

private val LyricWhite = Color.White

/**
 * 一条唱词行（对应 `NormalLineElement`）：主词（逐字 / 整行）→ 背景人声子行 → 翻译 → 音译 → AI 谐音。
 *
 * 不透明度分层与桌面端一致：行容器 0.6/1/0.4（0.6s ease）× 元素自身 0.6/1/0.4（0.8s ease，激活瞬时）。
 * 两层都折算进文字颜色，不产生图层 alpha 的离屏合成。
 */
@Composable
internal fun LyricNormalLine(
    line: LyricLine,
    active: Boolean,
    played: Boolean,
    scrolling: Boolean,
    synced: Boolean,
    clock: LyricFrameClock,
    styles: LyricTextStyles,
    config: LyricPlayerConfig,
    availableWidthPx: Int,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    hasCharLyrics: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val fontPx = with(density) { styles.main.fontSize.toPx() }
    val alignEnd = line.duetSide == DuetSide.RIGHT
    // 浏览态（手动滚动）下已播放行回到 normal 样式（scss `[played]:not([scrolling] [played])`）
    val playedStyle = played && !scrolling

    // 行容器不透明度：0.6s ease
    // 无时间轴的纯文本歌词没有激活/已播状态：行容器按 active 不透明度，元素按 normal，整体 0.6
    val lineAlpha by animateFloatAsState(
        targetValue = if (synced) config.lineOpacity.resolve(active, playedStyle) else config.lineOpacity.active,
        animationSpec = tween(config.lineOpacityTransitionMs, easing = CssEase),
        label = "lyricLineAlpha",
    )
    // 元素不透明度：激活时瞬时切到 active（`[active] & { transition-duration: 0s }`），否则 0.8s ease
    val mainAlpha = rememberStateAlpha(config.mainOpacity, active, playedStyle, config.mainOpacityTransitionMs)
    val annotationAlpha = rememberStateAlpha(config.annotationOpacity, active, playedStyle, config.annotationOpacityTransitionMs)

    // 退场锚点：float 反向回落 / emphasize 快进收尾
    // 进入窗口时不算退场：已播过的行保持初始帧（桌面 attach 后 reset 同理），只有本次激活过的行才做退场动画
    val anim = remember(line) { LineAnimState() }
    val wasActive = remember(line) { arrayOf(false) }
    LaunchedEffect(active) {
        if (active) {
            wasActive[0] = true
            anim.activate()
        } else if (wasActive[0]) {
            anim.deactivate((clock.position.value - line.timestamp).toFloat(), clock.wallMs.value)
        }
    }

    // 点击反馈：`.normal::before` inset -14px / radius 12px / currentColor，hover .08 / active .16
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressAlpha by animateFloatAsState(if (pressed) 0.16f else 0f, tween(200, easing = CssEase), label = "lyricPress")
    val highlightInset = fontPx * (14f / 28f)
    val highlightRadius = fontPx * (12f / 28f)

    val rowGap = with(density) { (config.rowGapEm * fontPx).toDp() }
    val fittedMain = rememberFittedSyllableOrPlain(line, styles.main, availableWidthPx, config, measurer)
    val mainColor = LyricWhite

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .drawBehind {
                if (pressAlpha > 0.002f) {
                    drawRoundRect(
                        color = mainColor.copy(alpha = pressAlpha),
                        topLeft = Offset(-highlightInset, -highlightInset),
                        size = Size(size.width + highlightInset * 2, size.height + highlightInset * 2),
                        cornerRadius = CornerRadius(highlightRadius),
                    )
                }
            },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(rowGap),
    ) {
        val romanStyle = if (showRomanization && line.characters.orEmpty().any { !it.romanization.isNullOrBlank() }) {
            fittedMain.copy(fontSize = fittedMain.fontSize * config.wordRomanFontScale,
                lineHeight = fittedMain.fontSize * config.wordRomanFontScale * 1.2f, fontWeight = FontWeight.Normal)
        } else null
        val perWordRoman = romanStyle != null

        // 主词
        if (hasCharLyrics && line.hasCharLyrics) {
            SyllableRow(
                chars = line.characters.orEmpty(),
                lineStart = minOf(line.timestamp, line.characters?.firstOrNull()?.startTime ?: line.timestamp),
                lineEnd = line.getEndTime(),
                style = fittedMain,
                romanStyle = romanStyle,
                maxWidth = availableWidthPx.toFloat(),
                alignEnd = alignEnd,
                config = config,
                emphasizeEnabled = true,
                isBackground = false,
                color = mainColor,
                alpha = { lineAlpha * mainAlpha.value },
                active = active,
                clock = clock,
                anim = anim,
                measurer = measurer,
            )
        } else {
            BasicText(
                text = line.text,
                style = fittedMain.copy(textAlign = if (alignEnd) TextAlign.End else TextAlign.Start),
                color = ColorProducer { mainColor.copy(alpha = lineAlpha * mainAlpha.value) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 背景人声子行（`.backgroundRow`：0.62em / 500 / opacity .72）
        if (line.backgrounds.isNotEmpty()) {
            val bgStyle = fittedMain.copy(
                fontSize = fittedMain.fontSize * config.backgroundFontScale,
                lineHeight = fittedMain.fontSize * config.backgroundFontScale * 1.3f,
                fontWeight = FontWeight.Medium,
            )
            for (bg in line.backgrounds) {
                BackgroundRow(bg, line, bgStyle, availableWidthPx, alignEnd, config, mainColor,
                    alpha = { lineAlpha * mainAlpha.value * config.backgroundOpacity }, active, clock, anim, measurer)
            }
        }

        val subAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        val annotationColor = ColorProducer { mainColor.copy(alpha = lineAlpha * annotationAlpha.value) }

        // 翻译
        if (showTranslation && line.hasTranslation) {
            BasicText(line.translation.orEmpty(), Modifier.fillMaxWidth(),
                styles.annotation.copy(textAlign = subAlign), color = annotationColor)
        }
        // 行级音译（有逐字音译时让位）
        val romanText = line.romanization ?: line.charRomanization?.joinToString("") { it.char }
        if (showRomanization && !perWordRoman && !romanText.isNullOrBlank()) {
            if (line.hasCharRomanization && hasCharLyrics) {
                val charRoman = line.charRomanization.orEmpty()
                SyllableRow(
                    chars = charRoman,
                    lineStart = minOf(line.timestamp, line.characters?.firstOrNull()?.startTime ?: line.timestamp),
                    lineEnd = charRoman.lastOrNull()?.endTime ?: line.getEndTime(),
                    style = styles.annotation,
                    romanStyle = null,
                    maxWidth = availableWidthPx.toFloat(),
                    alignEnd = alignEnd,
                    config = config,
                    emphasizeEnabled = false,
                    isBackground = false,
                    color = mainColor,
                    alpha = { lineAlpha * annotationAlpha.value },
                    active = active,
                    clock = clock,
                    anim = anim,
                    measurer = measurer,
                )
            } else {
                BasicText(romanText, Modifier.fillMaxWidth(), styles.annotation.copy(textAlign = subAlign), color = annotationColor)
            }
        }
        // AI 谐音
        if (showPhonetic && !line.phonetic.isNullOrBlank()) {
            BasicText(line.phonetic.orEmpty(), Modifier.fillMaxWidth(), styles.annotation.copy(textAlign = subAlign),
                color = annotationColor)
        }
    }
}

@Composable
private fun BackgroundRow(
    bg: LyricBackground, line: LyricLine, style: TextStyle, availableWidthPx: Int, alignEnd: Boolean,
    config: LyricPlayerConfig, color: Color, alpha: () -> Float, lineActive: Boolean, clock: LyricFrameClock,
    anim: LineAnimState, measurer: TextMeasurer,
) {
    if (bg.hasCharLyrics) {
        // 背景子行按自身时间轴擦除，但激活/退场跟随主行
        SyllableRow(
            chars = bg.characters.orEmpty(),
            lineStart = bg.timestamp,
            lineEnd = bg.endTimestamp,
            style = style,
            romanStyle = null,
            maxWidth = availableWidthPx.toFloat(),
            alignEnd = alignEnd,
            config = config,
            emphasizeEnabled = true,
            isBackground = true,
            color = color,
            alpha = alpha,
            active = lineActive,
            clock = clock,
            anim = anim,
            measurer = measurer,
            relativeTimeBase = bg.timestamp - minOf(line.timestamp, line.characters?.firstOrNull()?.startTime ?: line.timestamp),
        )
    } else {
        BasicText(bg.text, Modifier.fillMaxWidth(), style.copy(textAlign = if (alignEnd) TextAlign.End else TextAlign.Start),
            color = ColorProducer { color.copy(alpha = alpha()) })
    }
}

/** 元素级三态不透明度：切到 active 瞬时，其余按 [durationMs] ease 过渡。 */
@Composable
private fun rememberStateAlpha(opacity: LyricStateOpacity, active: Boolean, played: Boolean, durationMs: Int): Animatable<Float, *> {
    val target = opacity.resolve(active, played)
    val animatable = remember { Animatable(target) }
    LaunchedEffect(target, active) {
        if (active) animatable.snapTo(target)
        else animatable.animateTo(target, tween(durationMs, easing = CssEase))
    }
    return animatable
}

/**
 * 逐字行画布：布局只算一次，每帧只在 draw 阶段按时钟采样。
 *
 * @param relativeTimeBase 该行时间轴起点相对主行起点的偏移（背景子行用），用于退场锚点换算
 */
@Composable
private fun SyllableRow(
    chars: List<LyricChar>,
    lineStart: Long,
    lineEnd: Long,
    style: TextStyle,
    romanStyle: TextStyle?,
    maxWidth: Float,
    alignEnd: Boolean,
    config: LyricPlayerConfig,
    emphasizeEnabled: Boolean,
    isBackground: Boolean,
    color: Color,
    alpha: () -> Float,
    active: Boolean,
    clock: LyricFrameClock,
    anim: LineAnimState,
    measurer: TextMeasurer,
    relativeTimeBase: Long = 0L,
) {
    val density = LocalDensity.current
    val layout = remember(chars, style, romanStyle, maxWidth, alignEnd, config, emphasizeEnabled, isBackground) {
        buildSyllableLayout(chars, lineStart, lineEnd, style, romanStyle, maxWidth, alignEnd, measurer, density,
            config, emphasizeEnabled, isBackground)
    }
    val painter = remember(config) { SyllablePainter(config) }
    val maskNormalAlpha = config.mainOpacity.normal / config.mainOpacity.active
    val widthDp = with(density) { layout.width.toDp() }
    val heightDp = with(density) { layout.height.toDp() }
    val windDownMs = remember(layout) { layout.windDownMs(config) }
    val localAnim = remember(anim, relativeTimeBase) { ShiftedAnimState(anim, relativeTimeBase.toFloat()) }
    val graphicsContext = LocalGraphicsContext.current
    val layers = remember(layout, graphicsContext) { SyllableLayerCache(graphicsContext) }
    DisposableEffect(layers) { onDispose { layers.releaseAll() } }

    Box(
        Modifier
            .width(widthDp)
            .height(heightDp)
            .drawBehind {
                val a = alpha()
                val rel: Float
                val wall: Long
                if (active) {
                    rel = (clock.position.value - lineStart).toFloat()
                    wall = 0L
                } else {
                    rel = 0f
                    // 只在退场动画未结束时订阅墙钟，结束后不再逐帧重绘
                    wall = localAnim.wallIfWindingDown(clock.wallMs, windDownMs)
                }
                // 激活行与退场收尾中的行有运动，走离屏图层；静止后直接绘制并释放图层
                val moving = active || wall < WIND_DOWN_DONE
                if (!moving && !layers.isEmpty) layers.releaseAll()
                painter.draw(this, layout, color, a, active, rel, wall, localAnim.state, maskNormalAlpha,
                    if (moving) layers else null)
            },
    )
}

private const val WIND_DOWN_DONE = Long.MAX_VALUE / 4

/** 背景子行的退场锚点：主行退场时的相对时间换算到子行自己的时间轴。 */
private class ShiftedAnimState(private val source: LineAnimState, private val shift: Float) {
    private val shifted = LineAnimState()
    private var windDownDone = false
    private var lastDeactivated = -1L

    val state: LineAnimState
        get() {
            if (source.deactivatedWallMs != lastDeactivated) {
                lastDeactivated = source.deactivatedWallMs
                windDownDone = false
                shifted.deactivatedWallMs = source.deactivatedWallMs
                shifted.relAtDeactivation = if (source.relAtDeactivation <= 0f) 0f else source.relAtDeactivation - shift
            }
            return shifted
        }

    /** 退场收尾中返回当前墙钟；已结束（或从未播过）返回 [WIND_DOWN_DONE]，此时不订阅墙钟。 */
    fun wallIfWindingDown(wall: State<Long>, windDownMs: Long): Long {
        val s = state
        if (s.relAtDeactivation <= 0f || windDownDone) return WIND_DOWN_DONE
        val now = wall.value
        if (now >= s.deactivatedWallMs + windDownMs) {
            // 收尾的最后一帧直接按静止状态绘制，让调用方据此释放图层
            windDownDone = true
            return WIND_DOWN_DONE
        }
        return now
    }
}

/** 退场动画最长还要跑多久（float 反向 1× 回落；长音上浮以 disablePlaybackRate 快进）。 */
internal fun SyllableLayout.windDownMs(config: LyricPlayerConfig): Long {
    var max = 0f
    for (w in words) {
        if (config.float.enabled) max = maxOf(max, w.floatDuration)
        w.emphasize?.let { e ->
            val total = e.delay + e.stagger * (w.glyphs?.size ?: 1) + e.floatDuration
            max = maxOf(max, total / maxOf(1f, config.emphasize.disablePlaybackRate))
        }
    }
    return max.toLong() + 100L
}

/**
 * 超长行自动缩：逐字行按换行行数、整行按 TextMeasurer 行数，超过 [LyricPlayerConfig.fitMaxLines]
 * 则逐 sp 下调字号，直到放得下或触及 fitMinScale。
 */
@Composable
private fun rememberFittedSyllableOrPlain(
    line: LyricLine, base: TextStyle, availableWidthPx: Int, config: LyricPlayerConfig, measurer: TextMeasurer,
): TextStyle {
    val density = LocalDensity.current
    return remember(line.text, base, availableWidthPx, config.fitMaxLines, config.fitMinScale) {
        if (availableWidthPx <= 0 || line.text.isBlank() || base.fontSize.value <= 0f) return@remember base
        val minValue = base.fontSize.value * config.fitMinScale
        var current = base.fontSize.value
        fun lines(size: Float): Int {
            val style = base.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp)
            return if (line.hasCharLyrics) {
                buildSyllableLayout(line.characters.orEmpty(), line.timestamp, line.getEndTime(), style, null,
                    availableWidthPx.toFloat(), false, measurer, density, config, false, false).rowCount
            } else {
                measurer.measure(line.text, style, constraints = Constraints(maxWidth = availableWidthPx)).lineCount
            }
        }
        var count = lines(current)
        while (count > config.fitMaxLines && current > minValue) {
            current = (current - 1f).coerceAtLeast(minValue)
            count = lines(current)
        }
        if (current == base.fontSize.value) base else base.copy(fontSize = current.sp, lineHeight = (current * 1.3f).sp)
    }
}

// ==================== 间奏 ====================

/**
 * 间奏行（对应 `InterludeLineElement` + `Dot`）：3 个圆点各占 1/3 时长依次线性点亮，
 * 激活时 scale 0.8→1（0.6s ease-out，延迟 0.1s），退场时 1→0.8；非激活圆点 0.3s 线性回到 normal 透明度。
 * 折叠（hide）由视口按高度处理，这里始终保持自然高度。
 */
@Composable
internal fun LyricInterludeLine(
    line: LyricLine,
    active: Boolean,
    scrolling: Boolean,
    clock: LyricFrameClock,
    styles: LyricTextStyles,
    config: LyricPlayerConfig,
    alignEnd: Boolean,
    clickable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontPx = with(density) { styles.main.fontSize.toPx() }
    val cfg = config.interlude
    val dotPx = cfg.sizeEm * fontPx
    val gapPx = cfg.gapEm * fontPx
    val paddingPx = fontPx * (6f / 28f)
    val heightDp = with(density) { (dotPx + paddingPx * 2).toDp() }
    val rowWidthPx = dotPx * 3 + gapPx * 2

    val scale by animateFloatAsState(
        targetValue = if (active) 1f else 0.8f,
        animationSpec = tween(600, delayMillis = if (active) 100 else 0, easing = CssEaseOut),
        label = "interludeScale",
    )
    // 非激活圆点统一透明度（浏览态 0.6，否则 normal）；激活态由时间轴驱动，退场时 0.3s 线性回落
    val idleAlpha by animateFloatAsState(
        targetValue = if (scrolling && !active) cfg.scrollingOpacity else cfg.normalOpacity,
        animationSpec = tween(300), label = "interludeIdle",
    )
    val activeMix by animateFloatAsState(if (active) 1f else 0f, tween(300), label = "interludeMix")
    val startTime = line.timestamp
    val slice = ((line.getEndTime() - startTime) / 3).coerceAtLeast(1L).toFloat()
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier
            .fillMaxWidth()
            .height(heightDp)
            .clickable(interactionSource = interaction, indication = null, enabled = clickable, onClick = onClick)
            .drawBehind {
                val originX = if (alignEnd) size.width - rowWidthPx else 0f
                val pivotX = if (alignEnd) size.width else 0f
                val centerY = size.height / 2f
                val rel = if (active) (clock.position.value - startTime).toFloat() else 0f
                for (i in 0 until 3) {
                    val litProgress = if (active) clamp01((rel - slice * i) / slice) else 0f
                    val lit = cfg.normalOpacity + (cfg.activeOpacity - cfg.normalOpacity) * litProgress
                    val a = idleAlpha + (lit - idleAlpha) * activeMix
                    if (a <= 0.002f) continue
                    val cx = originX + dotPx / 2f + (dotPx + gapPx) * i
                    val sx = pivotX + (cx - pivotX) * scale
                    drawCircle(Color.White.copy(alpha = a), radius = dotPx / 2f * scale, center = Offset(sx, centerY))
                }
            },
    )
}
