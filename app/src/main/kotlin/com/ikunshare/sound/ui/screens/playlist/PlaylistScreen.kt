package com.ikunshare.sound.ui.screens.playlist

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.AddToPlaylistDialog
import com.ikunshare.sound.ui.components.CreatePlaylistDialog
import com.ikunshare.sound.ui.components.DownloadQualityDialog
import com.ikunshare.sound.ui.components.EmptyState
import com.ikunshare.sound.ui.components.ErrorState
import com.ikunshare.sound.ui.components.LoadingIndicator
import com.ikunshare.sound.ui.components.SelectionBottomBar
import com.ikunshare.sound.ui.components.SongCard
import com.ikunshare.sound.ui.components.SongContextMenu
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    navController: NavController,
    playlistId: String,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: PlaylistViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 多选状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<MusicItem>() }

    // 多选时返回键退出多选
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedSongs.clear()
    }

    // 添加到歌单对话框
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    val playlists by viewModelFactory.localMusicStore.playlistsFlow.collectAsState()

    // 长按上下文菜单
    var contextMenuSong by remember { mutableStateOf<MusicItem?>(null) }
    var singleDownloadSong by remember { mutableStateOf<MusicItem?>(null) }
    var singlePlaylistSong by remember { mutableStateOf<MusicItem?>(null) }

    fun exitSelectionMode() {
        selectionMode = false
        selectedSongs.clear()
    }

    fun toggleSelect(song: MusicItem) {
        if (selectedSongs.any { it.id == song.id }) {
            selectedSongs.removeAll { it.id == song.id }
            if (selectedSongs.isEmpty()) selectionMode = false
        } else {
            selectedSongs.add(song)
        }
    }

    fun enterSelection(song: MusicItem) {
        selectionMode = true
        selectedSongs.clear()
        selectedSongs.add(song)
    }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylist(playlistId)
    }

    var skippedToastShown by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(uiState.hasSkippedLocalSongs) {
        if (uiState.hasSkippedLocalSongs && !skippedToastShown) {
            skippedToastShown = true
            Toast.makeText(
                context,
                context.getString(R.string.skipped_local_songs_toast),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = { Text(stringResource(R.string.selected_count, selectedSongs.size)) },
                    navigationIcon = {
                        IconButton(onClick = ::exitSelectionMode) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.cancel_selection)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (selectedSongs.size == uiState.songs.size) {
                                selectedSongs.clear()
                            } else {
                                selectedSongs.clear()
                                selectedSongs.addAll(uiState.songs)
                            }
                        }) {
                            Icon(
                                Icons.Filled.SelectAll,
                                contentDescription = stringResource(R.string.select_all)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            } else {
                PlaylistTopBar(
                    title = uiState.playlistInfo?.title ?: "",
                    onBackClick = { navController.popBackStack() },
                    isOnlinePlaylist = uiState.isOnlinePlaylist,
                    isBookmarked = uiState.isBookmarked,
                    onBookmark = { viewModel.saveToLocal() }
                )
            }
        },
        bottomBar = {
            if (selectionMode && selectedSongs.isNotEmpty()) {
                val appSettings by viewModelFactory.appSettingsManager.settings.collectAsState()
                val onRemoveSelected: (() -> Unit)? =
                    if (uiState.isBookmarked && uiState.isOnlinePlaylist) {
                        {
                            val store = viewModelFactory.localMusicStore
                            val colonIndex = playlistId.indexOf(':')
                            val src =
                                if (colonIndex > 0) playlistId.substring(0, colonIndex) else null
                            val rid =
                                if (colonIndex > 0) playlistId.substring(colonIndex + 1) else null
                            val localPlaylist = if (src != null && rid != null) {
                                store.getPlaylistByRemoteId(src, rid)
                            } else null
                            if (localPlaylist != null) {
                                selectedSongs.forEach { song ->
                                    store.removeFromPlaylist(localPlaylist.id, song)
                                }
                                viewModel.removeSongsLocally(selectedSongs.map { it.id }.toSet())
                                Toast.makeText(
                                    context,
                                    context.getString(
                                        R.string.removed_from_playlist_count,
                                        selectedSongs.size
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            exitSelectionMode()
                        }
                    } else null
                SelectionBottomBar(
                    selectedCount = selectedSongs.size,
                    onPlayNext = {
                        playerViewModel.addToPlayNext(selectedSongs.toList())
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.added_to_play_next_count,
                                selectedSongs.size
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        exitSelectionMode()
                    },
                    onDownload = {
                        showQualityPicker = true
                    },
                    onAddToPlaylist = {
                        showPlaylistPicker = true
                    },
                    onRemove = onRemoveSelected,
                    removeLabel = stringResource(R.string.remove_from_playlist),
                    floatingBottomBar = appSettings.floatingBottomBar,
                    liquidGlass = appSettings.liquidGlassMode
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        when {
            uiState.isLoading && uiState.songs.isEmpty() && uiState.playlistInfo == null -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }

            uiState.error != null && uiState.songs.isEmpty() && uiState.playlistInfo == null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.unknown_error),
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(padding)
                )
            }

            else -> {
                val configuration = LocalConfiguration.current
                val isWideLayout =
                    configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                            || configuration.screenWidthDp >= 600
                val playerUiState by playerViewModel.uiState.collectAsState()
                val currentSong = playerUiState.currentSong

                val onSongClick: (MusicItem) -> Unit = { song ->
                    if (selectionMode) {
                        toggleSelect(song)
                    } else {
                        val songs = uiState.songs
                        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(songs, index)
                        if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                            navController.navigate(Screen.Player.createRoute(song.id))
                        }
                    }
                }

                val onLongClick: (MusicItem) -> Unit = { song ->
                    if (selectionMode) toggleSelect(song)
                    else contextMenuSong = song
                }

                val contextMenuHandlers = ContextMenuHandlers(
                    contextMenuSong = contextMenuSong,
                    onContextMenuDismiss = { contextMenuSong = null },
                    onContextPlay = { song ->
                        val index = uiState.songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(uiState.songs, index)
                    },
                    onContextPlayNext = { song ->
                        playerViewModel.addToPlayNext(song)
                        Toast.makeText(
                            context,
                            context.getString(R.string.added_to_play_next),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onContextDownload = { song -> singleDownloadSong = song },
                    onContextToggleFavorite = { song ->
                        val store = viewModelFactory.localMusicStore
                        if (store.isFavorite(song)) store.removeFavorite(song) else store.addFavorite(
                            song
                        )
                    },
                    onContextAddToPlaylist = { song -> singlePlaylistSong = song },
                    onContextMultiSelect = { song -> enterSelection(song) },
                    isFavorite = { song -> viewModelFactory.localMusicStore.isFavorite(song) }
                )

                if (isWideLayout) {
                    WidePlaylistContent(
                        uiState = uiState,
                        onPlayAll = {
                            val songs = viewModel.playAll()
                            if (!songs.isNullOrEmpty()) {
                                playerViewModel.setPlaylist(songs, 0)
                                navController.navigate(Screen.Player.createRoute(songs.first().id))
                            }
                        },
                        onSongClick = onSongClick,
                        onLoadMore = viewModel::loadMore,
                        onRefresh = { viewModel.loadPlaylist(playlistId, forceRefresh = true) },
                        selectionMode = selectionMode,
                        selectedSongs = selectedSongs,
                        currentSong = currentSong,
                        onLongClick = onLongClick,
                        onCheckedChange = { song -> toggleSelect(song) },
                        contextMenu = contextMenuHandlers,
                        modifier = Modifier.padding(padding)
                    )
                } else {
                    NarrowPlaylistContent(
                        uiState = uiState,
                        onPlayAll = {
                            val songs = viewModel.playAll()
                            if (!songs.isNullOrEmpty()) {
                                playerViewModel.setPlaylist(songs, 0)
                                navController.navigate(Screen.Player.createRoute(songs.first().id))
                            }
                        },
                        onSongClick = onSongClick,
                        onLoadMore = viewModel::loadMore,
                        onRefresh = { viewModel.loadPlaylist(playlistId, forceRefresh = true) },
                        selectionMode = selectionMode,
                        selectedSongs = selectedSongs,
                        currentSong = currentSong,
                        onLongClick = onLongClick,
                        onCheckedChange = { song -> toggleSelect(song) },
                        contextMenu = contextMenuHandlers,
                        modifier = Modifier.padding(padding)
                    )
                }
            }
        }
    }

    // 添加到歌单对话框
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistClick = { pid ->
                selectedSongs.forEach { song ->
                    viewModelFactory.localMusicStore.addToPlaylist(pid, song)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_playlist_count, selectedSongs.size),
                    Toast.LENGTH_SHORT
                ).show()
                showPlaylistPicker = false
                exitSelectionMode()
            },
            onCreateNew = {
                showPlaylistPicker = false
                showCreateDialog = true
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false; singlePlaylistSong = null },
            onConfirm = { name ->
                val store = viewModelFactory.localMusicStore
                val single = singlePlaylistSong
                if (single != null) {
                    store.createPlaylistAndAdd(name, single)
                    Toast.makeText(
                        context,
                        context.getString(R.string.created_playlist_added_count, 1),
                        Toast.LENGTH_SHORT
                    ).show()
                    singlePlaylistSong = null
                } else {
                    val songs = selectedSongs.toList()
                    if (songs.isNotEmpty()) {
                        val pid = store.createPlaylistAndAdd(name, songs.first())
                        songs.drop(1).forEach { store.addToPlaylist(pid, it) }
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.created_playlist_added_count, songs.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                }
                showCreateDialog = false
            }
        )
    }

    if (showQualityPicker) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities,
            onDismiss = { showQualityPicker = false },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                selectedSongs.forEach { song ->
                    viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                        song,
                        qualityId,
                        hideAi,
                        deferSchedule = true
                    )
                }
                viewModelFactory.downloadManager.flushSchedule()
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_download_queue_count, selectedSongs.size),
                    Toast.LENGTH_SHORT
                ).show()
                showQualityPicker = false
                exitSelectionMode()
            }
        )
    }

    // 单曲下载对话框（从上下文菜单触发）
    if (singleDownloadSong != null) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities,
            onDismiss = { singleDownloadSong = null },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                singleDownloadSong?.let { song ->
                    viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                        song,
                        qualityId,
                        hideAi
                    )
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_download),
                    Toast.LENGTH_SHORT
                ).show()
                singleDownloadSong = null
            }
        )
    }

    // 单曲添加到歌单对话框（从上下文菜单触发）
    if (singlePlaylistSong != null && !showCreateDialog) {
        val song = singlePlaylistSong!!
        AddToPlaylistDialog(
            playlists = playlists,
            isFavorite = viewModelFactory.localMusicStore.isFavorite(song),
            onFavoriteClick = {
                viewModelFactory.localMusicStore.addFavorite(song)
                singlePlaylistSong = null
            },
            onDismiss = { singlePlaylistSong = null },
            onPlaylistClick = { pid ->
                viewModelFactory.localMusicStore.addToPlaylist(pid, song)
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_playlist_count, 1),
                    Toast.LENGTH_SHORT
                ).show()
                singlePlaylistSong = null
            },
            onCreateNew = {
                showCreateDialog = true
            }
        )
    }
}

private data class ContextMenuHandlers(
    val contextMenuSong: MusicItem?,
    val onContextMenuDismiss: () -> Unit,
    val onContextPlay: (MusicItem) -> Unit,
    val onContextPlayNext: (MusicItem) -> Unit,
    val onContextDownload: (MusicItem) -> Unit,
    val onContextToggleFavorite: (MusicItem) -> Unit,
    val onContextAddToPlaylist: (MusicItem) -> Unit,
    val onContextMultiSelect: (MusicItem) -> Unit,
    val isFavorite: (MusicItem) -> Boolean
)

// ===== Wide (landscape / tablet) layout =====

@Composable
private fun WidePlaylistContent(
    uiState: PlaylistUiState,
    onPlayAll: () -> Unit,
    onSongClick: (MusicItem) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    selectionMode: Boolean = false,
    selectedSongs: List<MusicItem> = emptyList(),
    currentSong: MusicItem? = null,
    onLongClick: ((MusicItem) -> Unit)? = null,
    onCheckedChange: ((MusicItem) -> Unit)? = null,
    contextMenu: ContextMenuHandlers? = null,
    @SuppressLint("ModifierParameter") modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxSize()) {
        // Left panel: cover + info + play-all button
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PlaylistCover(img = uiState.playlistInfo?.img, size = 200)

            Spacer(Modifier.height(16.dp))

            uiState.playlistInfo?.let { info ->
                Text(
                    text = info.title ?: stringResource(R.string.unknown_playlist),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (!info.author.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = info.author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.total_songs, info.totalSongs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                if (!info.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = info.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            Button(
                onClick = onPlayAll,
                enabled = uiState.songs.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    disabledContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.play_all), style = MaterialTheme.typography.labelLarge)
            }
        }

        VerticalDivider()

        // Right panel: song list
        SongList(
            songs = uiState.songs,
            isLoading = uiState.isLoading,
            hasMore = uiState.hasMore,
            onSongClick = onSongClick,
            onLoadMore = onLoadMore,
            onRefresh = onRefresh,
            selectionMode = selectionMode,
            selectedSongs = selectedSongs,
            currentSong = currentSong,
            onLongClick = onLongClick,
            onCheckedChange = onCheckedChange,
            contextMenu = contextMenu,
            modifier = Modifier.weight(1f)
        )
    }
}

// ===== Narrow (portrait) layout =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowPlaylistContent(
    uiState: PlaylistUiState,
    onPlayAll: () -> Unit,
    onSongClick: (MusicItem) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    selectionMode: Boolean = false,
    selectedSongs: List<MusicItem> = emptyList(),
    currentSong: MusicItem? = null,
    onLongClick: ((MusicItem) -> Unit)? = null,
    onCheckedChange: ((MusicItem) -> Unit)? = null,
    contextMenu: ContextMenuHandlers? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && uiState.hasMore && !uiState.isLoading) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading && uiState.songs.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "playlist_header") {
                PlaylistHeader(playlistInfo = uiState.playlistInfo)
            }

            item(key = "play_all_button") {
                PlayAllButton(
                    onClick = onPlayAll,
                    enabled = uiState.songs.isNotEmpty(),
                    songCount = uiState.songs.size
                )
            }

            items(items = uiState.songs, key = { it.uniqueKey }) { song ->
                var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.firstOrNull()?.let {
                                    menuOffset =
                                        with(density) { DpOffset(it.position.x.toDp(), 0.dp) }
                                }
                            }
                        }
                    }
                ) {
                    SongCard(
                        song = song,
                        onClick = { onSongClick(song) },
                        selectionMode = selectionMode,
                        selected = selectedSongs.any { it.id == song.id },
                        isPlaying = currentSong?.id == song.id &&
                                currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                        onLongClick = { onLongClick?.invoke(song) },
                        onCheckedChange = { onCheckedChange?.invoke(song) }
                    )
                    if (contextMenu != null) {
                        SongContextMenu(
                            expanded = contextMenu.contextMenuSong?.id == song.id,
                            isFavorite = contextMenu.isFavorite(song),
                            onDismiss = contextMenu.onContextMenuDismiss,
                            onPlay = { contextMenu.onContextPlay(song) },
                            onPlayNext = { contextMenu.onContextPlayNext(song) },
                            onDownload = { contextMenu.onContextDownload(song) },
                            onToggleFavorite = { contextMenu.onContextToggleFavorite(song) },
                            onAddToPlaylist = { contextMenu.onContextAddToPlaylist(song) },
                            onMultiSelect = { contextMenu.onContextMultiSelect(song) },
                            offset = menuOffset,
                            mvSong = song
                        )
                    }
                }
            }

            if (uiState.isLoading && uiState.songs.isNotEmpty()) {
                item(key = "loading_more") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            if (!uiState.hasMore && uiState.songs.isNotEmpty()) {
                item(key = "no_more") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.all_songs_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (uiState.songs.isEmpty() && !uiState.isLoading && uiState.playlistInfo != null) {
                item(key = "empty_songs") {
                    EmptyState(
                        message = stringResource(R.string.playlist_no_songs),
                        modifier = Modifier.height(300.dp)
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ===== Shared components =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongList(
    songs: List<MusicItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onSongClick: (MusicItem) -> Unit,
    onLoadMore: () -> Unit,
    onRefresh: () -> Unit,
    selectionMode: Boolean = false,
    selectedSongs: List<MusicItem> = emptyList(),
    currentSong: MusicItem? = null,
    onLongClick: ((MusicItem) -> Unit)? = null,
    onCheckedChange: ((MusicItem) -> Unit)? = null,
    contextMenu: ContextMenuHandlers? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    PullToRefreshBox(
        isRefreshing = isLoading && songs.isNotEmpty(),
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(items = songs, key = { it.uniqueKey }) { song ->
                var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.firstOrNull()?.let {
                                    menuOffset =
                                        with(density) { DpOffset(it.position.x.toDp(), 0.dp) }
                                }
                            }
                        }
                    }
                ) {
                    SongCard(
                        song = song,
                        onClick = { onSongClick(song) },
                        selectionMode = selectionMode,
                        selected = selectedSongs.any { it.id == song.id },
                        isPlaying = currentSong?.id == song.id &&
                                currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                        onLongClick = { onLongClick?.invoke(song) },
                        onCheckedChange = { onCheckedChange?.invoke(song) }
                    )
                    if (contextMenu != null) {
                        SongContextMenu(
                            expanded = contextMenu.contextMenuSong?.id == song.id,
                            isFavorite = contextMenu.isFavorite(song),
                            onDismiss = contextMenu.onContextMenuDismiss,
                            onPlay = { contextMenu.onContextPlay(song) },
                            onPlayNext = { contextMenu.onContextPlayNext(song) },
                            onDownload = { contextMenu.onContextDownload(song) },
                            onToggleFavorite = { contextMenu.onContextToggleFavorite(song) },
                            onAddToPlaylist = { contextMenu.onContextAddToPlaylist(song) },
                            onMultiSelect = { contextMenu.onContextMultiSelect(song) },
                            offset = menuOffset,
                            mvSong = song
                        )
                    }
                }
            }

            if (isLoading && songs.isNotEmpty()) {
                item(key = "loading_more") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    }
                }
            }

            if (!hasMore && songs.isNotEmpty()) {
                item(key = "no_more") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.all_songs_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (songs.isEmpty() && !isLoading) {
                item(key = "empty_songs") {
                    EmptyState(
                        message = stringResource(R.string.playlist_no_songs),
                        modifier = Modifier.height(300.dp)
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun PlaylistCover(img: String?, size: Int = 140) {
    if (!img.isNullOrEmpty()) {
        AsyncImage(
            model = img,
            contentDescription = stringResource(R.string.playlist_cover),
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp)
            ) {}
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.no_cover),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistTopBar(
    title: String,
    onBackClick: () -> Unit,
    isOnlinePlaylist: Boolean = false,
    isBookmarked: Boolean = false,
    onBookmark: () -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title.ifEmpty { stringResource(R.string.nav_playlists) },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        actions = {
            if (isOnlinePlaylist) {
                IconButton(onClick = onBookmark, enabled = !isBookmarked) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isBookmarked) "已收藏" else "收藏到本地",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun PlaylistHeader(playlistInfo: PlayListInfoResult?) {
    if (playlistInfo == null) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        PlaylistCover(img = playlistInfo.img)

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = playlistInfo.title ?: stringResource(R.string.unknown_playlist),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!playlistInfo.author.isNullOrBlank()) {
                Text(
                    text = playlistInfo.author,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.total_songs, playlistInfo.totalSongs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )
            if (!playlistInfo.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = playlistInfo.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun PlayAllButton(onClick: () -> Unit, enabled: Boolean, songCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary,
                disabledContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.height(42.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play_all), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        if (songCount > 0) {
            Text(
                stringResource(R.string.song_count, songCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        thickness = 0.5.dp
    )
}
