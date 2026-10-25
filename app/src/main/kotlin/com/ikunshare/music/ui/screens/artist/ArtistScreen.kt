package com.ikunshare.music.ui.screens.artist

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Checkbox
import com.ikunshare.music.manager.enqueueAlbumDownloadBatch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.ikunshare.music.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import com.ikunshare.music.ui.components.GlassTopBar as TopAppBar
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.music.R
import com.ikunshare.music.common.albumReleaseYear
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.QueueSource
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumPreviewCache
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.ArtistMvItem
import com.ikunshare.music.ui.utils.rememberPlaylistWriteActions
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.AddToPlaylistDialog
import com.ikunshare.music.ui.components.CreatePlaylistDialog
import com.ikunshare.music.ui.components.DownloadQualityDialog
import com.ikunshare.music.ui.components.EmptyState
import com.ikunshare.music.ui.components.LiquidGlassScene
import com.ikunshare.music.ui.components.MediaDetailHeader
import com.ikunshare.music.ui.components.DetailLoadStatus
import com.ikunshare.music.ui.components.GlassButton
import com.ikunshare.music.ui.components.liquidGlass
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.ikunshare.music.ui.components.ErrorState
import com.ikunshare.music.ui.components.LoadingIndicator
import com.ikunshare.music.ui.components.MvDialog
import com.ikunshare.music.ui.components.SelectionBottomBar
import com.ikunshare.music.ui.components.SongCard
import com.ikunshare.music.ui.components.SongContextMenu
import com.ikunshare.music.ui.navigation.Screen
import com.ikunshare.music.ui.screens.player.PlayerViewModel
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
    val playerSheet = LocalPlayerSheet.current
    val viewModel: ArtistViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val settings by viewModelFactory.appSettingsManager.settings.collectAsState()
    val sortedAlbums = remember(uiState.albums, settings.albumSortAsc) {
        sortArtistAlbums(uiState.albums, settings.albumSortAsc)
    }
    val queueSource = QueueSource("artist", artistKey, uiState.artistInfo?.name.orEmpty())
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val activePageError = when (uiState.selectedTab) {
        ArtistTab.SONGS -> uiState.songsError
        ArtistTab.ALBUMS -> uiState.albumsError
        ArtistTab.MVS -> uiState.mvsError
    }
    LaunchedEffect(uiState.selectedTab, activePageError) {
        activePageError?.let { error ->
            if (snackbar.showSnackbar(error, context.getString(R.string.retry), duration = SnackbarDuration.Indefinite) == SnackbarResult.ActionPerformed) {
                when (uiState.selectedTab) {
                    ArtistTab.SONGS -> viewModel.retrySongs()
                    ArtistTab.ALBUMS -> viewModel.retryAlbums()
                    ArtistTab.MVS -> viewModel.retryMvs()
                }
            }
        }
    }

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
    var albumSelecting by remember { mutableStateOf(false) }
    val selectedAlbums = remember { mutableStateListOf<AlbumInfoResult>() }
    var albumDownloading by remember { mutableStateOf(false) }
    var showAlbumQuality by remember { mutableStateOf(false) }
    var albumDownloadJob by remember { mutableStateOf<Job?>(null) }
    fun exitAlbumSelection() {
        albumDownloadJob?.cancel()
        albumSelecting = false
        selectedAlbums.clear()
        showAlbumQuality = false
    }
    fun toggleAlbum(album: AlbumInfoResult) {
        if (albumDownloading) return
        val index = selectedAlbums.indexOfFirst { it.source == album.source && it.albumId == album.albumId }
        if (index >= 0) selectedAlbums.removeAt(index) else selectedAlbums.add(album)
    }
    androidx.activity.compose.BackHandler(enabled = albumSelecting) { exitAlbumSelection() }
    LaunchedEffect(artistKey) {
        selectionMode = false
        selectedSongs.clear()
        contextMenuSong = null
        singleDownloadSong = null
        singlePlaylistSong = null
        mvDialogSong = null
        showPlaylistPicker = false
        showCreateDialog = false
        showQualityPicker = false
        exitAlbumSelection()
    }
    val scope = rememberCoroutineScope()

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

    LiquidGlassScene(uiState.artistInfo?.avatar) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = LocalFloatingBottomBarReserve.current)) },
            topBar = {
                if (selectionMode || albumSelecting) {
                    TopAppBar(
                        title = { Text(if (albumSelecting) stringResource(R.string.selected_album_count, selectedAlbums.size)
                            else stringResource(R.string.selected_count, selectedSongs.size)) },
                        navigationIcon = {
                            IconButton(onClick = { if (albumSelecting) exitAlbumSelection() else exitSelectionMode() }) {
                                Icon(
                                    Icons.Filled.Close,
                                    contentDescription = stringResource(R.string.cancel_selection)
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = {
                                if (albumSelecting) {
                                    if (!albumDownloading) {
                                        if (selectedAlbums.size == uiState.albums.size) selectedAlbums.clear()
                                        else { selectedAlbums.clear(); selectedAlbums.addAll(sortedAlbums) }
                                    }
                                } else if (selectedSongs.size == uiState.songs.size) {
                                    selectedSongs.clear()
                                } else {
                                    selectedSongs.clear()
                                    selectedSongs.addAll(uiState.songs)
                                }
                            }, enabled = !albumSelecting || !albumDownloading) {
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
                        actions = {
                            if (uiState.selectedTab == ArtistTab.ALBUMS) IconButton(onClick = { albumSelecting = true }, enabled = uiState.albums.isNotEmpty()) {
                                Icon(Icons.Filled.Checklist, stringResource(R.string.artist_select_albums))
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                }
            },
            bottomBar = {
                if (albumSelecting) {
                    Row(Modifier.fillMaxWidth().padding(bottom = LocalFloatingBottomBarReserve.current)
                        .padding(16.dp).liquidGlass().padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.selected_album_count, selectedAlbums.size), Modifier.weight(1f))
                        GlassButton(onClick = { showAlbumQuality = true }, enabled = selectedAlbums.isNotEmpty() && !albumDownloading, accented = true) {
                            if (albumDownloading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Filled.Download, null, Modifier.size(20.dp))
                            Text(stringResource(if (albumDownloading) R.string.artist_albums_downloading else R.string.download))
                        }
                    }
                } else if (selectionMode && selectedSongs.isNotEmpty()) {
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
                        onAddToPlaylist = { showPlaylistPicker = true }
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
                    detailKey = artistKey,
                    uiState = uiState,
                    viewModel = viewModel,
                    playerViewModel = playerViewModel,
                    navController = navController,
                    viewModelFactory = viewModelFactory,
                    onPlayAll = {
                        val songs = viewModel.playAll()
                        if (!songs.isNullOrEmpty()) {
                            playerViewModel.setPlaylistAndPlay(songs, 0, queueSource)
                            playerSheet.open(songs.first())
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
                        if (albumSelecting) toggleAlbum(album) else {
                        AlbumPreviewCache.put(album)
                        navController.navigate(
                            Screen.Album.createRoute("${album.source}:${album.albumId}")
                        )
                        }
                    },
                    onAlbumLongClick = { album ->
                        if (!albumSelecting) { albumSelecting = true; selectedAlbums.clear() }
                        toggleAlbum(album)
                    },
                    albumSelecting = albumSelecting,
                    albumSelectionBusy = albumDownloading,
                    selectedAlbumKeys = selectedAlbums.map { "${it.source}:${it.albumId}" }.toSet(),
                    queueSource = queueSource,
                    sortedAlbums = sortedAlbums,
                    albumSortAsc = settings.albumSortAsc,
                    onToggleAlbumSort = { viewModelFactory.appSettingsManager.update { copy(albumSortAsc = !albumSortAsc) } },
                    albumListMode = settings.albumListMode,
                    onToggleListMode = { listMode ->
                        viewModelFactory.appSettingsManager.update { copy(albumListMode = listMode) }
                    },
                    onMvClick = { mv ->
                        val item = viewModel.buildMvItem(mv)
                        if (item != null) mvDialogSong = item
                    },
                    onTabSelected = { tab ->
                        if (albumDownloading) return@ArtistContent
                        if (tab != ArtistTab.SONGS) exitSelectionMode()
                        if (tab != ArtistTab.ALBUMS) exitAlbumSelection()
                        viewModel.selectTab(tab)
                    },
                    modifier = Modifier.padding(padding)
                )
            }
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
        artistKey = artistKey,
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

    if (showAlbumQuality) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { showAlbumQuality = false },
            onConfirm = { qualityId ->
                val targets = selectedAlbums.toList()
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                val withYear =
                    viewModelFactory.appSettingsManager.settings.value.downloadAlbumFolderWithYear
                        showAlbumQuality = false
                albumDownloading = true
                albumDownloadJob = scope.launch {
                    try {
                        val result = enqueueAlbumDownloadBatch(targets, withYear, uiState.artistInfo?.name,
                            fetch = { viewModel.getAlbumResult(it.source, it.albumId) },
                            enqueueDisc = { song, folder, number, disc -> viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                                song, qualityId, hideAi, deferSchedule = true, subDir = folder, trackNumber = number, disc = disc) },
                            enqueue = { song, folder, number -> viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                                song, qualityId, hideAi, deferSchedule = true, subDir = folder, trackNumber = number) },
                            flush = viewModelFactory.downloadManager::flushSchedule)
                        val message = if (result.failedAlbums.isEmpty()) context.getString(if (result.queuedSongs > 0) R.string.artist_album_download_done else R.string.download_not_added, result.queuedSongs)
                            else context.getString(R.string.artist_album_download_result, result.queuedSongs, result.failedAlbums.size)
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        selectedAlbums.clear()
                        selectedAlbums.addAll(result.failedAlbums)
                        if (result.failedAlbums.isEmpty()) albumSelecting = false
                    } catch (cancelled: CancellationException) { throw cancelled }
                    finally { albumDownloading = false }
                }
            }
        )
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ArtistDialogs(
    artistKey: String,
    viewModelFactory: KunyinViewModelFactory,
    playlists: List<com.ikunshare.music.database.Playlist>,
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
    val playlistWrites = rememberPlaylistWriteActions(viewModelFactory.localMusicStore)
    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(viewModelFactory.downloadManager, artistKey)

    if (showPlaylistPicker) {
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { onPlaylistPickerChange(false) },
            busy = playlistWrites.isWriting,
            onPlaylistClick = { pid ->
                playlistWrites.add(pid, selectedSongs.toList()) {
                    onPlaylistPickerChange(false)
                    onExitSelectionMode()
                }
            },
            onCreateNew = {
                onPlaylistPickerChange(false)
                onCreateDialogChange(true)
            }
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            busy = playlistWrites.isWriting,
            onDismiss = { onCreateDialogChange(false); onSinglePlaylistSongChange(null) },
            onConfirm = { name ->
                val songs = singlePlaylistSong?.let { listOf(it) } ?: selectedSongs.toList()
                playlistWrites.create(name, songs) {
                    onCreateDialogChange(false)
                    onSinglePlaylistSongChange(null)
                    onExitSelectionMode()
                }
            }
        )
    }

    if (showQualityPicker) {
        DownloadQualityDialog(
            busy = downloadQueue.isEnqueuing,
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { onQualityPickerChange(false) },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                downloadQueue.add(selectedSongs.toList(), qualityId, hideAi) {
                    onQualityPickerChange(false)
                    onExitSelectionMode()
                }
            }
        )
    }

    if (singleDownloadSong != null) {
        DownloadQualityDialog(
            busy = downloadQueue.isEnqueuing,
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { onSingleDownloadSongChange(null) },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                singleDownloadSong.let { song ->
                    downloadQueue.add(listOf(song), qualityId, hideAi) { onSingleDownloadSongChange(null) }
                }
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
    detailKey: String,
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
    albumSelecting: Boolean,
    albumSelectionBusy: Boolean,
    selectedAlbumKeys: Set<String>,
    queueSource: QueueSource,
    sortedAlbums: List<AlbumInfoResult>,
    albumSortAsc: Boolean,
    onToggleAlbumSort: () -> Unit,
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
    val tabStates = rememberSaveableStateHolder()
    val header: @Composable () -> Unit = {
        Column {
            ArtistHeader(
                info = uiState.artistInfo,
                songCount = uiState.songs.size,
                onPlayAll = onPlayAll
            )
            DetailLoadStatus(uiState.isLoading, uiState.error, viewModel::retry)
            val pageError = when (uiState.selectedTab) {
                ArtistTab.SONGS -> uiState.songsError
                ArtistTab.ALBUMS -> uiState.albumsError
                ArtistTab.MVS -> uiState.mvsError
            }
            DetailLoadStatus(false, pageError) {
                when (uiState.selectedTab) {
                    ArtistTab.SONGS -> viewModel.retrySongs()
                    ArtistTab.ALBUMS -> viewModel.retryAlbums()
                    ArtistTab.MVS -> viewModel.retryMvs()
                }
            }
        }
    }
    Column(modifier = modifier.fillMaxSize()) {
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
        tabStates.SaveableStateProvider("$detailKey:${uiState.selectedTab.name}") {
            when (uiState.selectedTab) {
                ArtistTab.SONGS -> ArtistSongsTab(
                    queueSource = queueSource,
                    songs = uiState.songs,
                    header = header,
                    loading = uiState.isLoading || uiState.songsLoadingMore,
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
                    albumSelecting = albumSelecting,
                    albumSelectionBusy = albumSelectionBusy,
                    selectedAlbumKeys = selectedAlbumKeys,
                    albums = sortedAlbums,
                    sortAscending = albumSortAsc,
                    onToggleSort = onToggleAlbumSort,
                    header = header,
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
                    header = header,
                    loading = uiState.mvsLoading,
                    hasMore = uiState.mvsHasMore,
                    onLoadMore = viewModel::loadMoreMvs,
                    onMvClick = onMvClick
                )
            }
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
private fun ArtistSongsTab(
    queueSource: QueueSource,
    songs: List<MusicItem>,
    header: @Composable () -> Unit,
    loading: Boolean,
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
    val playerSheet = LocalPlayerSheet.current
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
    LaunchedEffect(shouldLoadMore, hasMore, loading, songs.size) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = LocalFloatingBottomBarReserve.current + 16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "artist_header") { header() }
        if (songs.isEmpty() && !loading) item { EmptyState() }
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
                            val targetIndex = songs.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
                            playerViewModel.setPlaylistAndPlay(songs, targetIndex, queueSource)
                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                playerSheet.open(song)
                            }
                        }
                    },
                    selectionMode = selectionMode,
                    selected = selectedSongs.any { it.uniqueKey == song.uniqueKey },
                    isPlaying = currentSong?.uniqueKey == song.uniqueKey,
                    onLongClick = { onLongClick(song) },
                    onCheckedChange = { onToggleSelect(song) },
                    onPlayClick = {
                        val index = songs.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(songs, index, queueSource)
                    },
                    onAddToPlaylistClick = { onContextAddToPlaylist(song) },
                    onDownloadClick = { onContextDownload(song) }
                )
                SongContextMenu(
                    expanded = contextMenuSong?.uniqueKey == song.uniqueKey,
                    isFavorite = viewModelFactory.localMusicStore.isFavorite(song),
                    onDismiss = onContextMenuDismiss,
                    onPlay = {
                        val index = songs.indexOfFirst { it.uniqueKey == song.uniqueKey }.coerceAtLeast(0)
                        playerViewModel.setPlaylistAndPlay(songs, index, queueSource)
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
        if (loading && songs.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArtistAlbumsTab(
    albumSelecting: Boolean,
    albumSelectionBusy: Boolean,
    selectedAlbumKeys: Set<String>,
    albums: List<AlbumInfoResult>,
    header: @Composable () -> Unit,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    sortAscending: Boolean,
    onToggleSort: () -> Unit,
    isListMode: Boolean,
    onToggleListMode: (Boolean) -> Unit
) {
    val albumHeader: @Composable () -> Unit = {
        Column {
            header()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassButton(onClick = onToggleSort, enabled = !albumSelectionBusy) {
                    Icon(Icons.Filled.SwapVert, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(if (sortAscending) R.string.artist_album_sort_asc else R.string.artist_album_sort_desc))
                }
                IconButton(onClick = { onToggleListMode(!isListMode) }) {
                    Icon(
                        imageVector = if (isListMode) Icons.Filled.GridView else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = stringResource(if (isListMode) R.string.album_layout_grid else R.string.album_layout_list),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (albums.isEmpty()) {
                if (loading) LoadingIndicator(Modifier.height(120.dp)) else EmptyState(modifier = Modifier.height(160.dp))
            }
        }
    }
    if (isListMode) {
        AlbumListLayout(albums, albumHeader, loading, hasMore, onLoadMore, onAlbumClick, onAlbumLongClick, albumSelecting, selectedAlbumKeys, albumSelectionBusy)
    } else {
        AlbumGridLayout(albums, albumHeader, loading, hasMore, onLoadMore, onAlbumClick, onAlbumLongClick, albumSelecting, selectedAlbumKeys, albumSelectionBusy)
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumGridLayout(
    albums: List<AlbumInfoResult>,
    header: @Composable () -> Unit,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    selecting: Boolean,
    selectedKeys: Set<String>,
    busy: Boolean
) {
    val gridState = rememberLazyGridState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = gridState.layoutInfo.totalItemsCount
            last >= total - 4 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, loading, albums.size) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(156.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = LocalFloatingBottomBarReserve.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "artist_header", span = { GridItemSpan(maxLineSpan) }) { header() }
        items(items = albums, key = { "${it.source}_${it.albumId}" }) { album ->
            Column(
                modifier = Modifier.liquidGlass(selected = "${album.source}:${album.albumId}" in selectedKeys).combinedClickable(
                    onClick = { onAlbumClick(album) },
                    onLongClick = { onAlbumLongClick(album) }
                ).padding(10.dp)
            ) {
                if (selecting) Checkbox(checked = "${album.source}:${album.albumId}" in selectedKeys, enabled = !busy, onCheckedChange = { onAlbumClick(album) })
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
    header: @Composable () -> Unit,
    loading: Boolean,
    hasMore: Boolean,
    onLoadMore: () -> Unit,
    onAlbumClick: (AlbumInfoResult) -> Unit,
    onAlbumLongClick: (AlbumInfoResult) -> Unit,
    selecting: Boolean,
    selectedKeys: Set<String>,
    busy: Boolean
) {
    val listState = rememberLazyListState()
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            last >= total - 4 && total > 0
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, loading, albums.size) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = LocalFloatingBottomBarReserve.current + 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "artist_header") { header() }
        items(items = albums, key = { "${it.source}_${it.albumId}" }) { album ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlass(selected = "${album.source}:${album.albumId}" in selectedKeys)
                    .combinedClickable(
                        onClick = { onAlbumClick(album) },
                        onLongClick = { onAlbumLongClick(album) }
                    )
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selecting) Checkbox(checked = "${album.source}:${album.albumId}" in selectedKeys, enabled = !busy, onCheckedChange = { onAlbumClick(album) })
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
                        album.publishTime?.let(::formatYear)?.takeIf { it.isNotEmpty() }?.let {
                            if (isNotEmpty()) append(" · ")
                            append(it)
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
    header: @Composable () -> Unit,
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
    LaunchedEffect(shouldLoadMore, hasMore, loading, mvs.size) {
        if (shouldLoadMore && hasMore && !loading) onLoadMore()
    }
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(180.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = LocalFloatingBottomBarReserve.current + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "artist_header", span = { GridItemSpan(maxLineSpan) }) {
            Column {
            header()
            if (mvs.isEmpty()) { if (loading) LoadingIndicator(Modifier.height(120.dp)) else EmptyState(modifier = Modifier.height(160.dp)) }
            }
        }
        items(items = mvs, key = { "${it.source}_${it.vid}" }) { mv ->
            Column(
                modifier = Modifier.liquidGlass().clickable { onMvClick(mv) }.padding(10.dp)
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
    albumReleaseYear(epochMillis)

@Composable
private fun ArtistHeader(info: ArtistInfoResult?, songCount: Int, onPlayAll: () -> Unit) {
    val meta = buildList {
        val counts = buildList {
            info?.albumCount?.takeIf { it > 0 }?.let { add(stringResource(R.string.artist_album_count, it)) }
            val count = info?.musicCount?.takeIf { it > 0 } ?: songCount
            if (count > 0) add(stringResource(R.string.artist_song_count, count))
        }
        if (counts.isNotEmpty()) add(counts.joinToString(" · "))
        info?.fansCount?.takeIf { it > 0 }?.let { add(stringResource(R.string.artist_fans_count, formatFans(it))) }
    }
    MediaDetailHeader(info?.name.orEmpty(), info?.avatar, meta, info?.description, avatar = true) {
        GlassButton(onPlayAll, enabled = songCount > 0, accented = true) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Text(stringResource(R.string.play_all))
        }
    }
}

/** 粉丝数缩写：>= 10000 显示「万」，否则原样。 */
private fun formatFans(count: Long): String = when {
    count >= 100_000_000L -> "${count / 100_000_000L}.${(count % 100_000_000L) / 10_000_000L}亿"
    count >= 10_000L -> "${count / 10_000L}.${(count % 10_000L) / 1_000L}万"
    else -> count.toString()
}
