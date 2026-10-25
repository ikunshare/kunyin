package com.ikunshare.music.ui.screens.player

import android.annotation.SuppressLint
import com.ikunshare.music.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikunshare.music.ui.components.GlassDialog
import com.ikunshare.music.ui.components.glassDialogSurface
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import com.ikunshare.music.manager.comments.CommentItem
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.ui.components.playlistCoverUrl

internal data class CommentRow(val item: CommentItem, val depth: Int)

/** Preorder without recursive composition: long reply chains remain lazy and cannot exhaust the stack. */
internal fun commentRows(comments: List<CommentItem>): List<CommentRow> {
    val pending = ArrayDeque<CommentRow>()
    comments.asReversed().forEach { pending.addLast(CommentRow(it, 0)) }
    return buildList {
        while (pending.isNotEmpty()) {
            val row = pending.removeLast()
            add(row)
            row.item.reply.asReversed().forEach { pending.addLast(CommentRow(it, row.depth + 1)) }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
internal fun CommentDialog(song: MusicItem?, viewModel: CommentsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(song?.uniqueKey) { viewModel.setTrack(song) }
    DisposableEffect(viewModel) { onDispose { viewModel.close() } }
    val listState = rememberLazyListState()
    val active = state.active
    LaunchedEffect(song?.uniqueKey, state.hotSelected, active.revision) { listState.scrollToItem(0) }
    val rows = remember(active.comments) { commentRows(active.comments) }
    val brokenAvatars = remember(song?.uniqueKey) { mutableStateMapOf<String, Boolean>() }
    val dialogHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.82f).coerceAtMost(640.dp)
    GlassDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.widthIn(max = 752.dp).fillMaxWidth().safeDrawingPadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().height(dialogHeight).glassDialogSurface(24.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(song?.title.orEmpty().ifEmpty { stringResource(R.string.song_comments) },
                                style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!song?.artist.isNullOrEmpty()) Text(
                                song.artist, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, stringResource(R.string.comments_refresh)) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.comments_close)) }
                    }
                    if (!state.supported) Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
                        Text(stringResource(R.string.comments_unsupported))
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(state.hotSelected, { viewModel.selectTab(true) },
                                label = { Text(stringResource(R.string.comments_hot, state.hot.total)) })
                            FilterChip(!state.hotSelected, { viewModel.selectTab(false) },
                                label = { Text(stringResource(R.string.comments_latest, state.latest.total)) })
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when {
                                active.error -> TextButton(onClick = viewModel::retry, modifier = Modifier.align(Alignment.Center)) {
                                    Text(stringResource(R.string.comments_retry))
                                }
                                active.loading && rows.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                                rows.isEmpty() -> Text(stringResource(R.string.comments_empty), Modifier.align(Alignment.Center))
                                else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize().alpha(if (active.loading) 0.4f else 1f)) {
                                    itemsIndexed(rows) { index, row ->
                                        if (index > 0 && row.depth == 0) HorizontalDivider()
                                        CommentContent(row, brokenAvatars)
                                    }
                                }
                            }
                        }
                        if (active.maxPage > 1) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                commentPages(active.page, active.maxPage).forEach { page ->
                                    TextButton(onClick = { viewModel.goPage(page) }, enabled = !active.loading,
                                        modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp),
                                        colors = ButtonDefaults.textButtonColors(contentColor = if (page == active.page)
                                            MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) { Text(page.toString()) }
                                }
                            }
                            Text(stringResource(R.string.comments_pages, active.page, active.maxPage),
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.CenterHorizontally))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CommentContent(row: CommentRow, broken: MutableMap<String, Boolean>) {
    val item = row.item
    val avatar = remember(item.avatar) { playlistCoverUrl(item.avatar) }
    Row(Modifier.padding(start = (row.depth.coerceAtMost(4) * 12).dp)
        .background(if (row.depth > 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)).padding(vertical = 12.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
            if (avatar.isNotEmpty() && broken[avatar] != true) AsyncImage(avatar, null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, onError = { broken[avatar] = true })
            else Text(item.userName.take(1).uppercase().ifEmpty { "?" })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.userName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                item.likedCount?.let { Text(stringResource(R.string.comments_likes, it), style = MaterialTheme.typography.labelSmall) }
            }
            val metadata = listOfNotNull(item.timeStr.takeIf { it.isNotEmpty() }, item.location?.takeIf { it.isNotEmpty() })
            if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (item.text.isNotEmpty()) Text(item.text, style = MaterialTheme.typography.bodyMedium)
            if (item.images.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item.images.forEach { url -> AsyncImage(playlistCoverUrl(url), stringResource(R.string.comments_image),
                    Modifier.size(120.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Fit) }
            }
        }
    }
}
