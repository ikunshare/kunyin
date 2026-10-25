package com.ikunshare.music.ui.screens.lyrics.player

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikunshare.music.model.DuetSide
import com.ikunshare.music.model.LyricCredit
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.tool.lyricplayer.LyricPlayerMatch
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 歌词视口：对应桌面端 `core/layout.ts` + `core/line.ts`。
 *
 * - 行绝对定位：每行一个 [LyricMotion]，`top` 等用 Animatable 按桌面同款时长/曲线过渡，
 *   只在 graphicsLayer 里读取（draw 阶段），不触发重组/重布局
 * - 窗口化：只组合激活行 ± contentRadius 的行（含所有激活行），其余只保留高度估计
 * - 滚动：触屏拖动 / 惯性写入全局 drag，5 秒无操作后回到自动跟随
 */

private const val GAUSSIAN_SIGMA = 2.2f
private const val GAUSSIAN_CUTOFF = 4f * GAUSSIAN_SIGMA
private const val ESTIMATED_LINE_HEIGHT = 40
private const val MIN_COVERAGE_UNIT = 16
private const val FALLBACK_LINE_HEIGHT = 50
private const val CREDITS_KEY = -1

internal class LyricMotion {
    val y = Animatable(0f)
    val scale = Animatable(1f)
    val blur = Animatable(0f)
    /** hide → 0。 */
    val visible = Animatable(1f)
    /** 首次定位前隐藏（桌面 `visibility: hidden` 直到 seed 完成）。 */
    var seeded by mutableStateOf(false)
}

private class ViewportHolder {
    var previousLineIndex = -1
    var previousActive: List<Int> = emptyList()
    var previousOffset: Float? = null
    /** 手动滚动开始时冻结的自动偏移。 */
    var manualBase = 0f
    var lastAutoOffset = 0f
    var minDrag = Float.NEGATIVE_INFINITY
    var maxDrag = Float.POSITIVE_INFINITY
    var tops = FloatArray(0)
    val motions = HashMap<Int, LyricMotion>()
}

private class LineTarget(
    val y: Float, val scale: Float, val blur: Float, val hide: Boolean, val durationMs: Int, val delayMs: Int,
)

private fun gaussian(offset: Int): Float {
    val o = offset.toFloat()
    if (o > GAUSSIAN_CUTOFF || o < -GAUSSIAN_CUTOFF) return 0f
    return exp(-(o * o) / (2f * GAUSSIAN_SIGMA * GAUSSIAN_SIGMA))
}

private fun calcTransition(config: LyricScrollAnimation, offset: Int, played: Boolean, direction: Int): Pair<Int, Int> {
    val duration = config.durationMs.coerceAtLeast(0)
    return when (config.mode) {
        LyricScrollMode.Smooth -> duration to config.smoothDelayMs.coerceAtLeast(0)
        LyricScrollMode.Ripple -> {
            val step = config.rippleStepMs.coerceAtLeast(10)
            val range = config.rippleRange.coerceAtLeast(1)
            val normalized = min(abs(offset), range).toFloat() / range
            val eased = 1f - (1f - normalized) * (1f - normalized)
            duration to (eased * range * step).roundToInt()
        }
        LyricScrollMode.Directional -> {
            val step = config.directionalStepMs.coerceAtLeast(10)
            val range = config.directionalRange.coerceAtLeast(1)
            val normalized = min(abs(offset), range).toFloat() / range
            val eased = if (played) (1f - normalized) * (1f - normalized) else 1f - (1f - normalized) * (1f - normalized)
            duration to (eased * range * step).roundToInt()
        }
        LyricScrollMode.Stagger -> {
            if (direction == 0) return duration to 0
            val range = config.staggerRange.coerceAtLeast(1)
            val step = config.staggerStepMs.coerceAtLeast(1)
            val clamped = offset.coerceIn(-range, range)
            duration to ((range + direction * clamped) * step)
        }
    }
}

@Composable
internal fun LyricViewport(
    lines: List<LyricLine>,
    isSynced: Boolean,
    match: LyricPlayerMatch,
    clock: LyricFrameClock,
    isPlaying: Boolean,
    styles: LyricTextStyles,
    config: LyricPlayerConfig,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    hasCharLyrics: Boolean,
    credits: List<LyricCredit>,
    onLineClick: (Long) -> Unit,
    anchorFraction: Float,
    horizontalPadding: Dp,
    occludedBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val fontPx = with(density) { styles.main.fontSize.toPx() }
    val gapPx = config.lineGapEm * fontPx
    val generation = remember(lines) { Any() }
    val holder = remember(generation) { ViewportHolder() }
    val heights = remember(generation) { mutableStateMapOf<Int, Int>() }
    var heightsVersion by remember(generation) { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    val drag: MutableFloatState = remember(generation) { mutableFloatStateOf(0f) }
    var manual by remember(generation) { mutableStateOf(!isSynced) }
    var touchGeneration by remember(generation) { mutableIntStateOf(0) }

    val activeIndexes = match.indexes
    val activeSet = remember(activeIndexes) { activeIndexes.toSet() }
    val firstActive = if (isSynced) match.firstActiveIndex else -1
    val anchor = if (isSynced) anchorFraction else 0.08f

    // 窗口半径：覆盖半个视口（按最小已测行高）+ 缓冲，且不小于 contentWindow / animationWindow
    val contentRadius by remember(generation) {
        derivedStateOf {
            heightsVersion
            var minNormal = Int.MAX_VALUE
            for ((i, h) in heights) if (i >= 0 && h > 0 && !lines[i].isInterlude) minNormal = min(minNormal, h)
            val unit = maxOf(MIN_COVERAGE_UNIT, if (minNormal == Int.MAX_VALUE) ESTIMATED_LINE_HEIGHT else minNormal)
            val dynamicFloor = ceil(viewportHeight / 2f / unit).toInt() + 2
            maxOf(config.contentWindow, config.animationWindow, dynamicFloor)
        }
    }
    // 手动滚动时窗口中心跟随最接近锚点的行（只在整行变化时更新，避免逐帧重组）
    val manualCenter by remember(generation) {
        derivedStateOf {
            if (!manual || lines.isEmpty()) -1
            else {
                val d = drag.floatValue
                var best = 0
                var bestDist = Float.MAX_VALUE
                val tops = holder.tops
                for (i in lines.indices) {
                    val top = tops.getOrElse(i) { 0f } + holder.manualBase + d
                    val h = (heights[i] ?: FALLBACK_LINE_HEIGHT).toFloat()
                    val dist = abs(top + h / 2f - viewportHeight * anchor)
                    if (dist < bestDist) { bestDist = dist; best = i }
                }
                best
            }
        }
    }
    val attached: List<Int> = remember(generation, lines.size, contentRadius, activeSet, firstActive, manualCenter) {
        if (lines.isEmpty()) emptyList() else {
            val center = (if (manualCenter >= 0) manualCenter else firstActive.coerceAtLeast(0)).coerceIn(0, lines.lastIndex)
            val lo = maxOf(0, center - contentRadius)
            val hi = min(lines.lastIndex, center + contentRadius)
            val set = java.util.TreeSet<Int>()
            for (i in lo..hi) set.add(i)
            for (i in activeSet) if (i in lines.indices) set.add(i)
            set.toList()
        }
    }

    // 手动滚动：拖动 / 惯性写 drag；5 秒静止（播放中）后回到自动跟随
    val interaction = remember { MutableInteractionSource() }
    val dragging by interaction.collectIsDraggedAsState()
    val scrollState = rememberScrollableState { delta ->
        if (lines.isEmpty()) return@rememberScrollableState 0f
        if (!manual) {
            manual = true
            holder.manualBase = holder.lastAutoOffset
        }
        touchGeneration++
        val current = drag.floatValue
        val next = (current + delta).coerceIn(holder.minDrag, holder.maxDrag)
        drag.floatValue = next
        next - current
    }
    LaunchedEffect(manual, touchGeneration, dragging, scrollState.isScrollInProgress, isPlaying, isSynced) {
        if (manual && isSynced && isPlaying && !dragging && !scrollState.isScrollInProgress) {
            delay(config.manualScrollReturnMs)
            manual = false
        }
    }

    // ===== 布局计算（桌面 LayoutManager.update）=====
    val creditsVisible = credits.isNotEmpty() && lines.isNotEmpty()
    LaunchedEffect(generation, heightsVersion, viewportWidth, viewportHeight, activeIndexes, firstActive, manual,
        attached, config, anchor, creditsVisible) {
        if (lines.isEmpty() || viewportHeight <= 0) return@LaunchedEffect
        // 实时读取视口尺寸：组合期捕获的值可能早于 onSizeChanged
        val activePosition = viewportHeight * anchor
        val n = lines.size
        var lastNormal = -1
        var lastInterlude = -1
        for ((i, h) in heights) {
            if (i < 0 || h <= 0) continue
            if (lines[i].isInterlude) lastInterlude = h else lastNormal = h
        }
        val h = IntArray(n) { i ->
            heights[i] ?: if (lines[i].isInterlude) (if (lastInterlude > 0) lastInterlude else (fontPx * 1f).toInt())
            else (if (lastNormal > 0) lastNormal else FALLBACK_LINE_HEIGHT)
        }
        val hideInterlude = config.interlude.hideWhenInactive
        val tops = FloatArray(n)
        for (i in 1 until n) {
            val base = tops[i - 1] + h[i - 1]
            tops[i] = if (lines[i].isInterlude && hideInterlude && !manual && i !in activeSet) base - h[i] else base + gapPx
        }
        holder.tops = tops
        val fa = firstActive.coerceIn(0, n - 1)
        val autoOffset = activePosition - (tops[fa] + h[fa] / 2f)
        val d = round(drag.floatValue)
        // 回到自动跟随时把 drag 折进偏移：目标 = tops + offset，实际位置 = 目标 + drag
        val currentOffset = if (manual) holder.manualBase else autoOffset - d
        // 可见位置恒为 tops + currentOffset + drag；进入手动滚动时以此为基准，保证连续
        holder.lastAutoOffset = currentOffset
        // drag 边界：首行不低于锚点、末行（含附注）不高于锚点
        val creditsExtra = if (creditsVisible) (heights[CREDITS_KEY] ?: 0) + gapPx else 0f
        holder.maxDrag = activePosition - (tops[0] + currentOffset)
        holder.minDrag = activePosition - h[n - 1] - creditsExtra - (tops[n - 1] + currentOffset)
        if (holder.minDrag > holder.maxDrag) holder.minDrag = holder.maxDrag

        // 首次排版直接落位；激活行未实测时仍走动画（用估计高度），实测后由 heightsVersion 触发二次收敛
        val immediate = holder.previousOffset == null
        val seedOffset = holder.previousOffset ?: currentOffset

        val activeIndex = fa
        val currentLineIndex = firstActive
        val direction = if (!manual && holder.previousLineIndex != -1 && currentLineIndex != holder.previousLineIndex) {
            if (currentLineIndex > holder.previousLineIndex) 1 else -1
        } else 0
        val exiting = holder.previousActive.filter { it !in activeSet }
        val isExitingGroup = !manual && direction > 0 && holder.previousActive.size > 1 && exiting.size == holder.previousActive.size
        val exitingSet = if (isExitingGroup) exiting.toSet() else emptySet()

        // 释放窗口外的行
        val attachedSet = attached.toSet()
        val it = holder.motions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key != CREDITS_KEY && e.key !in attachedSet) it.remove()
        }

        fun targetFor(i: Int): LineTarget {
            val isPlayed = i < activeIndex
            val isActive = i in activeSet
            val indexOffset = i - activeIndex
            val y = round(tops[i] + currentOffset)
            if (manual) return LineTarget(y, 1f, 0f, false, 200, 0)
            val (dur, delay) = calcTransition(config.scroll, indexOffset, isPlayed, direction)
            val duration = if (i in exitingSet) maxOf(dur, 450) else dur
            var scale = 1f
            var blur = 0f
            var hide = false
            if (i in exitingSet) {
                scale = 1f; blur = 0f
            } else if (!isActive) {
                val g = gaussian(indexOffset)
                if (config.scale.enabled) {
                    val mn = config.scale.min.coerceAtLeast(0f)
                    val mx = maxOf(config.scale.max, mn)
                    scale = mn + (mx - mn) * g
                }
                if (config.blur.enabled) {
                    val mn = config.blur.min.coerceAtLeast(0f)
                    val mx = maxOf(config.blur.max, mn)
                    blur = mn + (mx - mn) * (1f - g)
                }
                if (lines[i].isInterlude && hideInterlude) hide = true
            }
            return LineTarget(y, scale, blur, hide, duration, delay)
        }

        fun apply(motion: LyricMotion, t: LineTarget, seedY: Float) {
            scope.launch {
                if (!motion.seeded) {
                    motion.y.snapTo(seedY)
                    motion.visible.snapTo(if (t.hide) 0f else 1f)
                    motion.scale.snapTo(t.scale)
                    motion.blur.snapTo(t.blur)
                    motion.seeded = true
                }
                if (immediate) {
                    motion.y.snapTo(t.y)
                } else if (motion.y.targetValue != t.y || (!motion.y.isRunning && motion.y.value != t.y)) {
                    motion.y.animateTo(t.y, tween(t.durationMs, t.delayMs, config.scroll.easing))
                }
            }
            val half = t.durationMs / 2
            val visible = if (t.hide) 0f else 1f
            if (motion.visible.targetValue != visible || (!motion.visible.isRunning && motion.visible.value != visible)) {
                scope.launch { motion.visible.animateTo(visible, tween(half, t.delayMs, CssEase)) }
            }
            if (motion.scale.targetValue != t.scale || (!motion.scale.isRunning && motion.scale.value != t.scale)) {
                scope.launch { motion.scale.animateTo(t.scale, tween(t.durationMs, t.delayMs, config.scroll.easing)) }
            }
            if (motion.blur.targetValue != t.blur || (!motion.blur.isRunning && motion.blur.value != t.blur)) {
                scope.launch { motion.blur.animateTo(t.blur, tween(half, t.delayMs, CssEase)) }
            }
        }

        for (i in attached) {
            val motion = holder.motions.getOrPut(i) { LyricMotion() }
            apply(motion, targetFor(i), round(tops[i] + seedOffset))
        }
        // 制作人附注：跟在末行之后，过渡与末行同款
        if (creditsVisible) {
            val last = n - 1
            val footerTop = tops[last] + h[last] + gapPx
            val (dur, delay) = if (manual) 200 to 0 else calcTransition(config.scroll, last - activeIndex, last < activeIndex, direction)
            val motion = holder.motions.getOrPut(CREDITS_KEY) { LyricMotion() }
            apply(motion, LineTarget(round(footerTop + currentOffset), 1f, 0f, false, dur, delay), round(footerTop + seedOffset))
        } else {
            holder.motions.remove(CREDITS_KEY)
        }

        holder.previousLineIndex = currentLineIndex
        holder.previousActive = activeIndexes
        holder.previousOffset = currentOffset
    }

    val padPx = with(density) { horizontalPadding.toPx() }
    val availableWidth = (viewportWidth - padPx * 2).toInt().coerceAtLeast(0)
    val scrolling = manual && isSynced

    Box(
        modifier
            .fillMaxSize()
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithCache {
                // 容器上下边缘淡出（桌面 fade top 5% / bottom 10%），底部按被控件遮挡的高度收缩
                val visibleFraction = if (size.height > 0f) ((size.height - occludedBottomPx) / size.height).coerceIn(0f, 1f) else 1f
                val topEdge = (0.05f).coerceAtMost(visibleFraction / 2f)
                val bottomEdge = (0.10f * visibleFraction).coerceAtMost(visibleFraction / 2f)
                val fade = Brush.verticalGradient(
                    0f to Color.Transparent, topEdge to Color.Black,
                    (visibleFraction - bottomEdge) to Color.Black,
                    visibleFraction to Color.Transparent, 1f to Color.Transparent,
                )
                onDrawWithContent {
                    drawContent()
                    drawRect(fade, blendMode = BlendMode.DstIn)
                }
            }
            .scrollable(scrollState, Orientation.Vertical, enabled = lines.isNotEmpty(), interactionSource = interaction)
            .onSizeChanged {
                viewportWidth = it.width
                viewportHeight = it.height
            },
    ) {
        // 视口尺寸未知前不组合行：否则逐字行按宽度 0 排版（每字一行）后再重排，首帧白做且会跳动
        if (viewportWidth > 0 && viewportHeight > 0) for (i in attached) {
            key(generation, i) {
                val line = lines[i]
                val motion = holder.motions.getOrPut(i) { LyricMotion() }
                val alignEnd = line.duetSide == DuetSide.RIGHT
                val active = i in activeSet
                val played = firstActive >= 0 && i < firstActive
                val lineModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding)
                    .graphicsLayer {
                        translationY = motion.y.value + round(drag.floatValue)
                        alpha = if (motion.seeded) motion.visible.value else 0f
                        val s = motion.scale.value
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                        val b = motion.blur.value
                        renderEffect = if (b > 0.05f && Build.VERSION.SDK_INT >= 31) BlurEffect(b, b, TileMode.Decal) else null
                    }
                    .onSizeChanged {
                        if (heights[i] != it.height) {
                            heights[i] = it.height
                            heightsVersion++
                        }
                    }
                if (line.isInterlude) {
                    LyricInterludeLine(
                        line = line, active = active, scrolling = scrolling, clock = clock, styles = styles,
                        config = config, alignEnd = alignEnd,
                        clickable = isSynced && (active || scrolling || !config.interlude.hideWhenInactive),
                        onClick = { if (isSynced) { manual = false; onLineClick(line.timestamp) } },
                        modifier = lineModifier,
                    )
                } else {
                    LyricNormalLine(
                        line = line, active = active, played = played, scrolling = scrolling, synced = isSynced, clock = clock,
                        styles = styles, config = config, availableWidthPx = availableWidth,
                        showTranslation = showTranslation, showRomanization = showRomanization,
                        showPhonetic = showPhonetic, hasCharLyrics = hasCharLyrics,
                        onClick = { if (isSynced) { manual = false; onLineClick(line.timestamp) } },
                        modifier = lineModifier,
                    )
                }
            }
        }
        if (creditsVisible && viewportHeight > 0) {
            key(generation, CREDITS_KEY) {
                val motion = holder.motions.getOrPut(CREDITS_KEY) { LyricMotion() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = horizontalPadding)
                        .graphicsLayer {
                            translationY = motion.y.value + round(drag.floatValue)
                            alpha = if (motion.seeded) 1f else 0f
                        }
                        .onSizeChanged {
                            if (heights[CREDITS_KEY] != it.height) {
                                heights[CREDITS_KEY] = it.height
                                heightsVersion++
                            }
                        },
                ) {
                    com.ikunshare.music.ui.screens.lyrics.LyricCredits(credits)
                }
            }
        }
    }
}

internal val LyricDefaultHorizontalPadding: Dp = 28.dp
