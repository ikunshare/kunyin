package com.ikunshare.sound.ui.screens.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.ArtistMvItem
import com.ikunshare.sound.platform.base.ArtistPreviewCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    // 专辑
    val albums: List<AlbumInfoResult> = emptyList(),
    val albumsLoaded: Boolean = false,
    val albumsLoading: Boolean = false,
    val albumsHasMore: Boolean = true,
    val albumsPage: Int = 0,
    // MV
    val mvs: List<ArtistMvItem> = emptyList(),
    val mvsLoaded: Boolean = false,
    val mvsLoading: Boolean = false,
    val mvsHasMore: Boolean = true,
    val mvsPage: Int = 0
)

class ArtistViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistUiState())
    val uiState: StateFlow<ArtistUiState> = _uiState.asStateFlow()

    private var currentArtistKey: String = ""
    private var source: String = ""
    private var artistId: String = ""

    private companion object {
        const val SONG_PAGE_SIZE = 30
        const val ALBUM_PAGE_SIZE = 30
        const val MV_PAGE_SIZE = 40
    }

    /**
     * 加载歌手详情。
     * @param artistKey "source:artistId" 格式
     */
    fun loadArtist(artistKey: String) {
        if (artistKey.isBlank()) return
        if (artistKey == currentArtistKey && _uiState.value.songs.isNotEmpty()) return
        currentArtistKey = artistKey

        val colonIndex = artistKey.indexOf(':')
        val src = if (colonIndex > 0) artistKey.substring(0, colonIndex) else ""
        val id = if (colonIndex > 0) artistKey.substring(colonIndex + 1) else artistKey
        if (src.isBlank() || id.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = SoundApplication.instance?.getString(R.string.invalid_artist_id)
                        ?: "invalid artist"
                )
            }
            return
        }
        source = src
        artistId = id

        val provider = repository.getProvider(src)
        val showAlbums = provider?.supportsArtistAlbums() ?: false
        val showMvs = provider?.supportsArtistMvs() ?: false

        viewModelScope.launch {
            val cached = ArtistPreviewCache.get(src, id)
            _uiState.update {
                ArtistUiState(
                    isLoading = true,
                    artistInfo = cached,
                    showAlbumsTab = showAlbums,
                    showMvsTab = showMvs
                )
            }
            try {
                val info = repository.getArtistInfoForSource(src, id)
                val songResult = repository.getArtistSongsForSource(src, id, 0, SONG_PAGE_SIZE)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        artistInfo = ArtistPreviewCache.merge(info, cached),
                        songs = songResult.result,
                        songsHasMore = songResult.hasNext,
                        songsPage = 0
                    )
                }
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance?.getString(R.string.network_error)
                            ?: e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance?.getString(
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
        if (state.songsLoadingMore || !state.songsHasMore || source.isEmpty()) return
        val nextPage = state.songsPage + 1
        _uiState.update { it.copy(songsLoadingMore = true) }
        viewModelScope.launch {
            try {
                val result = repository.getArtistSongsForSource(
                    source, artistId, nextPage, SONG_PAGE_SIZE
                )
                _uiState.update {
                    it.copy(
                        songsLoadingMore = false,
                        songs = it.songs + result.result,
                        songsHasMore = result.hasNext,
                        songsPage = nextPage
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(songsLoadingMore = false) }
            }
        }
    }

    fun loadMoreAlbums() {
        val state = _uiState.value
        if (state.albumsLoading || (state.albumsLoaded && !state.albumsHasMore)) return
        if (source.isEmpty()) return
        val nextPage = if (state.albumsLoaded) state.albumsPage + 1 else 0
        _uiState.update { it.copy(albumsLoading = true) }
        viewModelScope.launch {
            try {
                val result = repository.getArtistAlbumsForSource(
                    source, artistId, nextPage, ALBUM_PAGE_SIZE
                )
                _uiState.update {
                    it.copy(
                        albumsLoading = false,
                        albumsLoaded = true,
                        albums = if (nextPage == 0) result.result else it.albums + result.result,
                        albumsHasMore = result.hasNext,
                        albumsPage = nextPage
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(albumsLoading = false, albumsLoaded = true) }
            }
        }
    }

    fun loadMoreMvs() {
        val state = _uiState.value
        if (state.mvsLoading || (state.mvsLoaded && !state.mvsHasMore)) return
        if (source.isEmpty()) return
        val nextPage = if (state.mvsLoaded) state.mvsPage + 1 else 0
        _uiState.update { it.copy(mvsLoading = true) }
        viewModelScope.launch {
            try {
                val result = repository.getArtistMvsForSource(
                    source, artistId, nextPage, MV_PAGE_SIZE
                )
                _uiState.update {
                    it.copy(
                        mvsLoading = false,
                        mvsLoaded = true,
                        mvs = if (nextPage == 0) result.result else it.mvs + result.result,
                        mvsHasMore = result.hasNext,
                        mvsPage = nextPage
                    )
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(mvsLoading = false, mvsLoaded = true) }
            }
        }
    }

    /** 由 MV 列表条目构造可播放的占位 item，交给 MvDialog。 */
    fun buildMvItem(mv: ArtistMvItem): MusicItem? =
        repository.getProvider(mv.source)?.createMvItem(mv.vid, mv.title, mv.cover)

    fun retry() {
        if (currentArtistKey.isNotBlank()) loadArtist(currentArtistKey)
    }

    fun playAll(): List<MusicItem>? {
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return null
        return songs.toList()
    }

    suspend fun getAlbumSongs(source: String, albumId: String): List<MusicItem> {
        return try {
            repository.getAlbumSongsForSource(source, albumId).result
        } catch (_: Exception) {
            emptyList()
        }
    }
}
