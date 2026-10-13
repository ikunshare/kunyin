package com.ikunshare.music.ui.screens.discover

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.manager.discovery.discoveryPlaylistSorts
import com.ikunshare.music.manager.discovery.canSortDiscoveryCategory
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.*
import com.ikunshare.music.ui.navigation.Screen
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverScreen(nav: NavController, factory: KunyinViewModelFactory) {
    val vm: DiscoverViewModel = viewModel(factory = factory)
    val state by vm.uiState.collectAsState()
    val s = state.selection
    val grid = rememberLazyGridState()
    val failedCovers = remember { mutableStateMapOf<String, Boolean>() }
    var showCategories by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }
    var showSorts by remember { mutableStateOf(false) }
    val sources = listOf("wy" to "网易云音乐", "qq" to "QQ音乐", "kg" to "酷狗音乐", "kw" to "酷我音乐")
    val sorts = discoveryPlaylistSorts.getValue(s.source)
    LaunchedEffect(vm) { vm.start() }
    LaunchedEffect(s) { grid.scrollToItem(0) }
    LiquidGlassScene {
        Scaffold(containerColor = Color.Transparent, topBar = {
            GlassTopBar(title = { Text("发现歌单") }, navigationIcon = {
                GlassIconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            }, actions = { GlassIconButton(onClick = { nav.navigate(Screen.Charts.route) }) { Icon(Icons.Filled.Leaderboard, "排行榜") } })
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        GlassButton(onClick = { showSources = true }, modifier = Modifier.fillMaxWidth()) { Text(sources.first { it.first == s.source }.second, maxLines = 1) }
                        DropdownMenu(showSources, { showSources = false }) {
                            sources.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { showSources = false; vm.switchSource(id) }) }
                        }
                    }
                    GlassButton(onClick = { showCategories = true }, modifier = Modifier.weight(1f)) {
                        Text(state.categories.firstOrNull { it.id == s.category }?.name ?: if (s.category.isEmpty()) "全部分类" else s.category, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    GlassButton(onClick = { showSorts = true }, enabled = s.category.isEmpty() || canSortDiscoveryCategory(s.source)) {
                        Text("排序：${sorts.firstOrNull { it.id == s.order }?.name.orEmpty()}")
                    }
                    DropdownMenu(showSorts, { showSorts = false }) {
                        sorts.forEach { sort -> DropdownMenuItem(text = { Text(sort.name) }, onClick = { showSorts = false; vm.switchOrder(sort.id) }) }
                    }
                }
                when {
                    state.loading -> Box(Modifier.fillMaxWidth().weight(1f), Alignment.Center) { CircularProgressIndicator() }
                    state.error != null -> ErrorState(state.error!!, vm::load, Modifier.weight(1f))
                    state.items.isEmpty() -> EmptyState("暂无歌单", modifier = Modifier.weight(1f))
                    else -> LazyVerticalGrid(GridCells.Adaptive(280.dp), Modifier.weight(1f), state = grid,
                        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.items, key = { "${it.source}:${it.playListId}" }) { playlist ->
                            DiscoveryPlaylistCard(playlist, failedCovers = failedCovers, onClick = {
                                nav.navigate(Screen.Playlist.createRoute(Uri.encode("${playlist.source}:${playlist.playListId}")))
                            })
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    GlassButton(onClick = { vm.goToPage(s.page - 1) }, enabled = !state.loading && s.page > 0) { Text("上一页") }
                    val count = if (state.total > 0) ((state.total.toLong() + state.pageSize - 1) / state.pageSize).coerceAtLeast(1) else null
                    Text("第 ${s.page + 1} 页" + (count?.let { " / $it" } ?: ""))
                    GlassButton(onClick = { vm.goToPage(s.page + 1) }, enabled = !state.loading && state.hasNext) { Text("下一页") }
                }
                Spacer(Modifier.height(LocalFloatingBottomBarReserve.current))
            }
        }
    }
    if (showCategories) AlertDialog(onDismissRequest = { showCategories = false }, title = { Text("歌单分类") },
        text = {
            when {
                state.categoriesLoading -> Box(Modifier.fillMaxWidth(), Alignment.Center) { CircularProgressIndicator() }
                state.categoryError != null -> Column {
                    Text(state.categoryError!!)
                    TextButton(onClick = vm::retryCategories) { Text("重试分类") }
                    TextButton(onClick = { vm.switchCategory(""); showCategories = false }) { Text("查看全部歌单") }
                }
                else -> LazyColumn(Modifier.heightIn(max = 440.dp)) {
                    item { TextButton(onClick = { vm.switchCategory(""); showCategories = false }) { Text("全部分类") } }
                    state.categories.groupBy { it.group }.forEach { (group, categories) ->
                        item { Text(group, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall) }
                        items(categories) { category -> TextButton(onClick = { vm.switchCategory(category.id); showCategories = false }) { Text(category.name) } }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { showCategories = false }) { Text("关闭") } })
}
