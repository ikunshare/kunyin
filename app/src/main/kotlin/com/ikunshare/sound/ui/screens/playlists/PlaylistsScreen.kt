package com.ikunshare.sound.ui.screens.playlists

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.PlatformPlaylistStore
import com.ikunshare.sound.database.Playlist
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.model.neteaseCoverUrl
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.AddToPlaylistDialog
import com.ikunshare.sound.ui.components.CreatePlaylistDialog
import com.ikunshare.sound.ui.components.DownloadQualityDialog
import com.ikunshare.sound.ui.components.EmptyState
import com.ikunshare.sound.ui.components.SelectionBottomBar
import com.ikunshare.sound.ui.components.SongCard
import com.ikunshare.sound.ui.components.SongContextMenu
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.utils.LocalScrollOffsetReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class PlaylistSelection {
    data class Custom(val playlist: Playlist) : PlaylistSelection()
    data class PlatformPlaylist(val source: String, val playlist: PlayListInfoResult) :
        PlaylistSelection()
}

@Composable
private fun rememberScrollOffsetReporter(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory
) {
    val viewModel: PlaylistsViewModel = viewModel(factory = viewModelFactory)
    val appSettings by viewModelFactory.appSettingsManager.settings.collectAsState()
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val platformSections by viewModel.platformSections.collectAsState()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    // 长按平台歌单 → 创建本地副本
    var platformPlaylistToCopy by remember { mutableStateOf<Pair<String, PlayListInfoResult>?>(null) }

    val context = LocalContext.current

    val configuration = LocalConfiguration.current
    val isWideLayout =
        configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                || configuration.screenWidthDp >= 600

    if (isWideLayout) {
        WidePlaylistsLayout(
            customPlaylists = customPlaylists,
            platformSections = platformSections,
            navController = navController,
            playerViewModel = playerViewModel,
            localMusicStore = viewModel.localMusicStore,
            viewModelFactory = viewModelFactory,
            onCreatePlaylist = { showCreateDialog = true },
            onImportPlaylist = { showImportDialog = true },
            onDeletePlaylist = { playlistToDelete = it },
            onRefreshPlatform = { viewModel.refreshPlatform(it) },
            onCopyPlatformPlaylist = { source, pl -> platformPlaylistToCopy = source to pl },
            appSettings = appSettings
        )
    } else {
        NarrowPlaylistsLayout(
            customPlaylists = customPlaylists,
            platformSections = platformSections,
            navController = navController,
            onCreatePlaylist = { showCreateDialog = true },
            onImportPlaylist = { showImportDialog = true },
            onDeletePlaylist = { playlistToDelete = it },
            onRefreshPlatform = { viewModel.refreshPlatform(it) },
            onRefreshAll = { viewModel.refreshAllPlatforms() },
            onCopyPlatformPlaylist = { source, pl -> platformPlaylistToCopy = source to pl },
            appSettings = appSettings
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                viewModel.createPlaylist(name)
                showCreateDialog = false
            }
        )
    }

    if (showImportDialog) {
        val credentialSources = viewModel.getAvailablePlatformsForImport()
        val lastImport =
            viewModelFactory.appSettingsManager.settings.collectAsState().value.lastImportProvider
        ImportPlaylistDialog(
            availableSources = credentialSources,
            initialSource = lastImport,
            onDismiss = { showImportDialog = false },
            onConfirm = { source, link ->
                showImportDialog = false
                viewModelFactory.appSettingsManager.update { copy(lastImportProvider = source) }
                viewModel.importPlaylist(source, link) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text(stringResource(R.string.delete_playlist_title)) },
            text = { Text(stringResource(R.string.delete_playlist_confirm, playlist.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePlaylist(playlist.id)
                    playlistToDelete = null
                }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    platformPlaylistToCopy?.let { (source, pl) ->
        AlertDialog(
            onDismissRequest = { platformPlaylistToCopy = null },
            title = { Text(stringResource(R.string.create_local_copy)) },
            text = {
                Text(
                    stringResource(
                        R.string.copy_playlist_confirm,
                        pl.title ?: stringResource(R.string.unknown_playlist)
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    platformPlaylistToCopy = null
                    viewModel.copyPlatformPlaylistToLocal(source, pl) { _, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text(
                        stringResource(R.string.create_btn),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { platformPlaylistToCopy = null }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}

// ===== Wide (landscape) layout =====

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun WidePlaylistsLayout(
    customPlaylists: List<Playlist>,
    platformSections: List<PlatformSection>,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    localMusicStore: LocalMusicStore,
    viewModelFactory: KunyinViewModelFactory,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onRefreshPlatform: (String) -> Unit,
    onCopyPlatformPlaylist: (String, PlayListInfoResult) -> Unit,
    appSettings: AppSettings
) {
    val context = LocalContext.current
    LocalHapticFeedback.current
    val wideViewModel: LocalPlaylistViewModel =
        viewModel(key = "wide_playlist", factory = viewModelFactory)

    // 滚动状态监听
    val wideListState = rememberLazyListState()
    rememberScrollOffsetReporter(wideListState)

    // 平台歌单选中状态提升
    var widePlatformSource by rememberSaveable {
        mutableStateOf(platformSections.firstOrNull()?.source ?: "")
    }
    if (platformSections.isNotEmpty() && platformSections.none { it.source == widePlatformSource }) {
        widePlatformSource = platformSections.first().source
    }

    // 选中歌单的本地 ID（-1 表示未选中或来自平台导航）
    var selectedPlaylistId by rememberSaveable {
        mutableLongStateOf(customPlaylists.firstOrNull()?.id ?: -1L)
    }
    // 自定义歌单列表变化时若当前选中已不存在，则选第一个
    LaunchedEffect(customPlaylists) {
        if (customPlaylists.isNotEmpty() &&
            customPlaylists.none { it.id == selectedPlaylistId }
        ) {
            selectedPlaylistId = customPlaylists.first().id
        }
    }
    val selected: PlaylistSelection? =
        customPlaylists.firstOrNull { it.id == selectedPlaylistId }
            ?.let { PlaylistSelection.Custom(it) }

    // 异步分页加载
    val displaySongs by wideViewModel.displaySongs.collectAsState()
    val wideIsLoading by wideViewModel.isLoading.collectAsState()
    val wideHasMore by wideViewModel.hasMore.collectAsState()
    val wideIsInitialLoad by wideViewModel.isInitialLoad.collectAsState()
    val wideTotalCount by wideViewModel.totalCount.collectAsState()
    val playerUiState by playerViewModel.uiState.collectAsState()
    val currentSong = playerUiState.currentSong

    val songs = when (selected) {
        is PlaylistSelection.PlatformPlaylist -> emptyList()
        else -> displaySongs
    }

    // 选择变化时触发分页加载
    LaunchedEffect(selected) {
        when (selected) {
            is PlaylistSelection.Custom -> wideViewModel.initLoad("custom", selected.playlist.id)
            else -> {}
        }
    }

    // 分页列表状态
    val songListState = rememberLazyListState()
    rememberScrollOffsetReporter(songListState)

    // 多选状态
    var selectionMode by remember { mutableStateOf(false) }
    val selectedSongs = remember { mutableStateListOf<MusicItem>() }

    // 多选时返回键退出多选
    androidx.activity.compose.BackHandler(enabled = selectionMode) {
        selectionMode = false
        selectedSongs.clear()
    }

    // 对话框状态
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

    // 导入歌曲对话框（横屏右侧面板）
    var showWideImportPlatformDialog by remember { mutableStateOf(false) }
    var showWideImportIdDialog by remember { mutableStateOf(false) }
    var wideImportPlatform by remember { mutableStateOf("") }
    val wideImportScope = rememberCoroutineScope()

    // 切换歌单时清除选择
    LaunchedEffect(selected) {
        selectionMode = false
        selectedSongs.clear()
    }

    // 平台歌单选中时导航到歌单详情页
    LaunchedEffect(selected) {
        if (selected is PlaylistSelection.PlatformPlaylist) {
            navController.navigate("playlist/${selected.source}:${selected.playlist.playListId}")
            // 重置选中为第一个本地歌单
            selectedPlaylistId = customPlaylists.firstOrNull()?.id ?: -1L
        }
    }

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

    val onRemove: (() -> Unit)? = when (selected) {
        is PlaylistSelection.Custom -> {
            val playlistId = selected.playlist.id
            {
                selectedSongs.forEach { song ->
                    localMusicStore.removeFromPlaylist(playlistId, song)
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.removed_from_playlist_count, selectedSongs.size),
                    Toast.LENGTH_SHORT
                ).show()
                exitSelectionMode()
                wideViewModel.refresh()
            }
        }

        else -> null // Platform 只读 / 未选中
    }

    val removeLabel = stringResource(R.string.remove_from_playlist)

    Column(modifier = Modifier.fillMaxSize()) {
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
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .then(if (!selectionMode) Modifier.padding(top = 32.dp) else Modifier)
        ) {
            // Left panel: playlist selection (LazyColumn for lazy image loading)
            LazyColumn(
                state = wideListState,
                modifier = Modifier
                    .width(300.dp)
                    .fillMaxHeight()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(
                    items = customPlaylists,
                    key = { "custom_${it.id}" }
                ) { playlist ->
                    val cardIcon = when (playlist.systemKind) {
                        com.ikunshare.sound.database.MusicDatabase.SYSTEM_KIND_FAVORITES -> Icons.Filled.Favorite
                        com.ikunshare.sound.database.MusicDatabase.SYSTEM_KIND_TRIAL -> Icons.Filled.PlayArrow
                        else -> Icons.AutoMirrored.Filled.QueueMusic
                    }
                    CompactPlaylistCard(
                        title = playlist.name,
                        count = playlist.songCount,
                        coverUrl = neteaseCoverUrl(playlist.coverUrl),
                        icon = cardIcon,
                        selected = (selected as? PlaylistSelection.Custom)?.playlist?.id == playlist.id,
                        onClick = { selectedPlaylistId = playlist.id },
                        onLongClick = if (!playlist.isSystem) {
                            { onDeletePlaylist(playlist) }
                        } else null
                    )
                }

                // 新建歌单 + 导入歌单
                item(key = "actions") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onCreatePlaylist),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.create_new),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onImportPlaylist),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDownload,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_text),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                // 平台歌单 - Tab 切换布局（懒渲染）
                if (platformSections.isNotEmpty()) {
                    item(key = "platform_tabs") {
                        PlatformTabHeader(
                            platformSections = platformSections,
                            selectedSource = widePlatformSource,
                            onSelectSource = { widePlatformSource = it },
                            onRefreshPlatform = onRefreshPlatform
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    val widePlatformSection =
                        platformSections.find { it.source == widePlatformSource }
                    if (widePlatformSection != null) {
                        items(
                            items = widePlatformSection.playlists,
                            key = { "platform_${widePlatformSection.source}_${it.playListId}" }
                        ) { pl ->
                            CompactPlaylistCard(
                                title = pl.title ?: "",
                                count = pl.totalSongs,
                                coverUrl = neteaseCoverUrl(pl.img),
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                selected = false,
                                onClick = {
                                    navController.navigate("playlist/${widePlatformSection.source}:${pl.playListId}")
                                },
                                onLongClick = {
                                    onCopyPlatformPlaylist(widePlatformSection.source, pl)
                                }
                            )
                        }
                    }
                }
            }

            VerticalDivider()

            // Right panel: song list
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            ) {
                val isPaginated = selected is PlaylistSelection.Custom
                if (isPaginated && wideIsInitialLoad) {
                    // 初始加载中
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (songs.isEmpty() && !(isPaginated && wideIsLoading)) {
                    EmptyState(
                        message = when (selected) {
                            is PlaylistSelection.Custom -> stringResource(R.string.empty_playlist)
                            is PlaylistSelection.PlatformPlaylist -> stringResource(R.string.loading)
                            null -> stringResource(R.string.empty_playlist)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val selectionKey = when (selected) {
                        is PlaylistSelection.Custom -> "custom_${selected.playlist.id}"
                        is PlaylistSelection.PlatformPlaylist -> "platform_${selected.source}_${selected.playlist.playListId}"
                        null -> "none"
                    }
                    LazyColumn(state = songListState, modifier = Modifier.fillMaxSize()) {
                        item(key = "play_all_$selectionKey") {
                            PlayAllBar(
                                songCount = if (isPaginated) wideTotalCount else songs.size,
                                onClick = {
                                    if (!isPaginated || !wideHasMore) {
                                        playerViewModel.setPlaylist(songs, 0)
                                        navController.navigate(Screen.Player.createRoute(songs.first().id))
                                    } else {
                                        wideViewModel.loadAllSongs { all ->
                                            if (all.isNotEmpty()) {
                                                playerViewModel.setPlaylist(all, 0)
                                                navController.navigate(Screen.Player.createRoute(all.first().id))
                                            }
                                        }
                                    }
                                },
                                onClear = null,
                                onAdd = { showWideImportPlatformDialog = true }
                            )
                        }

                        items(items = songs, key = { "${it.uniqueKey}_$selectionKey" }) { song ->
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
                                            if (appSettings.autoOpenPlayerOnSongClick) {
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
                                    isFavorite = localMusicStore.isFavorite(song),
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
                                        if (localMusicStore.isFavorite(song)) localMusicStore.removeFavorite(
                                            song
                                        )
                                        else localMusicStore.addFavorite(song)
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
                                    onRemove = when (selected) {
                                        is PlaylistSelection.Custom -> {
                                            { deleteSong = song; contextMenuSong = null }
                                        }

                                        else -> null
                                    },
                                    removeLabel = removeLabel,
                                    onReorder = if (selected is PlaylistSelection.Custom) {
                                        { reorderSong = song; contextMenuSong = null }
                                    } else null,
                                    mvSong = song
                                )
                            }
                        }

                        if (isPaginated) {
                            if (wideIsLoading && !wideIsInitialLoad) {
                                item(key = "loading_more") {
                                    Box(
                                        modifier = Modifier
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
                            if (!wideHasMore && songs.isNotEmpty()) {
                                item(key = "no_more") {
                                    Box(
                                        modifier = Modifier
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
                        }

                        item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }

        if (selectionMode && selectedSongs.isNotEmpty()) {
            SelectionBottomBar(
                selectedCount = selectedSongs.size,
                onPlayNext = {
                    playerViewModel.addToPlayNext(selectedSongs.toList())
                    Toast.makeText(
                        context,
                        context.getString(R.string.added_to_play_next_count, selectedSongs.size),
                        Toast.LENGTH_SHORT
                    ).show()
                    exitSelectionMode()
                },
                onDownload = { showQualityPicker = true },
                onAddToPlaylist = { showPlaylistPicker = true },
                onRemove = onRemove,
                removeLabel = removeLabel,
                floatingBottomBar = appSettings.floatingBottomBar,
                liquidGlass = appSettings.liquidGlassMode
            )
        }
    }

    // 添加到歌单对话框（多选）
    if (showPlaylistPicker && singlePlaylistSong == null) {
        val excludeId = (selected as? PlaylistSelection.Custom)?.playlist?.id
        AddToPlaylistDialog(
            playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onPlaylistClick = { pid ->
                selectedSongs.forEach { song ->
                    localMusicStore.addToPlaylist(pid, song)
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
            excludePlaylistId = excludeId
        )
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = {
                showCreateDialog = false
                if (singlePlaylistSong != null) singlePlaylistSong = null
            },
            onConfirm = { name ->
                if (singlePlaylistSong != null) {
                    localMusicStore.createPlaylistAndAdd(name, singlePlaylistSong!!)
                    Toast.makeText(
                        context,
                        context.getString(R.string.created_playlist_added_count, 1),
                        Toast.LENGTH_SHORT
                    ).show()
                    singlePlaylistSong = null
                } else {
                    val songsToAdd = selectedSongs.toList()
                    if (songsToAdd.isNotEmpty()) {
                        val pid = localMusicStore.createPlaylistAndAdd(name, songsToAdd.first())
                        songsToAdd.drop(1).forEach { localMusicStore.addToPlaylist(pid, it) }
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
        val excludeId = (selected as? PlaylistSelection.Custom)?.playlist?.id
        AddToPlaylistDialog(
            playlists = playlists,
            isFavorite = localMusicStore.isFavorite(singlePlaylistSong!!),
            onFavoriteClick = {
                localMusicStore.addFavorite(singlePlaylistSong!!)
                singlePlaylistSong = null
            },
            onDismiss = { singlePlaylistSong = null },
            onPlaylistClick = { pid ->
                localMusicStore.addToPlaylist(pid, singlePlaylistSong!!)
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
            excludePlaylistId = excludeId
        )
    }

    // 删除确认对话框
    if (deleteSong != null) {
        AlertDialog(
            onDismissRequest = { deleteSong = null },
            title = { Text(stringResource(R.string.confirm)) },
            text = {
                Text(
                    stringResource(
                        R.string.delete_song_confirm,
                        removeLabel,
                        deleteSong!!.title
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val song = deleteSong!!
                    when (selected) {
                        is PlaylistSelection.Custom -> {
                            val pid = selected.playlist.id
                            localMusicStore.removeFromPlaylist(pid, song)
                            wideViewModel.refresh()
                        }

                        else -> {}
                    }
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

    if (reorderSong != null && selected is PlaylistSelection.Custom) {
        val playlistId = selected.playlist.id
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
                            localMusicStore.moveSongInPlaylist(
                                playlistId,
                                reorderSong!!,
                                newPos - 1
                            )
                            wideViewModel.refresh()
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

    // 导入歌曲 - 平台选择对话框（横屏）
    if (showWideImportPlatformDialog) {
        AlertDialog(
            onDismissRequest = { showWideImportPlatformDialog = false },
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
                                wideImportPlatform = platform.key
                                showWideImportPlatformDialog = false
                                showWideImportIdDialog = true
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
                TextButton(onClick = { showWideImportPlatformDialog = false }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    // 导入歌曲 - ID 输入对话框（横屏）
    if (showWideImportIdDialog) {
        var idInput by remember { mutableStateOf("") }
        var isImporting by remember { mutableStateOf(false) }

        val platformLabel = when (wideImportPlatform) {
            "qq" -> "QQ音乐"
            "wy" -> "网易云音乐"
            else -> ""
        }

        AlertDialog(
            onDismissRequest = { if (!isImporting) showWideImportIdDialog = false },
            title = { Text(stringResource(R.string.import_platform_songs, platformLabel)) },
            text = {
                Column {
                    Text(
                        text = when (wideImportPlatform) {
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
                        label = { Text(if (wideImportPlatform == "qq") "ID / Mid" else "ID") },
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
                        wideImportScope.launch(Dispatchers.IO) {
                            val repo =
                                SoundApplication.instance?.musicRepository
                            val item: MusicItem? = when (wideImportPlatform) {
                                "qq" -> {
                                    val p =
                                        repo?.getProvider("qq") as? com.ikunshare.sound.platform.qq.QQProvider
                                    if (input.matches(Regex("^\\d+$"))) p?.fromID(input.toLong())
                                    else p?.fromMid(input)
                                }

                                "wy" -> input.toLongOrNull()?.let {
                                    (repo?.getProvider("wy") as? com.ikunshare.sound.platform.wy.WyProvider)?.fromID(
                                        it
                                    )
                                }

                                "kw" -> input.toLongOrNull()?.let {
                                    (repo?.getProvider("kw") as? com.ikunshare.sound.platform.kw.KwProvider)?.fromID(
                                        it
                                    )
                                }

                                else -> null
                            }
                            withContext(Dispatchers.Main) {
                                if (item != null) {
                                    when (selected) {
                                        is PlaylistSelection.Custom -> {
                                            localMusicStore.addToPlaylist(
                                                selected.playlist.id,
                                                item
                                            )
                                            wideViewModel.refresh()
                                        }

                                        else -> localMusicStore.addFavorite(item)
                                    }
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.imported_song, item.title),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    showWideImportIdDialog = false
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
                    onClick = { showWideImportIdDialog = false },
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

// ===== Narrow (portrait) layout =====

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun NarrowPlaylistsLayout(
    customPlaylists: List<Playlist>,
    platformSections: List<PlatformSection>,
    navController: NavController,
    onCreatePlaylist: () -> Unit,
    onImportPlaylist: () -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onRefreshPlatform: (String) -> Unit,
    onRefreshAll: () -> Unit,
    onCopyPlatformPlaylist: (String, PlayListInfoResult) -> Unit,
    appSettings: AppSettings
) {
    // 滚动状态监听
    val narrowListState = rememberLazyListState()
    rememberScrollOffsetReporter(narrowListState)

    // 平台歌单选中状态提升
    var selectedPlatformSource by rememberSaveable {
        mutableStateOf(platformSections.firstOrNull()?.source ?: "")
    }
    if (platformSections.isNotEmpty() && platformSections.none { it.source == selectedPlatformSource }) {
        selectedPlatformSource = platformSections.first().source
    }

    val isRefreshing = platformSections.any { it.isLoading }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.playlists_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = Color.Transparent
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefreshAll,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state = narrowListState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(items = customPlaylists, key = { "custom_${it.id}" }) { playlist ->
                    val cardIcon = when (playlist.systemKind) {
                        com.ikunshare.sound.database.MusicDatabase.SYSTEM_KIND_FAVORITES -> Icons.Filled.Favorite
                        com.ikunshare.sound.database.MusicDatabase.SYSTEM_KIND_TRIAL -> Icons.Filled.PlayArrow
                        else -> Icons.AutoMirrored.Filled.QueueMusic
                    }
                    PlaylistCard(
                        title = playlist.name,
                        count = playlist.songCount,
                        coverUrl = neteaseCoverUrl(playlist.coverUrl),
                        icon = cardIcon,
                        onClick = { navController.navigate("local_playlist/custom_${playlist.id}") },
                        onLongClick = if (!playlist.isSystem) {
                            { onDeletePlaylist(playlist) }
                        } else null
                    )
                }

                item(key = "action_buttons") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 新建歌单
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onCreatePlaylist),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.create_playlist),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        // 导入歌单
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onImportPlaylist),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.CloudDownload,
                                    contentDescription = null,
                                    modifier = Modifier.size(28.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.import_playlist),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }

                // 平台歌单 - Tab 切换布局（懒渲染）
                if (platformSections.isNotEmpty()) {
                    item(key = "platform_header") {
                        PlatformTabHeader(
                            platformSections = platformSections,
                            selectedSource = selectedPlatformSource,
                            onSelectSource = { selectedPlatformSource = it },
                            onRefreshPlatform = onRefreshPlatform
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    val selectedSection =
                        platformSections.find { it.source == selectedPlatformSource }
                    if (selectedSection != null) {
                        items(
                            items = selectedSection.playlists,
                            key = { "platform_${selectedSection.source}_${it.playListId}" }
                        ) { pl ->
                            PlaylistCard(
                                title = pl.title ?: "",
                                count = pl.totalSongs,
                                coverUrl = neteaseCoverUrl(pl.img),
                                icon = Icons.AutoMirrored.Filled.QueueMusic,
                                onClick = {
                                    navController.navigate("playlist/${selectedSection.source}:${pl.playListId}")
                                },
                                onLongClick = {
                                    onCopyPlatformPlaylist(selectedSection.source, pl)
                                }
                            )
                        }
                    }
                }

                item(key = "bottom_spacer") {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

// ===== Platform Tab Section =====

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlatformTabHeader(
    platformSections: List<PlatformSection>,
    selectedSource: String,
    onSelectSource: (String) -> Unit,
    onRefreshPlatform: (String) -> Unit
) {
    LocalHapticFeedback.current
    val context = LocalContext.current
    val selectedSection = platformSections.find { it.source == selectedSource }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                platformSections.forEach { section ->
                    FilterChip(
                        selected = section.source == selectedSource,
                        onClick = { onSelectSource(section.source) },
                        label = {
                            Text(
                                text = section.displayName,
                                style = MaterialTheme.typography.labelMedium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f),
                            selectedLabelColor = MaterialTheme.colorScheme.tertiary
                        )
                    )
                }
            }

            if (selectedSection != null) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = {
                        onRefreshPlatform(selectedSource)
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.refreshing_platform,
                                selectedSection.displayName
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    if (selectedSection.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.refresh_playlists),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        if (selectedSection?.userInfo != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = selectedSection.userInfo.avatar,
                    contentDescription = selectedSection.userInfo.name,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = selectedSection.userInfo.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ===== Platform Section Header =====

// ===== Import Playlist Dialog =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportPlaylistDialog(
    availableSources: List<Pair<String, String>>, // (source key, display name)
    initialSource: String = "",
    onDismiss: () -> Unit,
    onConfirm: (source: String, link: String) -> Unit
) {
    val defaultSource =
        if (initialSource.isNotBlank() && availableSources.any { it.first == initialSource })
            initialSource else availableSources.firstOrNull()?.first ?: ""
    var selectedSource by remember { mutableStateOf(defaultSource) }
    var link by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    val selectedDisplayName = availableSources.find { it.first == selectedSource }?.second ?: ""

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_playlist_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (availableSources.size > 1) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded }
                    ) {
                        OutlinedTextField(
                            value = selectedDisplayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.select_platform)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            availableSources.forEach { (source, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        selectedSource = source
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                } else if (availableSources.size == 1) {
                    Text(
                        text = stringResource(R.string.platform_label, selectedDisplayName),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text(stringResource(R.string.share_link_or_id)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(selectedSource, link) },
                enabled = link.isNotBlank() && selectedSource.isNotBlank()
            ) {
                Text(
                    stringResource(R.string.import_btn),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    stringResource(R.string.cancel),
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    )
}

// ===== Shared components =====

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactPlaylistCard(
    title: String,
    count: Int,
    coverUrl: String?,
    icon: ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val containerColor = if (selected)
        MaterialTheme.colorScheme.secondaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (onLongClick != null) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                } else null
            ),
        color = containerColor,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(6.dp)
                    ) {}
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.song_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun PlayAllBar(
    songCount: Int,
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.tertiary
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.height(42.dp)
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play_all), style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.song_count, songCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        if (onAdd != null) {
            IconButton(onClick = onAdd) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.import_songs),
                    tint = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        if (onClear != null) {
            IconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        thickness = 0.5.dp
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    title: String,
    count: Int,
    coverUrl: String?,
    icon: ImageVector,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (onLongClick != null) {
                    {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongClick()
                    }
                } else null
            ),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 封面
            if (!coverUrl.isNullOrEmpty()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp)
                    ) {}
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.song_count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

// ===== ViewModel =====

data class PlatformSection(
    val source: String,
    val displayName: String,
    val userInfo: UserInfo?,
    val playlists: List<PlayListInfoResult>,
    val isLoading: Boolean = false
)

class PlaylistsViewModel(
    private val repository: MusicRepository,
    private val credentialManager: CredentialManager,
    val localMusicStore: LocalMusicStore,
    private val platformPlaylistStore: PlatformPlaylistStore
) : ViewModel() {
    val customPlaylists: StateFlow<List<Playlist>> = localMusicStore.playlistsFlow

    private val _platformSections = MutableStateFlow<List<PlatformSection>>(emptyList())
    val platformSections: StateFlow<List<PlatformSection>> = _platformSections.asStateFlow()

    companion object {
        private const val TAG = "PlaylistsViewModel"

        /** 从 providers 动态获取支持歌单同步的平台 */
        private val SYNCABLE_PLATFORMS: Set<String>
            get() {
                val app = SoundApplication.instance ?: return emptySet()
                return app.musicRepository.getAvailableProviders().filter { key ->
                    app.musicRepository.getProvider(key)?.supportsSyncPlaylist == true
                }.toSet()
            }

        /** 从 providers 动态获取平台显示名称 */
        private fun platformName(source: String): String {
            return SoundApplication.instance?.musicRepository?.getProvider(source)?.displayName
                ?: source
        }
    }

    init {
        // 从缓存加载立即显示
        loadCachedSections()
        // 后台刷新
        refreshAllPlatforms()
        // 监听凭据变化
        viewModelScope.launch {
            credentialManager.credentials.collect { creds ->
                val current = _platformSections.value.map { it.source }.toSet()
                // 新登录的平台
                for (key in creds.keys) {
                    if (key in SYNCABLE_PLATFORMS && key !in current) {
                        refreshPlatform(key)
                    }
                }
                // 已退出的平台
                val toRemove = current.filter { it !in creds.keys }
                if (toRemove.isNotEmpty()) {
                    _platformSections.value =
                        _platformSections.value.filter { it.source !in toRemove }
                    toRemove.forEach { platformPlaylistStore.clear(it) }
                }
            }
        }
    }

    private fun loadCachedSections() {
        val sections = mutableListOf<PlatformSection>()
        val creds = credentialManager.credentials.value
        for (source in SYNCABLE_PLATFORMS) {
            if (source !in creds) continue
            val userInfo = platformPlaylistStore.getUserInfo(source)
            val playlists = platformPlaylistStore.getPlaylists(source)
            if (userInfo != null || playlists.isNotEmpty()) {
                sections.add(
                    PlatformSection(
                        source = source,
                        displayName = platformName(source),
                        userInfo = userInfo,
                        playlists = playlists
                    )
                )
            }
        }
        _platformSections.value = sections
    }

    fun refreshAllPlatforms() {
        val creds = credentialManager.credentials.value
        for (source in SYNCABLE_PLATFORMS) {
            if (source in creds) {
                refreshPlatform(source)
            }
        }
    }

    fun refreshPlatform(source: String) {
        viewModelScope.launch {
            // 记住刷新前是否已有缓存数据
            val hadExistingData = _platformSections.value.any { it.source == source }

            // 设置 loading
            updateSection(source) { it.copy(isLoading = true) }
            // 如果该平台不在列表中，先添加
            if (!hadExistingData) {
                _platformSections.value += PlatformSection(
                    source = source,
                    displayName = platformName(source),
                    userInfo = platformPlaylistStore.getUserInfo(source),
                    playlists = platformPlaylistStore.getPlaylists(source),
                    isLoading = true
                )
            }

            try {
                // 先获取用户信息
                val userInfo = repository.getUserInfoForSource(source)
                if (userInfo != null) {
                    platformPlaylistStore.saveUserInfo(source, userInfo)
                    updateSection(source) { it.copy(userInfo = userInfo) }
                }

                // 再获取歌单列表
                val playlists = repository.getUserPlaylistForSource(source)
                platformPlaylistStore.savePlaylists(source, playlists)
                // 歌单列表变化，清除旧的歌曲缓存
                platformPlaylistStore.clearSongs(source)
                updateSection(source) { it.copy(playlists = playlists, isLoading = false) }

                // 仅首次加载时，如果用户信息和歌单都为空，移除空 section
                if (!hadExistingData && userInfo == null && playlists.isEmpty()) {
                    _platformSections.value = _platformSections.value.filter { it.source != source }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh platform $source", e)
                // 刷新失败：保留已有数据，仅停止 loading
                updateSection(source) { it.copy(isLoading = false) }
            }
        }
    }

    fun importPlaylist(source: String, link: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                // 获取歌单信息
                val info = repository.getPlaylistInfoForSource(source, link)
                val title = info?.title ?: "导入的歌单"
                info?.totalSongs ?: 0
                val playlistId = info?.playListId ?: link

                // 分页加载所有歌曲
                val allSongs = mutableListOf<MusicItem>()
                var page = 0
                val pageSize = 50
                var hasMore = true
                while (hasMore) {
                    val result =
                        repository.getPlaylistSongsForSource(source, playlistId, page, pageSize)
                    allSongs.addAll(result.result)
                    hasMore = result.hasNext
                    page++
                }

                if (allSongs.isEmpty()) {
                    onResult(false, "歌单为空或加载失败")
                    return@launch
                }

                // 创建本地歌单
                val localId = localMusicStore.createPlaylistAndAdd(
                    title, allSongs.first(),
                    remoteSource = source,
                    remoteId = playlistId
                )
                allSongs.drop(1).forEach { localMusicStore.addToPlaylist(localId, it) }

                onResult(
                    true,
                    SoundApplication.instance!!.getString(
                        R.string.import_playlist_result,
                        title,
                        allSongs.size
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Import playlist failed", e)
                onResult(
                    false,
                    SoundApplication.instance!!.getString(
                        R.string.import_playlist_failed,
                        e.message ?: ""
                    )
                )
            }
        }
    }

    fun getAvailablePlatformsForImport(): List<Pair<String, String>> {
        // 通过链接导入歌单不需要登录，从 providers 过滤支持导入的平台
        val app = SoundApplication.instance ?: return emptyList()
        return app.musicRepository.getAvailableProviders().mapNotNull { key ->
            val provider = app.musicRepository.getProvider(key) ?: return@mapNotNull null
            if (provider.supportsImportPlaylist) key to provider.displayName else null
        }
    }

    private fun updateSection(source: String, transform: (PlatformSection) -> PlatformSection) {
        _platformSections.value = _platformSections.value.map {
            if (it.source == source) transform(it) else it
        }
    }

    fun createPlaylist(name: String) {
        localMusicStore.createPlaylist(name)
    }

    fun deletePlaylist(playlistId: Long) {
        localMusicStore.deletePlaylist(playlistId)
    }

    fun copyPlatformPlaylistToLocal(
        source: String,
        playlist: PlayListInfoResult,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val title = playlist.title ?: "导入的歌单"
                val playlistId = playlist.playListId

                val allSongs = mutableListOf<MusicItem>()
                var page = 0
                val pageSize = 50
                var hasMore = true
                while (hasMore) {
                    val result =
                        repository.getPlaylistSongsForSource(source, playlistId, page, pageSize)
                    allSongs.addAll(result.result)
                    hasMore = result.hasNext
                    page++
                }

                if (allSongs.isEmpty()) {
                    onResult(false, "歌单为空或加载失败")
                    return@launch
                }

                val localId = localMusicStore.createPlaylistAndAdd(
                    title, allSongs.first(),
                    remoteSource = source,
                    remoteId = playlistId
                )
                allSongs.drop(1).forEach { localMusicStore.addToPlaylist(localId, it) }

                onResult(
                    true,
                    SoundApplication.instance!!.getString(
                        R.string.create_local_copy_result,
                        title,
                        allSongs.size
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Copy platform playlist failed", e)
                onResult(
                    false,
                    SoundApplication.instance!!.getString(
                        R.string.create_copy_failed,
                        e.message ?: ""
                    )
                )
            }
        }
    }
}
