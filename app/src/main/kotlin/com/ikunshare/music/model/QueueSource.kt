package com.ikunshare.music.model

/** Identifies the list that started playback; its display name may change without changing identity. */
data class QueueSource(val kind: String, val id: String = "", val name: String = "") {
    fun matches(other: QueueSource?): Boolean = other != null && kind == other.kind && id == other.id
}

internal fun newQueueItems(existing: List<MusicItem>, incoming: List<MusicItem>): List<MusicItem> {
    val keys = existing.mapTo(HashSet()) { it.uniqueKey }
    return incoming.filter { keys.add(it.uniqueKey) }
}

internal fun resolveQueueSongIndex(songs: List<MusicItem>, selectedIndex: Int, songId: Long, songKey: String? = null): Int {
    fun matches(item: MusicItem) = if (!songKey.isNullOrEmpty()) item.uniqueKey == songKey else item.id == songId
    return if (songs.getOrNull(selectedIndex)?.let(::matches) == true) selectedIndex
    else songs.indexOfFirst(::matches)
}
