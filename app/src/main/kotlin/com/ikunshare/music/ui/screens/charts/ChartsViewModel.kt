package com.ikunshare.music.ui.screens.charts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.discovery.ChartInfo
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.QueueSource
import com.ikunshare.music.platform.base.MusicListResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ChartsUiState(
    val source: String = "wy",
    val boards: List<ChartInfo> = emptyList(),
    val board: ChartInfo? = null,
    val boardsLoading: Boolean = false,
    val boardsError: String? = null,
    val songs: List<MusicItem> = emptyList(),
    val page: Int = 0,
    val total: Int = 0,
    val hasNext: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    val playingAll: Boolean = false,
    val collecting: Set<String> = emptySet()
)

internal fun ChartInfo.queueSource() = QueueSource("platform", "$source:chart:$id", name)

class ChartsViewModel internal constructor(
    private val catalog: suspend (String, Boolean) -> List<ChartInfo>,
    private val songs: suspend (ChartInfo, Int) -> MusicListResult,
    private val restore: () -> Pair<String, String> = { "wy" to "" },
    private val persist: (String, String) -> Unit = { _, _ -> },
    private val collectSnapshot: (suspend (ChartInfo) -> Long)? = null
) : ViewModel() {
    constructor(repository: MusicRepository, restore: () -> Pair<String, String>, persist: (String, String) -> Unit,
                collectSnapshot: suspend (ChartInfo) -> Long) : this(
        { source, refresh -> repository.getCharts(source, refresh) },
        { board, page -> repository.getChartSongs(board.source, board.id, page, 100, board.period) }, restore, persist, collectSnapshot)

    private val state = MutableStateFlow(ChartsUiState())
    val uiState = state.asStateFlow()
    private var catalogJob: Job? = null
    private var pageJob: Job? = null
    private var playJob: Job? = null
    private var catalogToken = 0
    private var pageToken = 0
    private var playToken = 0
    private var started = false
    private var requestedPage = 0

    fun start() {
        if (started) return
        started = true
        val saved = runCatching(restore).getOrDefault("wy" to "")
        loadCatalog(saved.first.takeIf { it in listOf("wy", "qq", "kg", "kw") } ?: "wy", saved.second)
    }

    fun switchSource(source: String) {
        if (source == state.value.source || source !in listOf("wy", "qq", "kg", "kw")) return
        loadCatalog(source, "")
    }

    fun refreshCatalog() = loadCatalog(state.value.source, state.value.board?.id.orEmpty(), true)

    private fun loadCatalog(source: String, preferred: String, refresh: Boolean = false) {
        catalogJob?.cancel()
        pageJob?.cancel()
        pageToken++
        val token = ++catalogToken
        val playing = state.value.playingAll
        state.value = ChartsUiState(source = source, boardsLoading = true, playingAll = playing, collecting = state.value.collecting)
        catalogJob = viewModelScope.launch {
            try {
                val boards = withContext(Dispatchers.IO) { catalog(source, refresh) }
                if (token != catalogToken) return@launch
                state.update { it.copy(boards = boards, boardsLoading = false) }
                (boards.firstOrNull { it.id == preferred } ?: boards.firstOrNull())?.let(::selectBoard)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == catalogToken) state.update { it.copy(boardsLoading = false, boardsError = failure.message ?: "榜单目录加载失败") }
            }
        }
    }

    fun selectBoard(board: ChartInfo) {
        if (board.source != state.value.source || board !in state.value.boards) return
        pageJob?.cancel()
        pageToken++
        state.update { it.copy(board = board, songs = emptyList(), page = 0, total = board.total, hasNext = false, error = null) }
        runCatching { persist(board.source, board.id) }
        loadPage(0)
    }

    fun loadPage(page: Int) {
        val board = state.value.board ?: return
        val requested = page.coerceAtLeast(0)
        requestedPage = requested
        pageJob?.cancel()
        val token = ++pageToken
        state.update { it.copy(loading = true, error = null) }
        pageJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { songs(board, requested) }
                if (token != pageToken) return@launch
                state.update { it.copy(songs = result.result.distinctBy { song -> song.uniqueKey }, page = requested,
                    total = result.total ?: board.total, hasNext = result.hasNext && result.result.isNotEmpty(), loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (token == pageToken) state.update { it.copy(loading = false, error = failure.message ?: "榜单歌曲加载失败") }
            }
        }
    }

    fun retryPage() = loadPage(requestedPage)

    fun collectBoard(board: ChartInfo, onSuccess: (Long) -> Unit, onFailure: (String) -> Unit) {
        val collect = collectSnapshot ?: return
        val key = "${board.source}:${board.id}"
        if (key in state.value.collecting) return
        state.update { it.copy(collecting = it.collecting + key) }
        viewModelScope.launch {
            try {
                val id = collect(board)
                onSuccess(id)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onFailure(failure.message ?: "收藏榜单失败，请重试") }
            finally { state.update { it.copy(collecting = it.collecting - key) } }
        }
    }

    fun stopPreparingPlayback() {
        playToken++
        playJob?.cancel()
        state.update { it.copy(playingAll = false) }
    }

    /** Page selection stays independent of the playback snapshot, as in the desktop. */
    fun playAll(onFirst: (List<MusicItem>, QueueSource) -> Boolean,
                onAppend: (List<MusicItem>, QueueSource) -> Boolean,
                onFailure: (String) -> Unit,
                boardToPlay: ChartInfo? = null) {
        val board = boardToPlay ?: state.value.board ?: return
        stopPreparingPlayback()
        val token = ++playToken
        val source = board.queueSource()
        val initial = state.value.takeIf { it.board == board && it.page == 0 && it.songs.isNotEmpty() && !it.loading && it.error == null }
        state.update { it.copy(playingAll = true) }
        playJob = viewModelScope.launch {
            try {
                val first = initial?.let { MusicListResult(board.source, it.hasNext, 0, 100, it.songs) }
                    ?: withContext(Dispatchers.IO) { songs(board, 0) }
                if (token != playToken) return@launch
                if (first.result.isEmpty()) error("这个榜单暂时没有可播放的歌曲")
                if (!onFirst(first.result, source)) return@launch
                var more = first.hasNext
                for (page in 1 until 30) {
                    if (!more) break
                    val result = withContext(Dispatchers.IO) { songs(board, page) }
                    if (token != playToken || !onAppend(result.result, source)) return@launch
                    more = result.hasNext && result.result.isNotEmpty()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (token == playToken) onFailure(failure.message ?: "播放失败，请重试") }
            finally { if (token == playToken) state.update { it.copy(playingAll = false) } }
        }
    }
}
