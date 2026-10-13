package com.ikunshare.music.manager

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.MusicListResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

internal data class AlbumDownloadBatchResult(val queuedSongs: Int, val failedAlbums: List<AlbumInfoResult>)

internal suspend fun enqueueAlbumDownloadBatch(
    albums: List<AlbumInfoResult>, withYear: Boolean, artistFallback: String?,
    fetch: suspend (AlbumInfoResult) -> MusicListResult,
    enqueue: (MusicItem, String, Int) -> Boolean, flush: () -> Unit,
    enqueueDisc: ((MusicItem, String, Int, com.ikunshare.music.ui.screens.download.DownloadDisc?) -> Boolean)? = null
): AlbumDownloadBatchResult {
    val snapshot = albums.toList().distinctBy { "${it.source}:${it.albumId}" }
    return withContext(Dispatchers.IO) {
        var queued = 0
        val failed = mutableListOf<AlbumInfoResult>()
        try {
            for (album in snapshot) {
                coroutineContext.ensureActive()
                try {
                    val response = fetch(album)
                    check(!response.hasNext) { "专辑曲目尚未读取完整" }
                    val tracks = albumDownloadTracks(response.result, response.discs)
                    val songs = tracks.map { it.song }
                    check(songs.isNotEmpty()) { "专辑没有返回曲目" }
                    val folder = AlbumFolderNamer.buildSubDir(album.copy(artist = album.artist?.takeIf { it.isNotBlank() }
                        ?: artistFallback?.takeIf { it.isNotBlank() } ?: songs.first().artist), withYear)
                    tracks.forEachIndexed { index, track ->
                        coroutineContext.ensureActive()
                        if (enqueueDisc?.invoke(track.song, folder, track.number, track.disc) ?: enqueue(track.song, folder, track.number)) queued++
                        if (index % 16 == 15) yield()
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed.add(album) }
            }
        } finally { flush() }
        AlbumDownloadBatchResult(queued, failed)
    }
}
