package com.ikunshare.sound.ui.screens.artist

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.AlbumPreviewCache
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.ArtistMvItem
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.AddToPlaylistDialog
import com.ikunshare.sound.ui.components.CreatePlaylistDialog
import com.ikunshare.sound.ui.components.DownloadQualityDialog
import com.ikunshare.sound.ui.components.EmptyState
import com.ikunshare.sound.ui.components.ErrorState
import com.ikunshare.sound.ui.components.LoadingIndicator
import com.ikunshare.sound.ui.components.MvDialog
import com.ikunshare.sound.ui.components.SelectionBottomBar
import com.ikunshare.sound.ui.components.SongCard
import com.ikunshare.sound.ui.components.SongContextMenu
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.launch

@SuppressLint("LocalContextGetResourceValueCall")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    navController: NavController,
    artistKey: String,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: ArtistViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModelFactory.appSettingsManager.settings.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(artistKey) {
        viewModel.loadArtist(artistKey)
    }

    var selectionMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<MusicItem>() }

    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedSongs.clear()
    }

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showQualityPicker by remember { mutableStateOf(false) }
    val playlists by viewModelFactory.localMusicStore.playlistsFlow.collectAsState()

    var contextMenuSong by remember { mutableStateOf<MusicItem?>(null) }
    var singleDownloadSong by remember { mutableStateOf<MusicItem?>(null) }
    var singlePlaylistSong by remember { mutableStateOf<MusicItem?>(null) }
    var mvDialogSong by remember { mutableStateOf<MusicItem?>(null) }
    var albumToDownload by remember { mutableStateOf<AlbumInfoResult?>(null) }
    val scope = rememberCoroutineScope()

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
                TopAppBar(
                    title = {
                        Text(
                            text = uiState.artistInfo?.name ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        },
        bottomBar = {
            if (selectionMode && selectedSongs.isNotEmpty()) {
                val appSettings by viewModelFactory.appSettingsManager.settings.collectAsState()
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
                    onDownload = { showQualityPicker = true },
                    onAddToPlaylist = { showPlaylistPicker = true },
                    floatingBottomBar = appSettings.floatingBottomBar,
                    liquidGlass = appSettings.liquidGlassMode
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        when {
            uiState.isLoading && uiState.songs.isEmpty() && uiState.artistInfo == null ->
                LoadingIndicator(modifier = Modifier.padding(padding))

            uiState.error != null && uiState.songs.isEmpty() && uiState.artistInfo == null ->
                ErrorState(
                    message = uiState.error
                        ?: stringResource(R.string.unknown_error),
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(padding)
                )

            uiState.songs.isEmpty() && uiState.artistInfo == null ->
                EmptyState(modifier = Modifier.padding(padding))

            else -> ArtistContent(
                uiState = uiState,
                viewModel = viewModel,
                playerViewModel = playerViewModel,
                navController = navController,
                viewModelFactory = viewModelFactory,
                onPlayAll = {
                    val songs = viewModel.playAll()
                    if (!songs.isNullOrEmpty()) {
                        playerViewModel.setPlaylist(songs, 0)
                        navController.navigate(Screen.Player.createRoute(songs.first().id))
                    }
                },
                selectionMode = selectionMode,
                selectedSongs = selectedSongs,
                onToggleSelect = ::toggleSelect,
                onLongClick = { song ->
                    if (selectionMode) toggleSelect(song)
                    else contextMenuSong = song
                },
                contextMenuSong = contextMenuSong,
                onContextMenuDismiss = { contextMenuSong = null },
                onContextDownload = { song -> singleDownloadSong = song },
                onContextAddToPlaylist = { song -> singlePlaylistSong = song },
                onContextMultiSelect = ::enterSelection,
                onAlbumClick = { album ->
                    AlbumPreviewCache.put(album)
                    navController.navigate(
                        Screen.Album.createRoute("${album.source}:${album.albumId}")
                    )
                },
                onAlbumLongClick = { albumToDownload = it },
                albumListMode = settings.albumListMode,
                onToggleListMode = { listMode ->
                    viewModelFactory.appSettingsManager.update { copy(albumListMode = listMode) }
                },
                onMvClick = { mv ->
                    val item = viewModel.buildMvItem(mv)
                    if (item != null) mvDialogSong = item
                },
                onTabSelected = { tab ->
                    if (tab != ArtistTab.SONGS) exitSelectionMode()
                    viewModel.selectTab(tab)
                },
                modifier = Modifier.padding(padding)
            )
        }
    }

    mvDialogSong?.let { song ->
        MvDialog(
            song = song,
            sourceTag = uiState.artistInfo?.source ?: "",
            onDismiss = { mvDialogSong = null }
        )
    }

    ArtistDialogs(
        viewModelFactory = viewModelFactory,
        playlists = playlists,
        selectedSongs = selectedSongs,
        showPlaylistPicker = showPlaylistPicker,
        onPlaylistPickerChange = { showPlaylistPicker = it },
        showCreateDialog = showCreateDialog,
        onCreateDialogChange = { showCreateDialog = it },
        showQualityPicker = showQualityPicker,
        onQualityPickerChange = { showQualityPicker = it },
        singleDownloadSong = singleDownloadSong,
        onSingleDownloadSongChange = { singleDownloadSong = it },
        singlePlaylistSong = singlePlaylistSong,
        onSinglePlaylistSongChange = { singlePlaylistSong = it },
        onExitSelectionMode = ::exitSelectionMode
    )

    if (albumToDownload != null) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { albumToDownload = null },
            onConfirm = { qualityId ->
                val album = albumToDownload!!
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                val withYear =
                    viewModelFactory.appSettingsManager.settings.value.downloadAlbumFolderWithYear
                val albumSubDir =
                    com.ikunshare.sound.manager.AlbumFolderNamer.buildSubDir(album, withYear)
                albumToDownload = null
                scope.launch {
                    val songs = viewModel.getAlbumSongs(album.source, album.albumId)
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
                            context.getString(R.string.added_to_download_queue_count, songs.size),
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

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ArtistDialogs(
    viewModelFactory: KunyinViewModelFactory,
    playlists: List<com.ikunshare.sound.database.Playlist>,
    selectedSongs: List<MusicItem>,
    showPlaylistPicker: Boolean,
    onPlaylistPickerChange: (Boolean) -> Unit,
    showCreateDialog: Boolean,
    onCreateDialogChange: (Boolean) -> Unit,
    showQualityPicker: Boolean,
    onQualityPickerChange: (Boolean) -> Unit,
    singleDownloadSong: MusicItem?,
    onSingleDownloadSongChange: (MusicItem?) -> Unit,
    singlePlaylistSong: MusicItem?,
    onSinglePlaylistSongChange: (MusicItem?) -> Unit,
    onExitSelectionMode: () -> Unit
) {
    val context = LocalContext.current

    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { onPlaylistPickerChange(false) },
            onPlaylistClick = { pid ->
                selectedSongs.forEach { song ->
                    viewModelFactory.localMusicStore.addToPlaylist(pid, song)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_playlist_count, selectedSongs.size),
                    Toast.LENGTH_SHORT
                ).show()
                onPlaylistPickerChange(false)
                onExitSelectionMode()
            },
            onCreateNew = {
                onPlaylistPickerChange(false)
                onCreateDialogChange(true)
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { onCreateDialogChange(false); onSinglePlaylistSongChange(null) },
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
                    onSinglePlaylistSongChange(null)
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
                    onExitSelectionMode()
                }
                onCreateDialogChange(false)
            }
        )
    }

    if (showQualityPicker) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { onQualityPickerChange(false) },
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
                onQualityPickerChange(false)
                onExitSelectionMode()
            }
        )
    }

    if (singleDownloadSong != null) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { onSingleDownloadSongChange(null) },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                singleDownloadSong.let { song ->
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
                onSingleDownloadSongChange(null)
            }
        )
    }

    if (singlePlaylistSong != null && !showCreateDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            isFavorite = viewModelFactory.localMusicStore.isFavorite(singlePlaylistSong),
            onFavoriteClick = {
                viewModelFactory.localMusicStore.addFavorite(singlePlaylistSong)
                onSinglePlaylistSongChange(null)
            },
            onDismiss = { onSinglePlaylistSongChange(null) },
            onPlaylistClick = { pid ->
                viewModelFactory.localMusicStore.addToPlaylist(pid, singlePlaylistSong)
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_playlist_count, 1),
                    Toast.LENGTH_SHORT
                ).show()
                onSinglePlaylistSongChange(null)
            },
            onCreateNew = {
                onCreateDialogChange(true)
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistContent(
    uiState: ArtistUiState,
    viewModel: ArtistViewModel,
    playerViewModel: PlayerViewModel,
    navController: NavController,
    viewModelFactory: KunyinViewModelFactory,
    onPlayAll: () -> Unit,
    selectionMode: Boolean,
    selectedSongs: List<MusicItem>,
    onToggleSelect: (MusicItem) -> Unit,
    onLongClick: (MusicItem) -> Unit,
    contextMenuSong: MusicItem?,
    onContextMenuDismiss: () -> Unit,
    onContextDownload: (MusicItem) -> Unit,
    onContextAddToPlaylist: (MusicItem) -> Unit,
    onContextMultiSelect: (MusicItem) -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    albumListMode: Boolean,
    onToggleListMode: (Boolean) -> Unit,
    onMvClick: (ArtistMvItem) -> Unit,
    onTabSelected: (ArtistTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = buildList {
        add(ArtistTab.SONGS to stringResource(R.string.artist_tab_songs))
        if (uiState.showAlbumsTab) {
            val n = uiState.artistInfo?.albumCount ?: 0
            add(
                ArtistTab.ALBUMS to stringResource(R.string.artist_tab_albums) +
                        if (n > 0) " $n" else ""
            )
        }
        if (uiState.showMvsTab) {
            add(ArtistTab.MVS to stringResource(R.string.artist_tab_mvs))
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
        ArtistHeader(
            info = uiState.artistInfo,
            songCount = uiState.songs.size,
            onPlayAll = onPlayAll
        )
        if (tabs.size > 1) {
            val selectedIndex = tabs.indexOfFirst { it.first == uiState.selectedTab }
                .coerceAtLeast(0)
            PrimaryTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.tertiary
            ) {
                tabs.forEach { (tab, label) ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { onTabSelected(tab) },
                        text = { Text(label) }
                    )
                }
            }
        }
        when (uiState.selectedTab) {
            ArtistTab.SONGS -> ArtistSongsTab(
                songs = uiState.songs,
                hasMore = uiState.songsHasMore,
                onLoadMore = viewModel::loadMoreSongs,
                playerViewModel = playerViewModel,
                navController = navController,
                viewModelFactory = viewModelFactory,
                selectionMode = selectionMode,
                selectedSongs = selectedSongs,
                onToggleSelect = onToggleSelect,
                onLongClick = onLongClick,
                contextMenuSong = contextMenuSong,
                onContextMenuDismiss = onContextMenuDismiss,
                onContextDownload = onContextDownload,
                onContextAddToPlaylist = onContextAddToPlaylist,
                onContextMultiSelect = onContextMultiSelect
            )

            ArtistTab.ALBUMS -> ArtistAlbumsTab(
                albums = uiState.albums,
                loading = uiState.albumsLoading,
                hasMore = uiState.albumsHasMore,
                onLoadMore = viewModel::loadMoreAlbums,
                onAlbumClick = onAlbumClick,
                onAlbumLongClick = onAlbumLongClick,
                isListMode = albumListMode,
                onToggleListMode = onToggleListMode
            )

            ArtistTab.MVS -> ArtistMvsTab(
                mvs = uiState.mvs,
                loading = uiState.mvsLoading,
                hasMore = uiState.mvsHasMore,
                onLoadMore = viewModel::loadMoreMvs,
                onMvClick = onMvClick
            )
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ArtistSongsTab(
    songs: List<MusicItem>,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    playerViewModel: PlayerViewModel,
    navController: NavController,
    viewModelFactory: KunyinViewModelFactory,
    selectionMode: Boolean,
    selectedSongs: List<MusicItem>,
    onToggleSelect: (MusicItem) -> Unit,
    onLongClick: (MusicItem) -> Unit,
    contextMenuSong: MusicItem?,
    onContextMenuDismiss: () -> Unit,
    onContextDownload: (MusicItem) -> Unit,
    onContextAddToPlaylist: (MusicItem) -> Unit,
    onContextMultiSelect: (MusicItem) -> Unit
) {
    val context = LocalContext.current
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentSong = playerUiState.currentSong
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            last >= total - 3 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = songs, key = { it.uniqueKey }) { song ->
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
                    onClick = {
                        if (selectionMode) {
                            onToggleSelect(song)
                        } else {
                            val store = viewModelFactory.localMusicStore
                            val atHead = viewModelFactory.appSettingsManager
                                .settings.value.trialListAddToHead
                            store.addToTrial(song, atHead)
                            val current = store.queryTrialSongs().toMutableList()
                            val key = "${song.id}_${song.getTypeDiscriminator()}"
                            current.removeAll {
                                "${it.id}_${it.getTypeDiscriminator()}" == key
                            }
                            if (atHead) current.add(0, song) else current.add(song)
                            val targetIndex = current.indexOfFirst {
                                "${it.id}_${it.getTypeDiscriminator()}" == key
                            }.coerceAtLeast(0)
                            playerViewModel.setPlaylistAndPlay(current, targetIndex)
                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                navController.navigate(Screen.Player.createRoute(song.id))
                            }
                        }
                    },
                    selectionMode = selectionMode,
                    selected = selectedSongs.any { it.id == song.id },
                    isPlaying = currentSong?.id == song.id,
                    onLongClick = { onLongClick(song) },
                    onCheckedChange = { onToggleSelect(song) }
                )
                SongContextMenu(
                    expanded = contextMenuSong?.id == song.id,
                    isFavorite = viewModelFactory.localMusicStore.isFavorite(song),
                    onDismiss = onContextMenuDismiss,
                    onPlay = {
                        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(songs, index)
                    },
                    onPlayNext = {
                        playerViewModel.addToPlayNext(song)
                        Toast.makeText(
                            context,
                            context.getString(R.string.added_to_play_next),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onDownload = { onContextDownload(song) },
                    onToggleFavorite = {
                        val store = viewModelFactory.localMusicStore
                        if (store.isFavorite(song)) store.removeFavorite(song)
                        else store.addFavorite(song)
                    },
                    onAddToPlaylist = { onContextAddToPlaylist(song) },
                    onMultiSelect = { onContextMultiSelect(song) },
                    offset = menuOffset,
                    mvSong = song
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistAlbumsTab(
    albums: List<AlbumInfoResult>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    isListMode: Boolean,
    onToggleListMode: (Boolean) -> Unit
) {
    if (albums.isEmpty() && loading) {
        LoadingIndicator()
        return
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = { onToggleListMode(!isListMode) }) {
                Icon(
                    imageVector = if (isListMode) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isListMode) {
            AlbumListLayout(albums, loading, hasMore, onLoadMore, onAlbumClick, onAlbumLongClick)
        } else {
            AlbumGridLayout(albums, loading, hasMore, onLoadMore, onAlbumClick, onAlbumLongClick)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridLayout(
    albums: List<AlbumInfoResult>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit
) {
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            last >= total - 4 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = albums, key = { "${it.source}_${it.albumId}" }) { album ->
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = { onAlbumClick(album) },
                    onLongClick = { onAlbumLongClick(album) }
                )
            ) {
                AsyncImage(
                    model = album.cover,
                    contentDescription = album.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = album.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val sub = buildString {
                    album.publishTime?.let { append(formatYear(it)) }
                    album.subType?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append(" · ")
                        append(it)
                    }
                }
                if (sub.isNotEmpty()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        if (loading && albums.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumListLayout(
    albums: List<AlbumInfoResult>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            last >= total - 4 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = albums, key = { "${it.source}_${it.albumId}" }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onAlbumClick(album) },
                        onLongClick = { onAlbumLongClick(album) }
                    )
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = album.cover,
                    contentDescription = album.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = album.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val info = buildString {
                        album.artist?.takeIf { it.isNotBlank() }?.let { append(it) }
                        album.publishTime?.let {
                            if (isNotEmpty()) append(" · ")
                            append(formatYear(it))
                        }
                        album.subType?.takeIf { it.isNotBlank() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
                        }
                        if (album.size > 0) {
                            if (isNotEmpty()) append(" · ")
                            append("${album.size}首")
                        }
                    }
                    if (info.isNotEmpty()) {
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
        if (loading && albums.isNotEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistMvsTab(
    mvs: List<ArtistMvItem>,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onMvClick: (ArtistMvItem) -> Unit
) {
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            last >= total - 4 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    if (mvs.isEmpty() && loading) {
        LoadingIndicator()
        return
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = mvs, key = { "${it.source}_${it.vid}" }) { mv ->
            Column(
                modifier = Modifier.clickable { onMvClick(mv) }
            ) {
                Box {
                    AsyncImage(
                        model = mv.cover,
                        contentDescription = mv.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    if (mv.duration > 0) {
                        Text(
                            text = formatDuration(mv.duration),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(4.dp)
                                .background(
                                    Color.Black.copy(alpha = 0.6f),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = mv.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (mv.playCount > 0) {
                    Text(
                        text = stringResource(R.string.mv_play_count, formatFans(mv.playCount)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
        if (loading && mvs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}

/** 秒数 → m:ss。 */
private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

/** epoch 毫秒 → 年份字符串。 */
private fun formatYear(epochMillis: Long): String =
    java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))

@Composable
private fun ArtistHeader(
    info: ArtistInfoResult?,
    songCount: Int,
    onPlayAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = info?.avatar,
            contentDescription = info?.name,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = info?.name ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val meta = buildString {
                val albumCount = info?.albumCount ?: 0
                if (albumCount > 0) {
                    append(stringResource(R.string.artist_album_count, albumCount))
                }
                val musicCount = info?.musicCount ?: 0
                val shownSongCount = if (musicCount > 0) musicCount else songCount
                if (shownSongCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.artist_song_count, shownSongCount))
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            info?.fansCount?.takeIf { it > 0 }?.let {
                Text(
                    text = stringResource(R.string.artist_fans_count, formatFans(it)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onPlayAll,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary
                )
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.play_all))
            }
        }
    }
    info?.description?.takeIf { it.isNotBlank() }?.let { desc ->
        Text(
            text = desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            maxLines = 6,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** 粉丝数缩写：>= 10000 显示「万」，否则原样。 */
private fun formatFans(count: Long): String = when {
    count >= 100_000_000L -> "${count / 100_000_000L}.${(count % 100_000_000L) / 10_000_000L}亿"
    count >= 10_000L -> "${count / 10_000L}.${(count % 10_000L) / 1_000L}万"
    else -> count.toString()
}
