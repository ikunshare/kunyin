package com.ikunshare.sound.ui.screens.lyrics

import android.annotation.SuppressLint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameMillis
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.model.LyricChar
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.ParsedLyric
import com.ikunshare.sound.model.neteaseCoverUrl
import com.ikunshare.sound.tool.lyricplayer.BaseLyricPlayer
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.LoadingIndicator
import com.ikunshare.sound.ui.components.NoLyricsState
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.theme.LocalLyricColors
import kotlin.math.round
import kotlin.math.sqrt

/** 将歌词字重设置键映射为 [FontWeight]。 */
internal fun lyricFontWeightFromKey(key: String): FontWeight = when (key) {
    "normal" -> FontWeight.Normal
    "medium" -> FontWeight.Medium
    "semibold" -> FontWeight.SemiBold
    "black" -> FontWeight.Black
    else -> FontWeight.Bold
}

/**
 * 歌词页面
 *
 * 显示与播放同步的歌词内容，支持翻译歌词、音译歌词和逐字歌词动画。
 * 顶部栏提供返回按钮和翻译/音译切换，底部显示迷你播放器控制。
 *
 * Requirements:
 * - 4.1: 歌词页面加载时显示与播放位置同步的歌词
 * - 4.2: 播放位置变化时自动滚动并高亮当前歌词行
 * - 4.3: 当前歌词行通过颜色和大小与其他行区分
 * - 4.4: 有翻译歌词时提供显示/隐藏翻译的切换按钮
 * - 4.5: 启用翻译切换时在每行原文下方显示翻译
 * - 4.6: 有逐字歌词时按播放进度逐字高亮动画
 * - 4.7: 点击歌词行跳转到该行的时间戳
 * - 4.8: 歌词页面底部显示迷你播放器控制
 * - 4.9: 无歌词时显示"暂无歌词"提示
 * - 4.10: 有音译歌词时提供显示/隐藏音译的切换按钮
 *
 * @param navController 导航控制器
 * @param playerViewModel 播放页面 ViewModel（跨页面共享）
 */
@Composable
fun LyricsScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: LyricsViewModel = viewModel(factory = viewModelFactory)
    val playerState by playerViewModel.uiState.collectAsState()
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    val lyricsState by viewModel.uiState.collectAsState()

    // 当播放歌曲变化时加载歌词
    LaunchedEffect(playerState.currentSong) {
        playerState.currentSong?.let { viewModel.loadLyrics(it) }
    }

    Scaffold(
        topBar = {
            LyricsTopBar(
                onBackClick = { navController.popBackStack() },
                showTranslation = lyricsState.showTranslation,
                onTranslationToggle = viewModel::toggleTranslation,
                hasTranslation = lyricsState.hasTranslation,
                showRomanization = lyricsState.showRomanization,
                onRomanizationToggle = viewModel::toggleRomanization,
                hasRomanization = lyricsState.hasRomanization
            )
        },
        bottomBar = {
            MiniPlayerControl(
                song = playerState.currentSong,
                isPlaying = playerState.isPlaying,
                onPlayPause = playerViewModel::togglePlayPause
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        when {
            // 加载中状态
            lyricsState.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }
            // 无歌词状态
            lyricsState.lyrics == null -> {
                NoLyricsState(modifier = Modifier.padding(padding))
            }
            // 正常歌词显示
            else -> {
                WebLyricsContent(
                    lyrics = lyricsState.parsedLyrics,
                    currentPosition = currentPosition,
                    isPlaying = playerState.isPlaying,
                    showTranslation = lyricsState.showTranslation,
                    showRomanization = lyricsState.showRomanization,
                    showPhonetic = lyricsState.showPhonetic,
                    hasCharLyrics = lyricsState.hasCharLyrics,
                    isSynced = lyricsState.isSynced,
                    onLineClick = { timestamp -> playerViewModel.seekTo(timestamp) },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

// ==================== 顶部栏 ====================

/**
 * 歌词页面顶部栏
 *
 * 包含返回按钮、标题以及翻译和音译的切换按钮。
 * 切换按钮仅在对应歌词可用时显示，激活状态使用 Accent 颜色高亮。
 *
 * Requirements:
 * - 4.4: 有翻译歌词时提供显示/隐藏翻译的切换按钮
 * - 4.10: 有音译歌词时提供显示/隐藏音译的切换按钮
 *
 * @param onBackClick 返回按钮点击回调
 * @param showTranslation 是否正在显示翻译
 * @param onTranslationToggle 翻译切换回调
 * @param hasTranslation 是否有翻译歌词可用
 * @param showRomanization 是否正在显示音译
 * @param onRomanizationToggle 音译切换回调
 * @param hasRomanization 是否有音译歌词可用
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LyricsTopBar(
    onBackClick: () -> Unit,
    showTranslation: Boolean,
    onTranslationToggle: () -> Unit,
    hasTranslation: Boolean,
    showRomanization: Boolean,
    onRomanizationToggle: () -> Unit,
    hasRomanization: Boolean
) {
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.lyrics_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        actions = {
            // 翻译切换按钮 - 仅在有翻译歌词时显示
            if (hasTranslation) {
                IconButton(
                    onClick = onTranslationToggle,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (showTranslation) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Translate,
                        contentDescription = if (showTranslation) stringResource(R.string.hide_translation) else stringResource(
                            R.string.show_translation_btn
                        )
                    )
                }
            }

            // 音译切换按钮 - 仅在有音译歌词时显示
            if (hasRomanization) {
                IconButton(
                    onClick = onRomanizationToggle,
                    colors = IconButtonDefaults.iconButtonColors(
                        contentColor = if (showRomanization) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Abc,
                        contentDescription = if (showRomanization) stringResource(R.string.hide_romanization) else stringResource(
                            R.string.show_romanization_btn
                        )
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent
        )
    )
}

/**
 * 平滑滚动到指定 item，使用自定义动画时长实现更柔和的过渡。
 * 默认的 animateScrollToItem 速度较快，这里通过 animateScrollBy 配合
 * tween 动画规格来控制滚动速度。
 */
private suspend fun LazyListState.animateScrollToItemCentered(
    index: Int,
    offset: Int = 0,
    animationSpec: AnimationSpec<Float> = tween(durationMillis = 600, easing = FastOutSlowInEasing)
) {
    // 先瞬移到大致位置（无动画），确保目标 item 已 compose
    val layoutInfo = layoutInfo
    val visibleItems = layoutInfo.visibleItemsInfo
    val targetVisible = visibleItems.find { it.index == index }

    if (targetVisible != null) {
        // 目标已可见，直接动画滚动差值
        val delta = targetVisible.offset + offset
        animateScrollBy(delta.toFloat(), animationSpec)
    } else {
        // 目标不可见，先跳到附近再动画微调
        scrollToItem(index, offset)
    }
}

// ==================== 歌词内容 ====================

/**
 * 歌词内容区域
 *
 * 使用 LazyColumn 显示所有歌词行，支持自动滚动到当前播放行。
 * 当前行使用 Accent 颜色和较大字体高亮显示，其他行使用次要颜色。
 * 支持翻译歌词、音译歌词和逐字歌词动画。
 *
 * Requirements:
 * - 4.1: 歌词与播放位置同步显示
 * - 4.2: 播放位置变化时自动滚动并高亮当前歌词行
 * - 4.3: 当前歌词行通过颜色和大小与其他行区分
 * - 4.5: 启用翻译切换时在每行原文下方显示翻译
 * - 4.6: 有逐字歌词时按播放进度逐字高亮动画
 * - 4.7: 点击歌词行跳转到该行的时间戳
 *
 * @param lyrics 解析后的歌词行列表
 * @param currentPosition 当前播放位置（毫秒）
 * @param showTranslation 是否显示翻译
 * @param showRomanization 是否显示音译
 * @param hasCharLyrics 是否有逐字歌词
 * @param onLineClick 歌词行点击回调，参数为该行的时间戳
 * @param modifier 修饰符
 */
@Composable
@SuppressLint("ModifierParameter")
internal fun LyricsContent(
    lyrics: List<LyricLine>,
    currentPosition: Long,
    isPlaying: Boolean = true,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean = false,
    hasCharLyrics: Boolean,
    isSynced: Boolean = true,
    onLineClick: (Long) -> Unit,
    appleStyle: Boolean = false,
    modifier: Modifier = Modifier
) {
    val appSettings = com.ikunshare.sound.SoundApplication.instance?.appSettingsManager?.settings
        ?.collectAsState()?.value
    // 歌词字号/字重统一由字体设置控制（播放详情页与独立歌词页一致）。
    val mainFontSize = appSettings?.lyricsMainFontSize ?: 24
    val transFontSize = appSettings?.lyricsTransFontSize ?: 16
    val mainFontWeight = lyricFontWeightFromKey(appSettings?.lyricsFontWeight ?: "bold")
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val focusedLineScrollOffsetPx = with(density) {
        if (appleStyle) -132.dp.roundToPx() else -200
    }

    // 使用 Kotlin 版 BaseLyricPlayer 统一处理 active line、offset 和 close-ending merge。
    val parsedLyric = remember(lyrics, isSynced) {
        ParsedLyric(
            lines = lyrics,
            hasTranslation = lyrics.any { it.hasTranslation },
            hasRomanization = lyrics.any { it.hasRomanization },
            hasCharLyrics = lyrics.any { it.hasCharLyrics },
            isSynced = isSynced
        )
    }
    val lyricPlayer = remember(parsedLyric) {
        BaseLyricPlayer().apply { updateLyric(parsedLyric) }
    }
    val playerMatch = remember(lyricPlayer, currentPosition, isSynced) {
        if (!isSynced) com.ikunshare.sound.tool.lyricplayer.LyricPlayerMatch.EMPTY
        else lyricPlayer.matchLinesWithTime(currentPosition)
    }
    val activeLineIndexes = remember(playerMatch) { playerMatch.indexes.toSet() }

    // 计算当前播放行索引
    val currentLineIndex = if (!isSynced) -1 else playerMatch.firstActiveIndex

    // 计算视觉聚焦行索引（间奏期间提前聚焦下一句）
    // 使用 stableFocusedIndex 防止 MediaPlayer.currentPosition 抖动导致聚焦行
    // 在边界处来回闪烁（表现为歌词行突然变大一下再缩小）。
    // 正常播放时只允许前进；后退超过 1 行或检测到向后 seek 时允许后退。
    var stableFocusedIndex by remember(lyrics) { mutableIntStateOf(-1) }

    // 追踪播放位置以检测向后 seek（点击上一句歌词会跳转数秒，抖动仅几毫秒）
    val seekDetector = remember(lyrics) {
        object {
            var lastPos = 0L
        }
    }
    val isSeekBack = currentPosition < seekDetector.lastPos - 500
    seekDetector.lastPos = currentPosition

    val rawFocusedIndex = remember(lyrics, currentPosition, currentLineIndex) {
        val baseIndex = currentLineIndex
        val nextIndex = baseIndex + 1
        if (nextIndex in lyrics.indices) {
            val nextLine = lyrics[nextIndex]
            // 逐字歌词：首字符 startTime 可能早于行 timestamp，
            // 当播放位置已到达首字符时提前切换，避免行切换滞后
            val nextCharStart = nextLine.characters?.firstOrNull()?.startTime
            if (nextCharStart != null && currentPosition >= nextCharStart) {
                nextIndex
            } else {
                val currentEndTime = if (baseIndex >= 0) {
                    lyrics[baseIndex].getEndTime(nextLine.timestamp)
                } else 0L
                val gap = nextLine.timestamp - currentEndTime
                val timeToNext = nextLine.timestamp - currentPosition
                // 间奏(gap>=3s)时，在最后 min(gap*0.3, 2s) 提前聚焦
                val threshold = (gap * 0.3).toLong().coerceAtMost(2000L)
                if (gap >= 3000L && timeToNext in 1..threshold) nextIndex
                else baseIndex
            }
        } else baseIndex
    }

    // 方向锁定：仅允许前进或大幅后退（seek）
    // isSeekBack 检测向后跳转（播放位置后退 >500ms），允许恰好后退 1 行的情况
    val focusedLineIndex =
        if (rawFocusedIndex >= stableFocusedIndex || rawFocusedIndex < stableFocusedIndex - 1 || isSeekBack) {
            stableFocusedIndex = rawFocusedIndex
            rawFocusedIndex
        } else {
            stableFocusedIndex
        }

    // 自动滚动到聚焦行
    LaunchedEffect(focusedLineIndex) {
        if (focusedLineIndex >= 0 && lyrics.isNotEmpty()) {
            listState.animateScrollToItemCentered(
                index = focusedLineIndex,
                offset = focusedLineScrollOffsetPx
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (appleStyle) 36.dp else 24.dp),
        contentPadding = PaddingValues(
            top = if (appleStyle) 4.dp else 48.dp,
            bottom = if (appleStyle) 150.dp else 200.dp
        ),
        verticalArrangement = Arrangement.spacedBy(if (appleStyle) 24.dp else 8.dp)
    ) {
        // 歌词行列表
        itemsIndexed(
            items = lyrics,
            key = { index, line -> "${index}_${line.timestamp}" }
        ) { index, line ->
            val lineAnimationStart = line.characters?.firstOrNull()?.startTime
                ?.let { minOf(line.timestamp, it) }
                ?: line.timestamp
            val lineAnimationEnd = line.getEndTime()
            val isTimedCharLineActive = line.hasCharLyrics &&
                    currentPosition >= lineAnimationStart &&
                    currentPosition < lineAnimationEnd
            val isLinePlayed = if (appleStyle) {
                if (line.hasCharLyrics) currentPosition >= lineAnimationEnd else index < currentLineIndex
            } else {
                index <= focusedLineIndex
            }
            LyricLineItem(
                line = line,
                isFocusedLine = index == focusedLineIndex,
                isPlayed = isLinePlayed,
                isPlayingLine = index == currentLineIndex || index in activeLineIndexes || isTimedCharLineActive,
                currentPosition = currentPosition,
                isPlaying = isPlaying,
                showTranslation = showTranslation,
                showRomanization = showRomanization,
                showPhonetic = showPhonetic,
                hasCharLyrics = hasCharLyrics,
                mainFontSize = mainFontSize,
                transFontSize = transFontSize,
                mainFontWeight = mainFontWeight,
                appleStyle = appleStyle,
                onClick = { onLineClick(line.timestamp) }
            )
        }
    }
}

// ==================== 歌词行 ====================

/**
 * 单行歌词组件
 *
 * 根据是否为当前播放行显示不同的样式：
 * - 当前行：Accent 颜色、较大字体、加粗
 * - 其他行：次要颜色、正常字体
 *
 * 支持逐字歌词动画、翻译和音译显示。
 * 点击歌词行可跳转到该行的时间戳。
 *
 * @param line 歌词行数据
 * @param isFocusedLine 是否为视觉聚焦行（控制缩放、透明度、颜色、背景）
 * @param isPlayingLine 是否为当前播放行（控制逐字歌词进度）
 * @param currentPosition 当前播放位置（毫秒）
 * @param showTranslation 是否显示翻译
 * @param showRomanization 是否显示音译
 * @param hasCharLyrics 是否有逐字歌词
 * @param onClick 点击回调
 */
@Composable
private fun LyricLineItem(
    line: LyricLine,
    isFocusedLine: Boolean,
    isPlayed: Boolean,
    isPlayingLine: Boolean,
    currentPosition: Long,
    isPlaying: Boolean,
    showTranslation: Boolean,
    showRomanization: Boolean,
    showPhonetic: Boolean,
    hasCharLyrics: Boolean,
    mainFontSize: Int,
    transFontSize: Int,
    mainFontWeight: FontWeight,
    appleStyle: Boolean,
    onClick: () -> Unit
) {
    val lyricColors = LocalLyricColors.current
    val lineInteractionSource = remember { MutableInteractionSource() }
    val isPressed by lineInteractionSource.collectIsPressedAsState()

    // 整行缩放动画：聚焦行 1.0，非聚焦行 0.85，左对齐
    val scale by animateFloatAsState(
        targetValue = if (appleStyle) 1f else if (isFocusedLine) 1f else 0.85f,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "lyricLineScale"
    )

    // 整行透明度
    val alpha by animateFloatAsState(
        targetValue = if (appleStyle) 1f else if (isFocusedLine) 1f else lyricColors.inactiveAlpha,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "lyricLineAlpha"
    )

    // 颜色动画
    val hasTimedCharacters = hasCharLyrics && line.hasCharLyrics
    val textColor by animateColorAsState(
        targetValue = if (appleStyle) {
            when {
                isPlayingLine -> Color.White
                isFocusedLine && !hasTimedCharacters -> Color.White
                isPlayed -> Color.White.copy(alpha = 0.36f)
                else -> Color.White.copy(alpha = 0.32f)
            }
        } else {
            when {
                isFocusedLine -> lyricColors.playedActive
                isPlayed -> lyricColors.playedInactive
                else -> lyricColors.unplayedInactive
            }
        },
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "lyricTextColor"
    )

    // 背景高亮动画
    val backgroundColor by animateColorAsState(
        targetValue = if (appleStyle) Color.Transparent
        else if (isFocusedLine) lyricColors.lineHighlight else Color.Transparent,
        animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing),
        label = "lyricBgColor"
    )
    val lineShape = RoundedCornerShape(if (appleStyle) 14.dp else 12.dp)
    val clickableBackgroundColor by animateColorAsState(
        targetValue = when {
            isPressed && appleStyle -> Color.White.copy(alpha = 0.13f)
            isPressed -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)
            else -> backgroundColor
        },
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
        label = "lyricPressedBackground"
    )

    // 统一歌词文字样式，避免 CharacterLyricText ↔ Text 切换时因样式差异导致布局尺寸突变
    val lyricTextStyle = MaterialTheme.typography.bodyLarge.copy(
        fontSize = mainFontSize.sp,
        lineHeight = (mainFontSize * if (appleStyle) 1.34f else 1.35f).sp,
        fontWeight = mainFontWeight
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                transformOrigin = TransformOrigin(0f, 0.5f) // 左对齐缩放
                this.alpha = alpha
            }
            .background(color = clickableBackgroundColor, shape = lineShape)
            .clickable(
                interactionSource = lineInteractionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                vertical = if (appleStyle) 7.dp else 8.dp,
                horizontal = if (appleStyle) 0.dp else 12.dp
            )
    ) {
        // 主歌词：有逐字歌词的行始终渲染 CharacterLyricText（不切换组件，防止组合树变化导致布局跳变）
        if (hasCharLyrics && line.hasCharLyrics) {
            val lineAnimationStart = line.characters?.firstOrNull()?.startTime
                ?.let { minOf(line.timestamp, it) }
                ?: line.timestamp
            CharacterLyricText(
                characters = line.characters!!,
                currentPosition = currentPosition,
                isPlaying = isPlaying,
                isFocused = isFocusedLine,
                textStyle = lyricTextStyle,
                textColor = textColor,
                isActiveLine = isPlayingLine,
                lineStartTime = lineAnimationStart,
                lineEndTime = line.getEndTime(),
                appleStyle = appleStyle
            )
        } else {
            Text(
                text = line.text,
                style = lyricTextStyle,
                color = textColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }

        // 音译
        if (showRomanization && line.hasRomanization) {
            val hasTimedRomanization = line.hasCharRomanization
            val romaColor = if (appleStyle) {
                if (isPlayingLine) Color.White.copy(alpha = 0.54f) else Color.White.copy(alpha = 0.30f)
            } else if (isFocusedLine && hasCharLyrics && line.hasCharLyrics) {
                val charsDone =
                    line.characters?.lastOrNull()?.let { currentPosition >= it.endTime } == true
                if (charsDone) textColor.copy(alpha = 0.6f)
                else lyricColors.unplayedActive.copy(alpha = 0.6f)
            } else {
                textColor.copy(alpha = 0.6f)
            }
            val romaTextStyle = MaterialTheme.typography.bodySmall.copy(
                fontSize = (transFontSize - 1).sp,
                lineHeight = ((transFontSize - 1) * if (appleStyle) 1.32f else 1.35f).sp,
                fontWeight = if (appleStyle) FontWeight.ExtraBold else FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (hasTimedRomanization) {
                val romanizationChars = line.charRomanization!!
                val romanizationStart = romanizationChars.firstOrNull()?.startTime
                    ?.let { minOf(line.timestamp, it) }
                    ?: line.timestamp
                CharacterLyricText(
                    characters = romanizationChars,
                    currentPosition = currentPosition,
                    isPlaying = isPlaying,
                    isFocused = isFocusedLine,
                    textStyle = romaTextStyle,
                    textColor = romaColor,
                    isActiveLine = isPlayingLine,
                    lineStartTime = romanizationStart,
                    lineEndTime = romanizationChars.lastOrNull()?.endTime ?: line.getEndTime(),
                    appleStyle = appleStyle,
                    appleEmphasis = false
                )
            } else {
                Text(
                    text = line.romanization ?: line.charRomanization?.joinToString("") { it.char }
                        .orEmpty(),
                    style = romaTextStyle,
                    color = romaColor,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Start
                )
            }
        }

        // 翻译
        if (showTranslation && line.hasTranslation) {
            // 活跃行且有逐字歌词：等逐字播完后才变为已播放颜色
            val transColor = if (appleStyle) {
                if (isPlayingLine) Color.White.copy(alpha = 0.54f) else Color.White.copy(alpha = 0.30f)
            } else if (isFocusedLine && hasCharLyrics && line.hasCharLyrics) {
                val charsDone =
                    line.characters?.lastOrNull()?.let { currentPosition >= it.endTime } == true
                if (charsDone) textColor.copy(alpha = 0.7f)
                else lyricColors.unplayedActive.copy(alpha = 0.7f)
            } else {
                textColor.copy(alpha = 0.7f)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = line.translation!!,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = transFontSize.sp,
                    lineHeight = (transFontSize * if (appleStyle) 1.32f else 1.35f).sp,
                    fontWeight = if (appleStyle) FontWeight.Bold else FontWeight.Normal
                ),
                color = transColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }

        // AI 谐音
        if (showPhonetic && line.phonetic != null && line.phonetic.isNotBlank()) {
            val phoneticColor = if (appleStyle) {
                if (isPlayingLine) Color.White.copy(alpha = 0.48f) else Color.White.copy(alpha = 0.26f)
            } else if (isFocusedLine && hasCharLyrics && line.hasCharLyrics) {
                val charsDone =
                    line.characters?.lastOrNull()?.let { currentPosition >= it.endTime } == true
                if (charsDone) textColor.copy(alpha = 0.6f)
                else lyricColors.unplayedActive.copy(alpha = 0.6f)
            } else {
                textColor.copy(alpha = 0.6f)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = line.phonetic,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = (transFontSize - 1).sp,
                    lineHeight = ((transFontSize - 1) * if (appleStyle) 1.32f else 1.35f).sp,
                    fontWeight = if (appleStyle) FontWeight.Bold else FontWeight.Normal
                ),
                color = phoneticColor,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Start
            )
        }
    }
}

// ==================== 逐字歌词动画 ====================

/**
 * 逐字歌词动画文本
 *
 * 根据当前播放位置逐字高亮歌词字符，实现卡拉OK效果。
 * 已播放的字符使用 Accent 颜色，未播放的字符使用次要颜色。
 *
 * 始终渲染以避免组合树变化导致的布局跳变：
 * - 聚焦时：显示逐字卡拉OK动画（played/unplayed 双色）
 * - 非聚焦时：统一使用 textColor 显示（无卡拉OK效果）
 *
 * @param characters 逐字歌词字符列表
 * @param currentPosition 当前播放位置（毫秒）
 * @param isPlaying 是否正在播放
 * @param isFocused 是否为聚焦行
 * @param textStyle 文字样式
 * @param textColor 非聚焦时的统一文字颜色
 */
@Composable
private fun CharacterLyricText(
    characters: List<LyricChar>,
    currentPosition: Long,
    isPlaying: Boolean,
    isFocused: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    isActiveLine: Boolean = isFocused,
    lineStartTime: Long = characters.firstOrNull()?.startTime ?: 0L,
    lineEndTime: Long = characters.lastOrNull()?.endTime ?: lineStartTime,
    appleStyle: Boolean = false,
    appleEmphasis: Boolean = true
) {
    // App 在后台时暂停帧动画以省电
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
    val isAppVisible = lifecycleState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
    val shouldDriveAnimation = if (appleStyle) isActiveLine else isFocused

    // 每帧推算的精确播放位置：
    // 基于上次已知的 currentPosition + 自那以后经过的真实时间
    var interpolatedPosition by remember(characters, lineStartTime, lineEndTime) {
        mutableLongStateOf(currentPosition)
    }
    var lastKnownPosition by remember(characters, lineStartTime, lineEndTime) {
        mutableLongStateOf(currentPosition)
    }
    var lastKnownTimestamp by remember(characters, lineStartTime, lineEndTime) {
        mutableLongStateOf(System.nanoTime())
    }

    // 当 ViewModel 推送新的 currentPosition 时，校准基准
    LaunchedEffect(currentPosition, lineStartTime, lineEndTime) {
        lastKnownPosition = currentPosition
        lastKnownTimestamp = System.nanoTime()
        interpolatedPosition = currentPosition
    }

    // 暂停或失焦时立即校准到当前位置
    LaunchedEffect(isPlaying, shouldDriveAnimation, lineStartTime, lineEndTime) {
        if (!isPlaying || !shouldDriveAnimation) {
            interpolatedPosition = currentPosition
            lastKnownPosition = currentPosition
            lastKnownTimestamp = System.nanoTime()
        }
    }

    val shouldInterpolatePosition = isAppVisible && isPlaying && shouldDriveAnimation

    // 每帧更新推算位置（仅当前聚焦行播放中才启动循环）
    LaunchedEffect(shouldInterpolatePosition) {
        if (!shouldInterpolatePosition) return@LaunchedEffect
        while (true) {
            withInfiniteAnimationFrameMillis {
                val elapsed = (System.nanoTime() - lastKnownTimestamp) / 1_000_000L
                interpolatedPosition = lastKnownPosition + elapsed
            }
        }
    }

    if (appleStyle) {
        AppleSyllableLyricText(
            characters = characters,
            currentPosition = interpolatedPosition,
            isActiveLine = isActiveLine,
            textStyle = textStyle,
            textColor = textColor,
            lineStartTime = lineStartTime,
            lineEndTime = lineEndTime,
            emphasisEnabled = appleEmphasis
        )
        return
    }

    val fullText = remember(characters) { characters.joinToString("") { it.char } }

    // 每个逐字字符在 fullText 中的 [startOffset, endOffset)
    val charOffsets = remember(characters) {
        var offset = 0
        characters.map { c ->
            val start = offset
            offset += c.char.length
            start to offset
        }
    }

    // 聚焦时使用卡拉OK双色，非聚焦时统一使用 textColor
    val lColors = LocalLyricColors.current
    val playedColor = if (isFocused) lColors.playedActive else textColor
    val unplayedColor = if (isFocused) lColors.unplayedActive else textColor
    var layoutResult by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }

    // 非聚焦时不需要计算遮罩坐标。
    val wipeDistance: Float
    if (!isFocused) {
        wipeDistance = 0f
    } else {
        val layout = layoutResult
        val pos = interpolatedPosition
        val textLength = layout?.layoutInput?.text?.length ?: 0
        if (layout == null || characters.isEmpty() || textLength == 0) {
            wipeDistance = 0f
        } else {
            // 计算 rawProgress
            var rawProgress = 0f
            for (i in characters.indices) {
                val ch = characters[i]
                when {
                    pos >= ch.endTime -> rawProgress = (i + 1).toFloat()
                    pos >= ch.startTime -> {
                        val frac = if (ch.duration > 0)
                            ((pos - ch.startTime).toFloat() / ch.duration).coerceIn(0f, 1f)
                        else 1f
                        rawProgress = i + frac
                        break
                    }

                    else -> break
                }
            }

            if (rawProgress <= 0f) {
                wipeDistance = 0f
            } else {
                val charIdx = rawProgress.toInt().coerceAtMost(characters.size - 1)
                val charFrac = rawProgress - rawProgress.toInt()
                val allDone = rawProgress >= characters.size

                if (allDone) {
                    var total = 0f
                    for (line in 0 until layout.lineCount) {
                        total += layout.getLineRight(line) - layout.getLineLeft(line)
                    }
                    wipeDistance = total
                } else {
                    val fillStart = charOffsets[charIdx].first.coerceIn(0, textLength)
                    val fillEnd = charOffsets[charIdx].second.coerceIn(0, textLength)
                    if (fillStart >= textLength) {
                        // charOffsets 超出 layout 文本范围，视为全部填充
                        var total = 0f
                        for (line in 0 until layout.lineCount) {
                            total += layout.getLineRight(line) - layout.getLineLeft(line)
                        }
                        wipeDistance = total
                    } else {
                        val charLine = layout.getLineForOffset(fillStart)
                        var accumulated = 0f
                        for (line in 0 until charLine) {
                            accumulated += layout.getLineRight(line) - layout.getLineLeft(line)
                        }
                        val lineLeft = layout.getLineLeft(charLine)
                        val startX = layout.getHorizontalPosition(fillStart, true)
                        val endX = layout.getHorizontalPosition(fillEnd, true)
                        val effectiveEndX =
                            if (endX >= startX) endX else layout.getLineRight(charLine)
                        val currentX = startX + (effectiveEndX - startX) * charFrac
                        accumulated += currentX - lineLeft
                        wipeDistance = accumulated
                    }
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = fullText,
            style = textStyle.copy(color = unplayedColor),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Start,
            onTextLayout = { layoutResult = it }
        )
        if (isFocused) {
            Text(
                text = fullText,
                style = textStyle.copy(color = playedColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        val l = layoutResult ?: return@drawWithContent
                        if (wipeDistance <= 0f) return@drawWithContent

                        drawContent()

                        var remaining = wipeDistance
                        val feather = 0f
                        for (line in 0 until l.lineCount) {
                            val lineLeft = l.getLineLeft(line)
                            val lineRight = l.getLineRight(line)
                            val lineWidth = lineRight - lineLeft
                            if (lineWidth <= 0f) continue

                            val top = l.getLineTop(line)
                            val bottom = l.getLineBottom(line)
                            val lineSize = Size(lineWidth, bottom - top)
                            val lineOffset = Offset(lineLeft, top)

                            if (remaining >= lineWidth) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = lineOffset,
                                    size = lineSize,
                                    blendMode = BlendMode.DstIn
                                )
                                remaining -= lineWidth
                            } else if (remaining <= 0f) {
                                drawRect(
                                    color = Color.Transparent,
                                    topLeft = lineOffset,
                                    size = lineSize,
                                    blendMode = BlendMode.DstIn
                                )
                            } else {
                                val solidStop = ((remaining - feather) / lineWidth).coerceIn(0f, 1f)
                                val fadeStop = ((remaining + feather) / lineWidth).coerceIn(0f, 1f)
                                val maskBrush = when {
                                    fadeStop <= 0f -> Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        1f to Color.Transparent,
                                        startX = lineLeft,
                                        endX = lineRight
                                    )

                                    solidStop >= 1f -> Brush.horizontalGradient(
                                        0f to Color.Black,
                                        1f to Color.Black,
                                        startX = lineLeft,
                                        endX = lineRight
                                    )

                                    else -> Brush.horizontalGradient(
                                        0f to Color.Black,
                                        solidStop to Color.Black,
                                        fadeStop to Color.Transparent,
                                        1f to Color.Transparent,
                                        startX = lineLeft,
                                        endX = lineRight
                                    )
                                }
                                drawRect(
                                    brush = maskBrush,
                                    topLeft = lineOffset,
                                    size = lineSize,
                                    blendMode = BlendMode.DstIn
                                )
                                remaining = 0f
                            }
                        }
                    },
                textAlign = TextAlign.Start
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AppleSyllableLyricText(
    characters: List<LyricChar>,
    currentPosition: Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    textColor: Color,
    lineStartTime: Long,
    lineEndTime: Long,
    emphasisEnabled: Boolean
) {
    val wordSizes = remember(characters) {
        mutableStateListOf<IntSize>().also { list ->
            repeat(characters.size) { list.add(IntSize.Zero) }
        }
    }
    val maskReady = wordSizes.size == characters.size &&
            wordSizes.all { it.width > 0 && it.height > 0 }
    val wordColor = if (isActiveLine) Color.White else textColor

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        characters.forEachIndexed { index, word ->
            AppleSyllableWord(
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
private fun AppleSyllableWord(
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
    val wordFloatY = resolveAppleWordFloat(
        word = word,
        relativeTime = relativeTime,
        lineStartTime = lineStartTime,
        isActiveLine = isActiveLine,
        cssPx = cssPx
    )
    val mask = if (maskReady) {
        resolveAppleWordMask(
            wordIndex = wordIndex,
            words = words,
            wordSizes = wordSizes,
            currentPosition = currentPosition,
            isActiveLine = isActiveLine,
            lineStartTime = lineStartTime,
            lineEndTime = lineEndTime
        )
    } else {
        AppleWordMask(0f, 0f)
    }

    Box(
        modifier = Modifier
            .onSizeChanged(onSizeChanged)
            .graphicsLayer {
                translationY = wordFloatY
            }
    ) {
        AppleWordContent(
            word = word,
            relativeTime = relativeTime,
            lineStartTime = lineStartTime,
            isActiveLine = isActiveLine,
            textStyle = textStyle,
            color = wordColor,
            cssPx = cssPx,
            emphasisEnabled = emphasisEnabled,
            modifier = if (isActiveLine) {
                Modifier
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        if (size.width <= 0f) return@drawWithContent
                        drawContent()
                        drawRect(
                            brush = appleMaskBrush(mask, size.width),
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
private fun AppleWordContent(
    word: LyricChar,
    relativeTime: Float,
    lineStartTime: Long,
    isActiveLine: Boolean,
    textStyle: TextStyle,
    color: Color,
    cssPx: Float,
    emphasisEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    if (!emphasisEnabled || !word.stress || word.char.isEmpty()) {
        Text(
            text = word.char,
            style = textStyle.copy(color = color),
            modifier = modifier,
            textAlign = TextAlign.Start
        )
        return
    }

    val chars = remember(word.char) { splitAppleChars(word.char) }
    Row(modifier = modifier) {
        chars.forEachIndexed { index, value ->
            val effect = resolveAppleEmphasisEffect(
                word = word,
                lineStartTime = lineStartTime,
                relativeTime = relativeTime,
                charIndex = index,
                charCount = chars.size,
                isActiveLine = isActiveLine,
                cssPx = cssPx
            )
            Text(
                text = value,
                style = textStyle.copy(
                    color = color,
                    shadow = if (effect.shadowAlpha > 0f) {
                        Shadow(
                            color = Color.Black.copy(alpha = effect.shadowAlpha),
                            offset = Offset.Zero,
                            blurRadius = effect.shadowRadius
                        )
                    } else {
                        null
                    }
                ),
                modifier = Modifier.graphicsLayer {
                    scaleX = effect.scale
                    scaleY = effect.scale
                    translationX = effect.translationX
                    translationY = effect.translationY
                },
                textAlign = TextAlign.Start
            )
        }
    }
}

private data class AppleWordMask(
    val activeX: Float,
    val fadeX: Float
)

private data class AppleMaskFrame(
    val offset: Float,
    val position: Float
)

private data class AppleMaskFrames(
    val leftPos: Float,
    val widthInTotal: Float,
    val imageWidth: Float,
    val frames: List<AppleMaskFrame>
)

private data class AppleEmphasisEffect(
    val scale: Float = 1f,
    val translationX: Float = 0f,
    val translationY: Float = 0f,
    val shadowAlpha: Float = 0f,
    val shadowRadius: Float = 0f
)

private const val APPLE_ACTIVE_OPACITY = 1f
private const val APPLE_NORMAL_OPACITY = 0.6f
private const val APPLE_MASK_FEATHER_NORMAL = 0.5f
private const val APPLE_MASK_FEATHER_FIRST = 1.5f
private const val APPLE_MASK_FEATHER_LAST = 0.5f
private const val APPLE_WORD_FLOAT_FROM = 0f
private const val APPLE_WORD_FLOAT_TO = -2f
private const val APPLE_EMPHASIS_MIN_DURATION_MS = 1000L
private const val APPLE_EMPHASIS_MAIN_SCALE = 0.1f
private const val APPLE_EMPHASIS_MAIN_OFFSET_HORIZONTAL = 1f
private const val APPLE_EMPHASIS_MAIN_OFFSET_VERTICAL = 1f
private const val APPLE_EMPHASIS_GLOW_MAX_RADIUS = 9f
private const val APPLE_EMPHASIS_GLOW_MAX_ALPHA = 1f
private const val APPLE_EMPHASIS_FLOAT_DURATION_SCALE = 1.4f
private const val APPLE_EMPHASIS_FLOAT_LEAD_MS = 400f
private const val APPLE_EMPHASIS_FLOAT_AMPLITUDE = 2f

private val AppleEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
private val AppleEmphasisRise = CubicBezierEasing(0.2f, 0.4f, 0.58f, 1f)
private val AppleEmphasisFall = CubicBezierEasing(0.3f, 0f, 0.58f, 1f)
private val AppleEmphasisFloatEase = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

private fun resolveAppleWordMask(
    wordIndex: Int,
    words: List<LyricChar>,
    wordSizes: List<IntSize>,
    currentPosition: Long,
    isActiveLine: Boolean,
    lineStartTime: Long,
    lineEndTime: Long
): AppleWordMask {
    val size = wordSizes.getOrNull(wordIndex) ?: return AppleWordMask(0f, 0f)
    val wordWidth = size.width.toFloat()
    val wordHeight = size.height.toFloat()
    if (wordWidth <= 0f || wordHeight <= 0f || words.isEmpty()) {
        return AppleWordMask(0f, 0f)
    }

    val lineDuration = (lineEndTime - lineStartTime).coerceAtLeast(1L).toFloat()
    val wordWidths = words.indices.map { wordSizes.getOrNull(it)?.width?.toFloat() ?: 0f }
    val frames = buildAppleMaskFrames(
        wordIndex = wordIndex,
        words = words,
        wordWidths = wordWidths,
        wordWidth = wordWidth,
        wordHeight = wordHeight,
        lineStartTime = lineStartTime,
        lineDuration = lineDuration
    )
    val relativeTime = when {
        isActiveLine -> (currentPosition - lineStartTime).toFloat()
        currentPosition <= lineStartTime -> 0f
        else -> lineDuration
    }
    val maskPosition = sampleAppleMaskPosition(frames.frames, relativeTime / lineDuration)
    return AppleWordMask(
        activeX = maskPosition + frames.leftPos * frames.imageWidth,
        fadeX = maskPosition + (frames.leftPos + frames.widthInTotal) * frames.imageWidth
    )
}

private fun buildAppleMaskFrames(
    wordIndex: Int,
    words: List<LyricChar>,
    wordWidths: List<Float>,
    wordWidth: Float,
    wordHeight: Float,
    lineStartTime: Long,
    lineDuration: Float
): AppleMaskFrames {
    val wordCount = words.size
    val invLineDuration = 1f / lineDuration
    val widthFade = wordHeight * APPLE_MASK_FEATHER_NORMAL
    val widthFadeFirst = widthFade * APPLE_MASK_FEATHER_FIRST
    val widthFadeLast = widthFade * APPLE_MASK_FEATHER_LAST
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
        AppleMaskFrame(
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
                AppleMaskFrame(
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
                        AppleMaskFrame(
                            offset = prevProgress + (positionMin - prevCursor) * rate,
                            position = positionMin
                        )
                    )
                }
                if (prevCursor < 0f && cursor > 0f) {
                    frames.add(
                        AppleMaskFrame(
                            offset = prevProgress - prevCursor * rate,
                            position = 0f
                        )
                    )
                }
            }

            prevCursor = cursor
            prevProgress = target
            frames.add(
                AppleMaskFrame(
                    offset = target,
                    position = cursor.coerceIn(positionMin, 0f)
                )
            )
        } else {
            prevCursor = cursor
        }
    }

    return AppleMaskFrames(
        leftPos = leftPos,
        widthInTotal = widthInTotal,
        imageWidth = imageWidth,
        frames = frames.sortedBy { it.offset }
    )
}

private fun sampleAppleMaskPosition(frames: List<AppleMaskFrame>, offset: Float): Float {
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

private fun appleMaskBrush(mask: AppleWordMask, width: Float): Brush {
    val active = Color.Black.copy(alpha = APPLE_ACTIVE_OPACITY)
    val normal = Color.Black.copy(alpha = APPLE_NORMAL_OPACITY)

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

private fun resolveAppleWordFloat(
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
    return (APPLE_WORD_FLOAT_FROM + (APPLE_WORD_FLOAT_TO - APPLE_WORD_FLOAT_FROM) * AppleEase.transform(
        progress
    )) * cssPx
}

private fun resolveAppleEmphasisEffect(
    word: LyricChar,
    lineStartTime: Long,
    relativeTime: Float,
    charIndex: Int,
    charCount: Int,
    isActiveLine: Boolean,
    cssPx: Float
): AppleEmphasisEffect {
    if (!isActiveLine || !word.stress || charCount <= 0) return AppleEmphasisEffect()

    val rawDuration = word.duration.coerceAtLeast(APPLE_EMPHASIS_MIN_DURATION_MS).toFloat()
    val delay = (word.startTime - lineStartTime).toFloat()
    val stagger = rawDuration / 2.5f / charCount
    val charDelay = delay + stagger * charIndex

    var mainIntensity = rawDuration / 2000f
    mainIntensity =
        if (mainIntensity > 1f) sqrt(mainIntensity) else mainIntensity * mainIntensity * mainIntensity
    mainIntensity = (mainIntensity * 0.6f).coerceIn(0f, 1.2f)

    var glowIntensity = rawDuration / 3000f
    glowIntensity =
        if (glowIntensity > 1f) sqrt(glowIntensity) else glowIntensity * glowIntensity * glowIntensity
    glowIntensity = (glowIntensity * 0.5f).coerceIn(0f, 0.8f)

    val mainProgress = ((relativeTime - charDelay) / rawDuration).coerceIn(0f, 1f)
    val mainPulse = applePulse(mainProgress, AppleEmphasisRise, AppleEmphasisFall)
    val peakScale = roundApple(1f + APPLE_EMPHASIS_MAIN_SCALE * mainIntensity)
    val offset = charCount / 2f - charIndex
    val offsetX = roundApple(APPLE_EMPHASIS_MAIN_OFFSET_HORIZONTAL * mainIntensity)
    val offsetY = roundApple(-APPLE_EMPHASIS_MAIN_OFFSET_VERTICAL * mainIntensity)
    val translationX = roundApple(-offsetX * offset) * cssPx * mainPulse
    val translationY = offsetY * cssPx * mainPulse
    val scale = 1f + (peakScale - 1f) * mainPulse

    val glowProgress = ((relativeTime - charDelay) / rawDuration).coerceIn(0f, 1f)
    val glowPulse = applePulse(glowProgress, AppleEmphasisRise, AppleEmphasisFall)
    val glowRadius = roundApple(
        minOf(APPLE_EMPHASIS_GLOW_MAX_RADIUS, glowIntensity * APPLE_EMPHASIS_GLOW_MAX_RADIUS)
    ) * cssPx
    val glowAlpha = roundApple(
        (glowIntensity * APPLE_EMPHASIS_GLOW_MAX_ALPHA).coerceIn(0f, 1f)
    ) * glowPulse

    val floatDuration = rawDuration * APPLE_EMPHASIS_FLOAT_DURATION_SCALE
    val floatDelay = charDelay - APPLE_EMPHASIS_FLOAT_LEAD_MS
    val floatProgress = ((relativeTime - floatDelay) / floatDuration).coerceIn(0f, 1f)
    val floatPulse = applePulse(floatProgress, AppleEmphasisFloatEase, AppleEmphasisFloatEase)
    val floatY = roundApple(-APPLE_EMPHASIS_FLOAT_AMPLITUDE) * cssPx * floatPulse

    return AppleEmphasisEffect(
        scale = scale,
        translationX = translationX,
        translationY = translationY + floatY,
        shadowAlpha = glowAlpha,
        shadowRadius = glowRadius
    )
}

private fun applePulse(progress: Float, rise: Easing, fall: Easing): Float {
    val p = progress.coerceIn(0f, 1f)
    return when {
        p <= 0f || p >= 1f -> 0f
        p < 0.5f -> rise.transform(p / 0.5f)
        else -> 1f - fall.transform((p - 0.5f) / 0.5f)
    }.coerceIn(0f, 1f)
}

private fun roundApple(value: Float): Float {
    return round(value * 1000f) / 1000f
}

private fun splitAppleChars(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    return text.codePoints().toArray().map { codePoint ->
        String(Character.toChars(codePoint))
    }
}

// ==================== 底部迷你播放器控制 ====================

/**
 * 迷你播放器控制组件
 *
 * 在歌词页面底部显示简化的播放控制，包含歌曲封面、标题、艺术家和播放/暂停按钮。
 * 与通用 MiniPlayer 组件不同，此组件不包含进度条和点击展开功能，
 * 专为歌词页面底部控制设计。
 *
 * Requirements:
 * - 4.8: 歌词页面底部显示迷你播放器控制
 *
 * @param song 当前播放的歌曲
 * @param isPlaying 是否正在播放
 * @param onPlayPause 播放/暂停按钮点击回调
 */
@Composable
private fun MiniPlayerControl(
    song: MusicItem?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 歌曲封面
            AsyncImage(
                model = neteaseCoverUrl(song?.cover),
                contentDescription = song?.title,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(12.dp))

            // 歌曲信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song?.title ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = song?.artist ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 播放/暂停按钮
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(
                        R.string.play
                    ),
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
