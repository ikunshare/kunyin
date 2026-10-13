package com.ikunshare.music.model

internal data class QueueRemoval(val songs: List<MusicItem>, val index: Int, val currentRemoved: Boolean)

/** Preserve the current song's identity; choose its next surviving successor with wrap-around. */
internal fun removeQueueSongs(
    songs: List<MusicItem>, index: Int, currentKey: String?, removed: Set<String>, fromNextQueue: Boolean
): QueueRemoval {
    val remaining = songs.filter { it.uniqueKey !in removed }
    val currentRemoved = currentKey != null && currentKey in removed
    if (remaining.isEmpty()) return QueueRemoval(remaining, -1, currentRemoved)
    if (currentRemoved && !fromNextQueue) {
        val successor = (songs.drop(index + 1) + songs.take((index + 1).coerceAtLeast(0)))
            .firstOrNull { it.uniqueKey !in removed }
        return QueueRemoval(remaining, remaining.indexOfFirst { it.uniqueKey == successor?.uniqueKey }.coerceAtLeast(0), true)
    }
    val selectedKey = songs.getOrNull(index)?.uniqueKey
    val selected = remaining.indexOfFirst { it.uniqueKey == selectedKey }
    if (selected >= 0) return QueueRemoval(remaining, selected, currentRemoved)
    val preceding = songs.take(index.coerceAtLeast(0)).count { it.uniqueKey !in removed } - 1
    return QueueRemoval(remaining, if (fromNextQueue) preceding else preceding.coerceAtLeast(0), currentRemoved)
}
