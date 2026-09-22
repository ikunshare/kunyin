package com.ikunshare.sound.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.PowerManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.ikunshare.sound.model.LyricLine

/**
 * 桌面歌词浮窗自定义 View
 *
 * 使用 Canvas 绘制卡拉OK歌词，支持逐字进度渲染、翻译/音译显示。
 * 通过 Choreographer 帧回调实现平滑的逐字动画插值。
 */
class FloatingLyricsView(context: Context) : View(context) {
    // ── 数据 ──
    private var currentLine: LyricLine? = null
    private var basePosition: Long = 0L
    private var baseNanoTime: Long = System.nanoTime()
    var isPlaying: Boolean = false
        private set

    // ── 息屏省电 ──
    private var isScreenOn: Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            isScreenOn = intent.action == Intent.ACTION_SCREEN_ON
            updateChoreographer()
            if (isScreenOn) invalidate()  // 亮屏时立即刷新一帧
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)
    }

    // ── 配置 ──
    private var alignment: Layout.Alignment = Layout.Alignment.ALIGN_CENTER
    var showKaraoke: Boolean = true
        private set
    var showTranslation: Boolean = false
        private set
    var showRomanization: Boolean = false
        private set
    var isLocked: Boolean = false
        private set
    var hideWhenNotPlaying: Boolean = false
        private set
    private var maxLines: Int = 2

    // ── 画笔 ──
    private val playedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80) // 绿色
        textSize = sp(18f)
        isFakeBoldText = true
    }
    private val unplayedPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(18f)
        isFakeBoldText = true
        setShadowLayer(4f, 1f, 1f, Color.argb(180, 0, 0, 0))
    }
    private val translationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
        setShadowLayer(3f, 1f, 1f, Color.argb(150, 0, 0, 0))
    }
    private val playedRomanPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(76, 175, 80)
        textSize = sp(14f)
        setShadowLayer(3f, 1f, 1f, Color.argb(150, 0, 0, 0))
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(100, 40, 40, 40)
        style = Paint.Style.FILL
    }
    private val lockIconPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(14f)
    }

    // ── 缓存 ──
    private var cachedMainLayout: StaticLayout? = null
    private var cachedMainText: String = ""
    private var cachedMainWidth: Int = 0
    private var cachedTransLayout: StaticLayout? = null
    private var cachedTransText: String = ""
    private var cachedTransWidth: Int = 0
    private var cachedRomanLayout: StaticLayout? = null
    private var cachedRomanText: String = ""
    private var cachedRomanWidth: Int = 0
    private var cachedHighlightLayout: StaticLayout? = null
    private var cachedHighlightText: String = ""
    private var cachedHighlightWidth: Int = 0

    // ── 触摸 ──
    private var dragStartRawX: Float = 0f
    private var dragStartRawY: Float = 0f
    private var isDragging = false
    private var dragCallback: ((Float, Float) -> Unit)? = null
    var onLockClickListener: (() -> Unit)? = null
    private val lockIconRect = RectF()

    // ── Choreographer ──
    private val choreographer = Choreographer.getInstance()
    private var choreographerRunning = false
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (choreographerRunning) {
                invalidate()
                choreographer.postFrameCallback(this)
            }
        }
    }

    private val bgRect = RectF()
    private val bgCornerRadius = dp(8f)
    private val paddingH = dp(12f).toInt()
    private val paddingV = dp(8f).toInt()
    private val lockIconSize = dp(24f).toInt()
    private val lineSpacing = dp(4f).toInt()
    private val dragThreshold = dp(4f)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    // ── 公开接口 ──

    fun updateLyricData(line: LyricLine?, position: Long, playing: Boolean) {
        val textChanged = line?.text != currentLine?.text
        currentLine = line
        basePosition = position
        baseNanoTime = System.nanoTime()
        isPlaying = playing

        if (textChanged) {
            clearLayoutCache()
        }

        updateChoreographer()
        invalidate()
    }

    fun setAlignment(align: String) {
        alignment = when (align) {
            "left" -> Layout.Alignment.ALIGN_NORMAL
            "right" -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_CENTER
        }
        clearLayoutCache()
        invalidate()
    }

    fun setShowKaraoke(show: Boolean) {
        showKaraoke = show
        updateChoreographer()
        invalidate()
    }

    fun setPlayedColor(color: Int) {
        playedPaint.color = color or 0xFF000000.toInt()
        invalidate()
    }

    fun setUnplayedColor(color: Int) {
        unplayedPaint.color = color or 0xFF000000.toInt()
        invalidate()
    }

    fun setSubColor(color: Int) {
        translationPaint.color = color or 0xFF000000.toInt()
        invalidate()
    }

    fun setBgOpacity(opacity: Float) {
        val normalized = opacity.coerceIn(0f, 1f)
        alpha = if (normalized >= 0.995f) 1f else normalized
    }

    fun setMaxLines(lines: Int) {
        val normalized = lines.coerceIn(1, 6)
        if (maxLines == normalized) return
        maxLines = normalized
        clearLayoutCache()
        invalidate()
    }

    fun setMainFontSize(sizeSp: Float) {
        val size = sp(sizeSp)
        playedPaint.textSize = size
        unplayedPaint.textSize = size
        clearLayoutCache()
        invalidate()
    }

    fun setSubFontSize(sizeSp: Float) {
        val size = sp(sizeSp)
        translationPaint.textSize = size
        clearLayoutCache()
        invalidate()
    }

    fun setShowTranslation(show: Boolean) {
        showTranslation = show
        if (show) showRomanization = false
        clearLayoutCache()
        invalidate()
    }

    fun setShowRomanization(show: Boolean) {
        showRomanization = show
        if (show) showTranslation = false
        clearLayoutCache()
        invalidate()
    }

    fun setLocked(locked: Boolean) {
        isLocked = locked
        invalidate()
    }

    fun setHideWhenNotPlaying(hide: Boolean) {
        hideWhenNotPlaying = hide
        invalidate()
    }

    fun setDragCallback(callback: ((Float, Float) -> Unit)?) {
        dragCallback = callback
    }

    fun release() {
        choreographerRunning = false
        try {
            choreographer.removeFrameCallback(frameCallback)
        } catch (_: Exception) {
        }
        try {
            context.unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
    }

    // ── 测量 ──

    /**
     * 根据给定的 View 宽度计算内容所需高度（含 padding）。
     * Manager 调用此方法获取精确高度后设置到 LayoutParams。
     */
    fun computeDesiredHeight(viewWidth: Int): Int {
        if (hideWhenNotPlaying && !isPlaying) return 0
        val line = currentLine ?: return 0
        val text = line.text
        val contentWidth = viewWidth - paddingH * 2
        if (text.isBlank() || contentWidth <= 0) return 0

        val mainLayout = getMainLayout(text, contentWidth)
        var totalHeight = mainLayout.height

        if (showTranslation && line.hasTranslation) {
            totalHeight += lineSpacing + getTransLayout(line.translation!!, contentWidth).height
        }
        val romanizationText =
            line.romanization ?: line.charRomanization?.joinToString("") { it.char }
        if (showRomanization && !romanizationText.isNullOrBlank()) {
            totalHeight += lineSpacing + getRomanLayout(romanizationText, contentWidth).height
        }

        return totalHeight + paddingV * 2
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = computeDesiredHeight(widthSize)

        setMeasuredDimension(
            resolveSize(widthSize, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec)
        )
    }

    // ── 绘制 ──

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hideWhenNotPlaying && !isPlaying) return
        val line = currentLine ?: return
        val text = line.text
        if (text.isBlank()) return

        val contentWidth = width - paddingH * 2
        if (contentWidth <= 0) return

        val mainLayout = getMainLayout(text, contentWidth)

        // 计算总内容高度（用于背景绘制）
        var totalHeight = mainLayout.height
        val transLayout = if (showTranslation && line.hasTranslation) {
            getTransLayout(
                line.translation!!,
                contentWidth
            ).also { totalHeight += lineSpacing + it.height }
        } else null
        val romanizationText =
            line.romanization ?: line.charRomanization?.joinToString("") { it.char }
        val romanLayout = if (showRomanization && !romanizationText.isNullOrBlank()) {
            getRomanLayout(
                romanizationText,
                contentWidth
            ).also { totalHeight += lineSpacing + it.height }
        } else null

        // 绘制背景（解锁时）
        if (!isLocked) {
            bgRect.set(0f, 0f, width.toFloat(), (totalHeight + paddingV * 2).toFloat())
            canvas.drawRoundRect(bgRect, bgCornerRadius, bgCornerRadius, bgPaint)

            // 锁定图标（右上角）
            val iconX = width - lockIconSize - dp(4f)
            val iconY = dp(4f)
            lockIconRect.set(iconX, iconY, iconX + lockIconSize, iconY + lockIconSize)
            canvas.drawText(
                "\uD83D\uDD12",
                iconX + dp(2f),
                iconY + lockIconSize * 0.75f,
                lockIconPaint
            )
        }

        // 主歌词
        canvas.save()
        canvas.translate(paddingH.toFloat(), paddingV.toFloat())

        val interpolatedPos = if (isPlaying) {
            basePosition + (System.nanoTime() - baseNanoTime) / 1_000_000L
        } else {
            basePosition
        }

        if (showKaraoke && line.hasCharLyrics) {
            drawKaraokeLine(
                canvas,
                text,
                line.characters!!,
                interpolatedPos,
                contentWidth,
                mainLayout
            )
        } else {
            getHighlightedMainLayout(text, contentWidth).draw(canvas)
        }

        var yOffset = mainLayout.height.toFloat()

        // 翻译
        if (transLayout != null) {
            yOffset += lineSpacing
            canvas.translate(0f, yOffset)
            transLayout.draw(canvas)
            canvas.translate(0f, -yOffset)
            yOffset += transLayout.height
        }

        // 音译
        if (romanLayout != null) {
            yOffset += lineSpacing
            canvas.translate(0f, yOffset)
            if (showKaraoke && line.hasCharRomanization) {
                drawKaraokeLine(
                    canvas,
                    romanizationText!!,
                    line.charRomanization!!,
                    interpolatedPos,
                    contentWidth,
                    romanLayout,
                    playedRomanPaint
                )
            } else {
                romanLayout.draw(canvas)
            }
        }

        canvas.restore()
    }

    /**
     * 卡拉OK裁切算法：底层绘制未播放颜色全文，顶层通过 clipPath 裁切绘制已播放颜色
     */
    private fun drawKaraokeLine(
        canvas: Canvas,
        text: String,
        characters: List<com.ikunshare.sound.model.LyricChar>,
        position: Long,
        textWidth: Int,
        layout: StaticLayout,
        playedPaintOverride: TextPaint? = null
    ) {
        val activePaint = playedPaintOverride ?: playedPaint
        // 底层：未播放颜色全文
        layout.draw(canvas)

        // 计算逐字偏移表（clamp 到 text 长度，防止 characters 与 text 不完全一致）
        val textLen = text.length
        var charOffsetAccum = 0
        val charOffsets = characters.map { c ->
            val start = charOffsetAccum.coerceAtMost(textLen)
            charOffsetAccum += c.char.length
            start to charOffsetAccum.coerceAtMost(textLen)
        }

        // 计算 rawProgress（参考 CharacterLyricText 的 linearX 逻辑）
        var rawProgress = 0f
        for (i in characters.indices) {
            val ch = characters[i]
            when {
                position >= ch.endTime -> rawProgress = (i + 1).toFloat()
                position >= ch.startTime -> {
                    val frac = if (ch.duration > 0)
                        ((position - ch.startTime).toFloat() / ch.duration).coerceIn(0f, 1f)
                    else 1f
                    rawProgress = i + frac
                    break
                }

                else -> break
            }
        }

        if (rawProgress <= 0f) return

        val allDone = rawProgress >= characters.size

        // 创建已播放文本的 StaticLayout
        val playedLayout = StaticLayout.Builder
            .obtain(text, 0, text.length, activePaint, textWidth)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

        if (allDone) {
            playedLayout.draw(canvas)
            return
        }

        val charIdx = rawProgress.toInt().coerceAtMost(characters.size - 1)
        val charFrac = rawProgress - rawProgress.toInt()

        // 计算 linearX
        val fillOffset = charOffsets[charIdx].first
        val charLine = layout.getLineForOffset(fillOffset)
        var accumulated = 0f
        for (line in 0 until charLine) {
            accumulated += layout.getLineRight(line) - layout.getLineLeft(line)
        }
        val lineLeft = layout.getLineLeft(charLine)
        val startX = layout.getPrimaryHorizontal(fillOffset)
        val endX = layout.getPrimaryHorizontal(charOffsets[charIdx].second)
        val effectiveEndX = if (endX >= startX) endX else layout.getLineRight(charLine)
        val currentX = startX + (effectiveEndX - startX) * charFrac
        accumulated += currentX - lineLeft
        var remaining = accumulated

        // 逐行构建裁切路径
        val path = Path()
        for (line in 0 until layout.lineCount) {
            if (remaining <= 0f) break
            val lLeft = layout.getLineLeft(line)
            val lineWidth = layout.getLineRight(line) - lLeft
            val top = layout.getLineTop(line).toFloat()
            val bottom = layout.getLineBottom(line).toFloat()

            if (remaining >= lineWidth) {
                path.addRect(0f, top, layout.getLineRight(line), bottom, Path.Direction.CW)
                remaining -= lineWidth
            } else {
                path.addRect(0f, top, lLeft + remaining, bottom, Path.Direction.CW)
                remaining = 0f
            }
        }

        canvas.save()
        canvas.clipPath(path)
        playedLayout.draw(canvas)
        canvas.restore()
    }

    // ── 触摸处理 ──

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isLocked) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                if (!isDragging) {
                    if (dx * dx + dy * dy > dragThreshold * dragThreshold) {
                        isDragging = true
                    }
                }
                if (isDragging) {
                    dragStartRawX = event.rawX
                    dragStartRawY = event.rawY
                    dragCallback?.invoke(dx, dy)
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (!isDragging && lockIconRect.contains(event.x, event.y)) {
                    onLockClickListener?.invoke()
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                return true
            }
        }
        return false
    }

    // ── 内部工具 ──

    private fun updateChoreographer() {
        val line = currentLine
        val needFrameUpdates = isScreenOn && isPlaying && showKaraoke &&
                (line?.hasCharLyrics == true || (showRomanization && line?.hasCharRomanization == true))
        if (needFrameUpdates && !choreographerRunning) {
            choreographerRunning = true
            choreographer.postFrameCallback(frameCallback)
        } else if (!needFrameUpdates && choreographerRunning) {
            choreographerRunning = false
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    private fun clearLayoutCache() {
        cachedMainLayout = null
        cachedMainText = ""
        cachedMainWidth = 0
        cachedTransLayout = null
        cachedTransText = ""
        cachedTransWidth = 0
        cachedRomanLayout = null
        cachedRomanText = ""
        cachedRomanWidth = 0
        cachedHighlightLayout = null
        cachedHighlightText = ""
        cachedHighlightWidth = 0
    }

    private fun getMainLayout(text: String, width: Int): StaticLayout {
        if (cachedMainLayout != null && cachedMainText == text && cachedMainWidth == width) {
            return cachedMainLayout!!
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, unplayedPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        cachedMainLayout = layout
        cachedMainText = text
        cachedMainWidth = width
        return layout
    }

    private fun getHighlightedMainLayout(text: String, width: Int): StaticLayout {
        if (cachedHighlightLayout != null && cachedHighlightText == text && cachedHighlightWidth == width) {
            return cachedHighlightLayout!!
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, playedPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        cachedHighlightLayout = layout
        cachedHighlightText = text
        cachedHighlightWidth = width
        return layout
    }

    private fun getTransLayout(text: String, width: Int): StaticLayout {
        if (cachedTransLayout != null && cachedTransText == text && cachedTransWidth == width) {
            return cachedTransLayout!!
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, translationPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        cachedTransLayout = layout
        cachedTransText = text
        cachedTransWidth = width
        return layout
    }

    private fun getRomanLayout(text: String, width: Int): StaticLayout {
        if (cachedRomanLayout != null && cachedRomanText == text && cachedRomanWidth == width) {
            return cachedRomanLayout!!
        }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, translationPaint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        cachedRomanLayout = layout
        cachedRomanText = text
        cachedRomanWidth = width
        return layout
    }
}
