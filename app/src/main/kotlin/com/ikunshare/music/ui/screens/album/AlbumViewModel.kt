package com.ikunshare.music.ui.screens.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.music.R
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.platform.base.AlbumPreviewCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

data class AlbumUiState(
    val albumInfo: AlbumInfoResult? = null,
    val songs: List<MusicItem> = emptyList(),
    val discs: List<AlbumDisc>? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class AlbumViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private var currentAlbumKey: String = ""
    private var loadJob: Job? = null

    /**
     * 加载专辑详情。
     * @param albumKey "source:albumId" 格式
     */
    fun loadAlbum(albumKey: String) {
        loadJob?.cancel()
        val previous = _uiState.value.takeIf { currentAlbumKey == albumKey }
        currentAlbumKey = albumKey

        val colonIndex = albumKey.indexOf(':')
        val source =
            if (colonIndex > 0) albumKey.substring(0, colonIndex) else null
        val albumId =
            if (colonIndex > 0) albumKey.substring(colonIndex + 1) else albumKey
        if (source == null || albumId.isBlank()) {
            _uiState.value = AlbumUiState(
                    error = MusicApplication.instance?.getString(R.string.invalid_album_id)
                        ?: "invalid album"
                )
            return
        }

        val cached = AlbumPreviewCache.get(source, albumId)
        _uiState.value = AlbumUiState(isLoading = true,
            albumInfo = previous?.albumInfo ?: cached, songs = previous?.songs.orEmpty(), discs = previous?.discs)
        loadJob = viewModelScope.launch {
            try {
                try {
                    val info = repository.getAlbumInfoForSource(source, albumId)
                    _uiState.update { it.copy(albumInfo = AlbumPreviewCache.merge(info, it.albumInfo)) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    _uiState.update { it.copy(error = failure.message ?: "专辑信息加载失败") }
                }
                val result = repository.getAlbumSongsForSource(source, albumId)
                val content = withContext(Dispatchers.IO) { albumContent(result.result, result.discs) }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        songs = content.songs,
                        discs = content.discs
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance?.getString(R.string.network_error)
                            ?: e.message
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = MusicApplication.instance?.getString(
                            R.string.load_album_failed, e.message ?: ""
                        ) ?: e.message
                    )
                }
            }
        }
    }

    fun retry() {
        if (currentAlbumKey.isNotBlank()) loadAlbum(currentAlbumKey)
    }

    fun playAll(): List<MusicItem>? {
        val songs = _uiState.value.songs
        if (songs.isEmpty()) return null
        return songs.toList()
    }
}
