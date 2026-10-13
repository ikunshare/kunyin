package com.ikunshare.music.ui.screens.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.isUnspecified
import androidx.compose.ui.unit.dp
import com.ikunshare.music.model.LyricChar
import kotlin.math.roundToInt

/**
 * 逐字歌词（卡拉 OK）渲染，改编自 music-lyric-player-web 的 syllable 动画：
 * - mask：从左到右的遮罩擦除，带 feather 软边（normal 0.5 / first 1.5 / last 0.5，单位为字高倍数）
 * - 正文和音译分别使用固定基线，避免混合字体和逐字高亮造成行内跳动。
 * - 重音强调：不再采用 web 的逐字放大 + 黑色辉光（逐帧重绘导致卡顿、黑辉光在浅色背景上是黑边），
 *   改为活跃时对重音字静态加粗，零逐帧动画。
 *
 * 本文件只处理可见行内的动画；滚动和当前行定位由原生 LazyColumn 处理。
 */

// ==================== 常量（对应 web config 默认值）====================

private const val WEB_ACTIVE_OPACITY = 1f
private const val WEB_NORMAL_OPACITY = 0f

// mask feather：normal 0.5 * 字高，first 1.5x，last 0.5x
private const val WEB_MASK_FEATHER_NORMAL = 0.5f
private const val WEB_MASK_FEATHER_FIRST = 1.5f
private const val WEB_MASK_FEATHER_LAST = 0.5f

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
 * @param isActiveLine 是否为当前正在唱的行（驱动 mask/重音加粗）
 * @param activeColor 活跃时（唱到处）的文字颜色，通常为纯白
 * @param baseColor 未唱到 / 非活跃行的文字颜色
 * @param emphasisEnabled 是否启用重音加粗强调（音译行关闭）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebSyllableText(
    characters: List<LyricChar>,
    currentPosition: () -> Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    activeColor: Color,
    baseColor: Color,
    lineStartTime: Long,
    lineEndTime: Long,
    emphasisEnabled: Boolean = true,
    alignEnd: Boolean = false,
    showWordRomanization: Boolean = false,
    modifier: Modifier = Modifier
) {
    val wordSizes = remember(characters) {
        mutableStateListOf<IntSize>().also { list -> repeat(characters.size) { list.add(IntSize.Zero) } }
    }
    val maskReady = wordSizes.size == characters.size &&
        wordSizes.all { it.width > 0 && it.height > 0 }

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
                activeColor = activeColor,
                baseColor = baseColor,
                showWordRomanization = showWordRomanization,
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
    currentPosition: () -> Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    activeColor: Color,
    baseColor: Color,
    showWordRomanization: Boolean,
    lineStartTime: Long,
    lineEndTime: Long,
    emphasisEnabled: Boolean,
    onSizeChanged: (IntSize) -> Unit
) {
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
    LyricSyllableLayout(textStyle) {
        Box(Modifier.onSizeChanged(onSizeChanged)) {
        // Keep the unplayed syllable visible beneath the animated highlight.
        WebWordContent(
            word = word,
            isActiveLine = isActiveLine,
            textStyle = textStyle,
            color = baseColor,
            emphasisEnabled = emphasisEnabled
        )
        if (isActiveLine && maskFrames != null) WebWordContent(
            word = word, isActiveLine = true, textStyle = textStyle, color = activeColor,
            emphasisEnabled = emphasisEnabled,
            modifier =
                Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        if (size.width <= 0f) return@drawWithContent
                        val mask = sampleWordMask(maskFrames, currentPosition(), lineStartTime, lineEndTime)
                        drawContent()
                        drawRect(
                            brush = webMaskBrush(mask, size.width),
                            size = size,
                            blendMode = BlendMode.DstIn
                        )
                    }
        )
        }
        if (showWordRomanization && !word.romanization.isNullOrBlank()) {
            val romanStyle = textStyle.copy(fontSize = textStyle.fontSize * 0.45f,
                lineHeight = textStyle.fontSize * 0.6f, fontWeight = FontWeight.Normal)
            Row {
                word.romanizationWords.ifEmpty {
                    listOf(LyricChar(word.romanization, word.startTime, word.endTime))
                }.forEach { token ->
                    WebRomanToken(token, romanStyle, currentPosition, isActiveLine, activeColor)
                }
            }
        }
    }
}

/** Font fallback may change a glyph's ascent; it must not move its neighbours or annotation. */
@Composable
private fun LyricSyllableLayout(style: TextStyle, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val fontPx = with(density) { style.fontSize.toPx() }
    val linePx = with(density) {
        if (style.lineHeight.isUnspecified) fontPx * 1.3f else style.lineHeight.toPx()
    }
    Layout(content = content) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val main = measurables.first().measure(loose)
        val roman = measurables.getOrNull(1)?.measure(loose)
        val width = constraints.constrainWidth(maxOf(main.width, roman?.width ?: 0))
        val height = constraints.constrainHeight((linePx + if (roman != null) fontPx * 0.6f else 0f).roundToInt())
        fun baseline(placeable: androidx.compose.ui.layout.Placeable): Int =
            placeable[FirstBaseline].let { if (it == androidx.compose.ui.layout.AlignmentLine.Unspecified) 0 else it }
        layout(width, height) {
            main.placeRelative((width - main.width) / 2, (fontPx - baseline(main)).roundToInt())
            roman?.placeRelative((width - roman.width) / 2,
                (linePx + fontPx * 0.45f - baseline(roman)).roundToInt())
        }
    }
}

/** One-to-one romans share the main word's window; connected syllables keep their own. */
@Composable
private fun WebRomanToken(token: LyricChar, style: TextStyle, position: () -> Long,
                          active: Boolean, color: Color) {
    Box {
        androidx.compose.material3.Text(token.char, style = style, color = color.copy(alpha = 0.28f),
            maxLines = 1, softWrap = false)
        if (active) androidx.compose.material3.Text(token.char, style = style, color = color.copy(alpha = 0.68f),
            maxLines = 1, softWrap = false,
            modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    val fraction = ((position() - token.startTime).toFloat() / token.duration.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (fraction <= 0f) return@drawWithContent
                    drawContent()
                    if (fraction < 1f) {
                        val edge = size.width * fraction
                        drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent),
                            startX = edge - size.height * 0.25f, endX = edge + size.height * 0.25f),
                            blendMode = BlendMode.DstIn)
                    }
                })
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
        maxLines = 1,
        softWrap = false,
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
