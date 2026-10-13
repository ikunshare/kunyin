package com.ikunshare.music.platform.qq

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.tool.cache.Cache
import com.ikunshare.music.tool.cache.DiskCache
import com.ikunshare.music.tool.cache.LruCacheWrapper
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

private const val APP_ID = "2000000638"
private const val APP_KEY = "ZKFKQDruNqOogrgL"
private fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 255) }

internal fun qqCoverUrl(mids: List<String>, timestamp: Long = System.currentTimeMillis() / 1000): String {
    val params = linkedMapOf("app_id" to APP_ID, "chid" to "listen_together", "client_ip" to "127.0.0.1",
        "ct" to "iOS", "cv" to "", "device_id" to "1", "enc_vkey_url" to "0", "login_type" to "1",
        "opi_cmd" to "fcg_music_custom_get_song_info_batch.fcg", "opi_protocol_version" to "0",
        "qqmusic_access_token" to "", "qqmusic_open_appid" to APP_ID, "qqmusic_open_id" to "",
        "sign_version" to "v2", "song_mid" to mids.joinToString(","), "song_token" to "", "timestamp" to timestamp.toString())
    params["tech_version"] = "sdk|${md5("sdk_${APP_KEY}_$timestamp")}"
    params["sign"] = md5(params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" } + "_$APP_KEY")
    return "https://qplaycloud.y.qq.com/rpc_proxy/fcgi-bin/music_open_api.fcg?" +
        params.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }
}

private fun coverCache(): Cache<String, String> = MusicApplication.instance?.let {
    DiskCache(File(it.filesDir, "qq-cover-cache.json"), 2000, String::class.java, Gson())
} ?: LruCacheWrapper(2000)

/** Album-less QQ tracks need OPI picMid covers; failures are optional and are never cached. */
internal class QQCoverEnrichment(
    private val transport: suspend (List<String>) -> JsonObject? = { mids ->
        HTTPUtils.getWithTimeout(qqCoverUrl(mids), 8000).takeIf { it.isSuccessful }?.json(JsonObject::class.java)
    },
    private val cacheFactory: () -> Cache<String, String> = ::coverCache,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val cache by lazy(cacheFactory)
    private val mutex = Mutex()
    private val slots = Semaphore(3)

    suspend fun fill(items: List<MusicItem>) {
        val targets = items.filterIsInstance<QQMusicItem>().filter { it.albumMid.isBlank() && it.mid.isNotBlank() }.groupBy { it.mid }
        if (targets.isEmpty()) return
        // Shared workers finish caching even when one list leaves; the mutex avoids duplicate lookups.
        val covers = scope.async {
            mutex.withLock {
                val missing = targets.keys.filter { cache.get(it) == null }
                coroutineScope {
                    missing.chunked(50).map { batch -> async {
                        slots.withPermit { fetch(batch) }
                    } }.awaitAll().filterNotNull().forEach { values ->
                        if (cache is DiskCache<String>) (cache as DiskCache<String>).putAll(values)
                        else values.forEach { (mid, cover) -> cache.put(mid, cover) }
                    }
                }
                targets.keys.associateWith { cache.get(it).orEmpty() }
            }
        }.await()
        covers.forEach { (mid, cover) -> if (cover.isNotBlank()) targets[mid]?.forEach { it.setCover(cover) } }
    }

    private suspend fun fetch(mids: List<String>): Map<String, String>? {
        try {
            val json = transport(mids) ?: return null
            if (json.get("ret")?.takeIf { it.isJsonPrimitive }?.asString != "0") return null
            val rows = json.get("songlist")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val result = mids.associateWith { "" }.toMutableMap()
            rows.forEach { row ->
                if (!row.isJsonObject) return@forEach
                val obj = row.asJsonObject
                fun text(key: String) = obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val mid = text("song_mid")
                if (mid in result) result[mid] = text("album_pic_500x500").ifBlank { text("album_pic") }
                    .replace(Regex("^http:"), "https:").replace(Regex("R\\d+x\\d+M000"), "R800x800M000")
            }
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return null }
    }

    companion object { val shared by lazy { QQCoverEnrichment() } }
}
