package com.ikunshare.sound.ui.screens.album

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.LocalLocale
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
    val viewModel: AlbumViewModel = viewModel(factory = viewModelFactory)
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

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
    val playlists by viewModelFactory.localMusicStore.playlistsFlow.collectAsState()

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
                songs = uiState.songs,
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
                modifier = Modifier.padding(padding)
            )
        }
    }

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
    songs: List<MusicItem>,
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
    val context = LocalContext.current
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentSong = playerUiState.currentSong
    val listState = rememberLazyListState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize()
    ) {
        item {
            AlbumHeader(info = albumInfo, songCount = songs.size, onPlayAll = onPlayAll)
        }
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
                            // 与搜索结果点击保持一致：以整个试听列表作为播放队列，
                            // 用「当前列表 + 新歌」组装乐观队列以避开异步写库时序。
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

@Composable
private fun AlbumHeader(
    info: AlbumInfoResult?,
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
            model = info?.cover,
            contentDescription = info?.name,
            modifier = Modifier
                .size(120.dp)
                .clip(RoundedCornerShape(12.dp)),
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
            info?.artist?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            val meta = buildString {
                info?.publishTime?.takeIf { it > 0 }?.let {
                    val fmt = SimpleDateFormat("yyyy-MM-dd", LocalLocale.current.platformLocale)
                    append(fmt.format(Date(it)))
                }
                if (songCount > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(stringResource(R.string.album_song_count, songCount))
                }
                info?.subType?.takeIf { it.isNotBlank() }?.let {
                    if (isNotEmpty()) append(" · ")
                    append(it)
                }
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            info?.company?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = stringResource(R.string.album_company_label, it),
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
