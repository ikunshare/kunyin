package com.ikunshare.music.manager.discovery

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.platform.qq.utils.QQMusicUtils
import com.ikunshare.music.platform.wy.utils.NeteaseCrypto
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.*
import java.io.IOException

data class PlaylistCategory(val id: String, val name: String, val group: String)
data class PlaylistSortOption(val id: String, val name: String)

val discoveryPlaylistSorts = mapOf(
    "wy" to listOf(PlaylistSortOption("hot", "最热")),
    "qq" to listOf(PlaylistSortOption("5", "最热"), PlaylistSortOption("2", "最新")),
    "kg" to listOf(PlaylistSortOption("5", "推荐"), PlaylistSortOption("6", "最热"), PlaylistSortOption("7", "最新"),
        PlaylistSortOption("3", "热藏"), PlaylistSortOption("8", "飙升")),
    "kw" to listOf(PlaylistSortOption("hot", "最热"), PlaylistSortOption("new", "最新"))
)
fun canSortDiscoveryCategory(source: String) = source == "wy" || source == "kg"

internal data class CategoryRequest(val source: String, val hot: Boolean = false)

internal class PlaylistCategories(
    private val transport: suspend (CategoryRequest) -> JsonObject = ::requestCategories,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private val memo = DiscoveryMemo<String, List<PlaylistCategory>>(60 * 60_000, scope, clock)

    suspend fun categories(source: String, refresh: Boolean = false): List<PlaylistCategory> {
        require(source in discoveryPlaylistSorts) { "Unsupported discovery source" }
        return memo.get(source, refresh) { coroutineScope {
            val hot = if (source == "wy" || source == "kw") async { optionalHot(source) } else null
            val main = fetch(CategoryRequest(source), if (source == "kg" || source == "kw") 3 else 1)
            parsePlaylistCategories(source, main, hot?.await())
        } }
    }

    private suspend fun optionalHot(source: String): JsonObject? = try {
        fetch(CategoryRequest(source, true), if (source == "kw") 2 else 1)
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { null }

    private suspend fun fetch(request: CategoryRequest, attempts: Int): JsonObject {
        var failure: Exception? = null
        repeat(attempts) {
            try {
                val json = transport(request)
                when (request.source) {
                    "wy" -> check(json.chartText("code") == "200")
                    "qq" -> { check(json.getAsJsonObject("req")?.chartText("code") == "0"); check(json.getAsJsonObject("req")?.get("data")?.isJsonObject == true) }
                    "kg" -> { check(json.chartText("status") == "1"); check(json.get("data")?.isJsonObject == true) }
                    "kw" -> { check(json.chartText("code") == "200"); if (!request.hot) check(json.get("data")?.isJsonArray == true) }
                }
                return json
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error }
        }
        throw IOException("${request.source} 歌单分类暂时无法加载，请重试", failure)
    }
}

private suspend fun requestCategories(request: CategoryRequest): JsonObject {
    val response = when (request.source) {
        "wy" -> NeteaseCrypto.weapiPost(if (request.hot) "/api/playlist/hottags" else "/api/playlist/catalogue", emptyMap<String, Any>())
        "qq" -> QQMusicUtils.zzcRequest(mapOf(
            "comm" to mapOf("ct" to 20, "cv" to 1859, "format" to "json", "uin" to 0),
            "req" to mapOf("module" to "playlist.PlaylistAllCategoriesServer", "method" to "get_all_categories", "param" to mapOf("qq" to ""))))
        "kg" -> HTTPUtils.get("http://www2.kugou.kugou.com/yueku/v9/special/getSpecial?is_smarty=1&")
        "kw" -> HTTPUtils.get("http://wapi.kuwo.cn/api/pc/classify/playlist/" +
            if (request.hot) "getRcmTagList?loginUid=0&loginSid=0&appUid=76039576"
            else "getTagList?cmd=rcm_keyword_playlist&user=0&prod=kwplayer_pc_9.0.5.0&vipver=9.0.5.0&source=kwplayer_pc_9.0.5.0&loginUid=0&loginSid=0&appUid=76039576")
        else -> error("Unsupported category source")
    }
    if (!response.isSuccessful) throw IOException("Category request failed (${response.status})")
    return response.json(JsonObject::class.java) ?: throw IOException("Empty category response")
}

internal fun discoveryClean(value: String) = value.replace(Regex("<[^>]*>"), "")
    .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
private fun objects(value: JsonElement?): List<JsonObject> = value?.takeIf { it.isJsonArray }?.asJsonArray
    ?.mapNotNull { it.takeIf { item -> item.isJsonObject }?.asJsonObject }.orEmpty()

internal fun parsePlaylistCategories(source: String, main: JsonObject, hot: JsonObject?): List<PlaylistCategory> {
    val out = mutableListOf<PlaylistCategory>()
    fun add(id: String, name: String, group: String) {
        val label = discoveryClean(name)
        if (id.isNotBlank() && label.isNotBlank()) out.add(PlaylistCategory(id, label, discoveryClean(group)))
    }
    when (source) {
        "wy" -> {
            objects(hot?.get("tags")).forEach { tag ->
                val name = tag.get("playlistTag")?.takeIf { it.isJsonObject }?.asJsonObject?.chartText("name")
                    .orEmpty().ifBlank { tag.chartText("name") }.let(::discoveryClean)
                add(name, name, "热门")
            }
            val groups = main.get("categories")?.takeIf { it.isJsonObject }?.asJsonObject
            objects(main.get("sub")).forEach { tag -> add(tag.chartText("name"), tag.chartText("name"),
                groups?.get(tag.chartText("category"))?.takeIf { it.isJsonPrimitive }?.asString ?: "分类") }
        }
        "qq" -> {
            val data = main.getAsJsonObject("req").getAsJsonObject("data")
            objects(data.get("v_group")).forEach { group -> objects(group.get("v_item")).forEach {
                add(it.chartText("id"), it.chartText("name"), group.chartText("group_name"))
            } }
        }
        "kg" -> {
            val data = main.getAsJsonObject("data")
            val hotTag = data.get("hotTag")?.takeIf { it.isJsonObject }?.asJsonObject
            if (hotTag?.chartText("status") == "1") {
                val tags = hotTag.get("data")
                val rows = if (tags?.isJsonObject == true) tags.asJsonObject.entrySet().mapNotNull { it.value.takeIf { value -> value.isJsonObject }?.asJsonObject } else objects(tags)
                rows.forEach { add(it.chartText("special_id"), it.chartText("special_name"), "热门") }
            }
            data.get("tagids")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (group, info) ->
                objects(info.takeIf { it.isJsonObject }?.asJsonObject?.get("data")).forEach { add(it.chartText("id"), it.chartText("name"), group) }
            }
        }
        "kw" -> {
            fun tag(tag: JsonObject, group: String) {
                val id = tag.chartText("id"); val digest = tag.chartText("digest")
                if (id.isNotBlank() && digest.isNotBlank()) add("$id-$digest", tag.chartText("name"), group)
            }
            objects(hot?.get("data")).firstOrNull()?.let { group -> objects(group.get("data")).forEach { tag(it, "热门") } }
            objects(main.get("data")).forEach { group -> objects(group.get("data")).forEach { tag(it, group.chartText("name")) } }
        }
        else -> error("Unsupported category parser")
    }
    return out
}
