package com.ikunshare.music.ui.screens.search

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumPreviewCache
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.ArtistPreviewCache
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.SearchType
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.ui.components.AddToPlaylistDialog
import com.ikunshare.music.ui.components.CreatePlaylistDialog
import com.ikunshare.music.ui.components.DownloadQualityDialog
import com.ikunshare.music.ui.components.GlassIconButton as IconButton
import com.ikunshare.music.ui.components.GlassTopBar as TopAppBar
import com.ikunshare.music.ui.components.LiquidGlassScene
import com.ikunshare.music.ui.components.SelectionBottomBar
import com.ikunshare.music.ui.components.ScrollGlassScope
import com.ikunshare.music.ui.components.SongCard
import com.ikunshare.music.ui.components.SongContextMenu
import com.ikunshare.music.ui.components.DiscoveryPlaylistCard
import com.ikunshare.music.ui.utils.rememberPlaylistWriteActions
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.navigation.Screen
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.utils.LocalScrollOffsetReporter
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged


@Composable
private fun RememberScrollOffsetReporter(
    lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    // 监听滚动偏移量并上报
    val scrollOffsetReporter = LocalScrollOffsetReporter.current

    var previousVisibleItemIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }.collect { (firstVisibleItemIndex, scrollOffset) ->
            val isScrollingUp = firstVisibleItemIndex < previousVisibleItemIndex ||
                    (firstVisibleItemIndex == previousVisibleItemIndex && scrollOffset < previousScrollOffset)
            scrollOffsetReporter?.invoke(firstVisibleItemIndex, scrollOffset, isScrollingUp)
            previousVisibleItemIndex = firstVisibleItemIndex
            previousScrollOffset = scrollOffset
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    onSongClick: (List<MusicItem>, MusicItem) -> Unit = { _, _ -> }
) {
    val playerSheet = LocalPlayerSheet.current
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val appSettings by viewModelFactory.appSettingsManager.settings.collectAsState()
    val context = LocalContext.current
    val playlistWrites = rememberPlaylistWriteActions(viewModelFactory.localMusicStore)
    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(viewModelFactory.downloadManager,
        Pair(Triple(uiState.currentProvider, uiState.searchType, uiState.submittedQuery), uiState.searchRevision))
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var searchFocused by remember { mutableStateOf(false) }

    // 多选状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<MusicItem>() }

    // 添加到歌单对话框
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    val playlists by viewModelFactory.localMusicStore.playlistsFlow.collectAsState()

    // 长按上下文菜单
    var contextMenuSong by remember { mutableStateOf<MusicItem?>(null) }
    // 单曲操作 dialog（从上下文菜单触发）
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

    // 替换搜索上下文清理旧操作；追加分页保留选择。
    val searchContext = Pair(Triple(uiState.currentProvider, uiState.searchType, uiState.submittedQuery), uiState.searchRevision)
    LaunchedEffect(searchContext, uiState.results.isEmpty()) {
        exitSelectionMode()
        contextMenuSong = null
        singleDownloadSong = null
        singlePlaylistSong = null
        showPlaylistPicker = false
        showCreateDialog = false
        showQualityPicker = false
    }

    androidx.activity.compose.BackHandler(enabled = selectionMode) { exitSelectionMode() }

    LiquidGlassScene {
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
                                if (selectedSongs.size == uiState.results.size) {
                                    selectedSongs.clear()
                                } else {
                                    selectedSongs.clear()
                                    selectedSongs.addAll(uiState.results)
                                }
                            }) {
                                Icon(
                                    Icons.Filled.SelectAll,
                                    contentDescription = stringResource(R.string.select_all)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.Transparent
                        )
                    )
                } else {
                    SearchTopBar(
                        query = uiState.query,
                        onQueryChange = viewModel::onQueryChange,
                        onSearch = viewModel::search,
                        currentProvider = uiState.currentProvider,
                        availableProviders = uiState.availableProviders,
                        onProviderSwitch = viewModel::switchProvider,
                        searchType = uiState.searchType,
                        supportedSearchTypes = uiState.supportedSearchTypes,
                        onSearchTypeSwitch = viewModel::switchSearchType,
                        onFocusChange = { searchFocused = it },
                        onOpenDiscovery = { navController.navigate(Screen.Discover.route) }
                    )
                }
            },
            bottomBar = {
                if (selectionMode && selectedSongs.isNotEmpty()) {
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
                        floatingBottomBar = appSettings.floatingBottomBar,
                        liquidGlass = appSettings.liquidGlassMode
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
                Column(Modifier.fillMaxSize()) {
                    val loadedCount = when (uiState.searchType) {
                        SearchType.SONG -> uiState.results.size
                        SearchType.PLAYLIST -> uiState.playlistResults.size
                        SearchType.ALBUM -> uiState.albumResults.size
                        SearchType.ARTIST -> uiState.artistResults.size
                    }
                    if (loadedCount > 0 && !selectionMode) {
                        SearchResultsHeading(uiState.submittedQuery ?: uiState.query, uiState.searchType, loadedCount)
                    }
                    if (loadedCount > 0 && uiState.error != null) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(uiState.error.orEmpty(), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                                maxLines = 3, overflow = TextOverflow.Ellipsis)
                            TextButton(enabled = !uiState.isLoading, onClick = viewModel::retry) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        when {
                            // History remains usable while online trends load or fail.
                            uiState.query.isBlank() && loadedCount == 0 -> {
                                HotSearchSection(
                                    hotSearches = uiState.hotSearches,
                                    onHotSearchClick = { keyword ->
                                        viewModel.onHotSearchClick(keyword)
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    },
                                    searchHistory = uiState.searchHistory,
                                    onHistoryClick = { keyword ->
                                        viewModel.onHistoryClick(keyword)
                                        focusManager.clearFocus()
                                        keyboardController?.hide()
                                    },
                                    onRemoveHistory = viewModel::removeHistory,
                                    onClearHistory = viewModel::clearHistory,
                                    isLoading = uiState.isLoading,
                                    error = uiState.error,
                                    onRetry = viewModel::retry
                                )
                            }
                            uiState.isLoading && uiState.submittedQuery != null && loadedCount == 0 -> {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    SearchFeedback(stringResource(R.string.loading), loading = true)
                                }
                            }
                            uiState.error != null && uiState.submittedQuery == uiState.query.trim() && loadedCount == 0 -> {
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    SearchFeedback(stringResource(R.string.search_error_title), uiState.error, onRetry = viewModel::retry)
                                }
                            }
                            loadedCount == 0 -> {
                                val submitted = uiState.submittedQuery == uiState.query.trim()
                                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                    SearchFeedback(
                                        stringResource(if (submitted) R.string.search_empty_title else R.string.search_ready_title),
                                        stringResource(if (submitted) R.string.search_empty_message else R.string.search_ready_message)
                                    )
                                }
                            }
                            // 歌单搜索结果
                            uiState.searchType == SearchType.PLAYLIST && uiState.playlistResults.isNotEmpty() -> {
                                PlaylistSearchResultsList(
                                    results = uiState.playlistResults,
                                    isLoading = uiState.isLoading,
                                    hasMore = uiState.hasMore && uiState.error == null,
                                    onPlaylistClick = { playlist ->
                                        navController.navigate(Screen.Playlist.createRoute("${playlist.source}:${playlist.playListId}"))
                                    },
                                    onLoadMore = viewModel::loadMore,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            // 专辑搜索结果
                            uiState.searchType == SearchType.ALBUM && uiState.albumResults.isNotEmpty() -> {
                                val coroutineScope = rememberCoroutineScope()
                                val savingAlbumText = stringResource(R.string.saving_album_as_playlist)
                                var albumMenuTarget by remember { mutableStateOf<AlbumInfoResult?>(null) }
                                var albumToDownload by remember { mutableStateOf<AlbumInfoResult?>(null) }
                                var albumDownloadBusy by remember { mutableStateOf(false) }
                                AlbumSearchResultsList(
                                    results = uiState.albumResults,
                                    isLoading = uiState.isLoading,
                                    hasMore = uiState.hasMore && uiState.error == null,
                                    onAlbumClick = { album ->
                                        AlbumPreviewCache.put(album)
                                        navController.navigate(Screen.Album.createRoute("${album.source}:${album.albumId}"))
                                    },
                                    onAlbumLongClick = { album -> albumMenuTarget = album },
                                    onLoadMore = viewModel::loadMore,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // 长按菜单
                                if (albumMenuTarget != null) {
                                    AlertDialog(
                                        onDismissRequest = { albumMenuTarget = null },
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                        title = {
                                            Text(
                                                albumMenuTarget!!.name,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        text = {
                                            Column {
                                                TextButton(
                                                    onClick = {
                                                        val album = albumMenuTarget!!
                                                        albumMenuTarget = null
                                                        albumToDownload = album
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        stringResource(R.string.download_entire_album),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                                TextButton(
                                                    onClick = {
                                                        val album = albumMenuTarget!!
                                                        albumMenuTarget = null
                                                        Toast.makeText(context, savingAlbumText, Toast.LENGTH_SHORT)
                                                            .show()
                                                        coroutineScope.launch {
                                                            try {
                                                                val songs = MusicApplication.instance
                                                                    ?.musicRepository
                                                                    ?.getAlbumSongsForSource(
                                                                        album.source,
                                                                        album.albumId
                                                                    )
                                                                    ?.result
                                                                    ?: emptyList()
                                                                if (songs.isEmpty()) {
                                                                    Toast.makeText(
                                                                        context,
                                                                        context.getString(
                                                                            R.string.save_album_failed,
                                                                            ""
                                                                        ),
                                                                        Toast.LENGTH_SHORT
                                                                    ).show()
                                                                    return@launch
                                                                }
                                                                val playlistName = listOfNotNull(
                                                                    album.name.takeIf { it.isNotBlank() },
                                                                    album.artist?.takeIf { it.isNotBlank() }
                                                                ).joinToString(" - ").ifBlank { album.name }
                                                                val store = viewModelFactory.localMusicStore
                                                                store.createPlaylistWithSongs(playlistName, songs)
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(
                                                                        R.string.saved_album_as_playlist,
                                                                        songs.size
                                                                    ),
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            } catch (e: Exception) {
                                                                Toast.makeText(
                                                                    context,
                                                                    context.getString(
                                                                        R.string.save_album_failed,
                                                                        e.message ?: ""
                                                                    ),
                                                                    Toast.LENGTH_SHORT
                                                                ).show()
                                                            }
                                                        }
                                                    },
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Text(
                                                        stringResource(R.string.save_as_local_playlist),
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        },
                                        confirmButton = {}
                                    )
                                }

                                // 下载品质选择
                                if (albumToDownload != null) {
                                    DownloadQualityDialog(
                                        busy = albumDownloadBusy,
                                        hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
                                        onDismiss = { albumToDownload = null },
                                        onConfirm = { qualityId ->
                                            if (albumDownloadBusy) return@DownloadQualityDialog
                                            val album = albumToDownload!!
                                            val hideAi =
                                                viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                                            val withYear =
                                                viewModelFactory.appSettingsManager.settings.value.downloadAlbumFolderWithYear
                                            albumDownloadBusy = true
                                            coroutineScope.launch {
                                                try {
                                                    val result = com.ikunshare.music.manager.enqueueAlbumDownloadBatch(
                                                        listOf(album), withYear, null,
                                                        fetch = { selected -> MusicApplication.instance?.musicRepository
                                                            ?.getAlbumSongsForSource(selected.source, selected.albumId)
                                                            ?: error("Album repository unavailable") },
                                                        enqueue = { song, folder, number -> viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                                                            song, qualityId, hideAi, deferSchedule = true, subDir = folder, trackNumber = number) },
                                                        enqueueDisc = { song, folder, number, disc -> viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                                                            song, qualityId, hideAi, deferSchedule = true, subDir = folder, trackNumber = number, disc = disc) },
                                                        flush = viewModelFactory.downloadManager::flushSchedule)
                                                    val message = if (result.failedAlbums.isNotEmpty())
                                                        context.getString(R.string.artist_album_download_result, result.queuedSongs, result.failedAlbums.size)
                                                    else context.getString(if (result.queuedSongs > 0) R.string.added_to_download_queue_count
                                                        else R.string.download_not_added, result.queuedSongs)
                                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                                    albumToDownload = null
                                                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                                catch (failure: Exception) {
                                                    Toast.makeText(context, context.getString(R.string.download_failed_msg, failure.message.orEmpty()), Toast.LENGTH_SHORT).show()
                                                }
                                            }.invokeOnCompletion { albumDownloadBusy = false }
                                        }
                                    )
                                }
                            }
                            // 歌手搜索结果
                            uiState.searchType == SearchType.ARTIST && uiState.artistResults.isNotEmpty() -> {
                                ArtistSearchResultsList(
                                    results = uiState.artistResults,
                                    isLoading = uiState.isLoading,
                                    hasMore = uiState.hasMore && uiState.error == null,
                                    onArtistClick = { artist ->
                                        ArtistPreviewCache.put(artist)
                                        navController.navigate(
                                            Screen.Artist.createRoute("${artist.source}:${artist.artistId}")
                                        )
                                    },
                                    onLoadMore = viewModel::loadMore,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            // 搜索结果列表
                            else -> {
                                val currentSongKey by remember(playerViewModel) {
                                    playerViewModel.uiState.map { it.currentSong?.uniqueKey }.distinctUntilChanged()
                                }.collectAsState(initial = null)
                                SearchResultsList(
                                    results = uiState.results,
                                    isLoading = uiState.isLoading,
                                    hasMore = uiState.hasMore && uiState.error == null,
                                    currentSongKey = currentSongKey,
                                    onSongClick = { song ->
                                        if (selectionMode) {
                                            toggleSelect(song)
                                        } else {
                                            onSongClick(uiState.results, song)
                                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                                playerSheet.open(song)
                                            }
                                        }
                                    },
                                    onLoadMore = viewModel::loadMore,
                                    selectionMode = selectionMode,
                                    selectedSongs = selectedSongs,
                                    onLongClick = { song ->
                                        if (selectionMode) toggleSelect(song)
                                        else contextMenuSong = song
                                    },
                                    onCheckedChange = { song -> toggleSelect(song) },
                                    contextMenuSong = contextMenuSong,
                                    onContextMenuDismiss = { contextMenuSong = null },
                                    onContextPlay = { song ->
                                        onSongClick(uiState.results, song)
                                    },
                                    onContextPlayNext = { song ->
                                        playerViewModel.addToPlayNext(song)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.added_to_play_next),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onContextDownload = { song ->
                                        singleDownloadSong = song
                                    },
                                    onContextToggleFavorite = { song ->
                                        val store = viewModelFactory.localMusicStore
                                        if (store.isFavorite(song)) store.removeFavorite(song) else store.addFavorite(
                                            song
                                        )
                                    },
                                    onContextAddToPlaylist = { song ->
                                        singlePlaylistSong = song
                                    },
                                    onContextMultiSelect = { song ->
                                        enterSelection(song)
                                    },
                                    isFavorite = { song -> viewModelFactory.localMusicStore.isFavorite(song) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                } // Results column
            } // Padded content
        } // Scaffold
    } // LiquidGlassScene

    // 添加到歌单对话框
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            busy = playlistWrites.isWriting,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistClick = { pid ->
                playlistWrites.add(pid, selectedSongs.toList()) {
                    showPlaylistPicker = false
                    exitSelectionMode()
                }
            },
            onCreateNew = { showPlaylistPicker = false; showCreateDialog = true }
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
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
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
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
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
            onPlaylistClick = { playlistId ->
                viewModelFactory.localMusicStore.addToPlaylist(playlistId, song)
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

@Composable
@SuppressLint("ModifierParameter")
private fun SearchResultsList(
    results: List<MusicItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onSongClick: (MusicItem) -> Unit,
    onLoadMore: () -> Unit,
    selectionMode: Boolean = false,
    selectedSongs: List<MusicItem> = emptyList(),
    currentSongKey: String? = null,
    onLongClick: ((MusicItem) -> Unit)? = null,
    onCheckedChange: ((MusicItem) -> Unit)? = null,
    contextMenuSong: MusicItem? = null,
    onContextMenuDismiss: () -> Unit = {},
    onContextPlay: ((MusicItem) -> Unit)? = null,
    onContextPlayNext: ((MusicItem) -> Unit)? = null,
    onContextDownload: ((MusicItem) -> Unit)? = null,
    onContextToggleFavorite: ((MusicItem) -> Unit)? = null,
    onContextAddToPlaylist: ((MusicItem) -> Unit)? = null,
    onContextMultiSelect: ((MusicItem) -> Unit)? = null,
    isFavorite: ((MusicItem) -> Boolean)? = null,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val bottomReserve = LocalFloatingBottomBarReserve.current
    val selectedKeys by remember(selectedSongs) { derivedStateOf { selectedSongs.map { it.uniqueKey }.toHashSet() } }
    RememberScrollOffsetReporter(listState)

    // 无限滚动检测
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore, hasMore, isLoading, results.size) {
        if (shouldLoadMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    ScrollGlassScope(listState) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomReserve + 24.dp),
            modifier = modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = results,
                key = { index, song -> "${song.uniqueKey}_$index" }
            ) { _, song ->
                var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
                val density = LocalDensity.current
                Box(
                    modifier = Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.firstOrNull()?.let {
                                    menuOffset = with(density) { DpOffset(it.position.x.toDp(), 0.dp) }
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
                        isPlaying = currentSongKey == song.uniqueKey,
                        onLongClick = { onLongClick?.invoke(song) },
                        onCheckedChange = { onCheckedChange?.invoke(song) },
                        onPlayClick = { onContextPlay?.invoke(song) },
                        onAddToPlaylistClick = { onContextAddToPlaylist?.invoke(song) },
                        onDownloadClick = { onContextDownload?.invoke(song) }
                    )
                    SongContextMenu(
                        expanded = contextMenuSong?.uniqueKey == song.uniqueKey,
                        isFavorite = isFavorite?.invoke(song) ?: false,
                        onDismiss = onContextMenuDismiss,
                        onPlay = { onContextPlay?.invoke(song) },
                        onPlayNext = { onContextPlayNext?.invoke(song) },
                        onDownload = { onContextDownload?.invoke(song) },
                        onToggleFavorite = { onContextToggleFavorite?.invoke(song) },
                        onAddToPlaylist = { onContextAddToPlaylist?.invoke(song) },
                        onMultiSelect = { onContextMultiSelect?.invoke(song) },
                        offset = menuOffset,
                        mvSong = song
                    )
                }
            }

            // 底部加载指示器
            if (isLoading && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
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

            // 没有更多数据提示
            if (!hasMore && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.all_results_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 底部间距
            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PlaylistSearchResultsList(
    results: List<PlayListInfoResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    onPlaylistClick: (PlayListInfoResult) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val failedCovers = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val bottomReserve = LocalFloatingBottomBarReserve.current
    RememberScrollOffsetReporter(listState)

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore, hasMore, isLoading, results.size) {
        if (shouldLoadMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    ScrollGlassScope(listState) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomReserve + 24.dp),
            modifier = modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = results,
                key = { index, item -> "${item.source}_${item.playListId}_$index" }
            ) { _, playlist ->
                DiscoveryPlaylistCard(
                    playlist = playlist,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    failedCovers = failedCovers,
                    showSource = true,
                    onClick = { onPlaylistClick(playlist) }
                )
            }

            if (isLoading && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
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

            if (!hasMore && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.all_results_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumSearchResultsList(
    results: List<AlbumInfoResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val bottomReserve = LocalFloatingBottomBarReserve.current
    RememberScrollOffsetReporter(listState)

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore, hasMore, isLoading, results.size) {
        if (shouldLoadMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    ScrollGlassScope(listState) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomReserve + 24.dp),
            modifier = modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = results,
                key = { index, item -> "${item.source}_${item.albumId}_$index" }
            ) { _, album ->
                val count = stringResource(R.string.song_count, album.size)
                SearchResultCard(
                    title = album.name,
                    subtitle = listOfNotNull(album.artist?.takeIf { it.isNotBlank() },
                        count.takeIf { album.size > 0 }, album.subType?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    cover = album.cover,
                    source = album.source,
                    album = true,
                    onClick = { onAlbumClick(album) },
                    onLongClick = { onAlbumLongClick(album) }
                )
            }

            if (isLoading && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
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

            if (!hasMore && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.all_results_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistSearchResultsList(
    results: List<ArtistInfoResult>,
    isLoading: Boolean,
    hasMore: Boolean,
    onArtistClick: (ArtistInfoResult) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val bottomReserve = LocalFloatingBottomBarReserve.current
    RememberScrollOffsetReporter(listState)

    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleItem >= totalItems - 5 && totalItems > 0
        }
    }

    LaunchedEffect(shouldLoadMore, hasMore, isLoading, results.size) {
        if (shouldLoadMore && hasMore && !isLoading) {
            onLoadMore()
        }
    }

    ScrollGlassScope(listState) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 4.dp, bottom = bottomReserve + 24.dp),
            modifier = modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = results,
                key = { index, item -> "${item.source}_${item.artistId}_$index" }
            ) { _, artist ->
                val albumCount = stringResource(R.string.search_album_count, artist.albumCount)
                val songCount = stringResource(R.string.song_count, artist.musicCount)
                SearchResultCard(
                    title = artist.name,
                    subtitle = listOfNotNull(albumCount.takeIf { artist.albumCount > 0 },
                        songCount.takeIf { artist.musicCount > 0 }).joinToString(" · "),
                    cover = artist.avatar,
                    source = artist.source,
                    artist = true,
                    onClick = { onArtistClick(artist) }
                )
            }

            if (isLoading && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
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

            if (!hasMore && results.isNotEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.all_results_loaded),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
