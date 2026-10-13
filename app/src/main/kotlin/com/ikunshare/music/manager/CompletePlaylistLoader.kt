package com.ikunshare.music.manager

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.MusicListResult
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/** Desktop playlist-refresh.ts behavior, including filtering tolerance and bounded pagination. */
internal suspend fun loadCompletePlaylist(
    expectedCount: Int,
    onPage: ((Int, MusicListResult, List<MusicItem>, Int) -> Unit)? = null,
    fetchPage: suspend (page: Int, size: Int) -> MusicListResult
): List<MusicItem> {
    val all = linkedMapOf<String, MusicItem>()
    var skipped = 0
    for (page in 0 until 100) {
        coroutineContext.ensureActive()
        val result = fetchPage(page, 100)
        val before = all.size
        result.result.forEach { all[it.uniqueKey] = it }
        skipped += result.skippedLocalSongs.coerceAtLeast(0)
        if (page > 0 && result.result.isEmpty() && result.skippedLocalSongs <= 0) {
            error("歌单未完整返回，已保留原歌单，请重试")
        }
        if (!result.hasNext) {
            if (all.isEmpty()) error("歌单为空或暂时无法访问")
            val tolerance = maxOf(5, ceil(expectedCount * 0.1).toInt())
            if (expectedCount.toLong() > all.size.toLong() + skipped + tolerance) {
                error("部分歌曲未能加载，已保留原歌单，请重试")
            }
            val songs = all.values.toList()
            onPage?.invoke(page, result, songs, skipped)
            return songs
        }
        if (all.size == before && result.skippedLocalSongs <= 0) {
            error("歌单分页异常，请稍后重试")
        }
        onPage?.invoke(page, result, all.values.toList(), skipped)
    }
    error("歌单过大，未完整加载，已保留原歌单")
}
