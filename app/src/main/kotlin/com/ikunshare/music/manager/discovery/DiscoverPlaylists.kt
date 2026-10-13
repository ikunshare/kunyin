package com.ikunshare.music.manager.discovery

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.PlaylistSearchResult
import com.ikunshare.music.platform.qq.utils.QQMusicUtils
import com.ikunshare.music.platform.wy.utils.NeteaseCrypto
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.*
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.roundToLong

internal data class PlaylistPageQuery(val source: String, val category: String, val order: String, val page: Int, val size: Int)
internal data class DiscoveryListRequest(val query: PlaylistPageQuery, val kind: String = "list")
internal data class KgListInfo(val size: Int = 0, val total: Int = 0)

internal class DiscoverPlaylists(
    private val transport: suspend (DiscoveryListRequest) -> JsonElement = ::requestDiscoveryList,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val pages = DiscoveryMemo<PlaylistPageQuery, PlaylistSearchResult>(5 * 60_000, scope, clock)
    private val kgInfo = DiscoveryMemo<String, KgListInfo>(10 * 60_000, scope, clock)
    private val kgRecommend = DiscoveryMemo<Unit, List<PlayListInfoResult>>(10 * 60_000, scope, clock)

    suspend fun playlists(source: String, category: String = "", order: String = "", page: Int = 0, size: Int = 30): PlaylistSearchResult {
        require(source in discoveryPlaylistSorts) { "Unsupported discovery source" }
        val query = PlaylistPageQuery(source, category.trim(), order.trim().ifBlank { discoveryPlaylistSorts.getValue(source).first().id },
            page.coerceAtLeast(0), size.coerceIn(1, 50))
        return pages.get(query) { coroutineScope {
            if (isKuwoSection(query) && query.page > 0)
                return@coroutineScope PlaylistSearchResult(source, false, query.page, query.size, emptyList())
            val info = if (source == "kg") async {
                kgInfo.get(query.category) {
                    val json = optional(DiscoveryListRequest(query, "info"))?.obj()
                    val params = if (json?.chartText("status") == "1") json.objectAt("data")?.objectAt("params") else null
                    KgListInfo(params?.count("pagesize") ?: 0, params?.count("total") ?: 0)
                }
            } else null
            val recommend = if (source == "kg" && query.category.isEmpty() && query.page == 0 && kgSort(query.order) == "5") async {
                kgRecommend.get(Unit) {
                    val json = optional(DiscoveryListRequest(query, "recommend"))?.obj()
                    if (json?.chartText("status") == "1") playlistRows("kg", json.objectAt("data")?.get("special_list")) else emptyList()
                }
            } else null
            var failure: Exception? = null
            repeat(if (source == "kg" || source == "kw") 3 else 1) {
                try {
                    return@coroutineScope parseDiscoveryPlaylists(query, transport(DiscoveryListRequest(query)), info?.await() ?: KgListInfo(), recommend?.await().orEmpty())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { failure = error }
            }
            throw IOException("$source 暂时无法返回歌单，请稍后重试", failure)
        } }
    }

    private suspend fun optional(request: DiscoveryListRequest): JsonElement? = try { transport(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
}

private const val KG_ROOT = "http://www2.kugou.kugou.com/yueku/v9/special/getSpecial"
private const val KW_ROOT = "http://wapi.kuwo.cn/api/pc/classify/playlist"
private const val KW_AUTH = "loginUid=0&loginSid=0&appUid=76039576"
private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
private fun isKuwoSection(q: PlaylistPageQuery) = q.source == "kw" && q.category.substringBefore('-').isNotBlank() && q.category.substringAfter('-', "") == "43"
internal fun kgSort(order: String) = if (order.matches(Regex("\\d+"))) order else if (order == "new") "7" else "6"

internal fun qqDiscoveryPlaylistPayload(query: PlaylistPageQuery): Map<String, Any> {
    val category = query.category.isNotEmpty()
    val param = if (category) mapOf("titleid" to query.category.toLong(), "caller" to "0", "category_id" to query.category.toLong(),
        "page" to query.page, "size" to query.size, "use_page" to 1)
    else mapOf("id" to 10000000, "sin" to query.page.toLong() * query.size, "size" to query.size,
        "order" to when (query.order) { "new" -> 2; "hot" -> 5; else -> query.order.toIntOrNull() ?: 5 }, "cur_page" to query.page.toLong() + 1)
    return mapOf("comm" to mapOf("ct" to 20, "cv" to 1859, "format" to "json", "uin" to 0),
        "req" to mapOf("module" to if (category) "playlist.PlayListCategoryServer" else "playlist.PlayListPlazaServer",
            "method" to if (category) "get_category_content" else "get_playlist_by_tag", "param" to param))
}

internal fun discoveryListUrl(request: DiscoveryListRequest): String {
    val q = request.query
    if (q.source == "kg") return when (request.kind) {
        "info" -> if (q.category.isEmpty()) "$KG_ROOT?is_smarty=1&" else "$KG_ROOT?is_smarty=1&cdn=cdn&t=5&c=${encode(q.category)}"
        "recommend" -> "http://everydayrec.service.kugou.com/guess_special_recommend"
        else -> "$KG_ROOT?is_ajax=1&cdn=cdn&t=${kgSort(q.order)}&c=${encode(q.category)}&p=${q.page.toLong() + 1}"
    }
    val tag = q.category.substringBefore('-')
    return when {
        q.category.substringAfter('-', "") == "43" && tag.isNotBlank() -> "http://mobileinterfaces.kuwo.cn/er.s?type=get_pc_qz_data&f=web&id=${encode(tag)}&prod=pc"
        tag.isNotEmpty() -> "$KW_ROOT/getTagPlayList?$KW_AUTH&pn=${q.page.toLong() + 1}&id=${encode(tag)}&rn=${q.size}"
        else -> "$KW_ROOT/getRcmPlayList?$KW_AUTH&&pn=${q.page.toLong() + 1}&rn=${q.size}&order=${if (q.order == "new") "new" else "hot"}"
    }
}

private suspend fun requestDiscoveryList(request: DiscoveryListRequest): JsonElement {
    val q = request.query
    val response = when (q.source) {
        "wy" -> NeteaseCrypto.weapiPost("/api/playlist/list", mapOf("cat" to q.category.ifBlank { "全部" },
            "order" to if (q.order == "new") "new" else "hot", "limit" to q.size, "offset" to q.page.toLong() * q.size, "total" to true))
        "qq" -> QQMusicUtils.zzcRequest(qqDiscoveryPlaylistPayload(q))
        "kg" -> if (request.kind == "recommend") HTTPUtils.post(discoveryListUrl(request),
            headers = mapOf("User-Agent" to "KuGou2012-8275-web_browser_event_handler"), body = mapOf("appid" to 1001,
                "clienttime" to 1566798337219L, "clientver" to 8275, "key" to "f1f93580115bb106680d2375f8032d96",
                "mid" to "21511157a05844bd085308bc76ef3343", "platform" to "pc", "userid" to "262643156", "return_min" to 6, "return_max" to 15))
            else HTTPUtils.get(discoveryListUrl(request))
        "kw" -> HTTPUtils.get(discoveryListUrl(request))
        else -> error("Unsupported discovery source")
    }
    if (!response.isSuccessful) throw IOException("Discovery request failed (${response.status})")
    return response.json(JsonElement::class.java) ?: throw IOException("Empty discovery response")
}

private fun JsonElement.obj() = takeIf { isJsonObject }?.asJsonObject
private fun JsonObject.objectAt(key: String) = get(key)?.obj()
private fun JsonObject.count(key: String) = chartText(key).toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, Int.MAX_VALUE.toDouble())?.toInt() ?: 0
internal fun discoveryPlayCount(value: String): Long {
    val match = Regex("^([\\d.]+)\\s*(亿|万|w|W)?").find(value.trim()) ?: return 0
    val count = match.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0
    val multiplier = when (match.groupValues[2]) { "亿" -> 1e8; "万", "w", "W" -> 1e4; else -> 1.0 }
    return (count * multiplier).roundToLong().coerceAtLeast(0)
}
private fun arrayObjects(value: JsonElement?) = value?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.obj() }.orEmpty()

internal fun playlistRows(source: String, rows: JsonElement?): List<PlayListInfoResult> = arrayObjects(rows).mapNotNull { p ->
    fun text(vararg keys: String): String = keys.firstNotNullOfOrNull { p.chartText(it).takeIf(String::isNotBlank) }.orEmpty()
    val rawId = when (source) { "wy" -> text("id"); "qq" -> text("tid", "dissid", "diss_id"); "kg" -> text("specialid", "special_id", "id"); else -> text("id") }
    if (rawId.isBlank()) return@mapNotNull null
    val id = when (source) { "kg" -> "id_$rawId"; "kw" -> "digest-${text("digest").ifBlank { "8" }}__$rawId"; else -> rawId }
    val title = when (source) { "wy" -> text("name"); "qq" -> text("title", "dissname", "diss_name"); "kg" -> text("specialname", "special_name", "name"); else -> text("name") }
    val cover = when (source) { "wy" -> text("coverImgUrl"); "qq" -> text("cover_url_medium", "imgurl", "logo").ifBlank { p.objectAt("cover")?.chartText("medium_url").orEmpty() }; else -> text("img", "imgurl", "pic") }
    val author = when (source) {
        "wy" -> p.objectAt("creator")?.chartText("nickname").orEmpty()
        "qq" -> p.objectAt("creator_info")?.chartText("nick").orEmpty().ifBlank { p.objectAt("creator")?.chartText("nick").orEmpty() }
            .ifBlank { p.objectAt("creator")?.chartText("name").orEmpty() }.ifBlank { text("nickname") }
        "kg" -> text("nickname", "username"); else -> text("uname")
    }
    val countKey = when (source) { "wy" -> "trackCount"; "qq" -> if (p.has("song_count")) "song_count" else "songnum"; "kg" -> if (p.has("songcount")) "songcount" else "song_count"; else -> "total" }
    val plays = when (source) { "wy" -> text("playCount"); "qq" -> text("access_num", "play_cnt", "listennum", "listen_num");
        "kg" -> text("play_count").takeIf { discoveryPlayCount(it) > 0 } ?: text("total_play_count"); else -> text("listencnt", "play_count") }
    PlayListInfoResult(source, cover.ifBlank { null }, discoveryClean(text("description", "desc", "introduction", "intro", "info")),
        discoveryClean(author), id, discoveryClean(title), p.count(countKey), discoveryPlayCount(plays))
}

internal fun parseDiscoveryPlaylists(q: PlaylistPageQuery, json: JsonElement, kgInfo: KgListInfo = KgListInfo(), recommend: List<PlayListInfoResult> = emptyList()): PlaylistSearchResult {
    if (isKuwoSection(q)) {
        check(json.isJsonArray && json.asJsonArray.size() > 0)
        val rows = com.google.gson.JsonArray()
        arrayObjects(json).flatMap { arrayObjects(it.get("list")) }.filter { it.chartText("type") in listOf("songlist", "list") }.forEach { rows.add(it) }
        return PlaylistSearchResult(q.source, false, q.page, q.size, playlistRows("kw", rows))
    }
    val root = json.obj() ?: error("Invalid discovery payload")
    var size = q.size
    val data: JsonObject
    val rows: JsonElement?
    val total: Int
    when (q.source) {
        "wy" -> { check(root.chartText("code") == "200"); data = root; rows = data.get("playlists"); total = data.count("total") }
        "qq" -> {
            val req = root.objectAt("req") ?: error("Missing QQ payload"); check(req.chartText("code") == "0")
            data = req.objectAt("data") ?: error("Missing QQ data")
            if (q.category.isNotEmpty()) {
                val content = data.objectAt("content")
                rows = com.google.gson.JsonArray().apply { arrayObjects(content?.get("v_item")).forEach { it.get("basic")?.let(::add) } }
                total = content?.count("total_cnt") ?: 0
            } else { rows = data.get("v_playlist"); total = data.count("total") }
        }
        "kg" -> { check(root.chartText("status") == "1"); rows = root.get("special_db"); check(rows?.isJsonArray == true); data = root; total = kgInfo.total }
        "kw" -> { check(root.chartText("code") == "200"); data = root.objectAt("data") ?: error("Missing Kuwo data"); rows = data.get("data"); total = data.count("total") }
        else -> error("Unsupported discovery parser")
    }
    val count = rows?.takeIf { it.isJsonArray }?.asJsonArray?.size() ?: 0
    val pageRows = playlistRows(q.source, rows)
    if (q.source == "kg") size = kgInfo.size.takeIf { it > 0 } ?: count
    val more = if (total > 0) (q.page.toLong() + 1) * size < total else when (q.source) {
        "wy" -> false; "qq" -> count == size; "kg" -> count > 0 && count >= size; else -> count >= size
    }
    return PlaylistSearchResult(q.source, more, q.page, size, recommend + pageRows, total.takeIf { it > 0 })
}
