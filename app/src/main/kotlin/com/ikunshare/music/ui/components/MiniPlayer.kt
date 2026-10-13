package com.ikunshare.music.ui.components

import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.ikunshare.music.ui.theme.Motion
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ikunshare.music.R
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.neteaseCoverUrl
import com.kyant.backdrop.Backdrop

@Composable
fun MiniPlayer(
    song: MusicItem?,
    isPlaying: Boolean,
    isLoading: Boolean,
    progress: () -> Float,
    currentLyricText: String,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.surface
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = barColor,
        tonalElevation = if (barColor.alpha < 1f) 0.dp else 4.dp
    ) {
        Column {
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier
                    .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = neteaseCoverUrl(song?.cover),
                    contentDescription = song?.title,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // 标题 - 歌手
                    Text(
                        text = buildAnnotatedString {
                            append(song?.title ?: "")
                            if (song != null && !song.artist.isNullOrEmpty()) {
                                withStyle(
                                    SpanStyle(
                                        color = MaterialTheme.colorScheme.onSurface.copy(
                                            alpha = 0.6f
                                        )
                                    )
                                ) {
                                    append(" - ${song.artist}")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 当前歌词
                    Text(
                        text = currentLyricText.ifEmpty { song?.album ?: "" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // 上一曲
                IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(R.string.previous_track),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }

                // 播放/暂停
                IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.tertiary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(
                                R.string.play
                            ),
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                // 下一曲
                IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(R.string.next_track),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun LiquidGlassMiniPlayer(
    song: MusicItem?,
    isPlaying: Boolean,
    isLoading: Boolean,
    progress: () -> Float,
    currentLyricText: String,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.surface,
    surfaceColor: Color = if (!isSystemInDarkTheme()) Color.White.copy(0.3f) else Color.Black.copy(
        0.15f
    ),
    backdrop: Backdrop,
    height: Dp = 64.dp,
    isWideScreen: Boolean = false,
    compactMode: Boolean = false,
    compactIcon: ImageVector? = null,
    compactLabel: String? = null,
    onCompactExit: (() -> Unit)? = null,
    enableBlur: Boolean = true,
) {
    val effectiveSurfaceColor =
        if (enableBlur) surfaceColor else MaterialTheme.colorScheme.surfaceContainer

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LiquidButton(
            onClick = onClick,
            backdrop = backdrop,
            surfaceColor = effectiveSurfaceColor,
            progress = progress,
            progressColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f),
            height = height,
            enableBlur = enableBlur,
            modifier = Modifier
                .padding(
                    // 12 + 8 + 56 + 8 + 16 + 280 + 16 + 36 - 16
                    start = if (isWideScreen) 416.dp else 36.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                    end = if (compactMode) 8.dp else 36.dp
                )
                .weight(1f)
        ) {
            // 歌曲信息内容层
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = neteaseCoverUrl(song?.cover),
                        contentDescription = song?.title,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        // 标题 - 歌手
                        Text(
                            text = buildAnnotatedString {
                                append(song?.title ?: "")
                                if (song != null && !song.artist.isNullOrEmpty()) {
                                    withStyle(
                                        SpanStyle(
                                            color = MaterialTheme.colorScheme.onSurface.copy(
                                                alpha = 0.6f
                                            )
                                        )
                                    ) {
                                        append(" - ${song.artist}")
                                    }
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        // 当前歌词
                        Text(
                            text = currentLyricText.ifEmpty { song?.album ?: "" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // 上一曲 - 带动画
                    AnimatedVisibility(
                        visible = (!compactMode) || isWideScreen,
                        enter = fadeIn(Motion.fadeIn()) + expandHorizontally(Motion.smoothSize, expandFrom = Alignment.End),
                        exit = fadeOut(Motion.fadeOut()) + shrinkHorizontally(Motion.smoothSize, shrinkTowards = Alignment.End)
                    ) {
                        IconButton(onClick = onPrevious, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Filled.SkipPrevious,
                                contentDescription = stringResource(R.string.previous_track),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    // 播放/暂停
                    IconButton(onClick = onPlayPause, modifier = Modifier.size(36.dp)) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.tertiary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(
                                    R.string.play
                                ),
                                tint = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }

                    // 下一曲 - 带动画
                    AnimatedVisibility(
                        visible = (!compactMode) || isWideScreen,
                        enter = fadeIn(Motion.fadeIn()) + expandHorizontally(Motion.smoothSize, expandFrom = Alignment.Start),
                        exit = fadeOut(Motion.fadeOut()) + shrinkHorizontally(Motion.smoothSize, shrinkTowards = Alignment.Start)
                    ) {
                        IconButton(onClick = onNext, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = stringResource(R.string.next_track),
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // 紧凑模式退出按钮 - 带动画
        // Keep the last tab while the button animates out; the caller clears it immediately.
        val lastCompactIcon = remember { mutableStateOf<ImageVector?>(null) }
        val lastCompactExit = remember { mutableStateOf<(() -> Unit)?>(null) }
        if (compactIcon != null) lastCompactIcon.value = compactIcon
        if (onCompactExit != null) lastCompactExit.value = onCompactExit
        AnimatedVisibility(
            visible = compactMode && compactIcon != null && onCompactExit != null,
            enter = fadeIn(Motion.fadeIn()) + scaleIn(Motion.snappy(), initialScale = 0.6f) +
                expandHorizontally(Motion.smoothSize, expandFrom = Alignment.Start),
            exit = fadeOut(Motion.fadeOut()) + scaleOut(Motion.snappy(), targetScale = 0.6f) +
                shrinkHorizontally(Motion.smoothSize, shrinkTowards = Alignment.Start)
        ) {
            LiquidButton(
                onClick = { lastCompactExit.value?.invoke() },
                backdrop = backdrop,
                surfaceColor = effectiveSurfaceColor,
                height = height,
                enableBlur = enableBlur,
                modifier = Modifier
                    .padding(end = 36.dp, top = 8.dp, bottom = 8.dp, start = 8.dp)
                    .width(height)
            ) {
                Icon(
                    imageVector = lastCompactIcon.value ?: return@LiquidButton,
                    contentDescription = compactLabel,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
