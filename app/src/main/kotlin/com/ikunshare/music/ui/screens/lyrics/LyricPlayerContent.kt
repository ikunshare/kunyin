package com.ikunshare.music.ui.screens.lyrics

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.model.LyricCredit
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.model.ParsedLyric
import com.ikunshare.music.tool.lyricplayer.BaseLyricPlayer
import kotlinx.coroutines.delay

internal val LocalLyricPlaybackRate = staticCompositionLocalOf { 1f }

/** Native viewport for the desktop lyric model; only visible lines are composed. */
@Composable
internal fun WebLyricsContent(
    lyrics: List<LyricLine>, currentPosition: Long, isPlaying: Boolean,
    showTranslation: Boolean, showRomanization: Boolean, showPhonetic: Boolean,
    hasCharLyrics: Boolean, isSynced: Boolean, onLineClick: (Long) -> Unit,
    modifier: Modifier = Modifier, anchorFraction: Float = 0.45f,
    horizontalPadding: Dp = 28.dp, credits: List<LyricCredit> = emptyList(),
    playbackRate: Float = 1f, occludedBottomPx: Int = 0
) {
    val settings = MusicApplication.instance?.appSettingsManager?.settings?.collectAsState()?.value
    val mainSize = (settings?.lyricsMainFontSize ?: 24).coerceIn(14, 48)
    val subSize = (settings?.lyricsTransFontSize ?: 16).coerceIn(10, 32)
    val mainStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = mainSize.sp,
        lineHeight = (mainSize * 1.3f).sp, fontWeight = lyricFontWeightFromKey(settings?.lyricsFontWeight ?: "bold"))
    val subStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = subSize.sp,
        lineHeight = (subSize * 1.32f).sp)
    val parsed = remember(lyrics, isSynced, hasCharLyrics) { ParsedLyric(lyrics, false, false, hasCharLyrics, isSynced) }
    val player = remember(parsed) { BaseLyricPlayer().apply { updateLyric(parsed) } }
    val match = remember(player, currentPosition) { player.matchLinesWithTime(currentPosition) }
    val focus = remember(lyrics, match, currentPosition) {
        if (match.firstActiveIndex >= 0) match.firstActiveIndex
        else parsed.findCurrentLineIndex(currentPosition).coerceAtLeast(0)
    }
    // Exclude hidden interludes entirely, including their LazyColumn spacing.
    // Keep the original index for matching, stable keys and neighbouring times.
    val activeInterlude = match.indexes.firstOrNull { lyrics[it].isInterlude }
    val presented = remember(lyrics, activeInterlude) {
        lyrics.withIndex().filter { !it.value.isInterlude || it.index == activeInterlude }
    }
    val presentedFocus = presented.indexOfFirst { it.index == focus }.let { index ->
        if (index >= 0) index else presented.indexOfLast { it.index < focus }.coerceAtLeast(0)
    }
    val state = rememberLazyListState()
    val dragging by state.interactionSource.collectIsDraggedAsState()
    val density = LocalDensity.current
    var viewportHeight by remember { mutableIntStateOf(0) }
    var viewportWidth by remember { mutableIntStateOf(0) }
    var manual by remember(lyrics) { mutableStateOf(false) }
    var touchGeneration by remember(lyrics) { mutableIntStateOf(0) }
    val measuredHeights = remember(lyrics, mainSize, subSize, showTranslation, showRomanization, showPhonetic) { mutableStateMapOf<Int, Int>() }
    val input = remember(lyrics) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    manual = true
                    touchGeneration++
                }
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(manual, touchGeneration, dragging, state.isScrollInProgress, isPlaying) {
        if (manual && isPlaying && !dragging && !state.isScrollInProgress) {
            delay(5000)
            manual = false
        }
    }
    val focusHeight = measuredHeights[focus] ?: with(density) { (mainSize * 1.3f).sp.toPx().toInt() }
    var initialized by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(lyrics, presentedFocus, focus, focusHeight, viewportHeight, manual) {
        if (manual || !isSynced || presented.isEmpty() || viewportHeight <= 0) return@LaunchedEffect
        if (!initialized) {
            state.scrollToItem(presentedFocus, focusHeight / 2)
            initialized = true
        } else state.animateScrollToItem(presentedFocus, focusHeight / 2)
    }
    CompositionLocalProvider(LocalLyricPlaybackRate provides playbackRate) {
        LazyColumn(
            state = state,
            modifier = modifier.fillMaxSize().clipToBounds()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val visibleFraction = if (size.height > 0f)
                        ((size.height - occludedBottomPx) / size.height).coerceIn(0f, 1f) else 1f
                    val edgeFraction = if (size.height > 0f)
                        (28.dp.toPx() / size.height).coerceAtMost(visibleFraction / 2f) else 0f
                    val fade = Brush.verticalGradient(0f to Color.Transparent, edgeFraction to Color.Black,
                        (visibleFraction - edgeFraction) to Color.Black,
                        visibleFraction to Color.Transparent, 1f to Color.Transparent)
                    onDrawWithContent {
                        drawContent()
                        drawRect(fade, blendMode = BlendMode.DstIn)
                    }
                }.nestedScroll(input).onSizeChanged {
                viewportHeight = it.height
                viewportWidth = it.width
            },
            contentPadding = PaddingValues(start = horizontalPadding, end = horizontalPadding,
                    top = with(density) { (viewportHeight * anchorFraction).toDp() },
                    bottom = with(density) { (viewportHeight * (1f - anchorFraction)).toDp() }),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            itemsIndexed(presented, key = { _, item -> "${item.value.timestamp}:${item.index}" },
                contentType = { _, item -> if (item.value.isInterlude) "interlude" else "lyric" }) { _, item ->
                val index = item.index
                val line = item.value
                WebLyricLine(
                    line = line,
                    active = index in match.indexes,
                    played = isSynced && currentPosition >= line.getEndTime(lyrics.getOrNull(index + 1)?.timestamp),
                    currentPosition = currentPosition, isPlaying = isPlaying,
                    showTranslation = showTranslation, showRomanization = showRomanization,
                    showPhonetic = showPhonetic, hasCharLyrics = hasCharLyrics,
                    mainStyle = mainStyle, subStyle = subStyle,
                    availableWidthPx = (viewportWidth - with(density) { horizontalPadding.toPx() * 2 }).toInt().coerceAtLeast(0),
                    onClick = { if (isSynced) { manual = false; onLineClick(line.timestamp) } },
                    modifier = Modifier.fillMaxWidth().onSizeChanged { measuredHeights[index] = it.height }
                )
            }
            if (credits.isNotEmpty()) item(key = "credits", contentType = "credits") {
                LyricCredits(credits)
            }
        }
    }
}

@Composable
internal fun LyricCredits(credits: List<LyricCredit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        credits.forEach { credit ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(credit.role, color = Color.White.copy(alpha = 0.42f), style = MaterialTheme.typography.labelMedium)
                Text(credit.names.joinToString(" · "), color = Color.White.copy(alpha = 0.68f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
