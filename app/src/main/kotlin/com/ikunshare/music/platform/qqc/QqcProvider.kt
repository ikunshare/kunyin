package com.ikunshare.music.platform.qqc

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ikunshare.music.model.UserInfo
import com.ikunshare.music.platform.base.BaseProvider
import com.ikunshare.music.platform.base.MusicListResult
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.qq.QQMusicItem
import com.ikunshare.music.platform.qq.utils.QQMusicUtils
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.*
import java.io.IOException

/** A search source only. Returned tracks keep QQ identity, credentials, URLs and detail routes. */
class QqcProvider(
    private val searchRequest: suspend (String, Int, Int) -> JsonObject = { keyword, page, size ->
        val response = HTTPUtils.postCancellable(SEARCH_ENDPOINT, body = mapOf("keyword" to keyword, "page" to page.toLong() + 1, "limit" to size))
        if (!response.isSuccessful) throw IOException("QQ 音乐云请求失败 (${response.status})")
        response.json(JsonObject::class.java) ?: throw IOException("QQ 音乐云响应无效")
    },
    private val detailsRequest: suspend (List<Long>) -> JsonArray? = { ids ->
        val request = mapOf("comm" to mapOf("ct" to 19, "cv" to 1859, "uin" to 0),
            "req" to mapOf("module" to "music.trackInfo.UniformRuleCtrl", "method" to "CgiGetTrackInfo",
                "param" to mapOf("ids" to ids, "types" to ids.map { 1 })))
        QQMusicUtils.zzcRequestCancellable(request).json(JsonObject::class.java)
            ?.getAsJsonObject("req")?.getAsJsonObject("data")?.getAsJsonArray("tracks")
    }
) : BaseProvider("qqc") {
    override val displayName = "QQ音乐云"
    override val shortTag = "qqc"

    companion object { const val SEARCH_ENDPOINT = "https://c.wwwweb.top/music/search" }

    override fun search(keyword: String, page: Int, size: Int): MusicListResult =
        runBlocking(Dispatchers.IO) { searchAsync(keyword, page, size) }

    override suspend fun searchAsync(keyword: String, page: Int, size: Int): MusicListResult = withContext(Dispatchers.IO) {
        require(page >= 0 && size > 0)
        val response = searchRequest(keyword, page, size)
        val rows = response.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
        if (response.text("code") != "200" || rows == null) throw IOException("QQ 音乐云响应无效")
        val list = rows.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }
        val ids = list.mapNotNull { it.text("id").toLongOrNull()?.takeIf { id -> id > 0 } }.distinct()
        val details = mutableMapOf<Long, QQMusicItem>()
        // A normal search page is one request. Cap oversized caller batches without per-song requests.
        ids.chunked(100).forEach { batch ->
            currentCoroutineContext().ensureActive()
            val tracks = try { detailsRequest(batch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            tracks?.forEach { element ->
                val track = runCatching { QQMusicItem.parseTrackInfo(element.asJsonObject) }.getOrNull()
                if (track != null && track.id in batch) details.putIfAbsent(track.id, track)
            }
        }
        currentCoroutineContext().ensureActive()
        val result = list.map { row -> details[row.text("id").toLongOrNull()] ?: placeholder(row) }
        val total = response.text("total").toLongOrNull()?.coerceAtLeast(0) ?: 0
        MusicListResult(source, (page.toLong() + 1) * size < total, page, size, result,
            total = total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    private fun placeholder(row: JsonObject): QQMusicItem {
        val albumMid = row.text("albummid")
        return QQMusicItem(id = row.text("id").toLongOrNull() ?: 0, title = row.text("name"),
            artist = row.text("artist"), album = row.text("album"),
            albumId = albumMid.ifBlank { row.text("albumid").takeIf { (it.toLongOrNull() ?: 0) > 0 }.orEmpty() }.ifBlank { null },
            cover = if (albumMid.isBlank()) "" else "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg",
            duration = 0, qualities = emptyMap(), mid = row.text("mid"), albumMid = albumMid,
            mediaMid = "", vs = emptyList(), size_new = emptyList())
    }

    override fun getHotSearch() = emptyList<String>()
    override fun getSearchTip(keyword: String) = emptyList<String>()
    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? = null
    override fun getPlayListSongs(playListId: String, page: Int, size: Int) = MusicListResult(source, false, page, size, emptyList())
    override fun getUserPlaylist() = emptyList<PlayListInfoResult>()
    override fun getUserInfo(): UserInfo? = null
}

private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
