package com.ikunshare.sound.ui.screens.lyrics

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.model.DuetSide
import com.ikunshare.sound.model.LyricBackground
import com.ikunshare.sound.model.LyricChar
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.ParsedLyric
import com.ikunshare.sound.tool.lyricplayer.BaseLyricPlayer
import com.ikunshare.sound.tool.lyricplayer.LyricPlayerMatch
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 搬运自 music-lyric-player-web（packages/dom）的歌词渲染。
 *
 * 关键：Web 版本的本质不是列表滚动，而是「绝对定位所有行 + 整体平移让当前行居中」。
 * 这里 1:1 还原该模型：
 * - 所有行绝对定位在一个 [Box] 里（每行 offset 到 tops[i] + 平移量，行距 [WEB_LINE_GAP]），
 *   整体平移使聚焦行的中心落在容器的 [anchorFraction] 处（默认 50%，即居中）。
 * - 平移用 tween(500ms, ease) 动画（对应 web scroll.animation 默认 Smooth 模式）。
 * - 每行的景深由到聚焦行距离的高斯值驱动：模糊 [WEB_BLUR_MIN]→[WEB_BLUR_MAX] px（GPU RenderEffect），
 *   透明度按 active/played/normal 三态取 1 / 0.4 / 0.6。
 * - 逐字卡拉 OK / 重音加粗交由 [WebSyllableText]（见 WebKaraoke.kt）。
 * - 对唱按声部左右对齐；间奏渲染脉冲圆点；背景/和声行紧贴正文下方。
 */

// ==================== 常量（对应 web config 默认值）====================

/** 高斯 sigma（core/layout.ts GAUSSIAN_SIGMA）。 */
private const val WEB_GAUSSIAN_SIGMA = 2.2f
private const val WEB_GAUSSIAN_CUTOFF = WEB_GAUSSIAN_SIGMA * 4f

/** blur.min / blur.max（effect/blur.ts 默认）。 */
private const val WEB_BLUR_MIN = 0.4f
private const val WEB_BLUR_MAX = 4.5f

/** 三态透明度（line.normal.base.style 默认 active 1 / played 0.4 / normal 0.6）。 */
private const val WEB_OPACITY_ACTIVE = 1f
private const val WEB_OPACITY_PLAYED = 0.4f
private const val WEB_OPACITY_NORMAL = 0.6f

/** 行距（layout.gap 默认 30）。 */
private val WEB_LINE_GAP = 30.dp

/** scroll.animation 默认 duration 500ms + easing "ease"。 */
private const val WEB_SCROLL_DURATION = 500
private val WebScrollEasing: Easing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/** 手动拖动后，多久无交互恢复自动跟随当前行（对应 web ScrollManager 的 3s 计时）。 */
private const val WEB_SCROLL_RESUME_MS = 3000L

/** 边缘回弹：拖过边界时的橡皮筋阻尼系数与最大过冲距离。 */
private const val WEB_OVERSCROLL_RESISTANCE = 0.35f
private val WEB_OVERSCROLL_MAX = 48.dp

/** 仅对聚焦行附近这么多行施加 GPU 模糊；更远的行会被容器裁掉，无需模糊。 */
private const val WEB_BLUR_WINDOW = 12

/** 间奏点透明度（interlude.style 默认 normal 0.2 / active 0.8）与尺寸（size 16px，静息 scale 0.8）。 */
private const val WEB_INTERLUDE_NORMAL_OPACITY = 0.2f
private const val WEB_INTERLUDE_ACTIVE_OPACITY = 0.8f
private val WEB_INTERLUDE_DOT_SIZE = 10.dp
private val WEB_INTERLUDE_DOT_GAP = 12.dp

/** 背景/和声行相对主歌词的字号缩放。 */
private const val WEB_BACKGROUND_FONT_SCALE = 0.86f

/** 动态字号：基准 = 容器宽dp × 系数 ×(用户设置/参考)，夹在 [min,max]。 */
private const val WEB_FONT_WIDTH_FACTOR = 0.06f
private const val WEB_FONT_REFERENCE = 24f
private const val WEB_FONT_MIN = 20f
private const val WEB_FONT_MAX = 36f

/** 超长行自动缩：超过这么多行就把该行字号往下调，直到放得下或触及最小比例。 */
private const val WEB_FONT_FIT_MAX_LINES = 2
private const val WEB_FONT_FIT_MIN_SCALE = 0.62f

/**
 * 歌词内容区（播放页全屏 / 独立歌词页共用）。
 *
 * @param anchorFraction 聚焦行中心在容器高度中的锚点比例（0f 顶部、0.5f 居中）。
 * @param horizontalPadding 左右内边距。
 */
@Composable
@SuppressLint("ModifierParameter")
internal fun WebLyricsContent(
    lyrics: List<LyricLine>,
    currentPosition: Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    hasCharLyrics: Boolean,
    isSynced: Boolean,
    onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    anchorFraction: Float = 0.5f,
    horizontalPadding: Dp = 28.dp
) {
    val settings = SoundApplication.instance?.appSettingsManager?.settings?.collectAsState()?.value
    val mainFontSize = settings?.lyricsMainFontSize ?: 30
    val subFontSize = settings?.lyricsTransFontSize ?: 18
    val mainFontWeight = lyricFontWeightFromKey(settings?.lyricsFontWeight ?: "medium")

    val parsedLyric = remember(lyrics, isSynced) {
        ParsedLyric(
            lines = lyrics,
            hasTranslation = lyrics.any { it.hasTranslation },
            hasRomanization = lyrics.any { it.hasRomanization },
            hasCharLyrics = lyrics.any { it.hasCharLyrics },
            isSynced = isSynced
        )
    }
    val player = remember(parsedLyric) { BaseLyricPlayer().apply { updateLyric(parsedLyric) } }
    val match = remember(player, currentPosition, isSynced) {
        if (!isSynced) LyricPlayerMatch.EMPTY else player.matchLinesWithTime(currentPosition)
    }
    val activeIndexes = remember(match) { match.indexes.toSet() }
    val activeIndex = if (isSynced) match.firstActiveIndex else -1
    val focusIndex = rememberWebFocusIndex(lyrics, currentPosition, activeIndex, isSynced)

    val density = LocalDensity.current
    var containerWidthPx by remember { mutableFloatStateOf(0f) }
    var containerHeightPx by remember { mutableFloatStateOf(0f) }

    // 动态基准字号：随容器宽度自适应，用户设置作为系数微调；夹在 [min,max]。
    val baseMainSp = if (containerWidthPx <= 0f) {
        mainFontSize.toFloat()
    } else {
        (containerWidthPx / density.density * WEB_FONT_WIDTH_FACTOR * (mainFontSize / WEB_FONT_REFERENCE))
            .coerceIn(WEB_FONT_MIN, WEB_FONT_MAX)
    }
    val baseSubSp = baseMainSp * (subFontSize.toFloat() / mainFontSize.toFloat())

    val mainStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = baseMainSp.sp,
        lineHeight = (baseMainSp * 1.3f).sp,
        fontWeight = mainFontWeight
    )
    val subStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = baseSubSp.sp,
        lineHeight = (baseSubSp * 1.32f).sp,
        fontWeight = FontWeight.Medium
    )

    // 未同步歌词（无逐行时间戳）：退化为普通可滚动列表。
    if (!isSynced) {
        WebUnsyncedLyrics(
            lyrics = lyrics,
            mainStyle = mainStyle,
            showTranslation = showTranslation,
            showRomanization = showRomanization,
            showPhonetic = showPhonetic,
            subStyle = subStyle,
            modifier = modifier,
            horizontalPadding = horizontalPadding
        )
        return
    }

    val gapPx = with(density) { WEB_LINE_GAP.toPx() }
    val lineHeights = remember(lyrics) { mutableStateMapOf<Int, Int>() }
    val availableWidthPx = (containerWidthPx - 2f * with(density) { horizontalPadding.toPx() })
        .roundToInt().coerceAtLeast(0)

    // 每行在整块内容中的绝对顶端（对应 web core/layout.ts 的 topPositions）。
    // 高度为 0 的行（被折叠的非活跃间奏）不贡献行距，避免出现双倍空隙。
    val tops = FloatArray(lyrics.size)
    run {
        var acc = 0f
        for (i in lyrics.indices) {
            tops[i] = acc
            val h = lineHeights[i] ?: 0
            acc += h
            if (h > 0) acc += gapPx
        }
    }

    // 聚焦行中心 → 目标平移量（把它移到 anchor 处）。读取 lineHeights/containerHeightPx（快照状态）
    // 与 focusIndex（每次组合的普通值），任一变化都会重算并触发下方 LaunchedEffect 重新动画。
    // focusIndex<0（前奏/尚未开始）回退到居中第 0 行（对应 web fallback queryElementIndexes(0)）。
    val focusForLayout = if (lyrics.isEmpty()) -1 else focusIndex.coerceAtLeast(0)
    val focusMeasured = focusForLayout >= 0 && (lineHeights[focusForLayout] ?: 0) > 0
    val targetOffset: Float = if (focusForLayout < 0 || containerHeightPx <= 0f) {
        0f
    } else {
        val h = (lineHeights[focusForLayout] ?: 0).toFloat()
        containerHeightPx * anchorFraction - (tops[focusForLayout] + h / 2f)
    }

    // 手动滚动：拖动临时接管，松手 [WEB_SCROLL_RESUME_MS] 后恢复自动跟随当前行（对应 web ScrollManager）。
    val scope = rememberCoroutineScope()
    val flingDecay = rememberSplineBasedDecay<Float>()
    val offsetAnim = remember(lyrics) { Animatable(0f) }
    var userScrolling by remember(lyrics) { mutableStateOf(false) }
    var lastInteractionNanos by remember(lyrics) { mutableLongStateOf(0L) }

    // 滚动范围：从「首行居中」到「末行居中」，防止拖到内容之外。
    val anchorY = containerHeightPx * anchorFraction
    val maxScrollOffset = if (lyrics.isEmpty()) 0f else anchorY - (lineHeights[0] ?: 0) / 2f
    val minScrollOffset = if (lyrics.isEmpty()) {
        0f
    } else {
        val last = lyrics.lastIndex
        (anchorY - (tops[last] + (lineHeights[last] ?: 0) / 2f)).coerceAtMost(maxScrollOffset)
    }

    // 边缘回弹：不设硬边界，改由拖动橡皮筋 + 松手/撞边回弹手动处理。
    val overscrollMaxPx = with(density) { WEB_OVERSCROLL_MAX.toPx() }
    val bounceSpring = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)
    }

    // 首次定位（或聚焦行尚未测量出高度前）直接吸附，避免歌曲加载时从错误位置滑入。
    var initialized by remember(lyrics) { mutableStateOf(false) }
    // 自动跟随：仅在非拖动状态下把当前行移到 anchor。
    LaunchedEffect(targetOffset, userScrolling) {
        if (containerHeightPx <= 0f || userScrolling) return@LaunchedEffect
        if (!initialized) {
            offsetAnim.snapTo(targetOffset)
            if (focusMeasured) initialized = true
        } else {
            offsetAnim.animateTo(
                targetValue = targetOffset,
                animationSpec = tween(WEB_SCROLL_DURATION, easing = WebScrollEasing)
            )
        }
    }
    // 拖动结束后，距最后一次交互满 [WEB_SCROLL_RESUME_MS] 就恢复自动跟随。
    LaunchedEffect(userScrolling) {
        while (userScrolling) {
            val idleMs = (System.nanoTime() - lastInteractionNanos) / 1_000_000L
            if (idleMs >= WEB_SCROLL_RESUME_MS) {
                userScrolling = false
                break
            }
            delay(WEB_SCROLL_RESUME_MS - idleMs)
        }
    }

    val draggableState = rememberDraggableState { delta ->
        lastInteractionNanos = System.nanoTime()
        val current = offsetAnim.value
        // 已在边界外且继续往外拉 → 橡皮筋阻尼；否则全量跟手。过冲封顶到 overscrollMaxPx。
        val pullingOut = (current > maxScrollOffset && delta > 0f) || (current < minScrollOffset && delta < 0f)
        val effective = if (pullingOut) delta * WEB_OVERSCROLL_RESISTANCE else delta
        val next = (current + effective)
            .coerceIn(minScrollOffset - overscrollMaxPx, maxScrollOffset + overscrollMaxPx)
        scope.launch { offsetAnim.snapTo(next) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .onSizeChanged {
                containerHeightPx = it.height.toFloat()
                containerWidthPx = it.width.toFloat()
            }
            .draggable(
                state = draggableState,
                orientation = Orientation.Vertical,
                onDragStarted = {
                    userScrolling = true
                    lastInteractionNanos = System.nanoTime()
                    offsetAnim.stop()
                    // 放开硬边界，让拖动可以橡皮筋过冲。
                    offsetAnim.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                },
                onDragStopped = { velocity ->
                    lastInteractionNanos = System.nanoTime()
                    scope.launch {
                        val v = offsetAnim.value
                        when {
                            // 拖过头松手 → 弹回边界。
                            v > maxScrollOffset -> offsetAnim.animateTo(maxScrollOffset, bounceSpring, velocity)
                            v < minScrollOffset -> offsetAnim.animateTo(minScrollOffset, bounceSpring, velocity)
                            else -> {
                                // 预测惯性落点：落在范围内就自由衰减；会冲出边界则衰减到边界、带余速过冲回弹。
                                val landing = flingDecay.calculateTargetValue(v, velocity)
                                if (landing in minScrollOffset..maxScrollOffset) {
                                    offsetAnim.animateDecay(velocity, flingDecay)
                                } else {
                                    val bound = if (landing < minScrollOffset) minScrollOffset else maxScrollOffset
                                    offsetAnim.updateBounds(minScrollOffset, maxScrollOffset)
                                    val result = offsetAnim.animateDecay(velocity, flingDecay)
                                    offsetAnim.updateBounds(Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY)
                                    offsetAnim.animateTo(bound, bounceSpring, result.endState.velocity)
                                }
                            }
                        }
                        lastInteractionNanos = System.nanoTime()
                    }
                }
            )
            .webEdgeFade()
            .padding(horizontal = horizontalPadding)
    ) {
        // 绝对定位所有行：每行放在 tops[i] + 平移量 处（对应 web 的整体平移 + 绝对定位模型）。
        // 视口裁剪由外层 Box.clipToBounds() 负责；折叠的间奏行高度为 0，不占位。
        lyrics.forEachIndexed { index, line ->
            val active = index == activeIndex ||
                index in activeIndexes ||
                isTimedLineActive(line, currentPosition) ||
                (line.isInterlude && currentPosition >= line.timestamp && currentPosition < line.getEndTime())
            val played = when {
                line.isInterlude -> currentPosition >= line.getEndTime()
                line.hasCharLyrics -> currentPosition >= line.getEndTime(lyrics.getOrNull(index + 1)?.timestamp)
                activeIndex >= 0 -> index < activeIndex
                else -> false
            }
            WebLyricLine(
                line = line,
                distance = index - focusForLayout,
                active = active,
                played = played,
                currentPosition = currentPosition,
                isPlaying = isPlaying,
                showTranslation = showTranslation,
                showRomanization = showRomanization,
                showPhonetic = showPhonetic,
                hasCharLyrics = hasCharLyrics,
                mainStyle = mainStyle,
                subStyle = subStyle,
                availableWidthPx = availableWidthPx,
                onClick = {
                    userScrolling = false
                    onLineClick(line.timestamp)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (tops[index] + offsetAnim.value).roundToInt()) }
                    .onSizeChanged { lineHeights[index] = it.height }
            )
        }
    }
}
// ==================== 单行 ====================

@Composable
private fun WebLyricLine(
    line: LyricLine,
    distance: Int,
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

    val gaussianValue = gaussian(distance.toFloat())
    val targetBlur = if (active) 0f else WEB_BLUR_MIN + (WEB_BLUR_MAX - WEB_BLUR_MIN) * (1f - gaussianValue)
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
    val blur by animateFloatAsState(
        targetValue = targetBlur,
        animationSpec = tween(WEB_SCROLL_DURATION / 2, easing = WebScrollEasing),
        label = "webLineBlur"
    )

    val baseColor = when {
        active -> Color.White.copy(alpha = WEB_OPACITY_ACTIVE)
        played -> Color.White.copy(alpha = WEB_OPACITY_PLAYED)
        else -> Color.White.copy(alpha = WEB_OPACITY_NORMAL)
    }
    val activeColor = Color.White
    val subActiveAlpha = if (active) 0.68f else 0.44f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                transformOrigin = TransformOrigin(if (alignEnd) 1f else 0f, 0.5f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    blur > 0.05f &&
                    kotlin.math.abs(distance) <= WEB_BLUR_WINDOW
                ) {
                    compositingStrategy = CompositingStrategy.Offscreen
                    renderEffect = android.graphics.RenderEffect
                        .createBlurEffect(blur, blur, android.graphics.Shader.TileMode.DECAL)
                        .asComposeRenderEffect()
                } else {
                    renderEffect = null
                }
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
                alignEnd = alignEnd
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
        if (showRomanization && !romanText.isNullOrBlank()) {
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
                val progress = ((interpolated - dotStart) / slice).coerceIn(0f, 1f)
                val opacity = WEB_INTERLUDE_NORMAL_OPACITY +
                    (WEB_INTERLUDE_ACTIVE_OPACITY - WEB_INTERLUDE_NORMAL_OPACITY) * progress
                Box(
                    modifier = Modifier
                        .size(WEB_INTERLUDE_DOT_SIZE)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = opacity))
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
 * 逐字行：每帧插帧出精确播放位置，再交给 [WebSyllableText] 做 mask/float/重音加粗。
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
    alignEnd: Boolean
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
        currentPosition = interpolated,
        isActiveLine = active,
        textStyle = style,
        activeColor = activeColor,
        baseColor = baseColor,
        lineStartTime = lineStartTime,
        lineEndTime = lineEndTime,
        emphasisEnabled = emphasis,
        alignEnd = alignEnd
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
): Long {
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)

    var interpolated by remember(resetKey) { mutableLongStateOf(currentPosition) }
    var lastPosition by remember(resetKey) { mutableLongStateOf(currentPosition) }
    var lastTimestamp by remember(resetKey) { mutableLongStateOf(System.nanoTime()) }

    LaunchedEffect(currentPosition, resetKey) {
        lastPosition = currentPosition
        lastTimestamp = System.nanoTime()
        interpolated = currentPosition
    }

    val drive = visible && isPlaying && active
    LaunchedEffect(drive) {
        if (!drive) {
            interpolated = currentPosition
            lastPosition = currentPosition
            lastTimestamp = System.nanoTime()
            return@LaunchedEffect
        }
        while (true) {
            withInfiniteAnimationFrameMillis {
                interpolated = lastPosition + (System.nanoTime() - lastTimestamp) / 1_000_000L
            }
        }
    }
    return interpolated
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

// ==================== 未同步歌词退化视图 ====================

@Composable
private fun WebUnsyncedLyrics(
    lyrics: List<LyricLine>,
    mainStyle: TextStyle,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    subStyle: TextStyle,
    modifier: Modifier,
    horizontalPadding: Dp
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = horizontalPadding, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        lyrics.forEach { line ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = line.text,
                    style = mainStyle,
                    color = Color.White.copy(alpha = WEB_OPACITY_NORMAL),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
                val roman = line.romanization ?: line.charRomanization?.joinToString("") { it.char }
                if (showRomanization && !roman.isNullOrBlank()) {
                    Text(roman, style = subStyle, color = Color.White.copy(alpha = 0.44f))
                }
                if (showTranslation && line.hasTranslation) {
                    Text(line.translation.orEmpty(), style = subStyle, color = Color.White.copy(alpha = 0.44f))
                }
                if (showPhonetic && !line.phonetic.isNullOrBlank()) {
                    Text(line.phonetic.orEmpty(), style = subStyle, color = Color.White.copy(alpha = 0.40f))
                }
            }
        }
    }
}

// ==================== 辅助 ====================

/**
 * 视觉聚焦行：间奏期间提前聚焦下一句；带方向锁定，防止播放位置抖动导致来回闪烁。
 */
@Composable
private fun rememberWebFocusIndex(
    lyrics: List<LyricLine>,
    currentPosition: Long,
    activeIndex: Int,
    isSynced: Boolean
): Int {
    if (!isSynced || lyrics.isEmpty()) return -1
    var stable by remember(lyrics) { mutableStateOf(-1) }
    var lastPosition by remember(lyrics) { mutableLongStateOf(0L) }
    val seekBack = currentPosition < lastPosition - 500
    lastPosition = currentPosition

    val raw = remember(lyrics, currentPosition, activeIndex) {
        val base = activeIndex
        val next = base + 1
        if (next in lyrics.indices) {
            val nextLine = lyrics[next]
            val nextCharStart = nextLine.characters?.firstOrNull()?.startTime
            if (nextCharStart != null && currentPosition >= nextCharStart) {
                next
            } else {
                val currentEnd = if (base >= 0) lyrics[base].getEndTime(nextLine.timestamp) else 0L
                val gap = nextLine.timestamp - currentEnd
                val timeToNext = nextLine.timestamp - currentPosition
                val threshold = (gap * 0.3f).roundToInt().coerceAtMost(2000)
                if (gap >= 3000L && timeToNext in 1..threshold) next else base
            }
        } else {
            base
        }
    }

    return if (raw >= stable || raw < stable - 1 || seekBack) {
        stable = raw
        raw
    } else {
        stable
    }
}

private fun isTimedLineActive(line: LyricLine, position: Long): Boolean {
    if (!line.hasCharLyrics) return false
    val start = line.characters?.firstOrNull()?.startTime?.let { minOf(line.timestamp, it) } ?: line.timestamp
    val end = line.getEndTime()
    return position in start until end
}

private fun gaussian(offset: Float): Float {
    if (offset > WEB_GAUSSIAN_CUTOFF || offset < -WEB_GAUSSIAN_CUTOFF) return 0f
    return exp(-(offset * offset) / (2f * WEB_GAUSSIAN_SIGMA * WEB_GAUSSIAN_SIGMA))
}

/** 上下边缘柔化（container.fade 默认 top 5% / bottom 10%）。 */
private fun Modifier.webEdgeFade(): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithContent {
    drawContent()
    drawRect(
        brush = Brush.verticalGradient(
            0f to Color.Transparent,
            0.05f to Color.Black,
            0.90f to Color.Black,
            1f to Color.Transparent
        ),
        size = size,
        blendMode = BlendMode.DstIn
    )
}
