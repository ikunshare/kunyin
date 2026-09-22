package com.ikunshare.sound.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpOffset
import com.ikunshare.sound.R
import com.ikunshare.sound.model.MusicItem

/**
 * 歌曲长按上下文菜单。
 *
 * 传入 [mvSong] 即可自动启用"查看MV"功能：
 * 仅在 song 对应平台 provider 支持 MV 时显示菜单项，点击后内部自管理 [MvDialog]。
 */
@Composable
fun SongContextMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onMultiSelect: () -> Unit,
    offset: DpOffset = DpOffset.Zero,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = "",
    onReorder: (() -> Unit)? = null,
    onRefreshInfo: (() -> Unit)? = null,
    onRedirect: (() -> Unit)? = null,
    mvSong: MusicItem? = null
) {
    var showMvDialog by remember { mutableStateOf(false) }

    val mvSource = mvSong?.getTypeDiscriminator()
    val showMvEntry = mvSong != null && mvSource in setOf("qq", "wy", "kw", "kg") && run {
        val mvid = mvSong.mvid
        when (mvSource) {
            "kw" -> mvSong.id > 0
            else -> !mvid.isNullOrBlank() && mvid != "0"
        }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, offset = offset) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.play_song)) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); onPlay() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.play_next)) },
            leadingIcon = { Icon(Icons.Filled.Queue, contentDescription = null) },
            onClick = { onDismiss(); onPlayNext() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.download)) },
            leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
            onClick = { onDismiss(); onDownload() }
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (isFavorite) stringResource(R.string.unfavorite)
                    else stringResource(R.string.favorite)
                )
            },
            leadingIcon = {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null
                )
            },
            onClick = { onDismiss(); onToggleFavorite() }
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.add_to_playlist)) },
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null
                )
            },
            onClick = { onDismiss(); onAddToPlaylist() }
        )
        if (showMvEntry) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.view_mv)) },
                leadingIcon = { Icon(Icons.Filled.Movie, contentDescription = null) },
                onClick = {
                    onDismiss()
                    showMvDialog = true
                }
            )
        }
        if (onReorder != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reorder)) },
                leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                onClick = { onDismiss(); onReorder() }
            )
        }
        if (onRefreshInfo != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.refresh_song_info)) },
                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                onClick = { onDismiss(); onRefreshInfo() }
            )
        }
        if (onRedirect != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.redirect_song_title)) },
                leadingIcon = { Icon(Icons.Filled.SwapHoriz, contentDescription = null) },
                onClick = { onDismiss(); onRedirect() }
            )
        }
        if (onRemove != null) {
            DropdownMenuItem(
                text = { Text(removeLabel, color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                },
                onClick = { onDismiss(); onRemove() }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.multi_select)) },
            leadingIcon = { Icon(Icons.Filled.Checklist, contentDescription = null) },
            onClick = { onDismiss(); onMultiSelect() }
        )
    }

    if (showMvDialog && mvSong != null && !mvSource.isNullOrBlank()) {
        MvDialog(
            song = mvSong,
            sourceTag = mvSource,
            onDismiss = { showMvDialog = false }
        )
    }
}
