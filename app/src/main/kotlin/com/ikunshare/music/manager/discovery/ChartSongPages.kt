package com.ikunshare.music.manager.discovery

import com.google.gson.JsonObject
import com.ikunshare.music.platform.base.MusicListResult
import com.ikunshare.music.platform.kw.KwProvider
import com.ikunshare.music.platform.qq.QQMusicItem
import com.ikunshare.music.platform.qq.utils.QQMusicUtils
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.URLEncoder

internal data class ChartPageRequest(val source: String, val id: String, val page: Int, val size: Int, val period: String)

internal class ChartSongPages(
    private val wySongs: suspend (String, Int, Int) -> MusicListResult,
    private val transport: suspend (ChartPageRequest) -> JsonObject = ::requestChartPage,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val enrich: suspend (MusicListResult) -> Unit = {}
) {
    private data class Hit(val at: Long, val value: MusicListResult)
    private val cache = linkedMapOf<ChartPageRequest, Hit>()
    private val pending = mutableMapOf<ChartPageRequest, Deferred<MusicListResult>>()
    private val mutex = Mutex()

    suspend fun songs(source: String, id: String, page: Int = 0, size: Int = 100, period: String = ""): MusicListResult {
        require(source in chartFallbacks && id.isNotBlank()) { "Invalid chart source or ID" }
        val query = ChartPageRequest(source, id, page.coerceAtLeast(0), size.coerceIn(1, 300), period)
        val task = mutex.withLock {
            cache[query]?.takeIf { clock() - it.at < 10 * 60_000 }?.let { return it.value }
            pending[query] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = load(query)
                    enrich(result)
                    mutex.withLock {
                        if (cache.size >= 400) cache.keys.take(200).forEach { cache.remove(it) }
                        cache.remove(query)
                        cache[query] = Hit(clock(), result)
                    }
                    result
                } finally { withContext(NonCancellable) { mutex.withLock { pending.remove(query) } } }
            }.also { pending[query] = it }
        }
        return task.await()
    }

    private suspend fun load(query: ChartPageRequest): MusicListResult {
        if (query.source == "wy") return wySongs(query.id, query.page, query.size)
        var failure: Exception? = null
        repeat(if (query.source == "qq") 1 else 3) {
            try { return parseChartPage(query, transport(query)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        throw IOException("${query.source} chart songs are unavailable", failure)
    }
}

internal fun qqChartPageRequest(query: ChartPageRequest): Map<String, Any> = mapOf(
    "comm" to mapOf("ct" to 20, "cv" to 1859, "format" to "json", "uin" to 0),
    "req" to mapOf("module" to "musicToplist.ToplistInfoServer", "method" to "GetDetail", "param" to mapOf(
        "topid" to (query.id.toLongOrNull() ?: throw IllegalArgumentException("Invalid QQ chart ID")),
        "num" to query.size, "offset" to query.page.toLong() * query.size, "period" to query.period))
)

private suspend fun requestChartPage(query: ChartPageRequest): JsonObject {
    val response = when (query.source) {
        "qq" -> QQMusicUtils.zzcRequest(qqChartPageRequest(query))
        "kg" -> HTTPUtils.get("http://mobilecdnbj.kugou.com/api/v3/rank/song?version=9108&ranktype=1&plat=0" +
            "&pagesize=${query.size}&area_code=1&page=${query.page.toLong() + 1}&rankid=${URLEncoder.encode(query.id, "UTF-8")}&with_res_tag=0&show_portrait_mv=1")
        "kw" -> HTTPUtils.get("https://wbd.kuwo.cn/api/bd/bang/bang_info?" + KuwoWbdCodec.parameters(linkedMapOf(
            "uid" to "", "devId" to "", "sFrom" to "kuwo_sdk", "user_type" to "AP",
            "carSource" to "kwplayercar_ar_6.0.1.0_apk_keluze.apk", "id" to query.id, "pn" to query.page, "rn" to query.size)))
        else -> throw IllegalArgumentException("Unsupported chart source")
    }
    if (!response.isSuccessful) throw IOException("Chart request failed (${response.status})")
    return if (query.source == "kw") KuwoWbdCodec.decode(response.body ?: throw IOException("Empty chart response"))
        else response.json(JsonObject::class.java) ?: throw IOException("Empty chart response")
}

internal fun parseChartPage(query: ChartPageRequest, json: JsonObject): MusicListResult {
    fun objectField(obj: JsonObject, key: String) = obj.get(key)?.takeIf { it.isJsonObject }?.asJsonObject
        ?: throw IOException("Missing chart $key")
    val data = when (query.source) {
        "qq" -> objectField(json, "req").also { check(it.chartText("code") == "0") }.let { objectField(it, "data") }
        "kg" -> { check(json.chartText("errcode") == "0"); objectField(json, "data") }
        "kw" -> { check(json.chartText("code") == "200"); objectField(json, "data") }
        else -> throw IllegalArgumentException("Unsupported chart parser")
    }
    val field = when (query.source) { "qq" -> "songInfoList"; "kg" -> "info"; else -> "musiclist" }
    val rows = data.get(field)?.takeIf { it.isJsonArray }?.asJsonArray ?: throw IOException("Missing chart songs")
    val kw = if (query.source == "kw") KwProvider() else null
    val songs = rows.mapNotNull { row ->
        runCatching {
            if (!row.isJsonObject) null else when (query.source) {
                "qq" -> QQMusicItem.parseTrackInfo(row.asJsonObject)
                "kg" -> parseKgChartSong(row.asJsonObject)
                else -> kw!!.parsePlaylistSong(row.asJsonObject)
            }
        }.getOrNull()
    }
    val total = if (query.source == "qq") (data.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.chartLong("totalNum") ?: 0).toInt()
        .takeIf { it > 0 } ?: if (query.page == 0) songs.size else 0
        else data.chartLong("total").toInt().takeIf { it > 0 } ?: songs.size
    return MusicListResult(query.source, (query.page.toLong() + 1) * query.size < total,
        query.page, query.size, songs, total = total)
}
