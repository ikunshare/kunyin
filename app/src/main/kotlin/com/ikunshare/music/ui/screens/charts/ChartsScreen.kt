package com.ikunshare.music.ui.screens.charts

import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.manager.discovery.ChartInfo
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.*
import com.ikunshare.music.ui.components.GlassAlertDialog
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.utils.rememberPlaylistWriteActions
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChartsScreen(navController: NavController, player: PlayerViewModel, factory: KunyinViewModelFactory) {
    val playerSheet = LocalPlayerSheet.current
    val vm: ChartsViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()
    val currentSongKey by remember(player) {
        player.uiState.map { it.currentSong?.uniqueKey }.distinctUntilChanged()
    }.collectAsState(initial = null)
    val context = LocalContext.current
    val selected = remember { mutableStateListOf<MusicItem>() }
    var menu by remember { mutableStateOf<MusicItem?>(null) }
    var download by remember { mutableStateOf<List<MusicItem>?>(null) }
    var addSongs by remember { mutableStateOf<List<MusicItem>?>(null) }
    var create by remember { mutableStateOf(false) }
    var boardMenu by remember { mutableStateOf<ChartInfo?>(null) }
    val writes = rememberPlaylistWriteActions(factory.localMusicStore)
    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(factory.downloadManager)
    val playlists by factory.localMusicStore.playlistsFlow.collectAsState()
    val listState = rememberLazyListState()
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    LaunchedEffect(vm) { vm.start() }
    LaunchedEffect(state.source, state.board?.id, state.page, state.loading) {
        selected.clear(); menu = null; listState.scrollToItem(0)
    }
    BackHandler(selected.isNotEmpty() && !writes.isWriting) { selected.clear() }
    fun toggle(song: MusicItem) {
        if (writes.isWriting) return
        if (selected.any { it.uniqueKey == song.uniqueKey }) selected.removeAll { it.uniqueKey == song.uniqueKey }
        else selected.add(song)
    }
    fun play(song: MusicItem) {
        val board = state.board ?: return
        vm.stopPreparingPlayback()
        val index = state.songs.indexOfFirst { it.uniqueKey == song.uniqueKey }
        if (index < 0) return
        player.setPlaylistAndPlay(state.songs, index, board.queueSource())
        playerSheet.open(song)
    }
    fun collect(board: ChartInfo) {
        vm.collectBoard(board, onSuccess = {
            Toast.makeText(context, "已收藏「${board.name}」到我的歌单", Toast.LENGTH_SHORT).show()
        }, onFailure = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() })
    }
    fun playAll(board: ChartInfo? = null) {
        val previousQueue = player.queueSongs.value
        val previousSong = player.uiState.value.currentSong?.uniqueKey
        vm.playAll(onFirst = { songs, source ->
            if (player.queueSongs.value !== previousQueue || player.uiState.value.currentSong?.uniqueKey != previousSong) false
            else {
                player.setPlaylistAndPlay(songs, 0, source)
                playerSheet.open(songs.first())
                true
            }
        }, onAppend = { songs, source -> player.appendToQueue(songs, source) },
            onFailure = { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }, boardToPlay = board)
    }

    LiquidGlassScene(cover = state.board?.cover) {
        Scaffold(containerColor = Color.Transparent,
            topBar = {
                GlassTopBar(title = { Text(if (selected.isEmpty()) "排行榜" else "已选择 ${selected.size} 首") },
                    navigationIcon = {
                        GlassIconButton(onClick = { if (selected.isNotEmpty()) selected.clear() else navController.popBackStack() }, enabled = !writes.isWriting) {
                            Icon(if (selected.isEmpty()) Icons.AutoMirrored.Filled.ArrowBack else Icons.Filled.Close, "返回")
                        }
                    }, actions = {
                        if (selected.isNotEmpty()) GlassIconButton(onClick = {
                            if (selected.size == state.songs.size) selected.clear()
                            else { selected.clear(); selected.addAll(state.songs) }
                        }, enabled = !writes.isWriting) { Icon(Icons.Filled.SelectAll, "全选") }
                        else GlassIconButton(onClick = vm::refreshCatalog, enabled = !state.boardsLoading && !writes.isWriting) {
                            Icon(Icons.Filled.Refresh, "刷新榜单")
                        }
                    })
            }, bottomBar = {
                if (selected.isNotEmpty()) SelectionBottomBar(selected.size,
                    onDownload = { download = selected.toList() }, onAddToPlaylist = { addSongs = selected.toList() },
                    onPlayNext = { player.addToPlayNext(selected.toList()); selected.clear() },
                    busy = writes.isWriting)
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ChartChoice(state.source, listOf("wy" to "网易云音乐", "qq" to "QQ音乐", "kg" to "酷狗音乐", "kw" to "酷我音乐"),
                    enabled = !writes.isWriting, onChoose = vm::switchSource)
                when {
                    state.boardsLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                    state.boardsError != null -> ErrorState(state.boardsError!!, vm::refreshCatalog)
                    state.boards.isEmpty() -> EmptyState("暂无榜单")
                    else -> Row(Modifier.weight(1f)) {
                        if (wide) LazyColumn(Modifier.width(230.dp).fillMaxHeight(), contentPadding = PaddingValues(12.dp)) {
                            items(state.boards, key = { it.id }) { board ->
                                Text(board.name, Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .liquidGlass(selected = state.board?.id == board.id)
                                    .combinedClickable(enabled = !writes.isWriting, onClick = { vm.selectBoard(board) },
                                        onLongClick = { boardMenu = board }).padding(16.dp),
                                    color = if (state.board?.id == board.id) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            if (!wide) ChartChoice(state.board?.id.orEmpty(), state.boards.map { it.id to it.name },
                                enabled = !writes.isWriting, onChoose = { id -> state.boards.firstOrNull { it.id == id }?.let(vm::selectBoard) },
                                onActions = { id -> boardMenu = state.boards.firstOrNull { it.id == id } })
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(state.board?.name.orEmpty(), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(listOfNotNull(state.total.takeIf { it > 0 }?.let { "$it 首" },
                                        state.board?.updateFrequency?.takeIf { it.isNotBlank() }, state.board?.period?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val collecting = state.board?.let { "${it.source}:${it.id}" in state.collecting } == true
                                GlassIconButton(onClick = { state.board?.let(::collect) }, enabled = state.board != null && !collecting) {
                                    if (collecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Filled.Bookmark, "收藏整榜到我的歌单")
                                }
                                GlassButton(onClick = { playAll() }, enabled = !state.playingAll && !state.loading && state.board != null) {
                                    Text(if (state.playingAll) "准备播放…" else "播放全部")
                                }
                            }
                            when {
                                state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                                state.error != null -> ErrorState(state.error!!, vm::retryPage, Modifier.weight(1f))
                                state.songs.isEmpty() -> EmptyState("榜单暂无歌曲", modifier = Modifier.weight(1f))
                                else -> LazyColumn(Modifier.weight(1f), state = listState,
                                    contentPadding = PaddingValues(bottom = 12.dp)) {
                                    items(state.songs, key = { it.uniqueKey }) { song ->
                                        Box {
                                            SongCard(song, onClick = { if (selected.isNotEmpty()) toggle(song) else play(song) },
                                                selectionMode = selected.isNotEmpty(), selected = selected.any { it.uniqueKey == song.uniqueKey },
                                                isPlaying = currentSongKey == song.uniqueKey,
                                                onLongClick = { if (selected.isNotEmpty()) toggle(song) else menu = song }, onCheckedChange = { toggle(song) },
                                                onPlayClick = { play(song) },
                                                onAddToPlaylistClick = { addSongs = listOf(song) },
                                                onDownloadClick = { download = listOf(song) }
                                            )
                                            SongContextMenu(expanded = menu?.uniqueKey == song.uniqueKey,
                                                isFavorite = factory.localMusicStore.isFavorite(song), onDismiss = { menu = null },
                                                onPlay = { play(song) }, onPlayNext = { player.addToPlayNext(song) },
                                                onDownload = { download = listOf(song) },
                                                onToggleFavorite = {
                                                    if (factory.localMusicStore.isFavorite(song)) factory.localMusicStore.removeFavorite(song)
                                                    else factory.localMusicStore.addFavorite(song)
                                                }, onAddToPlaylist = { addSongs = listOf(song) },
                                                onMultiSelect = { toggle(song) }, mvSong = song)
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                GlassButton(onClick = { vm.loadPage(state.page - 1) }, enabled = state.page > 0 && !state.loading && !writes.isWriting) { Text("上一页") }
                                Text("第 ${state.page + 1} 页", style = MaterialTheme.typography.bodyMedium)
                                GlassButton(onClick = { vm.loadPage(state.page + 1) }, enabled = state.hasNext && !state.loading && !writes.isWriting) { Text("下一页") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(LocalFloatingBottomBarReserve.current))
            }
        }
    }
    boardMenu?.let { board ->
        val collecting = "${board.source}:${board.id}" in state.collecting
        GlassAlertDialog(onDismissRequest = { boardMenu = null }, title = { Text(board.name) },
            text = { Text("播放完整榜单，或将全部歌曲收藏为本地歌单。") },
            confirmButton = { TextButton(onClick = { boardMenu = null; collect(board) }, enabled = !collecting) { Text(if (collecting) "正在收藏…" else "收藏到我的歌单") } },
            dismissButton = { TextButton(onClick = { boardMenu = null; playAll(board) }, enabled = !state.playingAll) { Text("播放全部") } })
    }
    download?.let { snapshot ->
        DownloadQualityDialog(factory.appSettingsManager.settings.value.hideAiQualities,
            busy = downloadQueue.isEnqueuing, onDismiss = { download = null }, onConfirm = { qualityId ->
                val hideAi = factory.appSettingsManager.settings.value.hideAiQualities
                downloadQueue.add(snapshot, qualityId, hideAi) { download = null; selected.clear() }
            })
    }
    addSongs?.let { snapshot ->
        if (create) CreatePlaylistDialog(busy = writes.isWriting, onDismiss = { create = false; addSongs = null },
            onConfirm = { name -> writes.create(name, snapshot) { create = false; addSongs = null; selected.clear() } })
        else AddToPlaylistDialog(playlists, busy = writes.isWriting, onDismiss = { addSongs = null },
            onPlaylistClick = { id -> writes.add(id, snapshot) { addSongs = null; selected.clear() } }, onCreateNew = { create = true })
    }
}

@Composable
private fun ChartChoice(value: String, choices: List<Pair<String, String>>, enabled: Boolean,
                        onChoose: (String) -> Unit, onActions: ((String) -> Unit)? = null) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        GlassButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { it.first == value }?.second ?: "选择榜单", maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded && enabled, onDismissRequest = { expanded = false }) {
            choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; onChoose(id) },
                trailingIcon = onActions?.let { action -> { IconButton(onClick = { expanded = false; action(id) }) { Icon(Icons.Filled.MoreVert, "$label 操作") } } }) }
        }
    }
}
