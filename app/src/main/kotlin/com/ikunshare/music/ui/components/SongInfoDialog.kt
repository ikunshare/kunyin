package com.ikunshare.music.ui.components

import com.ikunshare.music.R
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.model.MusicItem

internal data class SongInfoField(val key: String, val value: String)

internal fun songInfoFields(json: JsonObject): List<SongInfoField> = buildList {
    fun walk(value: JsonElement, path: String) {
        when {
            value.isJsonNull -> Unit
            value.isJsonObject -> value.asJsonObject.entrySet().forEach { (key, child) ->
                walk(child, if (path.isEmpty()) key else "$path.$key")
            }
            value.isJsonArray -> value.asJsonArray.forEachIndexed { index, child -> walk(child, "$path.$index") }
            else -> value.asString.takeIf { it.isNotEmpty() }?.let { add(SongInfoField(path, it)) }
        }
    }
    walk(json, "")
}

internal fun songInfoText(fields: List<SongInfoField>): String = fields.joinToString("\n") { "${it.key}: ${it.value}" }
internal fun songInfoJson(json: JsonObject): String = GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(json)

@Composable
fun SongInfoDialog(song: MusicItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // Capture the stored representation once; playback switching must not change the open dialog.
    val json = remember(song) { song.toJson().deepCopy() }
    val fields = remember(json) { songInfoFields(json) }
    var status by remember(json) { mutableStateOf<String?>(null) }
    val copied = stringResource(R.string.song_info_copied)
    val failed = stringResource(R.string.song_info_copy_failed)
    fun copy(value: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(song.title, value))
            status = copied
        } catch (_: Exception) { status = failed }
    }
    val bodyHeight = LocalConfiguration.current.screenHeightDp.dp * 0.5f
    GlassAlertDialog(onDismissRequest = onDismiss,
        title = { Text("${stringResource(R.string.song_info_title)} · ${song.title}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.song_info_hint), style = MaterialTheme.typography.bodySmall)
                status?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                SelectionContainer {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = bodyHeight)) {
                        itemsIndexed(fields, key = { index, field -> "${field.key}_$index" }) { _, field ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(field.key, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(field.value, style = MaterialTheme.typography.bodyMedium)
                                }
                                IconButton(onClick = { copy(field.value) }) {
                                    Icon(Icons.Filled.ContentCopy, stringResource(R.string.song_info_copy_field, field.key))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = { copy(songInfoText(fields)) }) { Text(stringResource(R.string.song_info_copy_text)) }
                TextButton(onClick = { copy(songInfoJson(json)) }) { Text(stringResource(R.string.song_info_copy_json)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}
