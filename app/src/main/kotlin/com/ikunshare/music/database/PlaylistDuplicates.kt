package com.ikunshare.music.database

import com.ikunshare.music.model.MusicItem
import java.util.Locale

internal data class PlaylistDuplicateEntry(val song: MusicItem, val nth: Int, val groupHead: Boolean)
// ECMAScript whitespace, matching the desktop title.toLowerCase().replace(/\s+/g, '').
private val titleWhitespace = Regex("[\\t-\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")
internal fun playlistDuplicateEntries(songs: List<MusicItem>): List<PlaylistDuplicateEntry> {
    val groups = linkedMapOf<String, MutableList<MusicItem>>()
    songs.forEach { song ->
        val key = titleWhitespace.replace(song.title.lowercase(Locale.ROOT), "")
        if (key.isNotEmpty()) groups.getOrPut(key) { mutableListOf() }.add(song)
    }
    return groups.values.filter { it.size > 1 }.flatMap { group ->
        group.mapIndexed { index, song -> PlaylistDuplicateEntry(song, index + 1, index == 0) }
    }
}
