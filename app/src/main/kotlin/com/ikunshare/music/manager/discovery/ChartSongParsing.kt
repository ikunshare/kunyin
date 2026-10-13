package com.ikunshare.music.manager.discovery

import com.google.gson.JsonObject
import com.ikunshare.music.model.Quality
import com.ikunshare.music.model.Singer
import com.ikunshare.music.platform.kg.KugouMusicItem
import java.nio.ByteBuffer
import java.security.MessageDigest

internal fun JsonObject.chartText(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.chartLong(key: String): Long = chartText(key).toLongOrNull() ?: 0L

internal fun parseKgChartSong(obj: JsonObject): KugouMusicItem? {
    val hash = obj.chartText("hash").takeIf { it.isNotBlank() } ?: return null
    val qualities = linkedMapOf<String, Quality>()
    val hashes = mutableListOf(hash)
    fun quality(id: String, label: String, bitrate: Int, size: Long) {
        if (size > 0) qualities[id] = Quality(id, label, size, bitrate)
    }
    quality("128k", "普通音质 128K", 128, obj.chartLong("filesize"))
    listOf(Triple("320hash", "320filesize", "320k"), Triple("sqhash", "sqfilesize", "flac"),
        Triple("hash_high", "filesize_high", "hires")).forEach { (hashField, sizeField, id) ->
        val h = obj.chartText(hashField)
        if (h.isNotBlank() && !(id == "flac" && h == "0".repeat(32))) {
            hashes.add(h)
            quality(id, when (id) { "320k" -> "高品音质 320K"; "flac" -> "无损音质 FLAC"; else -> "无损音质 HiRes" },
                when (id) { "320k" -> 320; "flac" -> 2000; else -> 4000 }, obj.chartLong(sizeField))
        }
    }
    if (qualities.isEmpty()) return null
    fun clean(s: String) = s.replace(Regex("</?em>"), "").replace('/', ' ').trim()
    val authors = obj.get("authors")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
        val author = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
        clean(author.chartText("author_name")).takeIf { it.isNotEmpty() }?.let { Singer(name = it, singerId = author.chartLong("author_id")) }
    }.orEmpty()
    val artist = (authors.takeIf { it.isNotEmpty() }?.joinToString("、") { it.name }
        ?: obj.chartText("singername").ifBlank { obj.chartText("filename").substringBefore(" - ") })
        .let(::clean).split("、").take(5).joinToString("、")
    val audioId = obj.chartText("album_audio_id").ifBlank { obj.chartText("audio_id") }
    val numericId = audioId.toLongOrNull() ?: 0
    // SQLite and player routes require a Long; keep missing IDs stable across pages/imports.
    // Hash remains the playback identity, and the provider's audio ID is kept separately.
    val id = numericId.takeIf { it > 0 } ?: (ByteBuffer.wrap(
        MessageDigest.getInstance("SHA-256").digest(hash.toByteArray(Charsets.UTF_8))).long or Long.MIN_VALUE)
    val cover = obj.get("trans_param")?.takeIf { it.isJsonObject }?.asJsonObject?.chartText("union_cover")
        .orEmpty().ifBlank { obj.chartText("album_sizable_cover") }.replace("{size}", "480")
    return KugouMusicItem(id, clean(obj.chartText("songname")), artist,
        clean(obj.chartText("remark").ifBlank { obj.chartText("album_name") }), cover,
        obj.chartLong("duration") * 1000, qualities, mixsongmid = numericId, hash = hash,
        allHash = hashes, audioId = audioId, albumId = obj.chartText("album_id").takeIf { it.isNotBlank() && it != "0" },
        singers = authors.takeIf { it.isNotEmpty() } ?: artist.split("、").filter { it.isNotBlank() }.map { Singer(name = it) })
}
