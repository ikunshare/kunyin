package com.ikunshare.music.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.SearchType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 搜索页面 UI 状态
 */
data class SearchUiState(
    val query: String = "",
    val submittedQuery: String? = null,
    val suggestions: List<String> = emptyList(),
    val hotSearches: List<String> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val results: List<MusicItem> = emptyList(),
    val playlistResults: List<PlayListInfoResult> = emptyList(),
    val albumResults: List<AlbumInfoResult> = emptyList(),
    val artistResults: List<ArtistInfoResult> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val currentProvider: String = "",
    val availableProviders: List<String> = emptyList(),
    val searchType: SearchType = SearchType.SONG,
    val searchRevision: Long = 0,
    val supportedSearchTypes: List<SearchType> = listOf(SearchType.SONG)
)

class SearchViewModel(
    private val repository: MusicRepository,
    private val appSettingsManager: AppSettingsManager,
    private val failureMessage: (Int, Exception) -> String = { id, failure ->
        MusicApplication.instance?.getString(id, failure.message.orEmpty()) ?: failure.message.orEmpty()
    }
) : ViewModel() {

    companion object {
        private const val DEBOUNCE_DELAY_MS = 300L
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestionJob: Job? = null
    private var requestJob: Job? = null
    private var generation = 0L
    private var lastFailedAction: FailedAction? = null

    private enum class FailedAction {
        LOAD_HOT_SEARCH,
        SEARCH,
        LOAD_MORE
    }

    init {
        val supportedTypes = repository.getSupportedSearchTypes()
        _uiState.update {
            it.copy(
                currentProvider = repository.currentKey,
                availableProviders = repository.getAvailableProviders(),
                searchHistory = appSettingsManager.getSearchHistory(),
                searchType = repository.getDefaultSearchType(),
                supportedSearchTypes = supportedTypes
            )
        }
        loadHotSearches()
    }

    private fun cancelRequests() {
        generation++
        requestJob?.cancel()
        requestJob = null
        suggestionJob?.cancel()
        suggestionJob = null
        lastFailedAction = null
    }

    private fun loadHotSearches() {
        cancelRequests()
        val token = generation
        val source = _uiState.value.currentProvider
        _uiState.update { it.copy(isLoading = true, error = null) }
        requestJob = viewModelScope.launch {
            try {
                val hot = repository.getHotSearch(source)
                coroutineContext.ensureActive()
                if (token == generation) _uiState.update { it.copy(isLoading = false, hotSearches = hot) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { coroutineContext.ensureActive(); failRequest(token, FailedAction.LOAD_HOT_SEARCH, failure) }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query, suggestions = emptyList()) }
        suggestionJob?.cancel()
        if (query.isBlank()) {
            cancelRequests()
            _uiState.update { it.copy(submittedQuery = null, isLoading = false, error = null,
                results = emptyList(), playlistResults = emptyList(), albumResults = emptyList(),
                artistResults = emptyList(), hasMore = true, currentPage = 0, searchRevision = generation) }
            if (_uiState.value.hotSearches.isEmpty()) loadHotSearches()
            return
        }
        val source = _uiState.value.currentProvider
        val token = generation
        suggestionJob = viewModelScope.launch {
            delay(DEBOUNCE_DELAY_MS.milliseconds)
            try {
                val tips = repository.getSearchTip(query, source)
                coroutineContext.ensureActive()
                if (token == generation && _uiState.value.query == query && _uiState.value.currentProvider == source) {
                    _uiState.update { it.copy(suggestions = tips) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return
        cancelRequests()
        appSettingsManager.addSearchHistory(query)
        _uiState.update { it.copy(isLoading = true, error = null, submittedQuery = query,
            results = emptyList(), playlistResults = emptyList(), albumResults = emptyList(),
            artistResults = emptyList(), suggestions = emptyList(), currentPage = 0, hasMore = true,
            searchHistory = appSettingsManager.getSearchHistory(), searchRevision = generation) }
        requestPage(_uiState.value, 0)
    }

    fun loadMore() = loadMore(retry = false)

    private fun loadMore(retry: Boolean) {
        val state = _uiState.value
        if (state.isLoading || !state.hasMore || state.submittedQuery.isNullOrBlank() || (!retry && state.error != null)) return
        _uiState.update { it.copy(isLoading = true, error = null) }
        lastFailedAction = null
        requestPage(state, state.currentPage + 1)
    }

    private fun requestPage(snapshot: SearchUiState, page: Int) {
        val token = generation
        requestJob = viewModelScope.launch {
            try {
                val updated = fetchPage(snapshot, page)
                coroutineContext.ensureActive()
                if (token == generation) {
                    lastFailedAction = null
                    // Keep the user's unsubmitted input and current history while publishing the page.
                    _uiState.update { current -> updated.copy(query = current.query,
                        suggestions = current.suggestions, searchHistory = current.searchHistory) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { coroutineContext.ensureActive(); failRequest(token, if (page == 0) FailedAction.SEARCH else FailedAction.LOAD_MORE, failure) }
        }
    }

    private suspend fun fetchPage(state: SearchUiState, page: Int): SearchUiState {
        val keyword = state.submittedQuery!!
        val source = state.currentProvider
        fun <T> merged(previous: List<T>, incoming: List<T>, more: Boolean): List<T> {
            if (more && incoming.isEmpty()) {
                error(MusicApplication.instance?.getString(R.string.search_page_no_progress) ?: "Search page returned no new results")
            }
            return (if (page == 0) emptyList() else previous) + incoming
        }
        return when (state.searchType) {
            SearchType.SONG -> {
                val response = repository.search(keyword, page, source = source)
                withContext(Dispatchers.Default) { state.copy(results = merged(state.results, response.result, response.hasNext),
                    isLoading = false, error = null, hasMore = response.hasNext, currentPage = page) }
            }
            SearchType.PLAYLIST -> {
                val response = repository.searchPlaylist(keyword, page, source = source)
                withContext(Dispatchers.Default) { state.copy(playlistResults = merged(state.playlistResults, response.result, response.hasNext),
                    isLoading = false, error = null, hasMore = response.hasNext, currentPage = page) }
            }
            SearchType.ALBUM -> {
                val response = repository.searchAlbum(keyword, page, source = source)
                withContext(Dispatchers.Default) { state.copy(albumResults = merged(state.albumResults, response.result, response.hasNext),
                    isLoading = false, error = null, hasMore = response.hasNext, currentPage = page) }
            }
            SearchType.ARTIST -> {
                val response = repository.searchArtist(keyword, page, source = source)
                withContext(Dispatchers.Default) { state.copy(artistResults = merged(state.artistResults, response.result, response.hasNext),
                    isLoading = false, error = null, hasMore = response.hasNext, currentPage = page) }
            }
        }
    }

    private fun failRequest(token: Long, action: FailedAction, failure: Exception) {
        if (token != generation) return
        lastFailedAction = action
        val id = if (failure is IOException) R.string.network_error else when (action) {
            FailedAction.LOAD_HOT_SEARCH -> R.string.load_hot_search_failed
            FailedAction.SEARCH -> R.string.search_failed
            FailedAction.LOAD_MORE -> R.string.load_more_failed
        }
        _uiState.update { it.copy(isLoading = false, error = failureMessage(id, failure)) }
    }

    fun onHotSearchClick(keyword: String) {
        _uiState.update { it.copy(query = keyword) }
        search()
    }

    fun retry() {
        when (lastFailedAction) {
            FailedAction.LOAD_HOT_SEARCH -> loadHotSearches()
            FailedAction.SEARCH -> search()
            FailedAction.LOAD_MORE -> loadMore(retry = true)
            null -> loadHotSearches()
        }
    }

    fun switchProvider(source: String) {
        if (source == _uiState.value.currentProvider) return

        try {
            repository.setProvider(source)
        } catch (_: IllegalArgumentException) {
            return
        }

        cancelRequests()

        // 记住上次使用的搜索平台
        appSettingsManager.update { copy(lastSearchProvider = source) }

        val previousQuery = _uiState.value.query
        val supportedTypes = repository.getSupportedSearchTypes()
        val defaultType = repository.getDefaultSearchType()
        _uiState.update {
            SearchUiState(
                query = previousQuery,
                searchRevision = generation,
                currentProvider = source,
                availableProviders = it.availableProviders,
                searchHistory = it.searchHistory,
                searchType = defaultType,
                supportedSearchTypes = supportedTypes
            )
        }
        lastFailedAction = null
        if (previousQuery.isNotBlank()) {
            search()
        } else {
            loadHotSearches()
        }
    }

    /**
     * 切换搜索类型
     */
    fun switchSearchType(type: SearchType) {
        if (type == _uiState.value.searchType || type !in _uiState.value.supportedSearchTypes) return
        cancelRequests()
        _uiState.update {
            it.copy(
                searchType = type,
                submittedQuery = null, isLoading = false, searchRevision = generation,
                results = emptyList(),
                playlistResults = emptyList(),
                albumResults = emptyList(),
                artistResults = emptyList(),
                hasMore = true,
                currentPage = 0,
                error = null
            )
        }
        if (_uiState.value.query.isNotBlank()) {
            search()
        }
    }

    fun onHistoryClick(keyword: String) {
        _uiState.update { it.copy(query = keyword) }
        search()
    }

    fun removeHistory(keyword: String) {
        appSettingsManager.removeSearchHistory(keyword)
        _uiState.update { it.copy(searchHistory = appSettingsManager.getSearchHistory()) }
    }

    fun clearHistory() {
        appSettingsManager.clearSearchHistory()
        _uiState.update { it.copy(searchHistory = emptyList()) }
    }
}
