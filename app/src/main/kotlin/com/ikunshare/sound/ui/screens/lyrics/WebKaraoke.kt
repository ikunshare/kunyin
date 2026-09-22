package com.ikunshare.sound.ui.screens.lyrics

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.model.LyricChar

/**
 * 逐字歌词（卡拉 OK）渲染，改编自 music-lyric-player-web 的 syllable 动画：
 * - mask：从左到右的遮罩擦除，带 feather 软边（normal 0.5 / first 1.5 / last 0.5，单位为字高倍数）
 * - float：每个音节在其活跃窗口内从 0 上浮到 -2px
 * - 重音强调：不再采用 web 的逐字放大 + 黑色辉光（逐帧重绘导致卡顿、黑辉光在浅色背景上是黑边），
 *   改为活跃时对重音字静态加粗，零逐帧动画。
 *
 * 该文件只负责“单行内的逐字动画”，行的滚动 / 模糊 / 缩放由 WebLyricsContent 的平移模型处理。
 */

// ==================== 常量（对应 web config 默认值）====================

private const val WEB_ACTIVE_OPACITY = 1f
private const val WEB_NORMAL_OPACITY = 0.6f

// mask feather：normal 0.5 * 字高，first 1.5x，last 0.5x
private const val WEB_MASK_FEATHER_NORMAL = 0.5f
private const val WEB_MASK_FEATHER_FIRST = 1.5f
private const val WEB_MASK_FEATHER_LAST = 0.5f

// float：word 从 0 上浮到 -2px
private const val WEB_WORD_FLOAT_FROM = 0f
private const val WEB_WORD_FLOAT_TO = -2f

private val WebEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

// ==================== 数据类 ====================

private data class WebWordMask(val activeX: Float, val fadeX: Float)

private data class WebMaskFrame(val offset: Float, val position: Float)

private data class WebMaskFrames(
    val leftPos: Float,
    val widthInTotal: Float,
    val imageWidth: Float,
    val frames: List<WebMaskFrame>
)

// ==================== 逐字行 ====================

/**
 * 逐字歌词行。
 *
 * @param characters 音节列表
 * @param currentPosition 已插帧的精确播放位置（毫秒）
 * @param isActiveLine 是否为当前正在唱的行（驱动 mask/float/重音加粗）
 * @param activeColor 活跃时（唱到处）的文字颜色，通常为纯白
 * @param baseColor 未唱到 / 非活跃行的文字颜色
 * @param emphasisEnabled 是否启用重音加粗强调（音译行关闭）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebSyllableText(
    characters: List<LyricChar>,
    currentPosition: Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    activeColor: Color,
    baseColor: Color,
    lineStartTime: Long,
    lineEndTime: Long,
    emphasisEnabled: Boolean = true,
    alignEnd: Boolean = false,
    modifier: Modifier = Modifier
) {
    val wordSizes = remember(characters) {
        mutableStateListOf<IntSize>().also { list -> repeat(characters.size) { list.add(IntSize.Zero) } }
    }
    val maskReady = wordSizes.size == characters.size &&
        wordSizes.all { it.width > 0 && it.height > 0 }
    val wordColor = if (isActiveLine) activeColor else baseColor

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (alignEnd) Arrangement.End else Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        characters.forEachIndexed { index, word ->
            WebSyllableWord(
                word = word,
                wordIndex = index,
                words = characters,
                wordSizes = wordSizes,
                maskReady = maskReady,
                currentPosition = currentPosition,
                isActiveLine = isActiveLine,
                textStyle = textStyle,
                wordColor = wordColor,
                lineStartTime = lineStartTime,
                lineEndTime = lineEndTime,
                emphasisEnabled = emphasisEnabled,
                onSizeChanged = { size ->
                    if (index in wordSizes.indices && wordSizes[index] != size) {
                        wordSizes[index] = size
                    }
                }
            )
        }
    }
}

@Composable
private fun WebSyllableWord(
    word: LyricChar,
    wordIndex: Int,
    words: List<LyricChar>,
    wordSizes: List<IntSize>,
    maskReady: Boolean,
    currentPosition: Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    wordColor: Color,
    lineStartTime: Long,
    lineEndTime: Long,
    emphasisEnabled: Boolean,
    onSizeChanged: (IntSize) -> Unit
) {
    val cssPx = with(LocalDensity.current) { 1.dp.toPx() }
    val relativeTime = (currentPosition - lineStartTime).toFloat()
    val wordFloatY = resolveWordFloat(
        word = word,
        relativeTime = relativeTime,
        lineStartTime = lineStartTime,
        isActiveLine = isActiveLine,
        cssPx = cssPx
    )
    // M2：只有活跃行才需要 mask（非活跃行的 mask 根本不会被读取）。
    // L3：与位置无关的 mask 帧构建按「词尺寸签名 + 行时间」缓存，每帧只做 sampleWordMask 采样。
    val maskFrames = if (maskReady && isActiveLine) {
        val sig = wordSizesSignature(wordSizes)
        remember(sig, wordIndex, words.size, lineStartTime, lineEndTime) {
            buildWordMaskFrames(wordIndex, words, wordSizes, lineStartTime, lineEndTime)
        }
    } else {
        null
    }
    val mask = if (maskFrames != null) {
        sampleWordMask(maskFrames, currentPosition, lineStartTime, lineEndTime)
    } else {
        WebWordMask(0f, 0f)
    }

    Box(
        modifier = Modifier
            .onSizeChanged(onSizeChanged)
            .graphicsLayer { translationY = wordFloatY }
    ) {
        WebWordContent(
            word = word,
            isActiveLine = isActiveLine,
            textStyle = textStyle,
            color = wordColor,
            emphasisEnabled = emphasisEnabled,
            modifier = if (isActiveLine) {
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        if (size.width <= 0f) return@drawWithContent
                        drawContent()
                        drawRect(
                            brush = webMaskBrush(mask, size.width),
                            size = size,
                            blendMode = BlendMode.DstIn
                        )
                    }
            } else {
                Modifier
            }
        )
    }
}

@Composable
private fun WebWordContent(
    word: LyricChar,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    color: Color,
    emphasisEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    // 重音字在活跃行用「加粗」强调，替代 web 的逐字放大 + 黑色辉光：
    // 放大动画要求逐帧重绘 graphicsLayer，黑色 Shadow 在浅色背景上就是黑边，
    // 两者都是低端机卡顿 / 黑边的来源，故弃用，改为零动画的静态加粗。
    val stressed = emphasisEnabled && word.stress && isActiveLine && word.char.isNotEmpty()
    androidx.compose.material3.Text(
        text = word.char,
        style = textStyle.copy(
            color = color,
            fontWeight = if (stressed) FontWeight.Bold else textStyle.fontWeight
        ),
        modifier = modifier,
        textAlign = TextAlign.Start
    )
}

// ==================== mask 计算 ====================

/** L3：与播放位置无关的 mask 帧（含 leftPos/widthInTotal/imageWidth），可跨帧缓存。 */
private fun buildWordMaskFrames(
    wordIndex: Int,
    words: List<LyricChar>,
    wordSizes: List<IntSize>,
    lineStartTime: Long,
    lineEndTime: Long
): WebMaskFrames? {
    val size = wordSizes.getOrNull(wordIndex) ?: return null
    val wordWidth = size.width.toFloat()
    val wordHeight = size.height.toFloat()
    if (wordWidth <= 0f || wordHeight <= 0f || words.isEmpty()) {
        return null
    }
    val lineDuration = (lineEndTime - lineStartTime).coerceAtLeast(1L).toFloat()
    val wordWidths = words.indices.map { wordSizes.getOrNull(it)?.width?.toFloat() ?: 0f }
    return buildMaskFrames(
        wordIndex = wordIndex,
        words = words,
        wordWidths = wordWidths,
        wordWidth = wordWidth,
        wordHeight = wordHeight,
        lineStartTime = lineStartTime,
        lineDuration = lineDuration
    )
}

/** 每帧：根据当前位置在已缓存的 [frames] 上采样出遮罩位置（仅活跃行调用）。 */
private fun sampleWordMask(
    frames: WebMaskFrames,
    currentPosition: Long,
    lineStartTime: Long,
    lineEndTime: Long
): WebWordMask {
    val lineDuration = (lineEndTime - lineStartTime).coerceAtLeast(1L).toFloat()
    val relativeTime = (currentPosition - lineStartTime).toFloat().coerceIn(0f, lineDuration)
    val maskPosition = sampleMaskPosition(frames.frames, relativeTime / lineDuration)
    return WebWordMask(
        activeX = maskPosition + frames.leftPos * frames.imageWidth,
        fadeX = maskPosition + (frames.leftPos + frames.widthInTotal) * frames.imageWidth
    )
}

/** 词尺寸内容签名（O(N) 无分配），用作 mask 帧缓存 key；字号变化导致词尺寸变化时会失效重建。 */
private fun wordSizesSignature(sizes: List<IntSize>): Int {
    var h = 1
    for (i in sizes.indices) {
        val s = sizes[i]
        h = h * 31 + s.width
        h = h * 31 + s.height
    }
    return h
}

private fun buildMaskFrames(
    wordIndex: Int,
    words: List<LyricChar>,
    wordWidths: List<Float>,
    wordWidth: Float,
    wordHeight: Float,
    lineStartTime: Long,
    lineDuration: Float
): WebMaskFrames {
    val wordCount = words.size
    val invLineDuration = 1f / lineDuration
    val widthFade = wordHeight * WEB_MASK_FEATHER_NORMAL
    val widthFadeFirst = widthFade * WEB_MASK_FEATHER_FIRST
    val widthFadeLast = widthFade * WEB_MASK_FEATHER_LAST
    val widthFront = wordWidths.take(wordIndex).sum() + widthFade
    val widthRatio = widthFade / wordWidth
    val widthSize = 2f + widthRatio
    val widthInTotal = widthRatio / widthSize
    val leftPos = (1f - widthInTotal) / 2f
    val imageWidth = wordWidth * widthSize
    val positionMin = -(wordWidth + widthFade)

    val pauseProgress = FloatArray(wordCount)
    val moveProgress = FloatArray(wordCount)
    val hasPause = BooleanArray(wordCount)
    val hasMove = BooleanArray(wordCount)

    var progress = 0f
    var offset = 0f
    for (index in 0 until wordCount) {
        val startTime = (words[index].startTime - lineStartTime).toFloat()
        val gap = startTime - offset
        if (gap > 0f) {
            progress = (progress + gap * invLineDuration).coerceIn(0f, 1f)
            pauseProgress[index] = progress
            hasPause[index] = true
        }
        offset = startTime

        val duration = words[index].duration.toFloat()
        progress = (progress + duration * invLineDuration).coerceIn(0f, 1f)
        if (duration > 0f) {
            moveProgress[index] = progress
            hasMove[index] = true
        }
        offset += duration
    }

    val frames = mutableListOf(
        WebMaskFrame(
            offset = 0f,
            position = (-widthFront - wordWidth - widthFade).coerceIn(positionMin, 0f)
        )
    )
    var cursor = -widthFront - wordWidth - widthFade
    var prevCursor = cursor
    var prevProgress = 0f

    for (index in 0 until wordCount) {
        if (hasPause[index]) {
            prevProgress = pauseProgress[index]
            frames.add(
                WebMaskFrame(
                    offset = prevProgress,
                    position = cursor.coerceIn(positionMin, 0f)
                )
            )
        }

        cursor += wordWidths.getOrElse(index) { 0f }
        if (index == 0) {
            cursor += widthFadeFirst
        }
        if (index == wordCount - 1) {
            cursor += widthFadeLast
        }

        if (hasMove[index]) {
            val target = moveProgress[index]
            val dt = target - prevProgress
            val dx = cursor - prevCursor
            if (dx != 0f && dt > 0f) {
                val rate = dt / dx
                if (prevCursor < positionMin && cursor > positionMin) {
                    frames.add(
                        WebMaskFrame(
                            offset = prevProgress + (positionMin - prevCursor) * rate,
                            position = positionMin
                        )
                    )
                }
                if (prevCursor < 0f && cursor > 0f) {
                    frames.add(
                        WebMaskFrame(
                            offset = prevProgress - prevCursor * rate,
                            position = 0f
                        )
                    )
                }
            }

            prevCursor = cursor
            prevProgress = target
            frames.add(
                WebMaskFrame(
                    offset = target,
                    position = cursor.coerceIn(positionMin, 0f)
                )
            )
        } else {
            prevCursor = cursor
        }
    }

    return WebMaskFrames(
        leftPos = leftPos,
        widthInTotal = widthInTotal,
        imageWidth = imageWidth,
        frames = frames.sortedBy { it.offset }
    )
}

private fun sampleMaskPosition(frames: List<WebMaskFrame>, offset: Float): Float {
    if (frames.isEmpty()) return 0f
    val progress = offset.coerceIn(0f, 1f)
    var previous = frames.first()
    if (progress <= previous.offset) return previous.position
    for (index in 1 until frames.size) {
        val next = frames[index]
        if (progress <= next.offset) {
            val span = next.offset - previous.offset
            if (span <= 0f) return next.position
            val t = ((progress - previous.offset) / span).coerceIn(0f, 1f)
            return previous.position + (next.position - previous.position) * t
        }
        previous = next
    }
    return frames.last().position
}

private fun webMaskBrush(mask: WebWordMask, width: Float): Brush {
    val active = Color.Black.copy(alpha = WEB_ACTIVE_OPACITY)
    val normal = Color.Black.copy(alpha = WEB_NORMAL_OPACITY)

    return when {
        mask.fadeX <= 0f -> Brush.horizontalGradient(
            0f to normal,
            1f to normal,
            startX = 0f,
            endX = width
        )

        mask.activeX >= width -> Brush.horizontalGradient(
            0f to active,
            1f to active,
            startX = 0f,
            endX = width
        )

        else -> Brush.horizontalGradient(
            colors = listOf(active, normal),
            startX = mask.activeX,
            endX = mask.fadeX
        )
    }
}

// ==================== 单词上浮 ====================

private fun resolveWordFloat(
    word: LyricChar,
    relativeTime: Float,
    lineStartTime: Long,
    isActiveLine: Boolean,
    cssPx: Float
): Float {
    if (!isActiveLine) return 0f
    val delay = (word.startTime - lineStartTime).toFloat()
    val duration = word.duration.coerceAtLeast(1000L).toFloat()
    val progress = ((relativeTime - delay) / duration).coerceIn(0f, 1f)
    return (WEB_WORD_FLOAT_FROM + (WEB_WORD_FLOAT_TO - WEB_WORD_FLOAT_FROM) * WebEase.transform(progress)) * cssPx
}
