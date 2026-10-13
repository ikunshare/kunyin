package com.ikunshare.music.ui.screens.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.ArtistMvItem
import com.ikunshare.music.platform.base.ArtistPreviewCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import java.io.IOException

enum class ArtistTab { SONGS, ALBUMS, MVS }

data class ArtistUiState(
    val artistInfo: ArtistInfoResult? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTab: ArtistTab = ArtistTab.SONGS,
    val showAlbumsTab: Boolean = false,
    val showMvsTab: Boolean = false,
    // 歌曲
    val songs: List<MusicItem> = emptyList(),
    val songsLoadingMore: Boolean = false,
    val songsHasMore: Boolean = true,
    val songsPage: Int = 0,
    val songsError: String? = null,
    // 专辑
    val albums: List<AlbumInfoResult> = emptyList(),
    val albumsLoaded: Boolean = false,
    val albumsLoading: Boolean = false,
    val albumsHasMore: Boolean = true,
    val albumsPage: Int = 0,
    val albumsError: String? = null,
    // MV
    val mvs: List<ArtistMvItem> = emptyList(),
    val mvsLoaded: Boolean = false,
    val mvsLoading: Boolean = false,
    val mvsHasMore: Boolean = true,
    val mvsPage: Int = 0,
    val mvsError: String? = null
)

class ArtistViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistUiState())
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    private var currentArtistKey: String = ""
    private var source: String = ""
    private var artistId: String = ""
    private var loadJob: Job? = null
    private var songsJob: Job? = null
    private var albumsJob: Job? = null
    private var mvsJob: Job? = null

    private companion object {
        const val SONG_PAGE_SIZE = 30
        const val ALBUM_PAGE_SIZE = 30
        const val MV_PAGE_SIZE = 40
    }

    /**
     * 加载歌手详情。
     * @param artistKey "source:artistId" 格式
     */
    fun loadArtist(artistKey: String, force: Boolean = false) {
        if (!force && artistKey == currentArtistKey && (_uiState.value.isLoading || _uiState.value.songs.isNotEmpty())) return
        loadJob?.cancel()
        songsJob?.cancel()
        albumsJob?.cancel()
        mvsJob?.cancel()
        source = ""
        artistId = ""
        currentArtistKey = artistKey

        val colonIndex = artistKey.indexOf(':')
        val src = if (colonIndex > 0) artistKey.substring(0, colonIndex) else ""
        val id = if (colonIndex > 0) artistKey.substring(colonIndex + 1) else artistKey
        if (src.isBlank() || id.isBlank()) {
            _uiState.value = ArtistUiState(
                    error = MusicApplication.instance?.getString(R.string.invalid_artist_id)
                        ?: "invalid artist"
                )
            return
        }
        source = src
        artistId = id

        val provider = repository.getProvider(src)
        val showAlbums = provider?.supportsArtistAlbums() ?: false
        val showMvs = provider?.supportsArtistMvs() ?: false

        val cached = ArtistPreviewCache.get(src, id)
        _uiState.value = ArtistUiState(
                    isLoading = true,
                    artistInfo = cached,
                    showAlbumsTab = showAlbums,
                    showMvsTab = showMvs
                )
        loadJob = viewModelScope.launch {
            try {
                try {
                    val info = repository.getArtistInfoForSource(src, id)
                    _uiState.update { it.copy(artistInfo = ArtistPreviewCache.merge(info, it.artistInfo)) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { _uiState.update { it.copy(error = failure.message ?: "歌手信息加载失败") } }
                val songResult = repository.getArtistSongsForSource(src, id, 0, SONG_PAGE_SIZE)
                check(!songResult.hasNext || songResult.result.isNotEmpty()) { "歌曲分页返回空内容，请重试" }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        songs = songResult.result.distinctBy { song -> song.uniqueKey },
                        songsHasMore = songResult.hasNext && songResult.result.isNotEmpty(),
                        songsPage = 0
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        songsHasMore = false,
                        error = MusicApplication.instance?.getString(R.string.network_error)
                            ?: e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        songsHasMore = false,
                        error = MusicApplication.instance?.getString(
                            R.string.load_artist_failed, e.message ?: ""
                        ) ?: e.message
                    )
                }
            }
        }
    }

    fun selectTab(tab: ArtistTab) {
        _uiState.update { it.copy(selectedTab = tab) }
        val state = _uiState.value
        when (tab) {
            ArtistTab.ALBUMS -> if (!state.albumsLoaded && !state.albumsLoading) loadMoreAlbums()
            ArtistTab.MVS -> if (!state.mvsLoaded && !state.mvsLoading) loadMoreMvs()
            ArtistTab.SONGS -> {}
        }
    }

    fun loadMoreSongs() {
        val state = _uiState.value
        if (state.isLoading || state.songsError != null || state.songsLoadingMore || !state.songsHasMore || source.isEmpty()) return
        val src = source
        val id = artistId
        val nextPage = state.songsPage + 1
        _uiState.update { it.copy(songsLoadingMore = true) }
        songsJob = viewModelScope.launch {
            try {
                val result = repository.getArtistSongsForSource(
                    src, id, nextPage, SONG_PAGE_SIZE
                )
                val keys = state.songs.map { it.uniqueKey }.toSet()
                check(!result.hasNext || result.result.any { it.uniqueKey !in keys }) { "歌曲分页没有返回新内容，请重试" }
                _uiState.update {
                    it.copy(
                        songsLoadingMore = false,
                        songs = (it.songs + result.result).distinctBy { song -> song.uniqueKey },
                        songsHasMore = result.hasNext,
                        songsPage = nextPage
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _uiState.update { it.copy(songsLoadingMore = false, songsError = e.message ?: "歌曲加载失败") }
            }
        }
    }

    fun loadMoreAlbums() {
        val state = _uiState.value
        if (state.albumsError != null) return
        if (state.albumsLoading || (state.albumsLoaded && !state.albumsHasMore)) return
        if (source.isEmpty()) return
        val src = source
        val id = artistId
        val nextPage = if (state.albumsLoaded) state.albumsPage + 1 else 0
        _uiState.update { it.copy(albumsLoading = true) }
        albumsJob = viewModelScope.launch {
            try {
                val result = repository.getArtistAlbumsForSource(
                    src, id, nextPage, ALBUM_PAGE_SIZE
                )
                val keys = state.albums.map { "${it.source}:${it.albumId}" }.toSet()
                check(!result.hasNext || result.result.any { "${it.source}:${it.albumId}" !in keys }) { "专辑分页没有返回新内容，请重试" }
                _uiState.update {
                    it.copy(
                        albumsLoading = false,
                        albumsLoaded = true,
                        albums = (if (nextPage == 0) result.result else it.albums + result.result).distinctBy { album -> "${album.source}:${album.albumId}" },
                        albumsHasMore = result.hasNext,
                        albumsPage = nextPage
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _uiState.update { it.copy(albumsLoading = false, albumsError = e.message ?: "专辑加载失败") }
            }
        }
    }

    fun loadMoreMvs() {
        val state = _uiState.value
        if (state.mvsError != null) return
        if (state.mvsLoading || (state.mvsLoaded && !state.mvsHasMore)) return
        if (source.isEmpty()) return
        val src = source
        val id = artistId
        val nextPage = if (state.mvsLoaded) state.mvsPage + 1 else 0
        _uiState.update { it.copy(mvsLoading = true) }
        mvsJob = viewModelScope.launch {
            try {
                val result = repository.getArtistMvsForSource(
                    src, id, nextPage, MV_PAGE_SIZE
                )
                val keys = state.mvs.map { "${it.source}:${it.vid}" }.toSet()
                check(!result.hasNext || result.result.any { "${it.source}:${it.vid}" !in keys }) { "MV 分页没有返回新内容，请重试" }
                _uiState.update {
                    it.copy(
                        mvsLoading = false,
                        mvsLoaded = true,
                        mvs = (if (nextPage == 0) result.result else it.mvs + result.result).distinctBy { mv -> "${mv.source}:${mv.vid}" },
                        mvsHasMore = result.hasNext,
                        mvsPage = nextPage
                    )
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                _uiState.update { it.copy(mvsLoading = false, mvsError = e.message ?: "MV 加载失败") }
            }
        }
    }

    /** 由 MV 列表条目构造可播放的占位 item，交给 MvDialog。 */
    fun buildMvItem(mv: ArtistMvItem): MusicItem? =
        repository.getProvider(mv.source)?.createMvItem(mv.vid, mv.title, mv.cover)

    fun retry() {
        if (currentArtistKey.isNotBlank()) loadArtist(currentArtistKey, force = true)
    }

    fun retrySongs() { _uiState.update { it.copy(songsError = null) }; loadMoreSongs() }
    fun retryAlbums() { _uiState.update { it.copy(albumsError = null) }; loadMoreAlbums() }
    fun retryMvs() { _uiState.update { it.copy(mvsError = null) }; loadMoreMvs() }

    fun playAll(): List<MusicItem>? {
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return null
        return songs.toList()
    }

    suspend fun getAlbumSongs(source: String, albumId: String): List<MusicItem> {
        return try {
            repository.getAlbumSongsForSource(source, albumId).result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun getAlbumResult(source: String, albumId: String) = repository.getAlbumSongsForSource(source, albumId)
}
