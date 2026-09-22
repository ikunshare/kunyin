package com.ikunshare.sound.ui.screens.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.PlatformPlaylistStore
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.PlayListInfoResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
        _uiState.update { it.copy(isBookmarked = bookmarked, isOnlinePlaylist = isOnline) }

        // 平台歌单：非强制刷新时优先使用缓存
        if (!forceRefresh && platformSource != null && platformPlaylistStore != null) {
            val cachedSongs = platformPlaylistStore.getSongs(platformSource, actualId)
            if (!cachedSongs.isNullOrEmpty()) {
                val cachedInfo = platformPlaylistStore.getPlaylistInfo(platformSource, actualId)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        playlistInfo = cachedInfo,
                        songs = cachedSongs,
                        hasMore = false,
                        currentPage = 0,
                        error = null
                    )
                }
                // 后台静默更新歌单信息
                viewModelScope.launch {
                    try {
                        val info = repository.getPlaylistInfoForSource(platformSource, actualId)
                        if (info != null) {
                            platformPlaylistStore.savePlaylistInfo(platformSource, actualId, info)
                        }
                        _uiState.update { it.copy(playlistInfo = info) }
                    } catch (_: Exception) {
                    }
                }
                return
            }
        }

        viewModelScope.launch {
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
                val songsResult: com.ikunshare.sound.platform.base.MusicListResult

                if (platformSource != null) {
                    // 平台歌单：使用 ForSource 方法
                    playlistInfo = repository.getPlaylistInfoForSource(platformSource, actualId)
                    songsResult = repository.getPlaylistSongsForSource(platformSource, actualId, 0)
                } else {
                    // 本地/当前 Provider 歌单
                    playlistInfo = repository.getPlaylistInfo(playlistId)
                    songsResult = repository.getPlaylistSongs(playlistId, 0)
                }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        playlistInfo = playlistInfo,
                        songs = songsResult.result,
                        hasMore = songsResult.hasNext,
                        currentPage = 0,
                        hasSkippedLocalSongs = songsResult.skippedLocalSongs > 0
                    )
                }

                // 平台歌单：静默加载剩余所有页
                if (platformSource != null && songsResult.hasNext) {
                    val allSongs = songsResult.result.toMutableList()
                    var page = 1
                    var hasMore = true
                    var totalSkipped = songsResult.skippedLocalSongs
                    while (hasMore) {
                        val more =
                            repository.getPlaylistSongsForSource(platformSource, actualId, page)
                        allSongs.addAll(more.result)
                        hasMore = more.hasNext
                        totalSkipped += more.skippedLocalSongs
                        page++
                        _uiState.update {
                            it.copy(
                                songs = allSongs.distinctBy { s -> s.id },
                                hasMore = hasMore,
                                currentPage = page - 1,
                                hasSkippedLocalSongs = totalSkipped > 0
                            )
                        }
                    }
                }

                // 全部加载完毕时缓存平台歌单歌曲和信息
                if (platformSource != null && platformPlaylistStore != null) {
                    val finalSongs = _uiState.value.songs
                    if (finalSongs.isNotEmpty()) {
                        platformPlaylistStore.saveSongs(platformSource, actualId, finalSongs)
                    }
                    val info = _uiState.value.playlistInfo
                    if (info != null) {
                        platformPlaylistStore.savePlaylistInfo(platformSource, actualId, info)
                    }
                }
            } catch (e: IOException) {
                lastFailedAction = FailedAction.LOAD_PLAYLIST
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                lastFailedAction = FailedAction.LOAD_PLAYLIST
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
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
        if (currentState.isLoading || !currentState.hasMore || currentPlaylistId.isBlank()) return

        val nextPage = currentState.currentPage + 1

        viewModelScope.launch {
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
                        songs = (it.songs + result.result).distinctBy { s -> s.id },
                        hasMore = result.hasNext,
                        currentPage = nextPage
                    )
                }

                // 全部加载完毕时才缓存
                if (platformSource != null && platformPlaylistStore != null && !result.hasNext) {
                    platformPlaylistStore.saveSongs(platformSource, actualId, _uiState.value.songs)
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

    /**
     * 收藏在线歌单到本地
     */
    fun saveToLocal() {
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

        val playlistId = store.createPlaylistAndAdd(
            info.title ?: "未知歌单",
            songs.first(),
            remoteSource = platformSource,
            remoteId = actualId,
            autoRefresh = false
        )
        songs.drop(1).forEach { store.addToPlaylist(playlistId, it) }
        _uiState.update { it.copy(isBookmarked = true) }
    }

    /**
     * 从当前已加载列表中按 id 移除歌曲（用于删除后立即更新 UI）
     */
    fun removeSongsLocally(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _uiState.update { state ->
            state.copy(songs = state.songs.filter { it.id !in ids })
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
