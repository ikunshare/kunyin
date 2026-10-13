package com.ikunshare.music.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.comments.CommentItem
import com.ikunshare.music.manager.comments.CommentResult
import com.ikunshare.music.model.MusicItem
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CommentTabState(
    val loading: Boolean = false, val error: Boolean = false,
    val page: Int = 1, val requestedPage: Int = 1, val total: Int = 0, val maxPage: Int = 1,
    val comments: List<CommentItem> = emptyList(), val revision: Long = 0
)
data class CommentsUiState(
    val supported: Boolean = false, val hotSelected: Boolean = true,
    val hot: CommentTabState = CommentTabState(), val latest: CommentTabState = CommentTabState()
) {
    val active get() = if (hotSelected) hot else latest
}

class CommentsViewModel internal constructor(
    private val supported: (String) -> Boolean,
    private val fetch: suspend (MusicItem, Boolean, Int) -> CommentResult
) : ViewModel() {
    constructor(repository: MusicRepository) : this(repository::supportsComments,
        { song, hot, page -> repository.getComments(song, hot, page, 20) })

    private val state = MutableStateFlow(CommentsUiState())
    val uiState = state.asStateFlow()
    private var track: MusicItem? = null
    private var generation = 0L
    private val jobs = mutableMapOf<Boolean, Job>()
    private val tokens = mutableMapOf<Boolean, Long>()

    fun setTrack(song: MusicItem?) {
        if (track?.uniqueKey == song?.uniqueKey) { track = song; return }
        track = song
        refresh()
    }

    fun refresh() {
        generation++
        jobs.values.forEach { it.cancel() }; jobs.clear()
        val available = track?.let { supported(it.getTypeDiscriminator()) } == true
        state.value = CommentsUiState(supported = available, hotSelected = state.value.hotSelected)
        if (available) { load(true, 1); load(false, 1) }
    }

    fun close() {
        generation++
        jobs.values.forEach { it.cancel() }; jobs.clear()
        track = null
        state.value = CommentsUiState()
    }

    fun selectTab(hot: Boolean) { state.update { it.copy(hotSelected = hot) } }
    fun goPage(page: Int) {
        val active = state.value.active
        if (page !in 1..active.maxPage || page == active.page || active.loading) return
        load(state.value.hotSelected, page)
    }
    fun retry() = load(state.value.hotSelected, state.value.active.requestedPage)

    private fun updateTab(hot: Boolean, transform: (CommentTabState) -> CommentTabState) {
        state.update { if (hot) it.copy(hot = transform(it.hot)) else it.copy(latest = transform(it.latest)) }
    }

    private fun load(hot: Boolean, page: Int) {
        val song = track ?: return
        if (!state.value.supported) return
        jobs[hot]?.cancel()
        val token = (tokens[hot] ?: 0) + 1
        tokens[hot] = token
        val epoch = generation
        updateTab(hot) { it.copy(loading = true, error = false, requestedPage = page) }
        jobs[hot] = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { fetch(song, hot, page) }
                if (generation != epoch || tokens[hot] != token) return@launch
                updateTab(hot) { it.copy(loading = false, comments = result.comments, page = page,
                    total = result.total, maxPage = result.maxPage.coerceAtLeast(1), revision = it.revision + 1) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == epoch && tokens[hot] == token)
                    updateTab(hot) { it.copy(loading = false, error = true) }
            }
        }
    }
}

internal fun commentPages(page: Int, maxPage: Int): List<Int> {
    if (maxPage <= 1) return emptyList()
    val start = maxOf(1, minOf(page - 2, maxPage - 4))
    return (start..minOf(maxPage.toLong(), start.toLong() + 4).toInt()).toList()
}
