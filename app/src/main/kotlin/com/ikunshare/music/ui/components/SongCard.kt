package com.ikunshare.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material3.Icon
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import com.ikunshare.music.R
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.neteaseCoverUrl
import com.ikunshare.music.ui.screens.player.formatDuration
import com.ikunshare.music.ui.theme.LocalUiColors
import com.kyant.shapes.RoundedRectangle

val LocalSongListOperationsVisible = staticCompositionLocalOf { true }
private val GLASS_CARD_SHAPE = RoundedRectangle(24.dp)
private val PLAIN_CARD_SHAPE = RoundedCornerShape(12.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: MusicItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    isPlaying: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onPlayClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val uiColors = LocalUiColors.current
    val useGlass = LocalLiquidGlassBackdrop.current != null
    val cardColor = if (selected) uiColors.selectionHighlight else uiColors.songCardColor
    val showPlayingIndicator = isPlaying && !selected
    val titleColor = if (showPlayingIndicator) MaterialTheme.colorScheme.tertiary
    else MaterialTheme.colorScheme.onSurface
    val sourceTag = remember(song.getTypeDiscriminator()) {
        MusicApplication.instance?.musicRepository?.getProvider(song.getTypeDiscriminator())?.shortTag
    }
    val artistAlbum = remember(song.artist, song.album) {
        buildArtistAlbumText(song.artist, song.album)
    }
    val durationText = remember(song.duration) {
        if (song.duration > 0) formatDuration(song.duration) else ""
    }
    val coverUrl = remember(song.cover) { neteaseCoverUrl(song.cover) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = if (useGlass) 5.dp else 4.dp)
            .then(if (useGlass) Modifier.liquidGlass(selected = selected || isPlaying) else Modifier),
        color = if (useGlass) Color.Transparent else cardColor,
        shape = if (useGlass) GLASS_CARD_SHAPE else PLAIN_CARD_SHAPE,
        shadowElevation = if (useGlass || selected || cardColor.alpha < 1f) 0.dp else 2.dp
    ) {
        BoxWithConstraints {
        val inlineActions = maxWidth >= 560.dp
        val showActions = LocalSongListOperationsVisible.current && !selectionMode &&
            onAddToPlaylistClick != null && onDownloadClick != null
        Column(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick?.let { callback ->
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            callback()
                        }
                    }
                )
                .padding(12.dp)
        ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (showPlayingIndicator) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .background(
                            color = MaterialTheme.colorScheme.tertiary,
                            shape = RoundedCornerShape(2.dp)
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            // 封面图片 / Checkbox
            if (selectionMode) {
                Box(
                    modifier = Modifier.size(56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onCheckedChange,
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.tertiary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            } else {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = song.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 歌曲信息
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sourceTag != null) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = sourceTag,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = artistAlbum,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 歌曲时长
            if (song.duration > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            if (showActions && inlineActions) {
                SongQuickActions(onPlayClick ?: onClick, onAddToPlaylistClick, onDownloadClick)
            }
        }
        if (showActions && !inlineActions) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SongQuickActions(onPlayClick ?: onClick, onAddToPlaylistClick, onDownloadClick)
            }
        }
        }

        }
    }
}

internal fun buildArtistAlbumText(artist: String, album: String): String {
    return when {
        artist.isNotBlank() && album.isNotBlank() -> "$artist · $album"
        artist.isNotBlank() -> artist
        album.isNotBlank() -> album
        else -> ""
    }
}


@Composable
private fun SongQuickActions(onPlay: () -> Unit, onAdd: () -> Unit, onDownload: () -> Unit) {
    Row {
        GlassIconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, stringResource(R.string.play_song)) }
        GlassIconButton(onClick = onAdd) { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, stringResource(R.string.add_to_playlist)) }
        GlassIconButton(onClick = onDownload) { Icon(Icons.Filled.Download, stringResource(R.string.download)) }
    }
}
