package com.ikunshare.music.ui.screens.lyrics.player

import android.icu.text.BreakIterator
import androidx.compose.ui.geometry.Offset
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
 * - 擦除用双层互补 clip 直接画，而非 DstIn 遮罩
 * - 运动中的词 / 字各自录进小离屏图层，靠图层位移做亚像素平滑上浮（见 [SyllableLayerCache]）
 */

internal class SyllableGlyph(
    val layout: TextLayoutResult,
    val x: Float,
    val width: Float,
    /** 该字形第一行基线相对其顶部的距离，用于跨字体基线对齐。 */
    val ascent: Float,
)

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
    /** 词框顶部；已按行基线对齐做过纵向偏移。 */
    val cellY: Float,
    /** 该词第一行基线相对词框顶部的距离。 */
    val ascent: Float,
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

private fun splitGraphemes(text: String, iterator: BreakIterator): List<String> {
    if (text.isEmpty()) return emptyList()
    iterator.setText(text)
    val out = ArrayList<String>(text.length)
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        out.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return out
}

private fun TextMeasurer.measureSingle(text: String, style: TextStyle): TextLayoutResult =
    measure(text = text, style = style, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1)

/**
 * 词框顶部到第一行基线的距离。
 *
 * 逐字行按词单独测量，拉丁字体（Roboto）与汉字 / 假名后备字体的 ascent 相差近 0.2em，
 * 若按词框顶部对齐，混排时拉丁文的基线就会浮在汉字上方。取真实基线后按行统一对齐即可消除。
 * 空串等测不出基线的情况退回 ascent 近似值。
 */
private fun TextLayoutResult.baselineOr(style: TextStyle, density: Density): Float {
    if (lineCount > 0) return getLineBaseline(0)
    val fontPx = with(density) { style.fontSize.toPx() }
    return if (fontPx > 0f) fontPx * 0.8f else size.height * 0.8f
}

/**
 * 构建一行逐字布局（FlowRow 式换行，左/右对齐）。
 *
 * @param maxWidth 可用宽度 px
 * @param romanStyle 逐字音译样式；null 表示不显示逐字音译
 * @param emphasizeEnabled 是否允许重音强调（音译行 / 背景行关闭）
 * @param measureOnly 仅计算换行时跳过绘制单元与遮罩关键帧
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
    measureOnly: Boolean = false,
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
        /** 词框顶部到第一行基线的距离；跨字体混排时按它对齐基线。 */
        val ascent: Float,
        val romans: List<SyllableRoman>,
        val romanWidth: Float,
        val romanHeight: Float,
        val romanRidesMask: Boolean,
        val cellWidth: Float,
        val wipeWidth: Float,
        val emphasize: EmphasizeParams?,
    )

    val pendings = ArrayList<Pending>(chars.size)
    var graphemeIterator: BreakIterator? = null
    for (word in chars) {
        val text = word.char
        val stressed = useEmphasize && word.stress && text.isNotBlank()
        var layout: TextLayoutResult? = null
        var glyphs: List<SyllableGlyph>? = null
        var wordWidth: Float
        var wordHeight: Float
        var wordAscent: Float
        if (stressed) {
            val iterator = graphemeIterator ?: BreakIterator.getCharacterInstance().also { graphemeIterator = it }
            var x = 0f
            var ascent = 0f
            var descent = 0f
            val list = splitGraphemes(text, iterator).map { g ->
                val r = measurer.measureSingle(g, style)
                val w = r.size.width.toFloat()
                val a = r.baselineOr(style, density)
                ascent = max(ascent, a)
                descent = max(descent, r.size.height.toFloat() - a)
                SyllableGlyph(r, x, w, a).also { x += w }
            }
            glyphs = list
            wordWidth = x
            wordAscent = ascent
            wordHeight = ascent + descent
        } else {
            val r = measurer.measureSingle(text, style)
            layout = r
            wordWidth = r.size.width.toFloat()
            wordHeight = r.size.height.toFloat()
            wordAscent = r.baselineOr(style, density)
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

        pendings.add(Pending(word, layout, glyphs, wordWidth, wordHeight, wordAscent, romans, romanWidth, romanHeight, rides,
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
    // 版式行高：纯拉丁词的 ascent+descent 小于 lineHeight，需补足到样式行高，
    // 否则逐字行的行距会比整行歌词（BasicText 走 lineHeight）更紧。
    val lineHeightPx = style.resolvedLineHeightPx(density)
    val words = ArrayList<SyllableWord>(pendings.size)
    var y = 0f
    for ((r, row) in rows.withIndex()) {
        // 行内按真实基线对齐：取最大 ascent 与最大 descent，逐词下移 (rowAscent - ascent)。
        // 拉丁文与汉字 / 假名后备字体的 ascent 差近 0.2em，按顶部对齐就会出现混排上浮。
        var rowAscent = 0f
        var rowDescent = 0f
        var romanHeight = 0f
        for (i in row) {
            val p = pendings[i]
            rowAscent = max(rowAscent, p.ascent)
            rowDescent = max(rowDescent, p.wordHeight - p.ascent)
            if (p.romans.isNotEmpty()) romanHeight = max(romanHeight, p.romanHeight)
        }
        val textHeight = rowAscent + rowDescent
        val rowHeight = max(textHeight, lineHeightPx) + romanHeight
        // 行高补足的部分上下均分，与 lineHeight 默认的居中分配一致
        val extraTop = max(0f, (lineHeightPx - textHeight) / 2f)
        val shift = if (alignEnd) layoutWidth - rowWidths[r] else 0f
        for (i in row) {
            val p = pendings[i]
            val wipeX = (p.cellWidth - p.wipeWidth) / 2f
            val wordX = wipeX + (p.wipeWidth - p.wordWidth) / 2f
            val romanX = (p.cellWidth - p.romanWidth) / 2f
            words.add(SyllableWord(
                info = p.info,
                cellX = cellXs[i] + shift,
                cellY = y + extraTop + (rowAscent - p.ascent),
                ascent = p.ascent,
                wipeX = wipeX,
                wipeWidth = p.wipeWidth,
                wordX = wordX,
                wordWidth = p.wordWidth,
                wordHeight = p.wordHeight,
                layout = p.layout,
                glyphs = p.glyphs,
                romans = p.romans,
                romanX = romanX,
                // 音译贴齐行底线（cellY + p.ascent + rowDescent 对所有词相同），
                // 不再各自贴词框底，避免混排时音译跟着上下错位
                romanY = p.ascent + rowDescent,
                romanWidth = p.romanWidth,
                romanRidesMask = p.romanRidesMask,
                floatDelay = (p.info.startTime - lineStart).toFloat(),
                floatDuration = max(config.float.minDurationMs.toFloat(), p.info.duration.toFloat()),
                emphasize = p.emphasize,
            ))
        }
        y += rowHeight
    }

    val result = SyllableLayout(words, if (measureOnly) emptyList() else buildDrawUnits(words), layoutWidth, y,
        rows.size, lineStart, lineDuration, fontPx, isBackground)

    // 擦除关键帧：词级（宽度取擦除容器宽、高度取词高）
    if (!measureOnly && config.mask.enabled && words.isNotEmpty()) {
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
                // 拆字逐个测量，同样按基线对齐到词的最大 ascent，否则同一词内混排也会错位
                units.add(DrawUnit(units.size, w, gi, w.cellX + w.wordX + g.x, w.cellY,
                    listOf(DrawPiece(g.layout, 0f, w.ascent - g.ascent, wordShift + g.x, g.width))))
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
     * 擦除 = 双层互补 clip 硬切：已唱区与未唱区各画一遍完整文字，互不重叠、无渐变、无状态分支。
     * 原渐变覆盖 [activeX, fadeX]，硬切边界取二者中点，避免直接用渐变起点导致高亮滞后。
     * 离屏图层合成时使用双线性采样，亚像素移动不闪。
     */
    private fun drawMasked(
        scope: DrawScope, layout: TextLayoutResult, left: Float, top: Float, activeX: Float, fadeX: Float,
        width: Float, color: Color, alphaActive: Float, alphaNormal: Float,
    ) {
        val topLeft = Offset(left, top)
        val height = layout.size.height.toFloat()
        val cut = ((activeX + fadeX) * 0.5f).coerceIn(0f, width)
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
