package com.ikunshare.music.manager

import com.ikunshare.music.model.Lyric
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.kg.KugouMusicItem
import com.ikunshare.music.platform.kg.hasMainLyric
import com.ikunshare.music.tool.cache.Cache
import kotlinx.coroutines.*
import java.security.MessageDigest

/** Share player/lyrics/download consumers; cancel the network worker when its last consumer leaves. */
internal class LyricRequests(
    private val cache: Cache<String, Lyric>,
    private val native: suspend (MusicItem) -> Lyric,
    private val fallback: suspend (MusicItem) -> Lyric,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private class Entry(val result: Deferred<Lyric>, var consumers: Int = 0)
    private val gate = Any()
    private val requests = mutableMapOf<String, Entry>()
    private var revision = 0L

    suspend fun get(item: MusicItem): Lyric = withContext(Dispatchers.IO) {
        val key = cacheKey(item)
        val entry = synchronized(gate) {
            cache.get(key)?.let { return@withContext it }
            requests[key]?.also { it.consumers++ } ?: run {
                val token = revision
                val worker = scope.async(start = CoroutineStart.LAZY) {
                    val legacyKey = "${item.getTypeDiscriminator()}_${item.uniqueKey}"
                    val explicit = item as? KugouMusicItem
                    val cached = if (explicit?.lyricDownloadId.isNullOrBlank()) cache.get(legacyKey) else null
                    var lyric = cached ?: safely { native(item) }
                    if (!lyric.hasMainLyric()) lyric = safely { fallback(item) }
                    currentCoroutineContext().ensureActive()
                    val context = currentCoroutineContext()
                    synchronized(gate) {
                        if (token == revision && context.isActive) {
                            // Includes negative results. Old translation-only caches have now run fallback.
                            cache.put(key, lyric)
                            cache.remove(legacyKey)
                        }
                    }
                    lyric
                }
                Entry(worker, 1).also { requests[key] = it }
            }
        }
        try { entry.result.await() }
        finally {
            synchronized(gate) {
                entry.consumers--
                if (entry.consumers == 0 && requests[key] === entry) {
                    requests.remove(key)
                    if (!entry.result.isCompleted) entry.result.cancel()
                }
            }
        }
    }

    fun clear() = synchronized(gate) {
        revision++
        cache.clear()
    }

    private suspend fun safely(load: suspend () -> Lyric): Lyric = try { load() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { Lyric() }

    private fun cacheKey(item: MusicItem): String {
        val kg = item as? KugouMusicItem
        val direct = if (kg?.lyricDownloadId.isNullOrBlank()) "" else {
            val identity = "${kg.lyricAccessKey}\u0000${kg.lyricDownloadId}"
            "_" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        return "fallback_v1_${item.uniqueKey}$direct"
    }
}
