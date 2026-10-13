package com.ikunshare.music.manager

import com.ikunshare.music.model.MusicItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Every clicked song is recorded, but only the latest request may replace the playback queue. */
internal class TrialPlaybackRequests(
    private val scope: CoroutineScope,
    private val load: suspend (MusicItem, Boolean) -> List<MusicItem>,
    private val play: (List<MusicItem>, Int) -> Unit
) {
    private var generation = 0L
    var pendingSongId: Long? = null
        private set
    var pendingSongKey: String? = null
        private set

    fun isPending(songId: Long, songKey: String? = null): Boolean =
        if (!songKey.isNullOrEmpty()) pendingSongKey == songKey else pendingSongId == songId

    private fun clearPending() { pendingSongId = null; pendingSongKey = null }

    fun invalidate() { generation++; clearPending() }

    fun request(song: MusicItem, atHead: Boolean) {
        val request = ++generation
        pendingSongId = song.id
        pendingSongKey = song.uniqueKey
        scope.launch {
            val songs = try {
                load(song, atHead)
            } catch (cancelled: CancellationException) {
                if (request == generation) clearPending()
                throw cancelled
            } catch (_: Exception) {
                listOf(song)
            }
            if (request != generation) return@launch
            clearPending()
            val key = song.uniqueKey
            val index = songs.indexOfFirst { it.uniqueKey == key }
            if (index >= 0) play(songs, index) else play(songs + song, songs.size)
        }
    }
}
