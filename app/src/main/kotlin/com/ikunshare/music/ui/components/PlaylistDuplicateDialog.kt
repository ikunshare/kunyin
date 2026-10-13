package com.ikunshare.music.ui.components

import com.ikunshare.music.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.database.PlaylistDuplicateEntry
import com.ikunshare.music.database.playlistDuplicateEntries
import com.ikunshare.music.model.MusicItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PlaylistDuplicateDialog(playlistId: Long, playlistName: String, store: LocalMusicStore,
    onPlay: (MusicItem) -> Unit, onDismiss: () -> Unit) {
    val revisions by store.playlistSongsRevision.collectAsState()
    val revision = revisions[playlistId] ?: 0L
    var entries by remember(playlistId) { mutableStateOf(emptyList<PlaylistDuplicateEntry>()) }
    var loading by remember(playlistId) { mutableStateOf(true) }
    var busy by remember(playlistId) { mutableStateOf(false) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    var scanAttempt by remember(playlistId) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(playlistId, revision, scanAttempt) {
        loading = true
        error = null
        try {
            entries = withContext(Dispatchers.IO) { playlistDuplicateEntries(store.queryPlaylistSongs(playlistId)) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.playlist_duplicates_title, playlistName)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { message ->
                    Text(stringResource(R.string.playlist_duplicates_failed, message), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { scanAttempt++ }, enabled = !busy && !loading) { Text(stringResource(R.string.retry)) }
                }
                if (!loading && error == null && entries.isEmpty()) Text(stringResource(R.string.playlist_duplicates_empty))
                LazyColumn(Modifier.weight(1f, fill = false)) {
                    items(entries, key = { it.song.uniqueKey }) { entry ->
                        val song = entry.song
                        if (entry.groupHead) HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${entry.nth}. ${song.title}", maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val seconds = song.duration / 1000
                                Text("${song.getTypeDiscriminator()} · ${song.artist} · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { onPlay(song) }, enabled = !busy) {
                                Icon(Icons.Filled.PlayArrow, stringResource(R.string.playlist_duplicates_preview))
                            }
                            IconButton(enabled = !busy && !loading, onClick = {
                                busy = true
                                scope.launch {
                                    try {
                                        if (!store.removeFromPlaylistAndWait(playlistId, song)) scanAttempt++
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (failure: Exception) { error = failure.message ?: failure.javaClass.simpleName }
                                    finally { busy = false }
                                }
                            }) { Icon(Icons.Filled.Delete, stringResource(R.string.playlist_duplicates_remove), tint = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
                if (!loading) Text(stringResource(R.string.playlist_duplicates_count, entries.size), style = MaterialTheme.typography.labelMedium)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.close)) } }
    )
}
