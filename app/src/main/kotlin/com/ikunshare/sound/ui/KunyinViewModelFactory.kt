package com.ikunshare.sound.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.PlatformPlaylistStore
import com.ikunshare.sound.database.PlaybackStateStore
import com.ikunshare.sound.manager.AuthManager
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.DownloadManager
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.manager.SoundMediaPlayer
import com.ikunshare.sound.tool.cache.AudioCache
import com.ikunshare.sound.ui.screens.album.AlbumViewModel
import com.ikunshare.sound.ui.screens.artist.ArtistViewModel
import com.ikunshare.sound.ui.screens.download.DownloadViewModel
import com.ikunshare.sound.ui.screens.lyrics.LyricsViewModel
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.screens.playlist.PlaylistViewModel
import com.ikunshare.sound.ui.screens.playlists.LocalPlaylistViewModel
import com.ikunshare.sound.ui.screens.playlists.PlaylistsViewModel
import com.ikunshare.sound.ui.screens.search.SearchViewModel
import com.ikunshare.sound.ui.screens.settings.SettingsViewModel

class KunyinViewModelFactory(
    private val repository: MusicRepository,
    private val mediaPlayer: SoundMediaPlayer,
    private val credentialManager: CredentialManager,
    val appSettingsManager: AppSettingsManager,
    val localMusicStore: LocalMusicStore,
    private val audioCache: AudioCache,
    private val authManager: AuthManager,
    val downloadManager: DownloadManager,
    private val playbackStateStore: PlaybackStateStore,
    val platformPlaylistStore: PlatformPlaylistStore
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(PlayerViewModel::class.java) -> {
                PlayerViewModel(
                    repository,
                    mediaPlayer,
                    appSettingsManager,
                    localMusicStore,
                    playbackStateStore,
                    audioCache
                ) as T
            }

            modelClass.isAssignableFrom(SearchViewModel::class.java) -> {
                SearchViewModel(repository, appSettingsManager) as T
            }

            modelClass.isAssignableFrom(PlaylistViewModel::class.java) -> {
                PlaylistViewModel(repository, platformPlaylistStore, localMusicStore) as T
            }

            modelClass.isAssignableFrom(AlbumViewModel::class.java) -> {
                AlbumViewModel(repository) as T
            }

            modelClass.isAssignableFrom(ArtistViewModel::class.java) -> {
                ArtistViewModel(repository) as T
            }

            modelClass.isAssignableFrom(LyricsViewModel::class.java) -> {
                LyricsViewModel(repository, appSettingsManager) as T
            }

            modelClass.isAssignableFrom(SettingsViewModel::class.java) -> {
                SettingsViewModel(
                    credentialManager,
                    appSettingsManager,
                    audioCache,
                    authManager,
                    repository,
                    localMusicStore
                ) as T
            }

            modelClass.isAssignableFrom(PlaylistsViewModel::class.java) -> {
                PlaylistsViewModel(
                    repository,
                    credentialManager,
                    localMusicStore,
                    platformPlaylistStore
                ) as T
            }

            modelClass.isAssignableFrom(LocalPlaylistViewModel::class.java) -> {
                LocalPlaylistViewModel(localMusicStore, repository) as T
            }

            modelClass.isAssignableFrom(DownloadViewModel::class.java) -> {
                DownloadViewModel(downloadManager) as T
            }

            else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
