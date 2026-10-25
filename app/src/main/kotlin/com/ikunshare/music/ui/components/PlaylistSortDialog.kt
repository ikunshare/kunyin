package com.ikunshare.music.ui.components

import com.ikunshare.music.R
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.database.PlaylistSortField
import com.ikunshare.music.database.PlaylistSortOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun PlaylistSortDialog(playlistId: Long, playlistName: String, store: LocalMusicStore, onDismiss: () -> Unit) {
    var field by rememberSaveable(playlistId) { mutableStateOf(PlaylistSortField.TITLE) }
    var order by rememberSaveable(playlistId) { mutableStateOf(PlaylistSortOrder.ASC) }
    var busy by remember(playlistId) { mutableStateOf(false) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    GlassAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.playlist_sort_dialog_title, playlistName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.playlist_sort_field))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistSortField.entries.forEach { value ->
                        FilterChip(selected = field == value, onClick = { field = value },
                            enabled = !busy && order != PlaylistSortOrder.RANDOM,
                            label = { Text(stringResource(value.label)) })
                    }
                }
                Text(stringResource(R.string.playlist_sort_order))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaylistSortOrder.entries.forEach { value ->
                        FilterChip(selected = order == value, onClick = { order = value }, enabled = !busy,
                            label = { Text(stringResource(value.label)) })
                    }
                }
                error?.let { Text(stringResource(R.string.playlist_sort_failed, it), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        store.sortPlaylistSongs(playlistId, field, order)
                        Toast.makeText(context, R.string.playlist_sort_done, Toast.LENGTH_SHORT).show()
                        onDismiss()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        error = failure.message ?: failure.javaClass.simpleName
                    } finally { busy = false }
                }
            }) { Text(stringResource(if (busy) R.string.playlist_sort_busy else R.string.playlist_sort_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(android.R.string.cancel)) } }
    )
}
