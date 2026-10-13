package com.ikunshare.music.manager.discovery

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.platform.qq.utils.QQMusicUtils
import com.ikunshare.music.platform.wy.utils.NeteaseCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

data class ChartInfo(
    val source: String, val id: String, val name: String, val cover: String? = null,
    val total: Int = 0, val period: String = "", val updateFrequency: String = "",
    val description: String = "", val creator: String = ""
)

/** Shared catalog requests survive an individual page leaving; failures fall back like the desktop. */
class ChartCatalog internal constructor(
    private val transport: suspend (String) -> JsonObject = ::fetchChartCatalog,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Cached(val at: Long, val charts: List<ChartInfo>)
    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Cached>()
    private val pending = mutableMapOf<String, Deferred<List<ChartInfo>>>()

    suspend fun charts(source: String, refresh: Boolean = false): List<ChartInfo> {
        require(source in chartFallbacks) { "Unsupported discovery source: $source" }
        val task = mutex.withLock {
            if (refresh) cache.remove(source)
            cache[source]?.takeIf { clock() - it.at < 30 * 60_000 }?.let { return it.charts }
            pending[source] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val result = load(source)
                    mutex.withLock { cache[source] = Cached(clock(), result) }
                    result
                } finally {
                    withContext(NonCancellable) { mutex.withLock { pending.remove(source) } }
                }
            }.also { pending[source] = it }
        }
        return task.await()
    }

    private suspend fun load(source: String): List<ChartInfo> {
        if (source == "kg" || source == "kw") return chartFallbacks.getValue(source)
        repeat(2) {
            try {
                val charts = parseChartCatalog(source, transport(source))
                if (charts.isNotEmpty()) return charts
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* Semantic and transport failures both retry before fallback. */ }
        }
        return chartFallbacks.getValue(source)
    }
}

private suspend fun fetchChartCatalog(source: String): JsonObject {
    val response = if (source == "wy") NeteaseCrypto.weapiPost("/api/toplist/detail", emptyMap<String, Any>())
    else QQMusicUtils.zzcRequest(mapOf(
        "comm" to mapOf("ct" to 20, "cv" to 1859, "format" to "json", "uin" to 0),
        "req" to mapOf("module" to "musicToplist.ToplistInfoServer", "method" to "GetAll", "param" to emptyMap<String, Any>())
    ))
    if (!response.isSuccessful) throw IOException("Chart catalog request failed (${response.status})")
    return response.json(JsonObject::class.java) ?: throw IOException("Empty chart catalog response")
}

internal fun parseChartCatalog(source: String, json: JsonObject): List<ChartInfo> {
    fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    fun clean(text: String): String = text.replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").trim()
    fun objects(element: JsonElement?): List<JsonObject> = element?.takeIf { it.isJsonArray }?.asJsonArray
        ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
    val result = when (source) {
        "wy" -> {
            if (json.text("code") != "200") return emptyList()
            objects(json.get("list")).mapNotNull { obj ->
                val id = obj.text("id")
                val name = clean(obj.text("name"))
                if (id.isBlank() || name.isBlank()) null else ChartInfo(source, id, name,
                    obj.text("coverImgUrl").ifBlank { null }, obj.text("trackCount").toIntOrNull() ?: 0,
                    updateFrequency = obj.text("updateFrequency"), description = clean(obj.text("description")),
                    creator = clean(obj.get("creator")?.takeIf { it.isJsonObject }?.asJsonObject?.text("nickname").orEmpty()))
            }
        }
        "qq" -> {
            val req = json.get("req")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            if (req.text("code") != "0") return emptyList()
            val data = req.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
            objects(data.get("group")).flatMap { group -> objects(group.get("toplist")) }.mapNotNull { obj ->
                val id = obj.text("topId")
                val name = clean(obj.text("title")).removePrefix("巅峰榜·")
                if (id.isBlank() || id == "201" || name.isBlank()) null else ChartInfo(source, id,
                    if (name.endsWith("榜")) name else "${name}榜",
                    obj.text("frontPicUrl").ifBlank { obj.text("headPicUrl") }.ifBlank { null },
                    obj.text("totalNum").toIntOrNull() ?: 0, obj.text("period"), obj.text("updateTime"))
            }
        }
        else -> throw IllegalArgumentException("Unsupported online chart catalog: $source")
    }
    return result.distinctBy { it.id }
}
