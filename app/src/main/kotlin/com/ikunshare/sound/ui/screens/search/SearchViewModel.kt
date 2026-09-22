package com.ikunshare.sound.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.platform.base.SearchType
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
    val supportedSearchTypes: List<SearchType> = listOf(SearchType.SONG)
)

class SearchViewModel(
    private val repository: MusicRepository,
    private val appSettingsManager: AppSettingsManager
) : ViewModel() {

    companion object {
        private const val DEBOUNCE_DELAY_MS = 300L
    }

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var suggestionJob: Job? = null
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

    private fun loadHotSearches() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val hotSearches = repository.getHotSearch()
                _uiState.update {
                    it.copy(isLoading = false, hotSearches = hotSearches)
                }
            } catch (e: IOException) {
                lastFailedAction = FailedAction.LOAD_HOT_SEARCH
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.LOAD_HOT_SEARCH
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.load_hot_search_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        suggestionJob?.cancel()

        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    suggestions = emptyList(),
                    results = emptyList(),
                    playlistResults = emptyList(),
                    albumResults = emptyList(),
                    artistResults = emptyList(),
                    hasMore = true,
                    currentPage = 0
                )
            }
            if (_uiState.value.hotSearches.isEmpty()) loadHotSearches()
            return
        }

        suggestionJob = viewModelScope.launch {
            delay(DEBOUNCE_DELAY_MS.milliseconds)
            try {
                val suggestions = repository.getSearchTip(query)
                if (_uiState.value.query == query) {
                    _uiState.update { it.copy(suggestions = suggestions) }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun search() {
        val query = _uiState.value.query.trim()
        if (query.isEmpty()) return

        appSettingsManager.addSearchHistory(query)
        _uiState.update { it.copy(searchHistory = appSettingsManager.getSearchHistory()) }

        suggestionJob?.cancel()
        suggestionJob = null

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true, error = null,
                    results = emptyList(), playlistResults = emptyList(),
                    albumResults = emptyList(), artistResults = emptyList(),
                    suggestions = emptyList(), currentPage = 0, hasMore = true
                )
            }
            try {
                when (_uiState.value.searchType) {
                    SearchType.SONG -> {
                        val result = repository.search(query, 0)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                results = result.result,
                                hasMore = result.hasNext,
                                currentPage = 0
                            )
                        }
                    }

                    SearchType.PLAYLIST -> {
                        val result = repository.searchPlaylist(query, 0)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistResults = result.result,
                                hasMore = result.hasNext,
                                currentPage = 0
                            )
                        }
                    }

                    SearchType.ALBUM -> {
                        val result = repository.searchAlbum(query, 0)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                albumResults = result.result,
                                hasMore = result.hasNext,
                                currentPage = 0
                            )
                        }
                    }

                    SearchType.ARTIST -> {
                        val result = repository.searchArtist(query, 0)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                artistResults = result.result,
                                hasMore = result.hasNext,
                                currentPage = 0
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                lastFailedAction = FailedAction.SEARCH
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.SEARCH
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.search_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun loadMore() {
        val currentState = _uiState.value
        if (currentState.isLoading || !currentState.hasMore || currentState.query.isBlank()) return

        val nextPage = currentState.currentPage + 1

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                when (currentState.searchType) {
                    SearchType.SONG -> {
                        val result = repository.search(currentState.query.trim(), nextPage)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                results = it.results + result.result,
                                hasMore = result.hasNext,
                                currentPage = nextPage
                            )
                        }
                    }

                    SearchType.PLAYLIST -> {
                        val result = repository.searchPlaylist(currentState.query.trim(), nextPage)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                playlistResults = it.playlistResults + result.result,
                                hasMore = result.hasNext,
                                currentPage = nextPage
                            )
                        }
                    }

                    SearchType.ALBUM -> {
                        val result = repository.searchAlbum(currentState.query.trim(), nextPage)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                albumResults = it.albumResults + result.result,
                                hasMore = result.hasNext,
                                currentPage = nextPage
                            )
                        }
                    }

                    SearchType.ARTIST -> {
                        val result = repository.searchArtist(currentState.query.trim(), nextPage)
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                artistResults = it.artistResults + result.result,
                                hasMore = result.hasNext,
                                currentPage = nextPage
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                lastFailedAction = FailedAction.LOAD_MORE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.LOAD_MORE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.load_more_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    fun onHotSearchClick(keyword: String) {
        _uiState.update { it.copy(query = keyword) }
        search()
    }

    fun retry() {
        when (lastFailedAction) {
            FailedAction.LOAD_HOT_SEARCH -> loadHotSearches()
            FailedAction.SEARCH -> search()
            FailedAction.LOAD_MORE -> loadMore()
            null -> loadHotSearches()
        }
    }

    fun switchProvider(source: String) {
        if (source == _uiState.value.currentProvider) return

        try {
            repository.setProvider(source)
            repository.clearCache()
        } catch (_: IllegalArgumentException) {
            return
        }

        // 记住上次使用的搜索平台
        appSettingsManager.update { copy(lastSearchProvider = source) }

        val previousQuery = _uiState.value.query
        val supportedTypes = repository.getSupportedSearchTypes()
        val defaultType = repository.getDefaultSearchType()
        _uiState.update {
            SearchUiState(
                query = previousQuery,
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
        if (type == _uiState.value.searchType) return
        _uiState.update {
            it.copy(
                searchType = type,
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
