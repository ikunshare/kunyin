package com.ikunshare.music.ui.screens.lyrics

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ikunshare.music.model.DuetSide
import com.ikunshare.music.model.LyricBackground
import com.ikunshare.music.model.LyricChar
import com.ikunshare.music.model.LyricLine

/**
 * 桌面歌词引擎的可见行渲染：透明度分层、逐字擦除、声部对齐、背景人声和间奏。
 * 原生列表负责视口和滚动，行本身不创建模糊层。
 */

// ==================== 常量（对应 web config 默认值）====================

/** 三态透明度（line.normal.base.style 默认 active 1 / played 0.4 / normal 0.6）。 */
private const val WEB_OPACITY_ACTIVE = 1f
private const val WEB_OPACITY_PLAYED = 0.4f
private const val WEB_OPACITY_NORMAL = 0.6f

/** scroll.animation 默认 duration 500ms + easing "ease"。 */
private const val WEB_SCROLL_DURATION = 500
private val WebScrollEasing: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** 间奏点透明度（interlude.style 默认 normal 0.2 / active 0.8）与尺寸（size 16px，静息 scale 0.8）。 */
private const val WEB_INTERLUDE_NORMAL_OPACITY = 0.2f
private const val WEB_INTERLUDE_ACTIVE_OPACITY = 0.8f
private val WEB_INTERLUDE_DOT_SIZE = 10.dp
private val WEB_INTERLUDE_DOT_GAP = 12.dp

/** 背景/和声行相对主歌词的字号缩放。 */
private const val WEB_BACKGROUND_FONT_SCALE = 0.86f

/** 超长行自动缩：超过这么多行就把该行字号往下调，直到放得下或触及最小比例。 */
private const val WEB_FONT_FIT_MAX_LINES = 2
private const val WEB_FONT_FIT_MIN_SCALE = 0.62f

// ==================== 单行 ====================

@Composable
internal fun WebLyricLine(
    line: LyricLine,
    active: Boolean,
    played: Boolean,
    currentPosition: Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    hasCharLyrics: Boolean,
    mainStyle: TextStyle,
    subStyle: TextStyle,
    availableWidthPx: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 间奏行：非活跃时折叠为 0 高度（web hide:true），活跃时展示脉冲圆点。
    if (line.isInterlude) {
        WebInterludeLine(
            active = active,
            startTime = line.timestamp,
            endTime = line.getEndTime(),
            currentPosition = currentPosition,
            isPlaying = isPlaying,
            modifier = modifier
        )
        return
    }

    val alignEnd = line.duetSide == DuetSide.RIGHT
    val textAlign = if (alignEnd) TextAlign.End else TextAlign.Start

    // 超长行自动缩：正文超过 maxLines 就把该行字号往下调（缓存在 per-line remember 内）。
    val fittedMainSize = rememberFittedFontSize(
        text = line.text,
        baseSize = mainStyle.fontSize,
        availableWidthPx = availableWidthPx,
        style = mainStyle,
        maxLines = WEB_FONT_FIT_MAX_LINES,
        minScale = WEB_FONT_FIT_MIN_SCALE
    )
    val fittedMainStyle = if (fittedMainSize == mainStyle.fontSize) {
        mainStyle
    } else {
        mainStyle.copy(fontSize = fittedMainSize, lineHeight = fittedMainSize * 1.3f)
    }

    val targetAlpha = when {
        active -> WEB_OPACITY_ACTIVE
        played -> WEB_OPACITY_PLAYED
        else -> WEB_OPACITY_NORMAL
    }

    val alpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(WEB_SCROLL_DURATION / 2, easing = WebScrollEasing),
        label = "webLineAlpha"
    )
    val baseColor = if (active && line.hasCharLyrics) Color.White.copy(alpha = 0.38f) else Color.White
    val activeColor = Color.White
    val subActiveAlpha = if (active) 0.68f else 0.44f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start
    ) {
        // 主歌词
        if (hasCharLyrics && line.hasCharLyrics) {
            WebKaraokeLine(
                characters = line.characters.orEmpty(),
                lineTimestamp = line.timestamp,
                lineEndTime = line.getEndTime(),
                active = active,
                currentPosition = currentPosition,
                isPlaying = isPlaying,
                style = fittedMainStyle,
                activeColor = activeColor,
                baseColor = baseColor,
                emphasis = true,
                alignEnd = alignEnd,
                showWordRomanization = showRomanization
            )
        } else {
            Text(
                text = line.text,
                style = fittedMainStyle,
                color = baseColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }

        // 背景/和声行（紧贴正文下方，字号更小）
        line.backgrounds.forEach { bg ->
            Spacer(Modifier.height(3.dp))
            WebBackgroundLine(
                background = bg,
                currentPosition = currentPosition,
                isPlaying = isPlaying,
                style = fittedMainStyle.copy(
                    fontSize = fittedMainSize * WEB_BACKGROUND_FONT_SCALE,
                    lineHeight = fittedMainSize * WEB_BACKGROUND_FONT_SCALE * 1.3f
                ),
                activeColor = activeColor,
                baseColor = baseColor,
                alignEnd = alignEnd
            )
        }

        // 音译
        val romanText = line.romanization ?: line.charRomanization?.joinToString("") { it.char }
        if (showRomanization && !romanText.isNullOrBlank() && line.characters.orEmpty().none { !it.romanization.isNullOrBlank() }) {
            Spacer(Modifier.height(5.dp))
            if (line.hasCharRomanization) {
                WebKaraokeLine(
                    characters = line.charRomanization.orEmpty(),
                    lineTimestamp = line.timestamp,
                    lineEndTime = line.charRomanization?.lastOrNull()?.endTime ?: line.getEndTime(),
                    active = active,
                    currentPosition = currentPosition,
                    isPlaying = isPlaying,
                    style = subStyle,
                    activeColor = Color.White.copy(alpha = 0.9f),
                    baseColor = baseColor.copy(alpha = subActiveAlpha),
                    emphasis = false,
                    alignEnd = alignEnd
                )
            } else {
                Text(
                    text = romanText,
                    style = subStyle,
                    color = baseColor.copy(alpha = subActiveAlpha),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = textAlign
                )
            }
        }

        // 翻译
        if (showTranslation && line.hasTranslation) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = line.translation.orEmpty(),
                style = subStyle,
                color = baseColor.copy(alpha = subActiveAlpha),
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }

        // AI 谐音
        if (showPhonetic && !line.phonetic.isNullOrBlank()) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = line.phonetic.orEmpty(),
                style = subStyle,
                color = baseColor.copy(alpha = if (active) 0.62f else 0.40f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = textAlign
            )
        }
    }
}

// ==================== 间奏点 ====================

/**
 * 间奏行：3 个圆点在间奏时长内依次点亮（每点占 1/3 时长，第 i 点延后 slice*i），
 * 非活跃时折叠为 0 高度（对应 web interlude hide:true）。
 */
@Composable
private fun WebInterludeLine(
    active: Boolean,
    startTime: Long,
    endTime: Long,
    currentPosition: Long,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    // M1：高度在 0（折叠）↔ 圆点行高之间平滑动画，避免间奏收/放时下方行位一帧突变。
    // 动画时长/曲线与整体平移一致，使高度回流与平移在聚焦行处相互抵消。
    Box(modifier.animateContentSize(tween(WEB_SCROLL_DURATION, easing = WebScrollEasing))) {
        if (!active) return@Box

        val interpolated = rememberInterpolatedPosition(
            currentPosition = currentPosition,
            active = true,
            isPlaying = isPlaying,
            resetKey = startTime
        )
        val duration = (endTime - startTime).coerceAtLeast(1L).toFloat()
        val slice = duration / 3f
        // L2：入场缩放 0.8 → 1。animateFloatAsState 常量目标不会动，必须用 Animatable 主动 animateTo。
        val scale = remember(startTime) { Animatable(0.8f) }
        LaunchedEffect(startTime) {
            scale.animateTo(1f, tween(600, delayMillis = 100, easing = EaseOut))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                },
            horizontalArrangement = Arrangement.spacedBy(WEB_INTERLUDE_DOT_GAP)
        ) {
            repeat(3) { i ->
                val dotStart = startTime.toFloat() + slice * i
                Box(
                    modifier = Modifier
                        .size(WEB_INTERLUDE_DOT_SIZE)
                        .graphicsLayer {
                            val progress = ((interpolated.value - dotStart) / slice).coerceIn(0f, 1f)
                            alpha = WEB_INTERLUDE_NORMAL_OPACITY +
                                (WEB_INTERLUDE_ACTIVE_OPACITY - WEB_INTERLUDE_NORMAL_OPACITY) * progress
                        }
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

// ==================== 背景/和声行 ====================

@Composable
private fun WebBackgroundLine(
    background: LyricBackground,
    currentPosition: Long,
    isPlaying: Boolean,
    style: TextStyle,
    activeColor: Color,
    baseColor: Color,
    alignEnd: Boolean
) {
    val active = currentPosition >= background.timestamp && currentPosition < background.endTimestamp
    val bgBase = baseColor.copy(alpha = if (active) 0.85f else 0.5f)
    if (background.hasCharLyrics) {
        WebKaraokeLine(
            characters = background.characters.orEmpty(),
            lineTimestamp = background.timestamp,
            lineEndTime = background.endTimestamp,
            active = active,
            currentPosition = currentPosition,
            isPlaying = isPlaying,
            style = style,
            activeColor = activeColor,
            baseColor = bgBase,
            emphasis = false,
            alignEnd = alignEnd
        )
    } else {
        Text(
            text = background.text,
            style = style,
            color = bgBase,
            modifier = Modifier.fillMaxWidth(),
            textAlign = if (alignEnd) TextAlign.End else TextAlign.Start
        )
    }
}

// ==================== 逐字行 ====================

/**
 * 逐字行：每帧插帧出精确播放位置，再交给 [WebSyllableText] 做 mask/重音加粗。
 */
@Composable
private fun WebKaraokeLine(
    characters: List<LyricChar>,
    lineTimestamp: Long,
    lineEndTime: Long,
    active: Boolean,
    currentPosition: Long,
    isPlaying: Boolean,
    style: TextStyle,
    activeColor: Color,
    baseColor: Color,
    emphasis: Boolean,
    alignEnd: Boolean,
    showWordRomanization: Boolean = false
) {
    val lineStartTime = minOf(lineTimestamp, characters.firstOrNull()?.startTime ?: lineTimestamp)
    val interpolated = rememberInterpolatedPosition(
        currentPosition = currentPosition,
        active = active,
        isPlaying = isPlaying,
        resetKey = characters
    )
    WebSyllableText(
        characters = characters,
        currentPosition = { interpolated.value },
        isActiveLine = active,
        textStyle = style,
        activeColor = activeColor,
        baseColor = baseColor,
        lineStartTime = lineStartTime,
        lineEndTime = lineEndTime,
        emphasisEnabled = emphasis,
        alignEnd = alignEnd,
        showWordRomanization = showWordRomanization
    )
}

/**
 * 基于系统帧时钟对 ViewModel 推送的 [currentPosition] 做插帧，得到每帧精确的播放位置。
 * 仅在可见 + 播放中 + 活跃时驱动帧循环，其余情况直接对齐到 [currentPosition]。
 */
@Composable
private fun rememberInterpolatedPosition(
    currentPosition: Long,
    active: Boolean,
    isPlaying: Boolean,
    resetKey: Any?
): State<Long> {
    val playbackRate = LocalLyricPlaybackRate.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    val positionState = remember(resetKey) { mutableLongStateOf(currentPosition) }
    val clock = remember(resetKey) { LyricPlaybackClock(currentPosition, System.nanoTime()) }
    val drive = visible && isPlaying && active
    LaunchedEffect(currentPosition, drive, playbackRate, clock) {
        positionState.longValue = clock.accept(currentPosition, System.nanoTime(), running = drive)
    }
    LaunchedEffect(drive, playbackRate, resetKey) {
        if (!drive) return@LaunchedEffect
        while (true) {
            withInfiniteAnimationFrameMillis {
                positionState.longValue = clock.frame(System.nanoTime(), playbackRate)
            }
        }
    }
    return positionState
}

/**
 * 超长行自动缩：用 [TextMeasurer] 预测某行文本的行数，超过 [maxLines] 就把字号逐步下调，
 * 直到放得下或触及 [minScale]×[baseSize]。结果按 (text, baseSize, width) 缓存，正常行只测一次。
 */
@Composable
private fun rememberFittedFontSize(
    text: String,
    baseSize: TextUnit,
    availableWidthPx: Int,
    style: TextStyle,
    maxLines: Int,
    minScale: Float
): TextUnit {
    val measurer = rememberTextMeasurer()
    return remember(text, baseSize, availableWidthPx, maxLines, minScale) {
        if (availableWidthPx <= 0 || text.isBlank() || baseSize.value <= 0f) {
            return@remember baseSize
        }
        val minValue = baseSize.value * minScale
        var current = baseSize.value
        var layout = measurer.measure(
            text = text,
            style = style.copy(fontSize = current.sp),
            constraints = Constraints(maxWidth = availableWidthPx)
        )
        while (layout.lineCount > maxLines && current > minValue) {
            current = (current - 1f).coerceAtLeast(minValue)
            layout = measurer.measure(
                text = text,
                style = style.copy(fontSize = current.sp),
                constraints = Constraints(maxWidth = availableWidthPx)
            )
        }
        current.sp
    }
}
