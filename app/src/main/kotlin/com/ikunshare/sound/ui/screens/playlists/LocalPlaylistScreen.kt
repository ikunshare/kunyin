package com.ikunshare.sound.ui.screens.playlists

import android.widget.Toast
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.sound.R
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.AddToPlaylistDialog
import com.ikunshare.sound.ui.components.CreatePlaylistDialog
import com.ikunshare.sound.ui.components.DownloadQualityDialog
import com.ikunshare.sound.ui.components.EmptyState
import com.ikunshare.sound.ui.components.SelectionBottomBar
import com.ikunshare.sound.ui.components.SongCard
import com.ikunshare.sound.ui.components.SongContextMenu
import com.ikunshare.sound.ui.components.SongRedirectDialog
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPlaylistScreen(
    navController: NavController,
    type: String,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: LocalPlaylistViewModel = viewModel(factory = viewModelFactory)
    val context = LocalContext.current

    val customPlaylistId = if (type.startsWith("custom_")) {
        type.removePrefix("custom_").toLongOrNull()
    } else null

    // 加载歌曲
    LaunchedEffect(customPlaylistId) {
        if (customPlaylistId != null) viewModel.initLoad("custom", customPlaylistId)
    }

    val songs by viewModel.displaySongs.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val isInitialLoad by viewModel.isInitialLoad.collectAsState()
    val totalCount by viewModel.totalCount.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentSong = playerUiState.currentSong

    // 远程歌单信息（用于下拉刷新和自动刷新）
    val playlist = remember(customPlaylistId) {
        customPlaylistId?.let { viewModel.getPlaylist(it) }
    }
    val hasRemote = playlist?.remoteSource != null
    val isFavoritesPlaylist =
        playlist?.systemKind == com.ikunshare.sound.database.MusicDatabase.SYSTEM_KIND_FAVORITES

    // 自动刷新
    LaunchedEffect(customPlaylistId, playlist?.autoRefresh) {
        if (customPlaylistId != null && playlist?.autoRefresh == true && playlist.remoteSource != null) {
            viewModel.refreshFromRemote(customPlaylistId)
        }
    }

    var title by remember { mutableStateOf("") }
    LaunchedEffect(customPlaylistId) {
        title = customPlaylistId?.let { viewModel.getPlaylistName(it) } ?: ""
    }

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
    var deleteSong by remember { mutableStateOf<MusicItem?>(null) }
    var reorderSong by remember { mutableStateOf<MusicItem?>(null) }
    var redirectSong by remember { mutableStateOf<MusicItem?>(null) }

    // 导入歌曲对话框
    var showImportPlatformDialog by remember { mutableStateOf(false) }
    var showImportIdDialog by remember { mutableStateOf(false) }
    var importPlatform by remember { mutableStateOf("") }
    val importScope = rememberCoroutineScope()

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

    // 确定删除操作
    val onRemove: (() -> Unit)? = if (customPlaylistId != null) {
        {
            selectedSongs.forEach { song ->
                viewModelFactory.localMusicStore.removeFromPlaylist(customPlaylistId, song)
            }
            Toast.makeText(
                context,
                context.getString(
                    if (isFavoritesPlaylist) R.string.unfavorited_count
                    else R.string.removed_from_playlist_count,
                    selectedSongs.size
                ),
                Toast.LENGTH_SHORT
            ).show()
            exitSelectionMode()
            viewModel.refresh()
        }
    } else null

    val removeLabel = stringResource(
        if (isFavoritesPlaylist) R.string.unfavorite
        else R.string.remove_from_playlist
    )

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
                            if (selectedSongs.size == songs.size) {
                                selectedSongs.clear()
                            } else {
                                selectedSongs.clear()
                                selectedSongs.addAll(songs)
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
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showImportPlatformDialog = true }) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = stringResource(R.string.import_songs),
                                tint = MaterialTheme.colorScheme.onBackground
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
                    onRemove = onRemove,
                    removeLabel = removeLabel,
                    floatingBottomBar = viewModelFactory.appSettingsManager.settings.collectAsState().value.floatingBottomBar,
                    liquidGlass = viewModelFactory.appSettingsManager.settings.collectAsState().value.liquidGlassMode
                )
            }
        },
        containerColor = Color.Transparent
    ) { padding ->
        val listState = rememberLazyListState()

        when {
            isInitialLoad -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            songs.isEmpty() && !isLoading -> {
                EmptyState(
                    message = stringResource(R.string.empty_playlist),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                )
            }

            else -> {
                val contentModifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                val lazyContent: @Composable () -> Unit = {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 播放全部按钮
                        item(key = "play_all") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Button(
                                    onClick = {
                                        if (!hasMore) {
                                            playerViewModel.setPlaylist(songs, 0)
                                            navController.navigate(Screen.Player.createRoute(songs.first().id))
                                        } else {
                                            viewModel.loadAllSongs { allSongs ->
                                                if (allSongs.isNotEmpty()) {
                                                    playerViewModel.setPlaylist(allSongs, 0)
                                                    navController.navigate(
                                                        Screen.Player.createRoute(
                                                            allSongs.first().id
                                                        )
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.tertiary
                                    ),
                                    shape = RoundedCornerShape(24.dp),
                                    modifier = Modifier.height(42.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.play_all),
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.song_count, totalCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )
                        }

                        // 歌曲列表
                        items(
                            items = songs,
                            key = { it.uniqueKey }
                        ) { song ->
                            var menuOffset by remember { mutableStateOf(DpOffset.Zero) }
                            val density = LocalDensity.current
                            Box(
                                modifier = Modifier.pointerInput(Unit) {
                                    awaitPointerEventScope {
                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            event.changes.firstOrNull()?.let {
                                                menuOffset = with(density) {
                                                    DpOffset(
                                                        it.position.x.toDp(),
                                                        0.dp
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            ) {
                                SongCard(
                                    song = song,
                                    onClick = {
                                        if (selectionMode) {
                                            toggleSelect(song)
                                        } else {
                                            playerViewModel.setPlaylistAndPlay(
                                                songs,
                                                songs.indexOf(song)
                                            )
                                            if (viewModelFactory.appSettingsManager.settings.value.autoOpenPlayerOnSongClick) {
                                                navController.navigate(
                                                    Screen.Player.createRoute(
                                                        song.id
                                                    )
                                                )
                                            }
                                        }
                                    },
                                    selectionMode = selectionMode,
                                    selected = selectedSongs.any { it.id == song.id },
                                    isPlaying = currentSong?.id == song.id &&
                                            currentSong.getTypeDiscriminator() == song.getTypeDiscriminator(),
                                    onLongClick = {
                                        if (!selectionMode) contextMenuSong = song
                                        else toggleSelect(song)
                                    },
                                    onCheckedChange = { toggleSelect(song) }
                                )
                                SongContextMenu(
                                    expanded = contextMenuSong == song,
                                    isFavorite = viewModelFactory.localMusicStore.isFavorite(song),
                                    onDismiss = { contextMenuSong = null },
                                    onPlay = {
                                        playerViewModel.setPlaylistAndPlay(
                                            songs,
                                            songs.indexOf(song)
                                        )
                                        contextMenuSong = null
                                    },
                                    onPlayNext = {
                                        playerViewModel.addToPlayNext(song)
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.added_to_play_next),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        contextMenuSong = null
                                    },
                                    onDownload = {
                                        singleDownloadSong = song
                                        contextMenuSong = null
                                    },
                                    onToggleFavorite = {
                                        val store = viewModelFactory.localMusicStore
                                        if (store.isFavorite(song)) store.removeFavorite(song)
                                        else store.addFavorite(song)
                                        contextMenuSong = null
                                    },
                                    onAddToPlaylist = {
                                        singlePlaylistSong = song
                                        contextMenuSong = null
                                    },
                                    onMultiSelect = {
                                        enterSelection(song)
                                        contextMenuSong = null
                                    },
                                    offset = menuOffset,
                                    onRemove = if (customPlaylistId != null) {
                                        { deleteSong = song; contextMenuSong = null }
                                    } else null,
                                    removeLabel = removeLabel,
                                    onReorder = if (customPlaylistId != null) {
                                        { reorderSong = song; contextMenuSong = null }
                                    } else null,
                                    onRefreshInfo = {
                                        contextMenuSong = null
                                        importScope.launch(Dispatchers.IO) {
                                            val repo =
                                                com.ikunshare.sound.SoundApplication.instance?.musicRepository
                                            val provider =
                                                repo?.getProvider(song.getTypeDiscriminator())
                                            val updated = provider?.updateInfo(song) ?: song
                                            if (updated !== song) {
                                                viewModelFactory.localMusicStore.updateSongInfo(
                                                    updated
                                                )
                                                viewModel.refresh()
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.refresh_song_info_success),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } else {
                                                withContext(Dispatchers.Main) {
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.refresh_song_info_failed),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            }
                                        }
                                    },
                                    onRedirect = {
                                        redirectSong = song
                                        contextMenuSong = null
                                    },
                                    mvSong = song
                                )
                            }
                        }

                        // 加载更多指示器
                        if (isLoading && songs.isNotEmpty()) {
                            item(key = "loading_more") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
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

                        // 底部间距
                        item(key = "bottom_spacer") {
                            Spacer(modifier = Modifier.height(80.dp))
                        }
                    }
                } // end lazyContent

                if (hasRemote && customPlaylistId != null) {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { viewModel.refreshFromRemote(customPlaylistId) },
                        modifier = contentModifier
                    ) {
                        lazyContent()
                    }
                } else {
                    Box(modifier = contentModifier) {
                        lazyContent()
                    }
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
            },
            excludePlaylistId = customPlaylistId
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreateDialog = false
                if (singlePlaylistSong != null) singlePlaylistSong = null
            },
            onConfirm = { name ->
                val store = viewModelFactory.localMusicStore
                if (singlePlaylistSong != null) {
                    store.createPlaylistAndAdd(name, singlePlaylistSong!!)
                    Toast.makeText(
                        context,
                        context.getString(R.string.created_playlist_added_count, 1),
                        Toast.LENGTH_SHORT
                    ).show()
                    singlePlaylistSong = null
                } else {
                    val songsToAdd = selectedSongs.toList()
                    if (songsToAdd.isNotEmpty()) {
                        val pid = store.createPlaylistAndAdd(name, songsToAdd.first())
                        songsToAdd.drop(1).forEach { store.addToPlaylist(pid, it) }
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.created_playlist_added_count, songsToAdd.size),
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

    // 单曲下载对话框
    if (singleDownloadSong != null) {
        DownloadQualityDialog(
            hideAi = viewModelFactory.appSettingsManager.settings.collectAsState().value.hideAiQualities,
            onDismiss = { singleDownloadSong = null },
            onConfirm = { qualityId ->
                val hideAi = viewModelFactory.appSettingsManager.settings.value.hideAiQualities
                viewModelFactory.downloadManager.addTaskWithPreferredQuality(
                    singleDownloadSong!!,
                    qualityId,
                    hideAi
                )
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_download),
                    Toast.LENGTH_SHORT
                ).show()
                singleDownloadSong = null
            }
        )
    }

    // 单曲添加到歌单对话框
    if (singlePlaylistSong != null && !showCreateDialog) {
        AddToPlaylistDialog(
            playlists = playlists,
            isFavorite = viewModelFactory.localMusicStore.isFavorite(singlePlaylistSong!!),
            onFavoriteClick = {
                viewModelFactory.localMusicStore.addFavorite(singlePlaylistSong!!)
                singlePlaylistSong = null
            },
            onDismiss = { singlePlaylistSong = null },
            onPlaylistClick = { pid ->
                viewModelFactory.localMusicStore.addToPlaylist(pid, singlePlaylistSong!!)
                Toast.makeText(
                    context,
                    context.getString(R.string.added_to_playlist_count, 1),
                    Toast.LENGTH_SHORT
                ).show()
                singlePlaylistSong = null
            },
            onCreateNew = {
                showCreateDialog = true
            },
            excludePlaylistId = customPlaylistId
        )
    }

    // 删除确认对话框
    if (deleteSong != null) {
        val actionLabel = stringResource(
            if (isFavoritesPlaylist) R.string.unfavorite
            else R.string.remove_from_playlist
        )
        AlertDialog(
            onDismissRequest = { deleteSong = null },
            title = { Text(stringResource(R.string.confirm)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_song_confirm,
                        actionLabel,
                        deleteSong!!.title
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val song = deleteSong!!
                    if (customPlaylistId != null) {
                        viewModelFactory.localMusicStore.removeFromPlaylist(
                            customPlaylistId,
                            song
                        )
                    }
                    viewModel.refresh()
                    deleteSong = null
                }) {
                    Text(stringResource(R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteSong = null }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    // 调整顺序对话框
    if (reorderSong != null && customPlaylistId != null) {
        val currentPos = songs.indexOf(reorderSong!!) + 1
        val totalCount = songs.size
        var posInput by remember(reorderSong) { mutableStateOf(currentPos.toString()) }

        AlertDialog(
            onDismissRequest = { reorderSong = null },
            title = { Text(stringResource(R.string.reorder_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.current_position, currentPos, totalCount),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = posInput,
                        onValueChange = { posInput = it.filter { c -> c.isDigit() } },
                        label = { Text(stringResource(R.string.new_position_hint, totalCount)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val newPos = posInput.toIntOrNull()
                        if (newPos != null && newPos in 1..totalCount) {
                            viewModelFactory.localMusicStore.moveSongInPlaylist(
                                customPlaylistId, reorderSong!!, newPos - 1
                            )
                            viewModel.refresh()
                            Toast.makeText(
                                context,
                                context.getString(R.string.reordered_success, newPos),
                                Toast.LENGTH_SHORT
                            ).show()
                            reorderSong = null
                        }
                    },
                    enabled = posInput.toIntOrNull()?.let { it in 1..totalCount } == true
                ) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { reorderSong = null }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    // 歌词/封面重定向对话框
    if (redirectSong != null) {
        val src = redirectSong!!
        val store = viewModelFactory.localMusicStore
        val current = remember(src, store.redirectKeysFlow.collectAsState().value) {
            store.getRedirect(src)
        }
        SongRedirectDialog(
            sourceItem = src,
            currentRedirect = current,
            onDismiss = { redirectSong = null },
            onClear = {
                store.clearRedirect(src)
                com.ikunshare.sound.SoundApplication.instance?.musicRepository?.clearLyricCache()
                viewModel.refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.redirect_clear_success),
                    Toast.LENGTH_SHORT
                ).show()
                redirectSong = null
            },
            onSave = { target ->
                store.setRedirect(src, target)
                com.ikunshare.sound.SoundApplication.instance?.musicRepository?.clearLyricCache()
                viewModel.refresh()
                Toast.makeText(
                    context,
                    context.getString(R.string.redirect_save_success),
                    Toast.LENGTH_SHORT
                ).show()
                redirectSong = null
            },
            lookup = { source, key, value ->
                withContext(Dispatchers.IO) {
                    val repo =
                        com.ikunshare.sound.SoundApplication.instance?.musicRepository
                    val provider = repo?.getProvider(source)
                    when (source) {
                        "qq" -> {
                            val qq = provider as? com.ikunshare.sound.platform.qq.QQProvider
                            if (key == "mid") qq?.fromMid(value.trim())
                            else qq?.fromID(value.trim().toLongOrNull() ?: return@withContext null)
                        }

                        "wy" -> {
                            val wy = provider as? com.ikunshare.sound.platform.wy.WyProvider
                            wy?.fromID(value.trim().toLongOrNull() ?: return@withContext null)
                        }

                        "kw" -> {
                            val kw = provider as? com.ikunshare.sound.platform.kw.KwProvider
                            kw?.fromID(value.trim().toLongOrNull() ?: return@withContext null)
                        }

                        else -> null
                    }
                }
            },
            kgLyricSearch = { keyword ->
                withContext(Dispatchers.IO) {
                    val repo = com.ikunshare.sound.SoundApplication.instance?.musicRepository
                    val kg = repo?.getProvider("kg")
                            as? com.ikunshare.sound.platform.kg.KgProvider
                    val srcKg = src as? com.ikunshare.sound.platform.kg.KugouMusicItem
                    val candidates = kg?.searchKgLyricCandidates(
                        keyword = keyword,
                        duration = src.duration,
                        hash = srcKg?.hash,
                        audioId = srcKg?.audioId
                    ).orEmpty()
                    // 并行探测每条候选的歌词内容类型（需下载解析）
                    coroutineScope {
                        candidates.map { c ->
                            async {
                                val types = runCatching {
                                    kg?.probeKgLyricTypes(c.accessKey, c.downloadId, c.contenttype)
                                }.getOrNull()
                                val badges = buildList {
                                    if (types != null) {
                                        if (types.hasWordByWord) add("逐字")
                                        else if (types.hasLine) add("逐行")
                                        if (types.hasTranslation) add("翻译")
                                        if (types.hasRomaji) add("音译")
                                        if (types.hasWordRomaji) add("逐字音译")
                                        if (types.hasPhonetic) add("谐音")
                                    }
                                }
                                com.ikunshare.sound.ui.components.KgLyricCandidateUi(
                                    accessKey = c.accessKey,
                                    downloadId = c.downloadId,
                                    contenttype = c.contenttype,
                                    song = c.song,
                                    singer = c.singer,
                                    language = c.language,
                                    durationMs = c.durationMs,
                                    score = c.score,
                                    typeBadges = badges
                                )
                            }
                        }.map { it.await() }
                    }
                }
            },
            kgBuildTarget = { c ->
                com.ikunshare.sound.platform.kg.KugouMusicItem(
                    id = 0L,
                    title = c.song.ifBlank { src.title },
                    artist = c.singer.ifBlank { src.artist },
                    album = "",
                    cover = "",
                    duration = c.durationMs / 1000,
                    qualities = emptyMap(),
                    tags = null,
                    mixsongmid = 0L,
                    hash = "",
                    allHash = emptyList(),
                    audioId = "",
                    lyricAccessKey = c.accessKey,
                    lyricDownloadId = c.downloadId
                )
            }
        )
    }

    // 导入歌曲 - 平台选择对话框
    if (showImportPlatformDialog) {
        AlertDialog(
            onDismissRequest = { showImportPlatformDialog = false },
            title = { Text(stringResource(R.string.select_import_platform)) },
            text = {
                Column {
                    data class PlatformOption(
                        val label: String,
                        val key: String,
                        val enabled: Boolean
                    )

                    val platforms = listOf(
                        PlatformOption("QQ音乐", "qq", true),
                        PlatformOption("网易云音乐", "wy", true),
                        PlatformOption("酷狗音乐", "kg", false),
                        PlatformOption("酷我音乐", "kw", true)
                    )
                    val notAvailableText = stringResource(R.string.not_available)
                    platforms.forEach { platform ->
                        TextButton(
                            onClick = {
                                importPlatform = platform.key
                                showImportPlatformDialog = false
                                showImportIdDialog = true
                            },
                            enabled = platform.enabled,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = platform.label + if (!platform.enabled) " ($notAvailableText)" else "",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showImportPlatformDialog = false }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    // 导入歌曲 - ID 输入对话框
    if (showImportIdDialog) {
        var idInput by remember { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }

        val platformLabel = when (importPlatform) {
            "qq" -> "QQ音乐"
            "wy" -> "网易云音乐"
            "kg" -> "酷狗音乐"
            "kw" -> "酷我音乐"
            else -> ""
        }

        AlertDialog(
            onDismissRequest = { if (!isImporting) showImportIdDialog = false },
            title = { Text(stringResource(R.string.import_platform_songs, platformLabel)) },
            text = {
                Column {
                    Text(
                        text = when (importPlatform) {
                            "qq" -> stringResource(R.string.input_qq_id)
                            "wy" -> stringResource(R.string.input_netease_id)
                            else -> stringResource(R.string.input_song_id)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = idInput,
                        onValueChange = { idInput = it },
                        label = { Text(if (importPlatform == "qq") "ID / Mid" else "ID") },
                        singleLine = true,
                        enabled = !isImporting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isImporting) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.importing),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isImporting = true
                        val input = idInput.trim()
                        importScope.launch(Dispatchers.IO) {
                            val repo =
                                com.ikunshare.sound.SoundApplication.instance?.musicRepository
                            val item: MusicItem? = when (importPlatform) {
                                "qq" -> {
                                    val p =
                                        repo?.getProvider("qq") as? com.ikunshare.sound.platform.qq.QQProvider
                                    if (input.matches(Regex("^\\d+$"))) {
                                        p?.fromID(input.toLong())
                                    } else {
                                        p?.fromMid(input)
                                    }
                                }

                                "wy" -> {
                                    val id = input.toLongOrNull()
                                    val p =
                                        repo?.getProvider("wy") as? com.ikunshare.sound.platform.wy.WyProvider
                                    if (id != null) p?.fromID(id) else null
                                }

                                "kw" -> {
                                    val id = input.toLongOrNull()
                                    val p =
                                        repo?.getProvider("kw") as? com.ikunshare.sound.platform.kw.KwProvider
                                    if (id != null) p?.fromID(id) else null
                                }

                                else -> null
                            }
                            withContext(Dispatchers.Main) {
                                if (item != null) {
                                    val store = viewModelFactory.localMusicStore
                                    if (customPlaylistId != null) {
                                        store.addToPlaylist(customPlaylistId, item)
                                        viewModel.refresh()
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.imported_song, item.title),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showImportIdDialog = false
                                } else {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.import_failed),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                isImporting = false
                            }
                        }
                    },
                    enabled = idInput.isNotBlank() && !isImporting
                ) {
                    Text(
                        stringResource(R.string.import_text),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showImportIdDialog = false },
                    enabled = !isImporting
                ) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}

class LocalPlaylistViewModel(
    private val localMusicStore: LocalMusicStore,
    private val repository: MusicRepository? = null
) : ViewModel() {
    // 分页展示
    private val _displaySongs = MutableStateFlow<List<MusicItem>>(emptyList())
    val displaySongs: StateFlow<List<MusicItem>> = _displaySongs.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    private val _isInitialLoad = MutableStateFlow(true)
    val isInitialLoad: StateFlow<Boolean> = _isInitialLoad.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private var currentOffset = 0
    private var currentPlaylistId: Long? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var watchJob: kotlinx.coroutines.Job? = null

    companion object {
        const val PAGE_SIZE = 100
    }

    fun initLoad(@Suppress("UNUSED_PARAMETER") type: String, playlistId: Long? = null) {
        if (playlistId == currentPlaylistId && _displaySongs.value.isNotEmpty()) return
        currentPlaylistId = playlistId
        currentOffset = 0
        _displaySongs.value = emptyList()
        _hasMore.value = true
        _isInitialLoad.value = true
        loadAllPages()
        watchRevision(playlistId)
    }

    private fun watchRevision(playlistId: Long?) {
        watchJob?.cancel()
        if (playlistId == null) return
        watchJob = viewModelScope.launch(Dispatchers.Main) {
            var lastRev = localMusicStore.playlistSongsRevision.value[playlistId] ?: 0L
            localMusicStore.playlistSongsRevision.collect { map ->
                val rev = map[playlistId] ?: 0L
                if (rev != lastRev) {
                    lastRev = rev
                    refresh()
                }
            }
        }
    }

    private fun queryPage(): List<MusicItem> {
        return currentPlaylistId?.let {
            localMusicStore.queryPlaylistSongsPaged(it, PAGE_SIZE, currentOffset)
        } ?: emptyList()
    }

    private fun loadAllPages() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            // 先获取总数
            _totalCount.value = currentPlaylistId?.let { pid ->
                localMusicStore.playlistsFlow.value.find { it.id == pid }?.songCount ?: 0
            } ?: 0

            // 加载第一页并立即显示
            val firstPage = queryPage()
            currentOffset += firstPage.size
            _displaySongs.value = firstPage
            _isInitialLoad.value = false
            _hasMore.value = firstPage.size == PAGE_SIZE

            // 后台自动连续加载剩余页面
            while (_hasMore.value) {
                _isLoading.value = true
                val page = queryPage()
                currentOffset += page.size
                _displaySongs.value += page
                _hasMore.value = page.size == PAGE_SIZE
            }
            _isLoading.value = false
            // 用实际加载数量修正总数
            _totalCount.value = _displaySongs.value.size
        }
    }

    fun refresh() {
        currentOffset = 0
        _displaySongs.value = emptyList()
        _hasMore.value = true
        _isInitialLoad.value = true
        loadAllPages()
    }

    fun loadAllSongs(callback: (List<MusicItem>) -> Unit) {
        if (!_hasMore.value) {
            callback(_displaySongs.value)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val items = mutableListOf<MusicItem>()
            items.addAll(_displaySongs.value)
            var off = currentOffset
            while (true) {
                val page = currentPlaylistId?.let {
                    localMusicStore.queryPlaylistSongsPaged(it, PAGE_SIZE, off)
                } ?: emptyList()
                items.addAll(page)
                off += page.size
                if (page.size < PAGE_SIZE) break
            }
            withContext(Dispatchers.Main) { callback(items) }
        }
    }

    fun getPlaylistName(playlistId: Long): String? {
        return localMusicStore.getPlaylistName(playlistId)
    }

    fun getPlaylist(playlistId: Long): com.ikunshare.sound.database.Playlist? {
        return localMusicStore.getPlaylist(playlistId)
    }

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    /**
     * 从远端刷新收藏的在线歌单，增量合并新章节
     */
    fun refreshFromRemote(playlistId: Long) {
        val repo = repository ?: return
        val playlist = localMusicStore.getPlaylist(playlistId) ?: return
        val remoteSource = playlist.remoteSource ?: return
        val remoteId = playlist.remoteId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _isRefreshing.value = true
            try {
                // 获取远端全部歌曲
                val allRemoteSongs = mutableListOf<MusicItem>()
                var page = 0
                var hasMore = true
                while (hasMore) {
                    val result = repo.getPlaylistSongsForSource(remoteSource, remoteId, page)
                    allRemoteSongs.addAll(result.result)
                    hasMore = result.hasNext
                    page++
                }

                // 获取本地已有歌曲 ID
                val localSongs = localMusicStore.queryPlaylistSongs(playlistId)
                val localIds = localSongs.map { it.id }.toSet()

                // 增量追加新歌曲
                var added = 0
                for (song in allRemoteSongs) {
                    if (song.id !in localIds) {
                        localMusicStore.addToPlaylist(playlistId, song)
                        added++
                    }
                }

                if (added > 0) {
                    // 重新加载显示
                    refresh()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}
