package com.ikunshare.sound.ui.screens.search

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.AlbumPreviewCache
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.ArtistPreviewCache
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.platform.base.SearchType
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
import com.ikunshare.sound.ui.theme.LocalUiColors
import com.ikunshare.sound.ui.utils.LocalScrollOffsetReporter
import kotlinx.coroutines.launch


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
    val viewModel: SearchViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val appSettings by viewModelFactory.appSettingsManager.settings.collectAsState()
    val uiColors = LocalUiColors.current
    val barColor = if (appSettings.themeMode == "custom") uiColors.bottomBarColor
    else uiColors.bottomBarColor.copy(alpha = uiColors.bottomBarColor.alpha * appSettings.bottomBarOpacity)
    val context = LocalContext.current

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

    // 退出搜索结果时自动退出选择模式
    LaunchedEffect(uiState.results) {
        if (uiState.results.isEmpty()) exitSelectionMode()
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
                        containerColor = barColor
                    )
                )
            } else {
                SearchTopBar(
                    query = uiState.query,
                    onQueryChange = viewModel::onQueryChange,
                    onSearch = viewModel::search,
                    suggestions = uiState.suggestions,
                    currentProvider = uiState.currentProvider,
                    availableProviders = uiState.availableProviders,
                    onProviderSwitch = viewModel::switchProvider,
                    barColor = barColor,
                    searchType = uiState.searchType,
                    supportedSearchTypes = uiState.supportedSearchTypes,
                    onSearchTypeSwitch = viewModel::switchSearchType
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
        when {
            // 加载中状态
            uiState.isLoading && uiState.results.isEmpty() && uiState.playlistResults.isEmpty() && uiState.albumResults.isEmpty() && uiState.artistResults.isEmpty() -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }
            // 错误状态
            uiState.error != null && uiState.results.isEmpty() && uiState.playlistResults.isEmpty() && uiState.albumResults.isEmpty() && uiState.artistResults.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.unknown_error),
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(padding)
                )
            }
            // 未搜索时显示热搜
            uiState.results.isEmpty() && uiState.playlistResults.isEmpty() && uiState.albumResults.isEmpty() && uiState.artistResults.isEmpty() && uiState.query.isEmpty() -> {
                HotSearchSection(
                    hotSearches = uiState.hotSearches,
                    onHotSearchClick = viewModel::onHotSearchClick,
                    searchHistory = uiState.searchHistory,
                    onHistoryClick = viewModel::onHistoryClick,
                    onRemoveHistory = viewModel::removeHistory,
                    onClearHistory = viewModel::clearHistory,
                    modifier = Modifier.padding(padding),
                    appSettings = appSettings
                )
            }
            // 搜索无结果
            uiState.results.isEmpty() && uiState.playlistResults.isEmpty() && uiState.albumResults.isEmpty() && uiState.artistResults.isEmpty() -> {
                EmptyState(modifier = Modifier.padding(padding))
            }
            // 歌单搜索结果
            uiState.searchType == SearchType.PLAYLIST && uiState.playlistResults.isNotEmpty() -> {
                PlaylistSearchResultsList(
                    results = uiState.playlistResults,
                    isLoading = uiState.isLoading,
                    hasMore = uiState.hasMore,
                    onPlaylistClick = { playlist ->
                        navController.navigate(Screen.Playlist.createRoute("${playlist.source}:${playlist.playListId}"))
                    },
                    onLoadMore = viewModel::loadMore,
                    modifier = Modifier.padding(padding)
                )
            }
            // 专辑搜索结果
            uiState.searchType == SearchType.ALBUM && uiState.albumResults.isNotEmpty() -> {
                val coroutineScope = rememberCoroutineScope()
                val savingAlbumText = stringResource(R.string.saving_album_as_playlist)
                var albumMenuTarget by remember { mutableStateOf<AlbumInfoResult?>(null) }
                var albumToDownload by remember { mutableStateOf<AlbumInfoResult?>(null) }
                AlbumSearchResultsList(
                    results = uiState.albumResults,
                    isLoading = uiState.isLoading,
                    hasMore = uiState.hasMore,
                    onAlbumClick = { album ->
                        AlbumPreviewCache.put(album)
                        navController.navigate(Screen.Album.createRoute("${album.source}:${album.albumId}"))
                    },
                    onAlbumLongClick = { album -> albumMenuTarget = album },
                    onLoadMore = viewModel::loadMore,
                    modifier = Modifier.padding(padding)
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
                                                val songs = SoundApplication.instance
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
                                                val pid = store.createPlaylistAndAdd(
                                                    playlistName,
                                                    songs.first()
                                                )
                                                songs.drop(1)
                                                    .forEach { store.addToPlaylist(pid, it) }
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
                        hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
                        onDismiss = { albumToDownload = null },
                        onConfirm = { qualityId ->
                            val album = albumToDownload!!
                            val hideAi =
                                viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                            val withYear =
                                viewModelFactory.appSettingsManager.settings.value.downloadAlbumFolderWithYear
                            val albumSubDir =
                                com.ikunshare.sound.manager.AlbumFolderNamer.buildSubDir(
                                    album,
                                    withYear
                                )
                            albumToDownload = null
                            coroutineScope.launch {
                                val songs = SoundApplication.instance
                                    ?.musicRepository
                                    ?.getAlbumSongsForSource(album.source, album.albumId)
                                    ?.result
                                    ?: emptyList()
                                if (songs.isNotEmpty()) {
                                    songs.forEachIndexed { index, song ->
                                        viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                                            song,
                                            qualityId,
                                            hideAi,
                                            deferSchedule = true,
                                            subDir = albumSubDir,
                                            trackNumber = index + 1
                                        )
                                    }
                                    viewModelFactory.downloadManager.flushSchedule()
                                    Toast.makeText(
                                        context,
                                        context.getString(
                                            R.string.added_to_download_queue_count,
                                            songs.size
                                        ),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.load_album_failed, ""),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
            // 歌手搜索结果
            uiState.searchType == SearchType.ARTIST && uiState.artistResults.isNotEmpty() -> {
                ArtistSearchResultsList(
                    results = uiState.artistResults,
                    isLoading = uiState.isLoading,
                    hasMore = uiState.hasMore,
                    onArtistClick = { artist ->
                        ArtistPreviewCache.put(artist)
                        navController.navigate(
                            Screen.Artist.createRoute("${artist.source}:${artist.artistId}")
                        )
                    },
                    onLoadMore = viewModel::loadMore,
                    modifier = Modifier.padding(padding)
                )
            }
            // 搜索结果列表
            else -> {
                val playerUiState by playerViewModel.uiState.collectAsState()
                SearchResultsList(
                    results = uiState.results,
                    isLoading = uiState.isLoading,
                    hasMore = uiState.hasMore,
                    currentSong = playerUiState.currentSong,
                    onSongClick = { song ->
                        if (selectionMode) {
                            toggleSelect(song)
                        } else {
                            onSongClick(uiState.results, song)
                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                navController.navigate(Screen.Player.createRoute(song.id))
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
                        val index =
                            uiState.results.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(uiState.results, index)
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
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }

    // 添加到歌单对话框
    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistClick = { playlistId ->
                selectedSongs.forEach { song ->
                    viewModelFactory.localMusicStore.addToPlaylist(playlistId, song)
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
                        val playlistId = store.createPlaylistAndAdd(name, songs.first())
                        songs.drop(1).forEach { store.addToPlaylist(playlistId, it) }
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
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
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
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    suggestions: List<String>,
    currentProvider: String,
    availableProviders: List<String>,
    onProviderSwitch: (String) -> Unit,
    barColor: Color = MaterialTheme.colorScheme.surface,
    searchType: SearchType = SearchType.SONG,
    supportedSearchTypes: List<SearchType> = listOf(SearchType.SONG),
    onSearchTypeSwitch: (SearchType) -> Unit = {}
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var showProviderMenu by remember { mutableStateOf(false) }

    Column {
        // 顶部搜索栏
        TopAppBar(
            navigationIcon = {
                // Provider 切换按钮
                Box {
                    Box(
                        modifier = Modifier
                            .clickable { showProviderMenu = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = stringResource(R.string.switch_platform),
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = SoundApplication.instance?.musicRepository?.getProvider(
                                    currentProvider
                                )?.displayName ?: currentProvider,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = showProviderMenu,
                        onDismissRequest = { showProviderMenu = false }
                    ) {
                        availableProviders.forEach { source ->
                            val displayName =
                                SoundApplication.instance?.musicRepository?.getProvider(source)?.displayName
                                    ?: source
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = displayName,
                                        color = if (source == currentProvider) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.onBackground
                                    )
                                },
                                onClick = {
                                    showProviderMenu = false
                                    onProviderSwitch(source)
                                }
                            )
                        }
                    }
                }
            },
            title = {
                TextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.search_placeholder),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        imeAction = ImeAction.Search
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch()
                            keyboardController?.hide()
                        }
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.tertiary,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            actions = {
                // 清除按钮（仅在有输入时显示）
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.clear_search),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // 搜索按钮
                IconButton(onClick = {
                    onSearch()
                    keyboardController?.hide()
                }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.nav_search),
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = barColor
            )
        )

        // 搜索类型切换（仅在支持多种类型时显示）
        if (supportedSearchTypes.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                supportedSearchTypes.forEach { type ->
                    val selected = type == searchType
                    SuggestionChip(
                        onClick = { onSearchTypeSwitch(type) },
                        label = {
                            Text(
                                type.displayName,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.tertiary.copy(
                                alpha = 0.15f
                            )
                            else MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = if (selected) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }

        // 搜索建议下拉列表
        if (suggestions.isNotEmpty()) {
            SearchSuggestionsList(
                suggestions = suggestions,
                onSuggestionClick = { suggestion ->
                    onQueryChange(suggestion)
                    onSearch()
                    keyboardController?.hide()
                }
            )
        }
    }
}

@Composable
private fun SearchSuggestionsList(
    suggestions: List<String>,
    onSuggestionClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        suggestions.forEach { suggestion ->
            Text(
                text = suggestion,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSuggestionClick(suggestion) }
                    .padding(vertical = 12.dp, horizontal = 8.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HotSearchSection(
    hotSearches: List<String>,
    onHotSearchClick: (String) -> Unit,
    searchHistory: List<String>,
    onHistoryClick: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
    appSettings: AppSettings
) {
    val scrollState = rememberScrollState()
    val scrollOffsetReporter = LocalScrollOffsetReporter.current
    var previousOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }
            .collect { scrollOffset ->
                val isScrollingUp = scrollOffset < previousOffset
                scrollOffsetReporter?.invoke(0, scrollOffset, isScrollingUp)
                previousOffset = scrollOffset
            }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        // 搜索历史区域
        if (searchHistory.isNotEmpty()) {
            // 标题行：图标 + 文字 + 清空按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.search_history),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.clear),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onClearHistory() }
                )
            }

            // 搜索历史标签流式布局
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                searchHistory.forEach { keyword ->
                    SuggestionChip(
                        onClick = { onHistoryClick(keyword) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = keyword,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(R.string.delete),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { onRemoveHistory(keyword) }
                                )
                            }
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onBackground
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // 热搜标题
        if (hotSearches.isNotEmpty()) {
            // 标题行：图标 + 文字
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.LocalFireDepartment,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.hot_searches),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }

            // 热搜关键词标签流式布局
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                hotSearches.forEach { keyword ->
                    SuggestionChip(
                        onClick = { onHotSearchClick(keyword) },
                        label = {
                            Text(
                                text = keyword,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onBackground
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.outline
                        )
                    )
                }
            }

            if (appSettings.liquidGlassMode) {
                Spacer(modifier = Modifier.height(160.dp))
            }
        } else if (searchHistory.isEmpty()) {
            // 热搜和历史都为空时显示提示
            EmptyState(message = stringResource(R.string.no_hot_search))
        }
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
    currentSong: MusicItem? = null,
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
    RememberScrollOffsetReporter(listState)

    // 无限滚动检测
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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        itemsIndexed(
            items = results,
            key = { index, song -> "${song.getTypeDiscriminator()}_${song.id}_$index" }
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
                    selected = selectedSongs.any { it.id == song.id },
                    isPlaying = currentSong?.id == song.id &&
                            currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                    onLongClick = { onLongClick?.invoke(song) },
                    onCheckedChange = { onCheckedChange?.invoke(song) }
                )
                SongContextMenu(
                    expanded = contextMenuSong?.id == song.id,
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
            Spacer(modifier = Modifier.height(80.dp))
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
    RememberScrollOffsetReporter(listState)

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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = results,
            key = { "${it.source}_${it.playListId}" }
        ) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlaylistClick(playlist) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = playlist.img,
                    contentDescription = playlist.title,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.title ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        playlist.author?.let { append(it) }
                        if (playlist.totalSongs > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${playlist.totalSongs}章")
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
            Spacer(modifier = Modifier.height(80.dp))
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
    RememberScrollOffsetReporter(listState)

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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = results,
            key = { "${it.source}_${it.albumId}" }
        ) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onAlbumClick(album) },
                        onLongClick = { onAlbumLongClick(album) }
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = album.cover,
                    contentDescription = album.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        album.artist?.takeIf { it.isNotBlank() }?.let { append(it) }
                        if (album.size > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${album.size}首")
                        }
                        album.subType?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
            Spacer(modifier = Modifier.height(80.dp))
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
    RememberScrollOffsetReporter(listState)

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

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        items(
            items = results,
            key = { "${it.source}_${it.artistId}" }
        ) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onArtistClick(artist) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = artist.avatar,
                    contentDescription = artist.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = artist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = buildString {
                        if (artist.albumCount > 0) {
                            append("${artist.albumCount}张专辑")
                        }
                        if (artist.musicCount > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${artist.musicCount}首")
                        }
                    }
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
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
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}
