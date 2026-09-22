package com.ikunshare.sound.ui.screens.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.AlbumPreviewCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

data class AlbumUiState(
    val albumInfo: AlbumInfoResult? = null,
    val songs: List<MusicItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class AlbumViewModel(
    private val repository: MusicRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumUiState())
    val uiState: StateFlow<AlbumUiState> = _uiState.asStateFlow()

    private var currentAlbumKey: String = ""

    /**
     * 加载专辑详情。
     * @param albumKey "source:albumId" 格式
     */
    fun loadAlbum(albumKey: String) {
        if (albumKey.isBlank()) return
        currentAlbumKey = albumKey

        val colonIndex = albumKey.indexOf(':')
        val source =
            if (colonIndex > 0) albumKey.substring(0, colonIndex) else null
        val albumId =
            if (colonIndex > 0) albumKey.substring(colonIndex + 1) else albumKey
        if (source == null || albumId.isBlank()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = SoundApplication.instance?.getString(R.string.invalid_album_id)
                        ?: "invalid album"
                )
            }
            return
        }

        viewModelScope.launch {
            val cached = AlbumPreviewCache.get(source, albumId)
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    albumInfo = cached,
                    songs = emptyList()
                )
            }
            try {
                val info = repository.getAlbumInfoForSource(source, albumId)
                val songs = repository.getAlbumSongsForSource(source, albumId).result
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        albumInfo = AlbumPreviewCache.merge(info, cached),
                        songs = songs
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
