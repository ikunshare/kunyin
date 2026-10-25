package com.ikunshare.music.ui.screens.lyrics.player

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.isUnspecified
import com.ikunshare.music.model.LyricChar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * 逐字行的布局与绘制，对应桌面端 `components/line/normal/syllable` 目录：
 * - 布局只做一次（词 / 字 / 逐字音译各测一次），结果缓存在 [SyllableLayout]
 * - 每帧只在 draw 阶段按时间采样：mask 擦除、float 上浮、长音逐字上浮
 * - 擦除用文字笔刷（横向渐变）直接画，而非 DstIn 遮罩
 * - 运动中的词 / 字各自录进小离屏图层，靠图层位移做亚像素平滑上浮（见 [SyllableLayerCache]）
 */

internal class SyllableGlyph(val layout: TextLayoutResult, val x: Float, val width: Float)

internal class SyllableRoman(
    val layout: TextLayoutResult,
    /** 在音译行内的 x。 */
    val x: Float,
    val width: Float,
    val height: Float,
    /** 相对行起点 ms。 */
    val start: Float,
    val duration: Float,
)

/**
 * 长音强调参数：只保留逐字错峰上浮（桌面 emphasize 的 float 子效果）。
 * 桌面的逐字放大扇开（main）与发光（glow）已按需求移除。
 */
internal class EmphasizeParams(
    val delay: Float,
    val stagger: Float,
    val floatDuration: Float,
    val floatLead: Float,
    val floatAmplitude: Float,
)

internal class SyllableWord(
    val info: LyricChar,
    val cellX: Float,
    val cellY: Float,
    /** 擦除容器（词 + 随词擦除的音译）在 cell 内的 x 与宽度。 */
    val wipeX: Float,
    val wipeWidth: Float,
    val wordX: Float,
    val wordWidth: Float,
    val wordHeight: Float,
    /** 整词布局；拆字强调时为 null。 */
    val layout: TextLayoutResult?,
    val glyphs: List<SyllableGlyph>?,
    val romans: List<SyllableRoman>,
    val romanX: Float,
    val romanY: Float,
    val romanWidth: Float,
    val romanRidesMask: Boolean,
    val floatDelay: Float,
    val floatDuration: Float,
    val emphasize: EmphasizeParams?,
) {
    var mask: MaskFrames? = null
    var romanMasks: List<MaskFrames?>? = null
}

internal class SyllableLayout(
    val words: List<SyllableWord>,
    val units: List<DrawUnit>,
    val width: Float,
    val height: Float,
    val rowCount: Int,
    val lineStart: Long,
    val lineDuration: Float,
    val fontPx: Float,
    val isBackground: Boolean,
)

/** 行激活/退场的时间锚点，供 float 反向回落与 emphasize 快进收尾使用（WAAPI 墙钟行为）。 */
internal class LineAnimState {
    var deactivatedWallMs: Long = 0L
    /** 退场瞬间的行内相对时间；≤0 表示该行从未真正播过（动画保持初始帧）。 */
    var relAtDeactivation: Float = 0f

    fun activate() {
        relAtDeactivation = 0f
        deactivatedWallMs = 0L
    }

    fun deactivate(relativeTime: Float, wallMs: Long) {
        relAtDeactivation = relativeTime
        deactivatedWallMs = wallMs
    }
}

private fun splitGraphemes(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val out = ArrayList<String>(text.length)
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        val n = Character.charCount(cp)
        out.add(text.substring(i, i + n))
        i += n
    }
    return out
}

private fun TextMeasurer.measureSingle(text: String, style: TextStyle): TextLayoutResult =
    measure(text = text, style = style, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1)

/**
 * 构建一行逐字布局（FlowRow 式换行，左/右对齐）。
 *
 * @param maxWidth 可用宽度 px
 * @param romanStyle 逐字音译样式；null 表示不显示逐字音译
 * @param emphasizeEnabled 是否允许重音强调（音译行 / 背景行关闭）
 */
internal fun buildSyllableLayout(
    chars: List<LyricChar>,
    lineStart: Long,
    lineEnd: Long,
    style: TextStyle,
    romanStyle: TextStyle?,
    maxWidth: Float,
    alignEnd: Boolean,
    measurer: TextMeasurer,
    density: Density,
    config: LyricPlayerConfig,
    emphasizeEnabled: Boolean,
    isBackground: Boolean,
): SyllableLayout {
    val fontPx = with(density) { style.fontSize.toPx() }
    val lineDuration = (lineEnd - lineStart).coerceAtLeast(1L).toFloat()
    val gapHalf = config.wordRomanGapEm * fontPx / 2f
    val emphasize = config.emphasize
    val useEmphasize = emphasizeEnabled && emphasize.anyEffect
    val romanSpaceWidth = if (romanStyle != null) {
        measurer.measureSingle(" ", romanStyle).size.width.toFloat()
            .takeIf { it > 0f } ?: (with(density) { romanStyle.fontSize.toPx() } * 0.25f)
    } else 0f

    class Pending(
        val info: LyricChar,
        val layout: TextLayoutResult?,
        val glyphs: List<SyllableGlyph>?,
        val wordWidth: Float,
        val wordHeight: Float,
        val romans: List<SyllableRoman>,
        val romanWidth: Float,
        val romanHeight: Float,
        val romanRidesMask: Boolean,
        val cellWidth: Float,
        val wipeWidth: Float,
        val emphasize: EmphasizeParams?,
    )

    val pendings = ArrayList<Pending>(chars.size)
    for (word in chars) {
        val text = word.char
        val stressed = useEmphasize && word.stress && text.isNotBlank()
        var layout: TextLayoutResult? = null
        var glyphs: List<SyllableGlyph>? = null
        var wordWidth: Float
        var wordHeight: Float
        if (stressed) {
            var x = 0f
            var h = 0f
            val list = splitGraphemes(text).map { g ->
                val r = measurer.measureSingle(g, style)
                val w = r.size.width.toFloat()
                h = max(h, r.size.height.toFloat())
                SyllableGlyph(r, x, w).also { x += w }
            }
            glyphs = list
            wordWidth = x
            wordHeight = h
        } else {
            val r = measurer.measureSingle(text, style)
            layout = r
            wordWidth = r.size.width.toFloat()
            wordHeight = r.size.height.toFloat()
        }

        // 逐字音译 token
        val romans = ArrayList<SyllableRoman>()
        var romanWidth = 0f
        var romanHeight = 0f
        var rides = false
        if (romanStyle != null && !word.romanization.isNullOrBlank()) {
            val tokens = word.romanizationWords.ifEmpty { listOf(LyricChar(word.romanization, word.startTime, word.endTime)) }
            var x = 0f
            for ((i, token) in tokens.withIndex()) {
                val content = token.char.trim()
                if (content.isEmpty()) continue
                if (i > 0 && x > 0f) x += romanSpaceWidth
                val r = measurer.measureSingle(content, romanStyle)
                val w = r.size.width.toFloat()
                romanHeight = max(romanHeight, r.size.height.toFloat())
                romans.add(SyllableRoman(r, x, w, r.size.height.toFloat(),
                    (token.startTime - lineStart).toFloat().coerceIn(0f, lineDuration),
                    token.duration.toFloat().coerceAtLeast(0f)))
                x += w
            }
            romanWidth = x
            // 单 token 且时间窗与主词一致 → 随主词擦除；否则自走时间轴
            rides = !config.forceOwnWipe && romans.size == 1 &&
                tokens.size == 1 && tokens[0].startTime == word.startTime && tokens[0].endTime == word.endTime
        }
        // 悬挂余量：注解行两侧各让出 gap/2，拼音略宽时悬进 gap 不推开汉字
        val hangingRoman = if (romans.isEmpty()) 0f else max(0f, romanWidth - gapHalf * 2f)
        val cellWidth = max(wordWidth, hangingRoman)
        val wipeWidth = if (rides) max(wordWidth, romanWidth) else wordWidth

        val emphasizeParams = if (stressed && glyphs != null && glyphs.isNotEmpty()) {
            val raw = max(emphasize.minDurationMs.toFloat(), word.duration.toFloat())
            val float = emphasize.float
            val amplitudeScale = if (isBackground && float.backgroundEnabled) float.backgroundScale else 1f
            EmphasizeParams(
                delay = (word.startTime - lineStart).toFloat(),
                stagger = raw / 2.5f / glyphs.size,
                floatDuration = raw * float.durationScale,
                floatLead = float.leadMs.toFloat(),
                floatAmplitude = float.amplitudeEm * fontPx * amplitudeScale,
            )
        } else null

        pendings.add(Pending(word, layout, glyphs, wordWidth, wordHeight, romans, romanWidth, romanHeight, rides,
            cellWidth, wipeWidth, emphasizeParams))
    }

    // 换行：cell 之间在有音译时各留 gap/2（首尾词外侧不留）
    val rows = ArrayList<MutableList<Int>>()
    val rowWidths = ArrayList<Float>()
    val cellXs = FloatArray(pendings.size)
    run {
        var x = 0f
        var current = ArrayList<Int>()
        for ((i, p) in pendings.withIndex()) {
            val hasRoman = p.romans.isNotEmpty()
            val marginLeft = if (hasRoman && i > 0) gapHalf else 0f
            val marginRight = if (hasRoman && i < pendings.lastIndex) gapHalf else 0f
            val advance = marginLeft + p.cellWidth + marginRight
            if (current.isNotEmpty() && x + advance > maxWidth + 0.5f) {
                rows.add(current)
                rowWidths.add(x)
                current = ArrayList()
                x = 0f
            }
            cellXs[i] = x + marginLeft
            current.add(i)
            x += advance
        }
        if (current.isNotEmpty()) {
            rows.add(current)
            rowWidths.add(x)
        }
    }

    val layoutWidth = rowWidths.maxOrNull() ?: 0f
    val words = ArrayList<SyllableWord>(pendings.size)
    var y = 0f
    for ((r, row) in rows.withIndex()) {
        var rowHeight = 0f
        for (i in row) {
            val p = pendings[i]
            rowHeight = max(rowHeight, p.wordHeight + if (p.romans.isNotEmpty()) p.romanHeight else 0f)
        }
        val shift = if (alignEnd) layoutWidth - rowWidths[r] else 0f
        for (i in row) {
            val p = pendings[i]
            val wipeX = (p.cellWidth - p.wipeWidth) / 2f
            val wordX = wipeX + (p.wipeWidth - p.wordWidth) / 2f
            val romanX = (p.cellWidth - p.romanWidth) / 2f
            words.add(SyllableWord(
                info = p.info,
                cellX = cellXs[i] + shift,
                cellY = y,
                wipeX = wipeX,
                wipeWidth = p.wipeWidth,
                wordX = wordX,
                wordWidth = p.wordWidth,
                wordHeight = p.wordHeight,
                layout = p.layout,
                glyphs = p.glyphs,
                romans = p.romans,
                romanX = romanX,
                romanY = p.wordHeight,
                romanWidth = p.romanWidth,
                romanRidesMask = p.romanRidesMask,
                floatDelay = (p.info.startTime - lineStart).toFloat(),
                floatDuration = max(config.float.minDurationMs.toFloat(), p.info.duration.toFloat()),
                emphasize = p.emphasize,
            ))
        }
        y += rowHeight
    }

    val result = SyllableLayout(words, buildDrawUnits(words), layoutWidth, y, rows.size, lineStart, lineDuration,
        fontPx, isBackground)

    // 擦除关键帧：词级（宽度取擦除容器宽、高度取词高）
    if (config.mask.enabled && words.isNotEmpty()) {
        val segments = words.map { w ->
            val start = (w.info.startTime - lineStart).toFloat().coerceIn(0f, lineDuration)
            val end = (w.info.endTime - lineStart).toFloat().coerceIn(start, lineDuration)
            MaskSegment(start, end - start, w.wipeWidth, w.wordHeight)
        }
        val frames = buildMaskFrames(segments, lineDuration, config.mask)
        for ((i, w) in words.withIndex()) w.mask = frames[i]
        // 自走时间轴的逐字音译：按 token 自身时间生成
        for (w in words) {
            if (w.romans.isEmpty() || w.romanRidesMask) continue
            val tokenSegments = w.romans.map { t ->
                val end = (t.start + t.duration).coerceIn(t.start, lineDuration)
                MaskSegment(t.start, end - t.start, t.width, t.height)
            }
            w.romanMasks = buildMaskFrames(tokenSegments, lineDuration, config.mask)
        }
    }
    return result
}

// ==================== 绘制 ====================

/** 绘制单元里的一段文字：单元坐标系下的位置，以及它在所属词擦除容器里的 x 偏移。 */
internal class DrawPiece(
    val layout: TextLayoutResult,
    val dx: Float,
    val dy: Float,
    val maskShift: Float,
    val width: Float,
)

/**
 * 一个随动画整体移动的绘制单元：普通词（含随词擦除的逐字音译），或长音词拆出的单字。
 * 运动中的单元录进各自的离屏图层，由图层位移承担上浮。
 */
internal class DrawUnit(
    val id: Int,
    val word: SyllableWord,
    /** 长音拆字时为字序号，否则为 -1。 */
    val glyphIndex: Int,
    val x: Float,
    val y: Float,
    val pieces: List<DrawPiece>,
) {
    val width: Float = pieces.maxOfOrNull { it.dx + it.width } ?: 0f
    val height: Float = pieces.maxOfOrNull { it.dy + it.layout.size.height } ?: 0f
}

internal fun buildDrawUnits(words: List<SyllableWord>): List<DrawUnit> {
    val units = ArrayList<DrawUnit>(words.size + 4)
    for (w in words) {
        val wordShift = w.wordX - w.wipeX
        val ride = if (w.romanRidesMask) {
            w.romans.map { t -> DrawPiece(t.layout, w.romanX + t.x, w.romanY, w.romanX - w.wipeX + t.x, t.width) }
        } else emptyList()
        val glyphs = w.glyphs
        if (glyphs == null) {
            val pieces = ArrayList<DrawPiece>(1 + ride.size)
            w.layout?.let { pieces.add(DrawPiece(it, w.wordX, 0f, wordShift, w.wordWidth)) }
            pieces.addAll(ride)
            if (pieces.isNotEmpty()) units.add(DrawUnit(units.size, w, -1, w.cellX, w.cellY, pieces))
        } else {
            for ((gi, g) in glyphs.withIndex()) {
                units.add(DrawUnit(units.size, w, gi, w.cellX + w.wordX + g.x, w.cellY,
                    listOf(DrawPiece(g.layout, 0f, 0f, wordShift + g.x, g.width))))
            }
            if (ride.isNotEmpty()) units.add(DrawUnit(units.size, w, -1, w.cellX, w.cellY, ride))
        }
    }
    return units
}

/**
 * 运动单元的离屏图层缓存。
 *
 * Android 直接绘制文字时纵坐标会对齐到整像素：几像素、持续一秒以上的上浮会一格一格地跳，
 * 各字跳的时机又不同，看起来就是持续抖动。离屏图层在非整数位移时按双线性采样合成，
 * 位移是连续的（与浏览器合成层的做法一致）。内容只在擦除状态变化时重录，
 * 透明度走图层 alpha，位移只改图层属性，都不触发重录。
 */
internal class SyllableLayerCache(private val context: GraphicsContext) {
    private class Slot(val layer: GraphicsLayer) {
        var recorded = false
        var activeX = 0f
        var fadeX = 0f
        var normalRatio = 0f
        var color = Color.Unspecified
        var size = IntSize.Zero
        var originX = 0f
        var originY = 0f
    }

    private val slots = HashMap<Int, Slot>()

    val isEmpty: Boolean get() = slots.isEmpty()

    fun releaseAll() {
        for (slot in slots.values) context.releaseGraphicsLayer(slot.layer)
        slots.clear()
    }

    fun draw(
        scope: DrawScope, unit: DrawUnit, painter: SyllablePainter, pad: Float, translationY: Float, alpha: Float,
        activeX: Float, fadeX: Float, normalRatio: Float, color: Color,
    ) {
        val slot = slots.getOrPut(unit.id) {
            Slot(context.createGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen })
        }
        // 图层左上角落在整像素上，静止位置的小数部分放进图层内容里，保证静止时与直接绘制逐像素一致
        val ix = floor(unit.x - pad).toInt()
        val iy = floor(unit.y - pad).toInt()
        val originX = unit.x - ix
        val originY = unit.y - iy
        val size = IntSize(ceil(originX + unit.width + pad).toInt(), ceil(originY + unit.height + pad).toInt())
        if (!slot.recorded || slot.activeX != activeX || slot.fadeX != fadeX || slot.normalRatio != normalRatio ||
            slot.color != color || slot.size != size || slot.originX != originX || slot.originY != originY
        ) {
            with(scope) {
                slot.layer.record(size) {
                    translate(originX, originY) { painter.drawPieces(this, unit, activeX, fadeX, color, 1f, normalRatio) }
                }
            }
            slot.recorded = true
            slot.activeX = activeX
            slot.fadeX = fadeX
            slot.normalRatio = normalRatio
            slot.color = color
            slot.size = size
            slot.originX = originX
            slot.originY = originY
        }
        slot.layer.topLeft = IntOffset(ix, iy)
        slot.layer.translationY = translationY
        slot.layer.alpha = alpha
        scope.drawLayer(slot.layer)
    }
}

/** 每帧绘制一行逐字内容。 */
internal class SyllablePainter(private val config: LyricPlayerConfig) {
    private val scratch = FloatArray(2)

    /**
     * @param relativeTime 行内相对时间 ms（可为负：行已激活但尚未唱到）
     * @param wallNowMs 墙钟 ms，供退场动画使用
     * @param elementAlpha 主词元素不透明度（0.6 / 1 / 0.4 的过渡值）
     * @param maskNormalAlpha 激活行未唱部分相对已唱部分的不透明度比（0.6）
     * @param layers 非空时运动单元走离屏图层（激活行与退场收尾中的行）；为空时直接绘制静止内容
     */
    fun draw(
        scope: DrawScope,
        layout: SyllableLayout,
        color: Color,
        elementAlpha: Float,
        active: Boolean,
        relativeTime: Float,
        wallNowMs: Long,
        anim: LineAnimState,
        maskNormalAlpha: Float,
        layers: SyllableLayerCache?,
    ) {
        if (elementAlpha <= 0.002f) return
        // 播过但未唱完就退场的行：mask 冻结在退场进度，未唱部分保持压暗随整行淡出，避免瞬间变亮闪烁
        val frozen = !active && anim.relAtDeactivation > 0f
        val progress = when {
            active -> clamp01(relativeTime / layout.lineDuration)
            frozen -> clamp01(anim.relAtDeactivation / layout.lineDuration)
            else -> 1f
        }
        val normalRatio = if (active || frozen) maskNormalAlpha else 1f
        // 上浮终点取整像素：静止后图层落在像素网格上，不会因双线性采样发虚
        val floatTo = round(config.float.toEm * layout.fontPx)
        val floatFrom = round(config.float.fromEm * layout.fontPx)
        val pad = ceil(layout.fontPx * 0.15f)
        val effects = config.emphasize

        var currentWord: SyllableWord? = null
        var floatY = 0f
        var activeX = Float.POSITIVE_INFINITY
        var fadeX = Float.POSITIVE_INFINITY
        var timeline: Float? = null
        for (unit in layout.units) {
            val word = unit.word
            if (word !== currentWord) {
                currentWord = word
                floatY = floatOffset(word, active, relativeTime, wallNowMs, anim, floatFrom, floatTo)
                activeX = Float.POSITIVE_INFINITY
                fadeX = Float.POSITIVE_INFINITY
                val mask = word.mask
                if ((active || frozen) && mask != null) {
                    sampleInto(mask, progress)
                    activeX = scratch[0]
                    fadeX = scratch[1]
                }
                // 长音逐字上浮的时间轴：激活行按内容时间；退场行按 disablePlaybackRate 倍速快进收尾
                timeline = when {
                    word.emphasize == null -> null
                    active -> relativeTime
                    anim.relAtDeactivation <= 0f -> null
                    else -> anim.relAtDeactivation + (wallNowMs - anim.deactivatedWallMs) * max(1f, effects.disablePlaybackRate)
                }
            }
            var ty = floatY
            val params = word.emphasize
            if (unit.glyphIndex >= 0 && params != null && timeline != null && effects.float.enabled && params.floatDuration > 0f) {
                val charDelay = params.delay + params.stagger * unit.glyphIndex
                val p = pulse((timeline - (charDelay - params.floatLead)) / params.floatDuration,
                    effects.float.easing, effects.float.easing)
                if (p > 0f) ty += -params.floatAmplitude * p
            }
            if (layers != null) {
                layers.draw(scope, unit, this, pad, ty, elementAlpha, activeX, fadeX, normalRatio, color)
            } else {
                scope.translate(unit.x, unit.y + ty) {
                    drawPieces(this, unit, activeX, fadeX, color, elementAlpha, elementAlpha * normalRatio)
                }
            }
        }

        // 自走时间轴的逐字音译：不随词上浮，静止绘制
        for (word in layout.words) {
            if (word.romans.isEmpty() || word.romanRidesMask) continue
            val romanLeft = word.cellX + word.romanX
            val romanTop = word.cellY + word.romanY
            val masks = word.romanMasks
            for ((i, t) in word.romans.withIndex()) {
                var a = Float.POSITIVE_INFINITY
                var f = Float.POSITIVE_INFINITY
                val m = masks?.getOrNull(i)
                if ((active || frozen) && m != null) {
                    sampleInto(m, progress)
                    a = scratch[0]
                    f = scratch[1]
                }
                drawMasked(scope, t.layout, romanLeft + t.x, romanTop, a, f, t.width, color, elementAlpha,
                    elementAlpha * normalRatio)
            }
        }
    }

    internal fun drawPieces(
        scope: DrawScope, unit: DrawUnit, activeX: Float, fadeX: Float, color: Color, alphaActive: Float, alphaNormal: Float,
    ) {
        for (piece in unit.pieces) {
            drawMasked(scope, piece.layout, piece.dx, piece.dy, activeX - piece.maskShift, fadeX - piece.maskShift,
                piece.width, color, alphaActive, alphaNormal)
        }
    }

    private fun sampleInto(mask: MaskFrames, progress: Float) {
        val position = sampleMaskPosition(mask.frames, progress)
        scratch[0] = position + mask.leftPos * mask.imageWidth
        scratch[1] = position + (mask.leftPos + mask.widthInTotal) * mask.imageWidth
    }

    private fun floatOffset(
        word: SyllableWord, active: Boolean, relativeTime: Float, wallNowMs: Long, anim: LineAnimState,
        from: Float, to: Float,
    ): Float {
        if (!config.float.enabled) return 0f
        val delay = word.floatDelay
        val duration = word.floatDuration
        val local = if (active) {
            relativeTime - delay
        } else {
            // 未播过的行保持初始帧；播过的行以 -1 倍速按墙钟回落（fill both：回到 delay 之前即为 from）
            if (anim.relAtDeactivation <= 0f) return from
            val current = min(anim.relAtDeactivation, delay + duration)
            current - (wallNowMs - anim.deactivatedWallMs).toFloat() - delay
        }
        return from + (to - from) * CssEase.transform(clamp01(local / duration))
    }

    /**
     * 擦除 = 双层互补 clip 硬切：已唱区 [0, activeX) 与未唱区 [activeX, width) 各画一遍完整文字，
     * 互不重叠、无渐变、无状态分支。边界是单一浮点值 activeX，离屏图层合成时双线性采样，
     * 亚像素移动不闪。[fadeX] 保留用于软边签名兼容，当前不参与绘制。
     */
    private fun drawMasked(
        scope: DrawScope, layout: TextLayoutResult, left: Float, top: Float, activeX: Float, fadeX: Float,
        width: Float, color: Color, alphaActive: Float, alphaNormal: Float,
    ) {
        val topLeft = Offset(left, top)
        val height = layout.size.height.toFloat()
        val cut = activeX.coerceIn(0f, width)
        if (cut > 0f) {
            scope.clipRect(left = left, top = top - 1f, right = left + cut, bottom = top + height + 1f) {
                scope.drawText(layout, color = color.copy(alpha = alphaActive), topLeft = topLeft)
            }
        }
        if (cut < width) {
            scope.clipRect(left = left + cut, top = top - 1f, right = left + width + 1f, bottom = top + height + 1f) {
                scope.drawText(layout, color = color.copy(alpha = alphaNormal), topLeft = topLeft)
            }
        }
    }
}

internal fun TextStyle.resolvedLineHeightPx(density: Density): Float = with(density) {
    if (lineHeight.isUnspecified) fontSize.toPx() * 1.3f else lineHeight.toPx()
}
