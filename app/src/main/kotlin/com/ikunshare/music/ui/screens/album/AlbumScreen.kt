package com.ikunshare.music.ui.screens.album

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.ikunshare.music.ui.components.GlassIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import com.ikunshare.music.ui.components.GlassTopBar as TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.R
import com.ikunshare.music.common.albumReleaseYear
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.QueueSource
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.ui.components.GlassSectionHeading
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
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import androidx.compose.material.icons.filled.Download
import com.ikunshare.music.ui.components.ErrorState
import com.ikunshare.music.ui.components.LoadingIndicator
import com.ikunshare.music.ui.components.SelectionBottomBar
import com.ikunshare.music.ui.components.SongCard
import com.ikunshare.music.ui.components.SongContextMenu
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import java.text.SimpleDateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    navController: NavController,
    albumKey: String,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val playerSheet = LocalPlayerSheet.current
    val viewModel: AlbumViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val queueSource = QueueSource("album", albumKey, uiState.albumInfo?.name.orEmpty())
    val context = LocalContext.current
    val playlistWrites = rememberPlaylistWriteActions(viewModelFactory.localMusicStore)
    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(viewModelFactory.downloadManager, albumKey)

    LaunchedEffect(albumKey) {
        viewModel.loadAlbum(albumKey)
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
    var downloadWholeAlbum by remember(albumKey) { mutableStateOf(false) }
    val playlists by viewModelFactory.localMusicStore.playlistsFlow.collectAsState()

    var contextMenuSong by remember { mutableStateOf<MusicItem?>(null) }
    var singleDownloadSong by remember { mutableStateOf<MusicItem?>(null) }
    var singlePlaylistSong by remember { mutableStateOf<MusicItem?>(null) }
    LaunchedEffect(albumKey) {
        selectionMode = false
        selectedSongs.clear()
        contextMenuSong = null
        singleDownloadSong = null
        singlePlaylistSong = null
        showPlaylistPicker = false
        showCreateDialog = false
        showQualityPicker = false
    }

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

    LiquidGlassScene(uiState.albumInfo?.cover) {
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
                                text = uiState.albumInfo?.name ?: "",
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
                        onDownload = { downloadWholeAlbum = false; showQualityPicker = true },
                        onAddToPlaylist = { showPlaylistPicker = true },
                        floatingBottomBar = appSettings.floatingBottomBar,
                        liquidGlass = appSettings.liquidGlassMode
                    )
                }
            },
            containerColor = Color.Transparent
        ) { padding ->
            when {
                uiState.isLoading && uiState.songs.isEmpty() && uiState.albumInfo == null ->
                    LoadingIndicator(modifier = Modifier.padding(padding))

                uiState.error != null && uiState.songs.isEmpty() && uiState.albumInfo == null ->
                    ErrorState(
                        message = uiState.error
                            ?: stringResource(R.string.unknown_error),
                        onRetry = viewModel::retry,
                        modifier = Modifier.padding(padding)
                    )

                uiState.songs.isEmpty() && uiState.albumInfo == null ->
                    EmptyState(modifier = Modifier.padding(padding))

                else -> AlbumBody(
                    albumInfo = uiState.albumInfo,
                    queueSource = queueSource,
                    songs = uiState.songs,
                    discs = uiState.discs,
                    loading = uiState.isLoading,
                    error = uiState.error,
                    onRetry = viewModel::retry,
                    onDownloadAll = { downloadWholeAlbum = true; showQualityPicker = true },
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
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
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
                val settings = viewModelFactory.appSettingsManager.settings.value
                val songs = if (downloadWholeAlbum) uiState.songs else selectedSongs.toList()
                val folder = if (downloadWholeAlbum) uiState.albumInfo?.let {
                    com.ikunshare.music.manager.AlbumFolderNamer.buildSubDir(it, settings.downloadAlbumFolderWithYear)
                }.orEmpty() else ""
                val tracks = if (downloadWholeAlbum) com.ikunshare.music.manager.albumDownloadTracks(songs, uiState.discs)
                    else songs.map { com.ikunshare.music.manager.AlbumDownloadTrack(it, 0, null) }
                downloadQueue.addRequests(tracks.map { track -> com.ikunshare.music.ui.utils.DownloadQueueRequest(
                    track.song, qualityId, settings.hideAiQualities, folder, track.number, track.disc) }) {
                    showQualityPicker = false
                    exitSelectionMode()
                }
            }
        )
    }

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

@Composable
private fun AlbumBody(
    albumInfo: AlbumInfoResult?,
    queueSource: QueueSource,
    songs: List<MusicItem>,
    discs: List<AlbumDisc>?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDownloadAll: () -> Unit,
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
    modifier: Modifier = Modifier
) {
    val playerSheet = LocalPlayerSheet.current
    val context = LocalContext.current
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentSong = playerUiState.currentSong
    val listState = rememberLazyListState()
    val sections = remember(songs, discs) { albumSections(songs, discs) }

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(bottom = LocalFloatingBottomBarReserve.current + 16.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            AlbumHeader(info = albumInfo, songCount = songs.size, discCount = discs?.size ?: 0, onPlayAll = onPlayAll, onDownloadAll = onDownloadAll)
            DetailLoadStatus(loading, error, onRetry)
            if (!loading && error == null && songs.isEmpty()) EmptyState()
        }
        sections.forEachIndexed { index, section ->
        section.disc?.let { disc ->
            item(key = "disc_header_$index") {
                val label = if (disc.name == "CD${disc.no}") disc.name else "CD${disc.no} · ${disc.name}"
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    GlassSectionHeading(label, stringResource(R.string.album_song_count, section.songs.size))
                }
            }
        }
        items(items = section.songs, key = { it.uniqueKey }) { song ->
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
        }
    }
}

@Composable
private fun AlbumHeader(info: AlbumInfoResult?, songCount: Int, discCount: Int, onPlayAll: () -> Unit, onDownloadAll: () -> Unit) {
    val metadataCount = info?.size?.takeIf { it > 0 } ?: songCount
    val locale = LocalLocale.current.platformLocale
    val date = remember(info?.publishTime, locale) {
        info?.publishTime?.takeIf { albumReleaseYear(it).isNotEmpty() }?.let { SimpleDateFormat("yyyy-MM-dd", locale).format(Date(it)) }
    }
    val meta = listOfNotNull(
        info?.artist?.takeIf { it.isNotBlank() },
        listOfNotNull(date, stringResource(R.string.album_song_count, metadataCount),
            if (discCount > 1) stringResource(R.string.album_disc_count, discCount) else null,
            info?.subType?.takeIf { it.isNotBlank() }).joinToString(" · "),
        info?.company?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.album_company_label, it) }
    )
    MediaDetailHeader(info?.name.orEmpty(), info?.cover, meta, info?.description) {
        GlassButton(onPlayAll, enabled = songCount > 0, accented = true) {
            Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
            Text(stringResource(R.string.play_all))
        }
        GlassButton(onDownloadAll, enabled = songCount > 0) {
            Icon(Icons.Filled.Download, null, Modifier.size(20.dp))
            Text(stringResource(R.string.album_download_all))
        }
    }
}
