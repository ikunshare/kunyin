package com.ikunshare.music.ui.screens.lyrics.player

/**
 * 逐字擦除遮罩的关键帧生成，逐行对应桌面端 `syllable/animation/mask.ts` 的 `MaskAnimationHost.generate`。
 *
 * 桌面端把结果交给 WAAPI 按行时长播放；这里保留同样的「进度(0..1) → 遮罩位置(px)」折线，
 * 每帧由 [sampleMaskPosition] 采样。遮罩几何（leftPos / widthInTotal / imageWidth）与
 * CSS `mask-image` 的线性渐变一致，最终转成词内坐标的 [activeX, fadeX]。
 */

internal class MaskSegment(
    /** 相对行起点的开始 ms。 */
    val start: Float,
    val duration: Float,
    val width: Float,
    val height: Float,
)

internal class MaskFrame(val offset: Float, val position: Float)

internal class MaskFrames(
    val leftPos: Float,
    val widthInTotal: Float,
    val imageWidth: Float,
    val frames: List<MaskFrame>,
) {
    /** 当前进度下，遮罩「全亮」边界与「渐变结束」边界在词内的 x。 */
    fun sample(progress: Float): Pair<Float, Float> {
        val position = sampleMaskPosition(frames, progress)
        return (position + leftPos * imageWidth) to (position + (leftPos + widthInTotal) * imageWidth)
    }
}

/**
 * 为一组连续音节生成每个音节的遮罩关键帧。
 *
 * @param segments 每个音节的时间/尺寸；start 相对行起点，已钳制到 [0, lineDuration]
 * @return 与 [segments] 等长；宽度为 0 的音节为 null
 */
internal fun buildMaskFrames(
    segments: List<MaskSegment>,
    lineDuration: Float,
    config: LyricMaskConfig,
): List<MaskFrames?> {
    val wordCount = segments.size
    if (lineDuration <= 0f || wordCount == 0) return List(wordCount) { null }

    val featherNormal = config.featherNormal.coerceIn(0f, 2f)
    val featherFirst = config.featherFirst.coerceIn(0f, 5f)
    val featherLast = config.featherLast.coerceIn(0f, 5f)
    val invLineDuration = 1f / lineDuration
    val lastIndex = wordCount - 1

    val wordWidths = FloatArray(wordCount) { segments[it].width }
    val wordFrontWidths = FloatArray(wordCount + 1)
    for (i in 0 until wordCount) wordFrontWidths[i + 1] = wordFrontWidths[i] + wordWidths[i]

    val pauseProgress = FloatArray(wordCount)
    val moveProgress = FloatArray(wordCount)
    val hasPause = BooleanArray(wordCount)
    val hasMove = BooleanArray(wordCount)
    run {
        var progress = 0f
        var offset = 0f
        for (i in 0 until wordCount) {
            val startTime = segments[i].start
            val gap = startTime - offset
            if (gap > 0f) {
                progress += gap * invLineDuration
                pauseProgress[i] = clamp01(progress)
                hasPause[i] = true
            }
            offset = startTime
            val duration = segments[i].duration
            progress += duration * invLineDuration
            if (duration > 0f) {
                moveProgress[i] = clamp01(progress)
                hasMove[i] = true
            }
            offset += duration
        }
    }

    return List(wordCount) { index ->
        val wordWidth = wordWidths[index]
        if (wordWidth <= 0f) return@List null

        val widthFade = segments[index].height * featherNormal
        val widthFadeFirst = widthFade * featherFirst
        val widthFadeLast = widthFade * featherLast
        val widthFront = wordFrontWidths[index] + widthFade

        val widthRatio = widthFade / wordWidth
        val widthSize = 2f + widthRatio
        val widthInTotal = widthRatio / widthSize
        val leftPos = (1f - widthInTotal) / 2f
        val positionMin = -(wordWidth + widthFade)

        fun clampPos(value: Float) = if (value < positionMin) positionMin else if (value > 0f) 0f else value

        var cursor = -widthFront - wordWidth - widthFade
        var prevCursor = cursor
        var prevProgress = 0f
        val frames = ArrayList<MaskFrame>(wordCount * 2 + 3)
        frames.add(MaskFrame(0f, clampPos(cursor)))

        for (i in 0 until wordCount) {
            if (hasPause[i]) {
                prevProgress = pauseProgress[i]
                frames.add(MaskFrame(prevProgress, clampPos(cursor)))
            }
            cursor += wordWidths[i]
            if (i == 0) cursor += widthFadeFirst
            if (i == lastIndex) cursor += widthFadeLast

            if (hasMove[i]) {
                val target = moveProgress[i]
                val dt = target - prevProgress
                val dx = cursor - prevCursor
                if (dx != 0f && dt > 0f) {
                    val rate = dt / dx
                    if (prevCursor < positionMin && cursor > positionMin) {
                        frames.add(MaskFrame(prevProgress + (positionMin - prevCursor) * rate, positionMin))
                    }
                    if (prevCursor < 0f && cursor > 0f) {
                        frames.add(MaskFrame(prevProgress - prevCursor * rate, 0f))
                    }
                }
                prevCursor = cursor
                prevProgress = target
                frames.add(MaskFrame(target, clampPos(cursor)))
            } else {
                prevCursor = cursor
            }
        }
        // WAAPI 要求 offset 单调；边界帧插入后可能略微乱序，按 offset 稳定排序
        frames.sortBy { it.offset }
        MaskFrames(leftPos, widthInTotal, wordWidth * widthSize, frames)
    }
}

internal fun sampleMaskPosition(frames: List<MaskFrame>, offset: Float): Float {
    if (frames.isEmpty()) return 0f
    val progress = clamp01(offset)
    var previous = frames[0]
    if (progress <= previous.offset) return previous.position
    for (index in 1 until frames.size) {
        val next = frames[index]
        if (progress <= next.offset) {
            val span = next.offset - previous.offset
            if (span <= 0f) return next.position
            val t = clamp01((progress - previous.offset) / span)
            return previous.position + (next.position - previous.position) * t
        }
        previous = next
    }
    return frames[frames.size - 1].position
}
