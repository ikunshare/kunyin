package com.ikunshare.music.ui.screens.lyrics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.model.LyricCredit
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.model.ParsedLyric
import com.ikunshare.music.tool.lyricplayer.BaseLyricPlayer
import com.ikunshare.music.tool.lyricplayer.LyricPlayerMatch
import com.ikunshare.music.ui.screens.lyrics.player.LyricFrameClock
import com.ikunshare.music.ui.screens.lyrics.player.LyricPlayerConfig
import com.ikunshare.music.ui.screens.lyrics.player.LyricTextStyles
import com.ikunshare.music.ui.screens.lyrics.player.LyricViewport

/**
 * 歌词面板入口：桌面端 `music-lyric-player` 的 Compose 还原。
 *
 * - [BaseLyricPlayer] 负责激活行判定（合并窗口、桥接）
 * - 一个帧循环产出插帧后的播放位置与墙钟，行组件只在 draw 阶段读取
 * - 激活行集合只在变化时写入 state，逐帧不重组
 */
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
    val weight = lyricFontWeightFromKey(settings?.lyricsFontWeight ?: "bold")
    val base = MaterialTheme.typography.bodyLarge
    val styles = remember(mainSize, subSize, weight, base) {
        LyricTextStyles(
            main = base.copy(fontSize = mainSize.sp, lineHeight = (mainSize * 1.3f).sp, fontWeight = weight, color = Color.White),
            annotation = base.copy(fontSize = subSize.sp, lineHeight = (subSize * 1.35f).sp, color = Color.White),
        )
    }
    val config = LyricPlayerConfig.Default

    val parsed = remember(lyrics, isSynced, hasCharLyrics) { ParsedLyric(lyrics, false, false, hasCharLyrics, isSynced) }
    val player = remember(parsed) { BaseLyricPlayer().apply { updateLyric(parsed) } }

    // 帧时钟：只在可见 + 播放中驱动；暂停 / 不可见时对齐到 ViewModel 推送的位置
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
    val drive = visible && isPlaying && isSynced
    val positionState = remember(lyrics) { mutableLongStateOf(currentPosition) }
    val wallState = remember { mutableLongStateOf(System.nanoTime() / 1_000_000L) }
    val clock = remember(lyrics) { LyricPlaybackClock(currentPosition, System.nanoTime()) }
    val matchState = remember(player) { mutableStateOf(player.matchLinesWithTime(currentPosition)) }
    // 激活集合只在行边界变化：记下下一个边界，边界之前跳过匹配（逐帧调用不再分配列表）
    val matchWindow = remember(player) { LongArray(2) { Long.MIN_VALUE } } // [上次位置, 下一边界]
    fun publish(position: Long) {
        positionState.longValue = position
        if (position < matchWindow[0] || position >= matchWindow[1]) {
            val next = player.matchLinesWithTime(position)
            if (next.indexes != matchState.value.indexes) matchState.value = next
            matchWindow[1] = player.nextChangeTime(position)
        }
        matchWindow[0] = position
    }
    LaunchedEffect(currentPosition, drive, playbackRate, clock) {
        publish(clock.accept(currentPosition, System.nanoTime(), running = drive))
    }
    LaunchedEffect(drive, playbackRate, clock) {
        // 墙钟在不播放时也以低频推进，供退场动画（float 回落 / 重音收尾）在暂停瞬间收完
        while (true) {
            withFrameNanos { nanos ->
                wallState.longValue = nanos / 1_000_000L
                if (drive) publish(clock.frame(nanos, playbackRate))
            }
            if (!drive) kotlinx.coroutines.delay(48)
        }
    }
    val frameClock = remember(positionState, wallState) { LyricFrameClock(positionState, wallState) }
    val match: LyricPlayerMatch = matchState.value

    LyricViewport(
        lines = lyrics,
        isSynced = isSynced,
        match = match,
        clock = frameClock,
        isPlaying = isPlaying,
        styles = styles,
        config = config,
        showTranslation = showTranslation,
        showRomanization = showRomanization,
        showPhonetic = showPhonetic,
        hasCharLyrics = hasCharLyrics,
        credits = credits,
        onLineClick = onLineClick,
        anchorFraction = anchorFraction,
        horizontalPadding = horizontalPadding,
        occludedBottomPx = occludedBottomPx,
        modifier = modifier,
    )
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
