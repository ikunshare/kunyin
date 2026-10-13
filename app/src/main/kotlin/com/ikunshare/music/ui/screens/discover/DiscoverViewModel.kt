package com.ikunshare.music.ui.screens.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.discovery.*
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.PlaylistSearchResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class DiscoverySelection(val source: String = "wy", val category: String = "", val order: String = "hot", val page: Int = 0)
data class DiscoverUiState(
    val selection: DiscoverySelection = DiscoverySelection(),
    val categories: List<PlaylistCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    val categoryError: String? = null,
    val items: List<PlayListInfoResult> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val total: Int = 0,
    val pageSize: Int = 30,
    val hasNext: Boolean = false
)

class DiscoverViewModel internal constructor(
    private val categories: suspend (String, Boolean) -> List<PlaylistCategory>,
    private val playlists: suspend (DiscoverySelection) -> PlaylistSearchResult,
    private val restore: () -> DiscoverySelection = { DiscoverySelection() },
    private val persist: (DiscoverySelection) -> Unit = {}
) : ViewModel() {
    constructor(repository: MusicRepository, restore: () -> DiscoverySelection, persist: (DiscoverySelection) -> Unit) : this(
        { source, refresh -> repository.getPlaylistCategories(source, refresh) },
        { s -> repository.discoverPlaylists(s.source, s.category, s.order, s.page, 30) }, restore, persist)
    private val state = MutableStateFlow(DiscoverUiState())
    val uiState = state.asStateFlow()
    private var started = false
    private var categoryJob: Job? = null
    private var pageJob: Job? = null
    private var categoryToken = 0
    private var pageToken = 0

    fun start() {
        if (started) return
        started = true
        val saved = runCatching(restore).getOrDefault(DiscoverySelection())
        val source = saved.source.takeIf { it in discoveryPlaylistSorts } ?: "wy"
        val sorts = discoveryPlaylistSorts.getValue(source)
        state.update { it.copy(selection = saved.copy(source = source, page = saved.page.coerceAtLeast(0),
            order = saved.order.takeIf { id -> sorts.any { it.id == id } } ?: sorts.first().id)) }
        loadCategories(); load()
    }

    fun switchSource(source: String) {
        if (source == state.value.selection.source || source !in discoveryPlaylistSorts) return
        state.value = DiscoverUiState(selection = DiscoverySelection(source, order = discoveryPlaylistSorts.getValue(source).first().id))
        loadCategories(); load()
    }

    fun switchCategory(category: String) {
        if (category == state.value.selection.category) return
        if (category.isNotEmpty() && state.value.categories.none { it.id == category }) return
        state.update { it.copy(selection = it.selection.copy(category = category, page = 0)) }; load()
    }

    fun switchOrder(order: String) {
        val selection = state.value.selection
        if (selection.order == order || (selection.category.isNotEmpty() && !canSortDiscoveryCategory(selection.source))) return
        if (discoveryPlaylistSorts.getValue(selection.source).none { it.id == order }) return
        state.update { it.copy(selection = selection.copy(order = order, page = 0)) }; load()
    }

    fun goToPage(page: Int) {
        if (page < 0 || page == state.value.selection.page || state.value.loading) return
        state.update { it.copy(selection = it.selection.copy(page = page)) }; load()
    }

    fun retryCategories() = loadCategories(true)
    private fun loadCategories(refresh: Boolean = false) {
        categoryJob?.cancel()
        val token = ++categoryToken
        val source = state.value.selection.source
        state.update { it.copy(categoriesLoading = true, categoryError = null) }
        categoryJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { categories(source, refresh) }
                if (token != categoryToken) return@launch
                state.update { it.copy(categories = result, categoriesLoading = false) }
                val selected = state.value.selection.category
                if (selected.isNotEmpty() && result.none { it.id == selected }) switchCategory("")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == categoryToken) state.update { it.copy(categories = emptyList(), categoriesLoading = false,
                    categoryError = failure.message ?: "分类加载失败") }
            }
        }
    }

    fun load() {
        pageJob?.cancel()
        val token = ++pageToken
        val selection = state.value.selection
        runCatching { persist(selection) }
        state.update { it.copy(loading = true, error = null, items = emptyList(), hasNext = false) }
        pageJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { playlists(selection) }
                if (token != pageToken) return@launch
                // LinkedHashMap keeps the first position and last metadata, matching desktop Map.
                val items = linkedMapOf<String, PlayListInfoResult>()
                result.result.forEach { items["${it.source}:${it.playListId}"] = it }
                state.update { it.copy(items = items.values.toList(), loading = false, total = result.total ?: 0,
                    pageSize = result.size.takeIf { it > 0 } ?: 30, hasNext = result.hasNext && result.result.isNotEmpty()) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == pageToken) state.update { it.copy(loading = false, error = failure.message ?: "歌单加载失败，请重试") } }
        }
    }
}
