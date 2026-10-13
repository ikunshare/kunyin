package com.ikunshare.music.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.database.PlatformPlaylistStore
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.loadCompletePlaylist
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.PlayListInfoResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 歌单页面 UI 状态
 *
 * @param playlistInfo 歌单元数据信息（封面、标题、作者、描述、歌曲总数）
 * @param songs 当前已加载的歌曲列表
 * @param isLoading 是否正在加载
 * @param error 错误信息，null 表示无错误
 * @param hasMore 是否还有更多歌曲可加载
 * @param currentPage 当前页码（从 0 开始）
 */
data class PlaylistUiState(
    val playlistInfo: PlayListInfoResult? = null,
    val songs: List<MusicItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val hasSkippedLocalSongs: Boolean = false,
    val isBookmarked: Boolean = false,
    val isSavingToLocal: Boolean = false,
    val isOnlinePlaylist: Boolean = false
)

/**
 * 歌单页面 ViewModel
 *
 * 管理歌单页面的 UI 状态，包括歌单信息加载、歌曲列表分页加载和播放全部功能。
 * 使用 MusicRepository 与数据层交互。
 *
 * **Validates: Requirements 3.1-3.10**
 * - 3.1: 歌单页面加载时显示歌单封面和标题
 * - 3.2: 显示歌单作者和描述
 * - 3.3: 显示歌曲总数
 * - 3.4: 提供"播放全部"按钮
 * - 3.5: 点击"播放全部"从第一首歌开始播放并导航到播放页面
 * - 3.6: 以可滚动列表显示歌单中的所有歌曲
 * - 3.7: 歌曲列表项显示歌曲标题、艺术家和时长
 * - 3.8: 点击歌曲导航到播放页面
 * - 3.9: 歌曲列表支持无限滚动分页
 * - 3.10: 加载失败时显示错误信息和重试选项
 */
class PlaylistViewModel(
    private val repository: MusicRepository,
    private val platformPlaylistStore: PlatformPlaylistStore? = null,
    private val localMusicStore: LocalMusicStore? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistUiState())
    val uiState: StateFlow<PlaylistUiState> = _uiState.asStateFlow()

    /** 当前歌单 ID，用于分页加载和重试 */
    private var currentPlaylistId: String = ""
    private var saveToLocalJob: kotlinx.coroutines.Job? = null
    private var loadJob: kotlinx.coroutines.Job? = null
    private var playbackQueueJob: kotlinx.coroutines.Job? = null

    /** Follow the existing loader, rather than issuing a second competing pagination request. */
    fun continuePlaybackQueue(onSongs: (List<MusicItem>) -> Boolean) {
        playbackQueueJob?.cancel()
        val playlistId = currentPlaylistId
        playbackQueueJob = viewModelScope.launch {
            uiState.first { state ->
                currentPlaylistId != playlistId || !onSongs(state.songs) ||
                    state.error != null || (!state.hasMore && !state.isLoading)
            }
        }
    }

    /** 记录最后一次失败的操作类型，用于 retry() */
    private var lastFailedAction: FailedAction? = null

    /**
     * 失败操作类型枚举，用于 retry() 重试逻辑
     */
    private enum class FailedAction {
        LOAD_PLAYLIST,
        LOAD_MORE
    }

    /**
     * 加载歌单
     *
     * 同时加载歌单元数据（封面、标题、作者、描述、歌曲总数）和第一页歌曲列表。
     * 加载前会重置所有状态，确保切换歌单时数据干净。
     * 支持 "source:id" 格式（如 "qq:7105698331"）来加载平台歌单。
     *
     * @param playlistId 歌单 ID 或 URL，或 "source:id" 格式
     *
     * **Validates: Requirements 3.1, 3.2, 3.3, 3.6, 3.10**
     */
    fun loadPlaylist(playlistId: String, forceRefresh: Boolean = false) {
        if (playlistId.isBlank()) return
        loadJob?.cancel()
        playbackQueueJob?.cancel()
        saveToLocalJob?.cancel()
        currentPlaylistId = playlistId

        // 检测 source:id 格式
        val colonIndex = playlistId.indexOf(':')
        val platformSource = if (colonIndex > 0) playlistId.substring(0, colonIndex) else null
        val actualId = if (colonIndex > 0) playlistId.substring(colonIndex + 1) else playlistId
        val isOnline = platformSource != null

        // 检查是否已收藏
        val bookmarked = if (isOnline && localMusicStore != null) {
            localMusicStore.getPlaylistByRemoteId(platformSource, actualId) != null
        } else false
        _uiState.value = PlaylistUiState(isLoading = true, isBookmarked = bookmarked, isOnlinePlaylist = isOnline)

        loadJob = viewModelScope.launch {
            // 平台歌单：非强制刷新时优先使用缓存；大型 JSON 在 IO 线程解析。
            if (!forceRefresh && platformSource != null && platformPlaylistStore != null) {
                val (cachedSongs, cachedInfo, skipped) = withContext(Dispatchers.IO) {
                    Triple(platformPlaylistStore.getCompletedSongs(platformSource, actualId),
                        platformPlaylistStore.getPlaylistInfo(platformSource, actualId),
                        platformPlaylistStore.hasSkippedLocalSongs(platformSource, actualId))
                }
                if (!cachedSongs.isNullOrEmpty()) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            playlistInfo = cachedInfo,
                            songs = cachedSongs,
                            hasMore = false,
                            currentPage = 0,
                            hasSkippedLocalSongs = skipped,
                            error = null
                        )
                    }
                    // 后台静默更新歌单信息
                    try {
                        val info = repository.getPlaylistInfoForSource(platformSource, actualId)
                        if (info != null) {
                            withContext(Dispatchers.IO) {
                                platformPlaylistStore.savePlaylistInfo(platformSource, actualId, info)
                            }
                        }
                        if (info != null) _uiState.update { it.copy(playlistInfo = info) }
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                    }
                    return@launch
                }
            }

            // 重置状态
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    playlistInfo = null,
                    songs = emptyList(),
                    currentPage = 0,
                    hasMore = true
                )
            }

            try {
                val playlistInfo: PlayListInfoResult?
                val songsResult: com.ikunshare.music.platform.base.MusicListResult

                if (platformSource != null) {
                    // 平台歌单：使用 ForSource 方法
                    playlistInfo = repository.getPlaylistInfoForSource(platformSource, actualId)
                    songsResult = repository.getPlaylistSongsForSource(platformSource, actualId, 0, 100)
                } else {
                    // 本地/当前 Provider 歌单
                    playlistInfo = repository.getPlaylistInfo(playlistId)
                    songsResult = repository.getPlaylistSongs(playlistId, 0)
                }

                _uiState.update {
                    it.copy(
                        isLoading = platformSource != null,
                        playlistInfo = playlistInfo,
                        songs = songsResult.result,
                        hasMore = songsResult.hasNext,
                        currentPage = 0,
                        hasSkippedLocalSongs = songsResult.skippedLocalSongs > 0
                    )
                }

                // 平台歌单：静默加载剩余所有页
                if (platformSource != null) {
                    loadCompletePlaylist(playlistInfo?.totalSongs ?: 0,
                        onPage = { page, result, allSongs, skipped ->
                            _uiState.update {
                                it.copy(
                                    songs = allSongs,
                                    isLoading = result.hasNext,
                                    hasMore = result.hasNext,
                                    currentPage = page,
                                    hasSkippedLocalSongs = skipped > 0
                                )
                            }
                    }) { page, size ->
                        if (page == 0) songsResult else
                            repository.getPlaylistSongsForSource(platformSource, actualId, page, size)
                    }
                }

                // 全部加载完毕时缓存平台歌单歌曲和信息
                if (platformSource != null && platformPlaylistStore != null) {
                    val finalSongs = _uiState.value.songs
                    val info = _uiState.value.playlistInfo
                    val skipped = _uiState.value.hasSkippedLocalSongs
                    withContext(Dispatchers.IO) {
                        if (finalSongs.isNotEmpty()) {
                            platformPlaylistStore.saveSongs(platformSource, actualId, finalSongs, skipped)
                        }
                        if (info != null) platformPlaylistStore.savePlaylistInfo(platformSource, actualId, info)
                    }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                lastFailedAction = FailedAction.LOAD_PLAYLIST
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.LOAD_PLAYLIST
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance!!.getString(
                            R.string.load_playlist_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    /**
     * 加载更多歌曲（分页）
     *
     * 在当前歌曲列表基础上加载下一页数据。
     * 新数据追加到现有列表末尾，页码递增。
     * 如果正在加载或没有更多数据，则不执行操作。
     *
     * **Validates: Requirements 3.9**
     */
    fun loadMore() {
        val currentState = _uiState.value
        // 如果正在加载、没有更多数据、或歌单 ID 为空，则不执行
        if (loadJob?.isActive == true || currentState.error != null || currentState.isLoading || !currentState.hasMore || currentPlaylistId.isBlank()) return

        val nextPage = currentState.currentPage + 1

        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                // 检测 source:id 格式
                val colonIndex = currentPlaylistId.indexOf(':')
                val platformSource =
                    if (colonIndex > 0) currentPlaylistId.substring(0, colonIndex) else null
                val actualId =
                    if (colonIndex > 0) currentPlaylistId.substring(colonIndex + 1) else currentPlaylistId

                val result = if (platformSource != null) {
                    repository.getPlaylistSongsForSource(platformSource, actualId, nextPage)
                } else {
                    repository.getPlaylistSongs(currentPlaylistId, nextPage)
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        songs = (it.songs + result.result).distinctBy { s -> s.uniqueKey },
                        hasMore = result.hasNext,
                        currentPage = nextPage
                    )
                }

                // 全部加载完毕时才缓存
                if (platformSource != null && platformPlaylistStore != null && !result.hasNext) {
                    platformPlaylistStore.saveSongs(platformSource, actualId, _uiState.value.songs)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                lastFailedAction = FailedAction.LOAD_MORE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.LOAD_MORE
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance!!.getString(
                            R.string.load_more_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    /**
     * 收藏在线歌单到本地
     */
    fun saveToLocal() {
        if (saveToLocalJob?.isActive == true) return
        val store = localMusicStore ?: return
        val info = _uiState.value.playlistInfo ?: return
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return

        val colonIndex = currentPlaylistId.indexOf(':')
        val platformSource =
            if (colonIndex > 0) currentPlaylistId.substring(0, colonIndex) else return
        val actualId = if (colonIndex > 0) currentPlaylistId.substring(colonIndex + 1) else return

        // 检查是否已存在
        if (store.getPlaylistByRemoteId(platformSource, actualId) != null) return

        _uiState.update { it.copy(isSavingToLocal = true) }
        saveToLocalJob = viewModelScope.launch {
            try {
                val completeSongs = repository.getCompletePlaylistSongsForSource(
                    platformSource, actualId, info.totalSongs
                )
                store.createPlaylistWithSongs(
                    info.title ?: "未知歌单", completeSongs,
                    remoteSource = platformSource, remoteId = actualId,
                    autoRefresh = false
                )
                if (currentPlaylistId == "$platformSource:$actualId") {
                    _uiState.update { it.copy(isBookmarked = true) }
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                android.util.Log.e("PlaylistViewModel", "Save playlist failed", failure)
                MusicApplication.instance?.let { context ->
                    android.widget.Toast.makeText(context,
                        context.getString(R.string.playlist_write_failed, failure.message.orEmpty()),
                        android.widget.Toast.LENGTH_LONG).show()
                }
            } finally {
                if (currentPlaylistId == "$platformSource:$actualId") {
                    _uiState.update { it.copy(isSavingToLocal = false) }
                }
            }
        }
    }

    /**
     * 从当前已加载列表中按来源与 id 移除歌曲（用于删除后立即更新 UI）
     */
    fun removeSongsLocally(keys: Set<String>) {
        if (keys.isEmpty()) return
        _uiState.update { state ->
            state.copy(songs = state.songs.filter { it.uniqueKey !in keys })
        }
    }

    /**
     * 播放全部歌曲
     *
     * 准备当前歌单中所有已加载的歌曲列表用于播放。
     * 返回歌曲列表供 UI 层传递给 PlayerViewModel。
     * 如果歌曲列表为空，则不执行操作。
     *
     * 注意：实际的播放操作和页面导航由 UI 层（PlaylistScreen）处理，
     * 该方法仅负责准备播放数据。
     *
     * @return 当前已加载的歌曲列表，如果为空返回 null
     *
     * **Validates: Requirements 3.4, 3.5**
     */
    fun playAll(): List<MusicItem>? {
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return null
        return songs.toList()
    }

    /**
     * 重试上一次失败的操作
     *
     * 根据 lastFailedAction 记录的失败操作类型，重新执行对应的操作。
     * 如果没有记录失败操作，则尝试重新加载歌单。
     *
     * **Validates: Requirements 3.10**
     */
    fun retry() {
        if (currentPlaylistId.contains(':')) {
            loadPlaylist(currentPlaylistId, forceRefresh = true)
            return
        }
        _uiState.update { it.copy(error = null) }
        when (lastFailedAction) {
            FailedAction.LOAD_PLAYLIST -> loadPlaylist(currentPlaylistId)
            FailedAction.LOAD_MORE -> loadMore()
            null -> {
                if (currentPlaylistId.isNotBlank()) {
                    loadPlaylist(currentPlaylistId)
                }
            }
        }
    }

    /**
     * 用于测试：直接设置 UI 状态
     *
     * @param state 要设置的 UI 状态
     */
    internal fun setState(state: PlaylistUiState) {
        _uiState.value = state
    }

    /**
     * 用于测试：设置当前歌单 ID
     *
     * @param playlistId 歌单 ID
     */
    internal fun setPlaylistId(playlistId: String) {
        currentPlaylistId = playlistId
    }
}
