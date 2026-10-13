package com.ikunshare.music.ui.utils

import com.ikunshare.music.R
import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.model.MusicItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Shared completion/error feedback for playlist writes from selection dialogs. */
@Composable
internal fun rememberPlaylistWriteActions(store: LocalMusicStore): PlaylistWriteActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(store, context, scope) { PlaylistWriteActions(store, context, scope) }
}

internal class PlaylistWriteActions(
    private val store: LocalMusicStore,
    private val context: Context,
    private val scope: CoroutineScope
) {
    var isWriting by mutableStateOf(false)
        private set

    fun add(playlistId: Long, songs: List<MusicItem>, onSuccess: () -> Unit) {
        val snapshot = songs.toList()
        if (snapshot.isEmpty()) return
        write(onSuccess) {
            val added = store.addSongsToPlaylist(playlistId, snapshot)
            context.getString(R.string.added_to_playlist_count, added)
        }
    }

    fun create(name: String, songs: List<MusicItem>, onSuccess: () -> Unit) {
        val snapshot = songs.toList()
        if (snapshot.isEmpty()) return
        write(onSuccess) {
            val id = store.createPlaylistWithSongs(name, snapshot)
            context.getString(R.string.created_playlist_added_count, store.getPlaylist(id)?.songCount ?: snapshot.size)
        }
    }

    fun remove(playlistId: Long, songs: List<MusicItem>, favorites: Boolean = false, onSuccess: () -> Unit) {
        val snapshot = songs.toList()
        if (snapshot.isEmpty()) return
        write(onSuccess) {
            val removed = store.removeSongsFromPlaylistAndWait(playlistId, snapshot)
            context.getString(if (favorites) R.string.unfavorited_count else R.string.removed_from_playlist_count, removed)
        }
    }

    private fun write(onSuccess: () -> Unit, operation: suspend () -> String) {
        if (isWriting) return
        isWriting = true
        scope.launch {
            try {
                val message = operation()
                onSuccess()
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Toast.makeText(context, context.getString(R.string.playlist_write_failed,
                    failure.message.orEmpty()), Toast.LENGTH_LONG).show()
            } finally {
                isWriting = false
            }
        }
    }
}
