package com.ikunshare.music.manager.discovery

import com.ikunshare.music.database.LocalMusicStore
import com.ikunshare.music.manager.loadCompletePlaylist
import com.ikunshare.music.platform.base.MusicListResult
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Desktop imports charts as complete local snapshots, without a remote refresh binding. */
internal class ChartCollector(
    private val store: LocalMusicStore,
    private val fetch: suspend (ChartInfo, Int, Int) -> MusicListResult,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<Pair<String, String>, Deferred<Long>>()

    suspend fun collect(board: ChartInfo): Long {
        val key = board.source to board.id
        val task = mutex.withLock {
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val songs = loadCompletePlaylist(0) { page, size -> fetch(board, page, size) }
                    store.createPlaylistWithSongs(board.name.ifBlank { "收藏的榜单" }, songs)
                } finally {
                    withContext(NonCancellable) { mutex.withLock { pending.remove(key) } }
                }
            }.also { pending[key] = it }
        }
        return task.await()
    }
}
