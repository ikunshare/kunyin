package com.ikunshare.music.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.ui.screens.charts.ChartsViewModel
import com.ikunshare.music.manager.discovery.ChartCollector
import com.ikunshare.music.ui.screens.discover.DiscoverViewModel
import com.ikunshare.music.ui.screens.discover.DiscoverySelection
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.database.PlatformPlaylistStore
import com.ikunshare.music.database.PlaybackStateStore
import com.ikunshare.music.manager.AuthManager
import com.ikunshare.music.manager.CredentialManager
import com.ikunshare.music.manager.DownloadManager
import com.ikunshare.music.manager.MusicRepository
import com.ikunshare.music.manager.SoundMediaPlayer
import com.ikunshare.music.tool.cache.AudioCache
import com.ikunshare.music.ui.screens.album.AlbumViewModel
import com.ikunshare.music.ui.screens.artist.ArtistViewModel
import com.ikunshare.music.ui.screens.download.DownloadViewModel
import com.ikunshare.music.ui.screens.lyrics.LyricsViewModel
import com.ikunshare.music.ui.screens.player.CommentsViewModel
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.screens.playlist.PlaylistViewModel
import com.ikunshare.music.ui.screens.playlists.LocalPlaylistViewModel
import com.ikunshare.music.ui.screens.playlists.PlaylistsViewModel
import com.ikunshare.music.ui.screens.search.SearchViewModel
import com.ikunshare.music.ui.screens.settings.SettingsViewModel

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

    private val chartCollector by lazy {
        ChartCollector(localMusicStore, { board, page, size -> repository.getChartSongs(board.source, board.id, page, size, board.period) })
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(CommentsViewModel::class.java) -> CommentsViewModel(repository) as T
            modelClass.isAssignableFrom(DiscoverViewModel::class.java) -> {
                val prefs = MusicApplication.instance!!.getSharedPreferences("discover_selection", android.content.Context.MODE_PRIVATE)
                DiscoverViewModel(repository, restore = { DiscoverySelection(prefs.getString("source", "wy").orEmpty(),
                    prefs.getString("category", "").orEmpty(), prefs.getString("order", "hot").orEmpty(), prefs.getInt("page", 0)) },
                    persist = { selection -> prefs.edit().putString("source", selection.source).putString("category", selection.category)
                        .putString("order", selection.order).putInt("page", selection.page).apply() }) as T
            }
            modelClass.isAssignableFrom(ChartsViewModel::class.java) -> {
                val prefs = MusicApplication.instance!!.getSharedPreferences("chart_selection", android.content.Context.MODE_PRIVATE)
                ChartsViewModel(repository,
                    restore = { prefs.getString("source", "wy").orEmpty() to prefs.getString("board", "").orEmpty() },
                    persist = { source, id -> prefs.edit().putString("source", source).putString("board", id).apply() },
                    collectSnapshot = chartCollector::collect) as T
            }
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
