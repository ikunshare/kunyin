package com.ikunshare.music.ui.screens.player

import com.ikunshare.music.R
import androidx.compose.animation.AnimatedVisibility
import com.ikunshare.music.ui.theme.Motion
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.ikunshare.music.model.PlayMode
import com.ikunshare.music.model.neteaseCoverUrl
import com.ikunshare.music.model.qualityDisplayName
import com.ikunshare.music.ui.utils.onPointerCancel
import com.ikunshare.music.ui.screens.lyrics.LyricCredits
import com.ikunshare.music.ui.screens.lyrics.LyricsUiState
import com.ikunshare.music.ui.screens.lyrics.WebLyricsContent
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.Capsule

/** The desktop player adapted to actual available space rather than device orientation alone. */
@Composable
internal fun AdaptivePlayer(
    state: PlayerUiState, lyrics: LyricsUiState, position: Long, backdrop: Backdrop,
    timerActive: Boolean, coverRounded: Boolean, onClose: () -> Unit, onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit,
    onVolume: (Float) -> Unit, onMute: () -> Unit, onSpeed: (Float) -> Unit, onMode: () -> Unit,
    onFavorite: () -> Unit, onMore: () -> Unit, onQuality: () -> Unit,
    onQueue: () -> Unit, onTimer: () -> Unit,
    onTranslation: () -> Unit, onRomanization: () -> Unit, onPhonetic: () -> Unit,
    onRetryLyrics: () -> Unit,
    initialLyricsVisible: Boolean = false,
    keepControlsVisible: Boolean = false,
    preservePitch: Boolean = true,
    onPreservePitch: () -> Unit = {}
) {
    val pager = rememberPagerState(initialPage = if (initialLyricsVisible) 1 else 0, pageCount = { 2 })
    val density = LocalDensity.current
    var portraitControlsHeight by remember(density) { mutableIntStateOf(with(density) { 208.dp.roundToPx() }) }
    // Stable insets: hiding the system bars while presented must not shift the layout.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(PlayerInsets)) {
        val wide = maxWidth >= 600.dp && maxWidth > maxHeight
        val autoHideEnabled = !wide && pager.settledPage == 1 && !pager.isScrollInProgress
        val controls = rememberPlayerControlsVisibility(state.currentSong?.uniqueKey, keepControlsVisible, autoHideEnabled)
        val controlsVisible = !autoHideEnabled || controls.visible
        val revealLabel = stringResource(R.string.show_playback_controls)
        val revealAction = if (controlsVisible) Modifier else Modifier.semantics {
            onClick(label = revealLabel) { controls.show(); true }
        }
        // Portrait: leave room for the measured controls plus the title block below the cover.
        val portraitControls = with(density) { portraitControlsHeight.toDp() }
        val coverSize = (maxWidth - 72.dp).coerceAtMost((maxHeight - portraitControls - 120.dp).coerceAtLeast(112.dp))
            .coerceAtLeast(0.dp)
        val pageHeight = maxHeight
        Column(Modifier.fillMaxSize().playerControlsGestures(controls, autoHideEnabled).then(revealAction)
            .padding(horizontal = if (wide) 32.dp else 24.dp)) {
            if (wide) Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerIcon(Icons.Default.KeyboardArrowDown, stringResource(R.string.back), backdrop, onClick = onClose)
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    BoxWithConstraints(Modifier.weight(0.42f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        val paneControls: @Composable () -> Unit = {
                            PlayerControlsTransition(controlsVisible) {
                                Column {
                                    Spacer(Modifier.height(20.dp))
                                    PlayerControls(state, lyrics, position, backdrop, timerActive,
                                        onSeek, onPlayPause, onPrevious, onNext, onVolume, onMute, onSpeed, onMode,
                                        onQuality, onQueue, onTimer, onTranslation, onRomanization, onPhonetic,
                                        onSpeedMenu = { controls.menuOpen = it; controls.show() },
                                        preservePitch = preservePitch, onPreservePitch = onPreservePitch)
                                }
                            }
                        }
                        if (maxHeight >= WIDE_COVER_MIN_PANE_HEIGHT) {
                            val paneWidth = maxWidth
                            val paneHeight = maxHeight
                            Column(Modifier.fillMaxSize().padding(vertical = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                    PlayerCover(state.currentSong?.cover,
                                        Modifier.sizeIn(maxWidth = paneWidth, maxHeight = paneHeight)
                                            .aspectRatio(1f, matchHeightConstraintsFirst = true), coverRounded)
                                }
                                Spacer(Modifier.height(24.dp))
                                PlayerTrackInfo(state, backdrop, onFavorite, onMore)
                                paneControls()
                            }
                        } else {
                            // Too short for a hero cover: thumbnail beside the title, scrolling only if needed.
                            Column(Modifier.widthIn(max = 380.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                                .padding(vertical = 8.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PlayerCover(state.currentSong?.cover, Modifier.size(56.dp), coverRounded)
                                    Box(Modifier.weight(1f)) {
                                        PlayerTrackInfo(state, backdrop, onFavorite, onMore, compact = true)
                                    }
                                }
                                paneControls()
                            }
                        }
                    }
                    PlayerLyricsPane(lyrics, state, position, onSeek, onRetryLyrics,
                        Modifier.weight(0.58f).fillMaxHeight())
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(),
                        key = { it }) { page ->
                        if (page == 1) {
                            Column(Modifier.fillMaxSize()) {
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    PlayerCover(state.currentSong?.cover, Modifier.size(52.dp), coverRounded)
                                    Box(Modifier.weight(1f)) { PlayerTrackInfo(state, backdrop, onFavorite, onMore, compact = true) }
                                }
                                PlayerLyricsPane(lyrics, state, position, onSeek, onRetryLyrics,
                                    Modifier.weight(1f), isVisible = pager.currentPage == 1,
                                    occludedBottomPx = if (controlsVisible) portraitControlsHeight else 0)
                            }
                        } else {
                            // Cover sits in the upper half: the space left after the cover and
                            // title is split ~1:2 above and below instead of dead center.
                            val coverTopGap = (pageHeight - portraitControls - coverSize - 84.dp)
                                .times(0.32f).coerceAtLeast(8.dp)
                            Column(Modifier.fillMaxSize().padding(bottom = portraitControls)
                                .verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Spacer(Modifier.height(coverTopGap))
                                PlayerCover(state.currentSong?.cover, Modifier.size(coverSize), coverRounded)
                                Spacer(Modifier.height(24.dp))
                                PlayerTrackInfo(state, backdrop, onFavorite, onMore)
                            }
                        }
                    }
                    PlayerControlsTransition(controlsVisible, Modifier.align(Alignment.BottomCenter)
                        .onSizeChanged { if (it.height > 0) portraitControlsHeight = it.height }) {
                        PlayerControls(state, lyrics, position, backdrop, timerActive,
                            onSeek, onPlayPause, onPrevious, onNext, onVolume, onMute, onSpeed, onMode,
                            onQuality, onQueue, onTimer, onTranslation, onRomanization, onPhonetic,
                            onSpeedMenu = { controls.menuOpen = it; controls.show() },
                            preservePitch = preservePitch, onPreservePitch = onPreservePitch)
                    }
                }
            }
        }
    }
}

/** Below this the wide pane cannot fit a useful cover above the title and controls. */
private val WIDE_COVER_MIN_PANE_HEIGHT = 520.dp

@OptIn(ExperimentalLayoutApi::class)
private val PlayerInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout)

@Composable
private fun PlayerControlsTransition(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    AnimatedVisibility(visible = visible, modifier = modifier,
        enter = fadeIn(Motion.fadeIn(200)) + slideInVertically(Motion.smoothOffset) { it / 4 },
        exit = fadeOut(Motion.fadeOut(180)) + slideOutVertically(Motion.smoothOffset) { it / 4 }) {
        content()
    }
}

internal fun playerCoverCornerRadius(rounded: Boolean) = if (rounded) 14.dp else 0.dp

@Composable
private fun PlayerCover(cover: String?, modifier: Modifier, coverRounded: Boolean) {
    Box(modifier.clip(RoundedCornerShape(playerCoverCornerRadius(coverRounded)))
        .background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        if (!cover.isNullOrBlank()) AsyncImage(model = neteaseCoverUrl(cover), contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.MusicNote, null, tint = Color.White.copy(alpha = 0.45f), modifier = Modifier.size(56.dp))
    }
}

@Composable
private fun PlayerTrackInfo(state: PlayerUiState, backdrop: Backdrop, onFavorite: () -> Unit,
                            onMore: () -> Unit, compact: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(state.currentSong?.title ?: stringResource(R.string.no_song_playing), color = Color.White,
                fontSize = if (compact) 16.sp else 20.sp, fontWeight = FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
            Text(state.currentSong?.artist.orEmpty(), color = Color.White.copy(alpha = 0.58f),
                fontSize = if (compact) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        PlayerIcon(if (state.isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            stringResource(R.string.favorite), backdrop, selected = state.isFavorite, onClick = onFavorite)
        PlayerIcon(Icons.Default.MoreHoriz, stringResource(R.string.more_actions), backdrop, onClick = onMore)
    }
}

@Composable
private fun PlayerLyricsPane(lyrics: LyricsUiState, state: PlayerUiState, position: Long,
                            onSeek: (Long) -> Unit, onRetry: () -> Unit, modifier: Modifier,
                            isVisible: Boolean = true, occludedBottomPx: Int = 0) {
    Column(modifier) {
        when {
            lyrics.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
            }
            lyrics.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(lyrics.error, color = Color.White.copy(alpha = 0.65f))
                TextButton(onClick = onRetry) { Text(stringResource(R.string.retry), color = Color.White) }
            }
            lyrics.parsedLyrics.isEmpty() -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp), verticalArrangement = Arrangement.Center) {
                Text(if (lyrics.credits.isEmpty()) stringResource(R.string.no_lyrics) else stringResource(R.string.instrumental),
                    color = Color.White.copy(alpha = 0.75f), fontSize = 20.sp)
                LyricCredits(lyrics.credits)
            }
            else -> WebLyricsContent(lyrics.parsedLyrics, position, state.isPlaying && isVisible,
                lyrics.showTranslation, lyrics.showRomanization, lyrics.showPhonetic,
                lyrics.hasCharLyrics, lyrics.isSynced, onSeek, modifier = Modifier.weight(1f),
                horizontalPadding = 4.dp, credits = lyrics.credits, playbackRate = state.playbackSpeed,
                occludedBottomPx = occludedBottomPx)
        }
    }
}

@Composable
private fun LyricLanguageButton(icon: ImageVector, label: String, selected: Boolean,
                               backdrop: Backdrop, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(36.dp)
        .semantics { this.selected = selected }) {
        Icon(icon, contentDescription = label,
            tint = Color.White.copy(alpha = if (selected) 1f else 0.5f), modifier = Modifier.size(20.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlayerControls(state: PlayerUiState, lyrics: LyricsUiState, position: Long, backdrop: Backdrop, timer: Boolean,
    onSeek: (Long) -> Unit, onPlayPause: () -> Unit, onPrevious: () -> Unit,
    onNext: () -> Unit, onVolume: (Float) -> Unit, onMute: () -> Unit, onSpeed: (Float) -> Unit, onMode: () -> Unit,
    onQuality: () -> Unit, onQueue: () -> Unit, onTimer: () -> Unit,
    onTranslation: () -> Unit, onRomanization: () -> Unit, onPhonetic: () -> Unit,
    onSpeedMenu: (Boolean) -> Unit, preservePitch: Boolean, onPreservePitch: () -> Unit) {
    var speedMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().pointerInput(Unit) { detectTapGestures(onTap = { }) }
        .padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PlayerSeek(position, state.duration, state.currentSong?.uniqueKey,
            state.currentQuality?.let { qualityDisplayName(it.id, state.currentSong?.getTypeDiscriminator()) },
            state.availableQualities.isNotEmpty(), onSeek, onQuality)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly) {
            PlayerIcon(Icons.Default.SkipPrevious, stringResource(R.string.previous), backdrop, onClick = onPrevious, large = true)
            IconButton(onClick = onPlayPause, enabled = state.currentSong != null,
                modifier = Modifier.size(56.dp)) {
                if (state.isLoading) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (state.isPlaying) R.string.pause else R.string.play), tint = Color.White, modifier = Modifier.size(32.dp))
            }
            PlayerIcon(Icons.Default.SkipNext, stringResource(R.string.next), backdrop, onClick = onNext, large = true)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onMute, modifier = Modifier.size(32.dp)) {
                Icon(
                    if (state.muted || state.volume == 0.0f) Icons.AutoMirrored.Filled.VolumeOff
                    else Icons.AutoMirrored.Filled.VolumeDown,
                    stringResource(if (state.muted) R.string.unmute else R.string.mute),
                    tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp)
                )
            }
            PlayerTrackSlider(if (state.muted) 0.0f else state.volume, onVolume,
                Modifier.weight(1f).padding(horizontal = 8.dp))
            Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
        }
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp), maxItemsInEachRow = 6) {
            val modeIcon = when (state.playMode) {
                PlayMode.SHUFFLE -> Icons.Default.Shuffle
                PlayMode.SINGLE_LOOP -> Icons.Default.RepeatOne
                PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Filled.QueueMusic
                PlayMode.LIST_LOOP -> Icons.Default.Repeat
                PlayMode.SINGLE_PLAY -> Icons.Default.LooksOne
            }
            PlayerIcon(modeIcon, state.playMode.label, backdrop, onClick = onMode,
                selected = state.playMode != PlayMode.SEQUENTIAL)
            Box {
                TextButton(onClick = { speedMenu = true; onSpeedMenu(true) }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(36.dp)) {
                    Text("${state.playbackSpeed}×", color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp)
                }
                DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false; onSpeedMenu(false) }) {
                    listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).forEach { speed ->
                        DropdownMenuItem(text = { Text("${speed}×") }, onClick = { onSpeed(speed); speedMenu = false; onSpeedMenu(false) })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.preserve_pitch))
                                if (!preservePitch) {
                                    Text(
                                        stringResource(R.string.preserve_pitch_off_hint),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            Checkbox(checked = preservePitch, onCheckedChange = { onPreservePitch() })
                        },
                        onClick = onPreservePitch
                    )
                }
            }
            if (lyrics.hasTranslation) LyricLanguageButton(PlayerLyricIcons.Translation, stringResource(R.string.sub_line_translation),
                lyrics.showTranslation, backdrop, onTranslation)
            if (lyrics.hasRomanization) LyricLanguageButton(PlayerLyricIcons.Romanization, stringResource(R.string.sub_line_romanization),
                lyrics.showRomanization, backdrop, onRomanization)
            if (lyrics.hasPhonetic) LyricLanguageButton(PlayerLyricIcons.Phonetic, stringResource(R.string.show_phonetic),
                lyrics.showPhonetic, backdrop, onPhonetic)
            PlayerIcon(Icons.Default.Timer, stringResource(R.string.sleep_timer), backdrop, selected = timer, onClick = onTimer)
            PlayerIcon(Icons.AutoMirrored.Filled.QueueMusic, stringResource(R.string.play_queue), backdrop, onClick = onQueue)
        }
    }
}

@Composable
private fun PlayerSeek(position: Long, duration: Long, songKey: String?, quality: String?,
                       hasQuality: Boolean, onSeek: (Long) -> Unit, onQuality: () -> Unit) {
    var preview by remember(songKey) { mutableStateOf<Float?>(null) }
    val fraction = preview ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = preview?.let { (it * duration).toLong() } ?: position
    Column {
        PlayerTrackSlider(
            fraction, { preview = it },
            Modifier.fillMaxWidth().onPointerCancel { preview = null },
            enabled = duration > 0,
            onFinished = { preview?.let { onSeek((it * duration).toLong()) }; preview = null }
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(shown), color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, modifier = Modifier.weight(1f))
            if (hasQuality) Text(quality.orEmpty(), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp,
                modifier = Modifier.clickable(onClick = onQuality).padding(horizontal = 8.dp, vertical = 4.dp))
            Text("-${formatDuration((duration - shown).coerceAtLeast(0L))}", color = Color.White.copy(alpha = 0.55f),
                fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerTrackSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier,
                              enabled: Boolean = true, onFinished: (() -> Unit)? = null) {
    Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onFinished,
        enabled = enabled, modifier = modifier.height(28.dp),
        thumb = { Box(Modifier.size(12.dp).clip(CircleShape).background(Color.White)) },
        track = { slider ->
            Box(Modifier.fillMaxWidth().height(4.dp).clip(Capsule()).background(Color.White.copy(alpha = 0.22f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(slider.value.coerceIn(0f, 1f)).background(Color.White.copy(alpha = 0.85f)))
            }
        })
}

@Composable
private fun PlayerIcon(icon: ImageVector, label: String, backdrop: Backdrop, selected: Boolean = false,
                       onClick: () -> Unit, large: Boolean = false) {
    IconButton(onClick = onClick, modifier = Modifier.size(if (large) 44.dp else 36.dp)) {
        Icon(icon, label, tint = Color.White.copy(alpha = if (selected || large) 1f else 0.72f),
            modifier = Modifier.size(if (large) 26.dp else 20.dp))
    }
}
