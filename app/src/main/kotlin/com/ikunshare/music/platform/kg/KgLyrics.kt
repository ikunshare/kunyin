package com.ikunshare.music.platform.kg

import android.util.Base64
import com.google.gson.JsonObject
import com.ikunshare.music.model.Lyric
import com.ikunshare.music.utils.HTTPUtils
import com.ikunshare.music.utils.lyric.LrcParser
import com.ikunshare.music.utils.lyric.toLyric
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.net.URLEncoder
import java.security.MessageDigest

internal class KgLyrics(
    private val transport: suspend (String, Map<String, String>) -> JsonObject? = { url, headers ->
        HTTPUtils.getCancellable(url, headers).takeIf { it.isSuccessful }?.json(JsonObject::class.java)
    },
    private val decode: (String) -> Lyric = { content -> LrcParser.parseKg(Base64.decode(content, Base64.DEFAULT)).toLyric() },
    private val keyword: (String, String) -> String = LyricSearchText::keyword
) {
    private val hosts = listOf("lyrics2.kugou.com", "lyrics.kugou.com", "krcsretry.kugou.com")

    suspend fun search(
        keyword: String, duration: Long, hash: String? = null, audioId: String? = null, manual: Boolean = true
    ): List<KgProvider.KgLyricCandidate> {
        if (keyword.isBlank()) return emptyList()
        val params = linkedMapOf("album_audio_id" to audioId.orEmpty().ifBlank { "0" }, "appid" to "1005",
            "clientver" to "20744", "duration" to duration.toString(), "keyword" to keyword, "lrctxt" to "1",
            "man" to if (manual) "yes" else "no", "query_copyright" to "1", "vocab" to "0")
        if (!hash.isNullOrBlank()) params["hash"] = hash.lowercase()
        val response = request("v1/search", params, 200) ?: return emptyList()
        val candidates = response.get("candidates")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return candidates.mapNotNull { element ->
            if (!element.isJsonObject) return@mapNotNull null
            val row = element.asJsonObject
            val access = row.text("accesskey")
            val id = row.text("id").ifBlank { row.text("download_id") }
            if (access.isBlank() || id.isBlank()) null else KgProvider.KgLyricCandidate(access, id,
                row.text("contenttype").toIntOrNull() ?: 0, row.text("song"), row.text("singer"), row.text("language"),
                row.text("duration").toLongOrNull() ?: 0, row.text("score").toIntOrNull() ?: 0)
        }
    }

    suspend fun download(accessKey: String, downloadId: String, contenttype: Int): Lyric? {
        val params = linkedMapOf("accesskey" to accessKey, "appid" to "1005", "clientver" to "20669",
            "contenttype" to contenttype.toString(), "download_id" to downloadId)
        val response = request("v2/download", params, 1) ?: return null
        val content = response.get("data")?.takeIf { it.isJsonObject }?.asJsonObject?.text("content").orEmpty()
        if (content.isBlank()) return null
        return try {
            currentCoroutineContext().ensureActive()
            decode(content).takeIf { it.hasMainLyric() }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    suspend fun get(item: KugouMusicItem): Lyric {
        if (!item.lyricAccessKey.isNullOrBlank() && !item.lyricDownloadId.isNullOrBlank()) {
            download(item.lyricAccessKey, item.lyricDownloadId, 0)?.let { return it }
        }
        return firstAvailable(search(keyword(item.title, item.artist), item.duration, item.hash, item.audioId, manual = false))
    }

    suspend fun fallback(title: String, artist: String, duration: Long): Lyric =
        firstAvailable(search(keyword(title, artist), duration, manual = true))

    private suspend fun firstAvailable(candidates: List<KgProvider.KgLyricCandidate>): Lyric {
        candidates.take(3).forEach { candidate ->
            currentCoroutineContext().ensureActive()
            download(candidate.accessKey, candidate.downloadId, candidate.contenttype)?.let { return it }
        }
        return Lyric()
    }

    private suspend fun request(path: String, params: Map<String, String>, success: Int): JsonObject? {
        val secret = "OIlwieks28dk2k092lksi2UIkp"
        val raw = secret + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + secret
        val signature = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val query = params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8").replace("+", "%20")}" }
        val headers = mapOf("clienttime" to (System.currentTimeMillis() / 1000).toString(), "mid" to "-", "dfid" to "-")
        for (host in hosts) {
            currentCoroutineContext().ensureActive()
            val response = try { transport("https://$host/$path?$query&signature=$signature", headers) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            if (response?.text("status") == success.toString() && response.text("error_code") != "20006") return response
        }
        return null
    }
}

private fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun Lyric.hasMainLyric(): Boolean = lrc.isNotBlank() || char.isNotBlank()
