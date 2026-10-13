package com.ikunshare.music.ui.screens.playlist

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.ikunshare.music.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import com.ikunshare.music.ui.components.GlassTopBar as TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.R
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.QueueSource
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.ui.utils.rememberPlaylistWriteActions
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.AddToPlaylistDialog
import com.ikunshare.music.ui.components.CreatePlaylistDialog
import com.ikunshare.music.ui.components.DownloadQualityDialog
import com.ikunshare.music.ui.components.EmptyState
import com.ikunshare.music.ui.components.LiquidGlassScene
import com.ikunshare.music.ui.components.PlaylistGlassHero
import com.ikunshare.music.ui.components.GlassButton
import com.ikunshare.music.ui.components.liquidGlass
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.components.ErrorState
import com.ikunshare.music.ui.components.LoadingIndicator
import com.ikunshare.music.ui.components.SelectionBottomBar
import com.ikunshare.music.ui.components.SongCard
import com.ikunshare.music.ui.components.SongContextMenu
import com.ikunshare.music.ui.screens.player.PlayerViewModel

@SuppressLint("ConfigurationScreenWidthHeight")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistScreen(
    navController: NavController,
    playlistId: String,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val playerSheet = LocalPlayerSheet.current
    val viewModel: PlaylistViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val retryLabel = stringResource(R.string.retry)
    LaunchedEffect(uiState.error) {
        val error = uiState.error ?: return@LaunchedEffect
        if (uiState.songs.isNotEmpty() && snackbar.showSnackbar(error, actionLabel = retryLabel,
                withDismissAction = true) == SnackbarResult.ActionPerformed) {
            viewModel.retry()
        }
    }
    val context = LocalContext.current
    val queueSource = QueueSource("platform", playlistId, uiState.playlistInfo?.title.orEmpty())
    val playlistWrites = rememberPlaylistWriteActions(viewModelFactory.localMusicStore)
    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(viewModelFactory.downloadManager, playlistId)

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
        if (selectedSongs.any { it.uniqueKey == song.uniqueKey }) {
            selectedSongs.removeAll { it.uniqueKey == song.uniqueKey }
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

    LiquidGlassScene(cover = uiState.playlistInfo?.img) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, modifier = Modifier.padding(bottom = LocalFloatingBottomBarReserve.current)) },
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
                        isSaving = uiState.isSavingToLocal,
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
                                    viewModel.removeSongsLocally(selectedSongs.map { it.uniqueKey }.toSet())
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
                            val index = songs.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
                            playerViewModel.setPlaylistAndPlay(songs, index, queueSource)
                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                playerSheet.open(song)
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
                            val index = uiState.songs.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
                            playerViewModel.setPlaylistAndPlay(uiState.songs, index, queueSource)
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
                                    playerViewModel.setPlaylistAndPlay(songs, 0, queueSource)
                                    viewModel.continuePlaybackQueue { playerViewModel.appendToQueue(it, queueSource) }
                                    playerSheet.open(songs.first())
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
                                    playerViewModel.setPlaylistAndPlay(songs, 0, queueSource)
                                    viewModel.continuePlaybackQueue { playerViewModel.appendToQueue(it, queueSource) }
                                    playerSheet.open(songs.first())
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

    } // LiquidGlassScene

    // 添加到歌单对话框
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            busy = playlistWrites.isWriting,
            onPlaylistClick = { pid ->
                playlistWrites.add(pid, selectedSongs.toList()) {
                    showPlaylistPicker = false
                    exitSelectionMode()
                }
            },
            onCreateNew = {
                showPlaylistPicker = false
                showCreateDialog = true
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            busy = playlistWrites.isWriting,
            onDismiss = { showCreateDialog = false; singlePlaylistSong = null },
            onConfirm = { name ->
                val songs = singlePlaylistSong?.let { listOf(it) } ?: selectedSongs.toList()
                playlistWrites.create(name, songs) {
                    showCreateDialog = false
                    singlePlaylistSong = null
                    exitSelectionMode()
                }
            }
        )
    }

    if (showQualityPicker) {
        DownloadQualityDialog(
            busy = downloadQueue.isEnqueuing,
            hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities,
            onDismiss = { showQualityPicker = false },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                downloadQueue.add(selectedSongs.toList(), qualityId, hideAi) {
                    showQualityPicker = false
                    exitSelectionMode()
                }
            }
        )
    }

    // 单曲下载对话框（从上下文菜单触发）
    if (singleDownloadSong != null) {
        DownloadQualityDialog(
            busy = downloadQueue.isEnqueuing,
            hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities,
            onDismiss = { singleDownloadSong = null },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                singleDownloadSong?.let { song ->
                    downloadQueue.add(listOf(song), qualityId, hideAi) { singleDownloadSong = null }
                }
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
        // Keep metadata separate from the independently scrolling song list.
        Column(
            Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(bottom = LocalFloatingBottomBarReserve.current + 16.dp)
        ) {
            PlaylistHeader(uiState.playlistInfo)
            PlayAllButton(onPlayAll, uiState.songs.isNotEmpty(), uiState.songs.size)
        }
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
    val selectedKeys by remember(selectedSongs) { derivedStateOf { selectedSongs.map { it.uniqueKey }.toHashSet() } }
    val bottomReserve = LocalFloatingBottomBarReserve.current

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
            contentPadding = PaddingValues(bottom = bottomReserve + 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "playlist_header") {
                PlaylistHeader(playlistInfo = uiState.playlistInfo)
            }

            stickyHeader(key = "play_all_button") {
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
                        selected = song.uniqueKey in selectedKeys,
                        isPlaying = currentSong?.uniqueKey == song.uniqueKey &&
                                currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                        onLongClick = { onLongClick?.invoke(song) },
                        onCheckedChange = { onCheckedChange?.invoke(song) },
                        onPlayClick = contextMenu?.let { menu -> { menu.onContextPlay(song) } },
                        onAddToPlaylistClick = contextMenu?.let { menu -> { menu.onContextAddToPlaylist(song) } },
                        onDownloadClick = contextMenu?.let { menu -> { menu.onContextDownload(song) } }
                    )
                    if (contextMenu != null) {
                        SongContextMenu(
                            expanded = contextMenu.contextMenuSong?.uniqueKey == song.uniqueKey,
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

            item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
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
    val selectedKeys by remember(selectedSongs) { derivedStateOf { selectedSongs.map { it.uniqueKey }.toHashSet() } }
    val bottomReserve = LocalFloatingBottomBarReserve.current

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
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = bottomReserve + 24.dp), modifier = Modifier.fillMaxSize()) {
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
                        selected = song.uniqueKey in selectedKeys,
                        isPlaying = currentSong?.uniqueKey == song.uniqueKey &&
                                currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                        onLongClick = { onLongClick?.invoke(song) },
                        onCheckedChange = { onCheckedChange?.invoke(song) },
                        onPlayClick = contextMenu?.let { menu -> { menu.onContextPlay(song) } },
                        onAddToPlaylistClick = contextMenu?.let { menu -> { menu.onContextAddToPlaylist(song) } },
                        onDownloadClick = contextMenu?.let { menu -> { menu.onContextDownload(song) } }
                    )
                    if (contextMenu != null) {
                        SongContextMenu(
                            expanded = contextMenu.contextMenuSong?.uniqueKey == song.uniqueKey,
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

            item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
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
    isSaving: Boolean = false,
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
                IconButton(onClick = onBookmark, enabled = !isBookmarked && !isSaving) {
                    if (isSaving) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        contentDescription = if (isBookmarked) "已收藏" else "收藏到本地",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}

@Composable
private fun PlaylistHeader(playlistInfo: PlayListInfoResult?) {
    playlistInfo ?: return
    PlaylistGlassHero(
        title = playlistInfo.title ?: stringResource(R.string.unknown_playlist),
        cover = playlistInfo.img,
        songCount = playlistInfo.totalSongs,
        author = playlistInfo.author,
        description = playlistInfo.description
    )
}

@Composable
private fun PlayAllButton(onClick: () -> Unit, enabled: Boolean, songCount: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .liquidGlass().padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GlassButton(onClick, enabled = enabled, accented = true) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Text(stringResource(R.string.play_all), maxLines = 1)
        }
        Text(stringResource(R.string.song_count, songCount), Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
