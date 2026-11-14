package com.ikunshare.music.platform.am

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.model.CommonFields
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.Quality
import com.ikunshare.music.model.Singer
import com.ikunshare.music.model.qualityDisplayName
import kotlin.math.roundToLong

class AppleMusicItem(
    id: Long,
    title: String,
    artist: String,
    album: String,
    cover: String,
    duration: Long,
    qualities: Map<String, Quality>,
    tags: List<String>? = null,
    albumId: String? = null,
    singers: List<Singer>? = null,
    mvid: String? = null,
    val previewUrl: String? = null
) : MusicItem(id, title, artist, album, cover, duration, qualities, tags, albumId, singers, mvid) {
    override fun getTypeDiscriminator(): String = "am"

    override fun toJson(): JsonObject = super.toJson().apply {
        previewUrl?.let { addProperty("previewUrl", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): AppleMusicItem {
            val c: CommonFields = parseCommon(json)
            return AppleMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                albumId = c.albumId, singers = c.singers, mvid = c.mvid,
                previewUrl = json.get("previewUrl")?.takeIf { it.isJsonPrimitive }?.asString
            )
        }

        fun parse(raw: JsonObject): AppleMusicItem? {
            val id = number(raw.first("songId", "id")).toLong()
            val title = text(raw.first("songName", "name", "title"))
            if (id <= 0L || title.isEmpty()) return null

            val singers = raw.get("artists")?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
                val artist = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val name = text(artist.get("name"))
                if (name.isEmpty()) return@mapNotNull null
                Singer(
                    name = name,
                    headimg = text(artist.get("cover")).takeIf { it.isNotEmpty() },
                    singerId = number(artist.get("id")).toLong()
                )
            }.orEmpty()
            val qualities = linkedMapOf<String, Quality>()
            raw.get("quality")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (key, value) ->
                val detail = value.takeIf { it.isJsonObject }?.asJsonObject
                qualities[key] = Quality(
                    id = key,
                    name = qualityDisplayName(key, "am"),
                    filesize = number(detail?.get("filesize") ?: value).toLong(),
                    bitrate = number(detail?.get("bitrate")).toInt().takeIf { it != 0 }
                )
            }
            if (qualities.isEmpty()) {
                qualities["320k"] = Quality("320k", qualityDisplayName("320k", "am"), 0)
            }
            val albumId = raw.get("albumId")?.takeUnless { it.isJsonNull }?.let(::text)?.takeIf { it.isNotEmpty() }
            return AppleMusicItem(
                id = id,
                title = title,
                artist = text(raw.first("artistName", "artist")).ifEmpty {
                    singers.joinToString("、") { it.name }
                },
                album = text(raw.first("albumName", "album")),
                albumId = albumId,
                cover = text(raw.first("coverUrl", "cover")),
                duration = durationMs(raw.first("duration", "durationInMillis")),
                qualities = qualities,
                singers = singers.takeIf { it.isNotEmpty() },
                previewUrl = text(raw.get("previewUrl")).takeIf { it.isNotEmpty() }
            )
        }

        internal fun durationMs(value: JsonElement?): Long {
            if (value?.isJsonPrimitive == true && value.asJsonPrimitive.isString) {
                val raw = value.asString
                if (':' in raw) {
                    val parts = raw.split(':').map { it.toDoubleOrNull() ?: return 0L }
                    if (parts.any { !it.isFinite() }) return 0L
                    return (parts.fold(0.0) { seconds, part -> seconds * 60.0 + part } * 1000.0).roundToLong()
                }
            }
            val number = number(value)
            return (if (number > 1000.0) number else number * 1000.0).roundToLong()
        }

        private fun JsonObject.first(vararg keys: String): JsonElement? =
            keys.firstNotNullOfOrNull { key -> get(key)?.takeUnless { it.isJsonNull } }

        private fun text(value: JsonElement?): String = when {
            value == null || value.isJsonNull -> ""
            value.isJsonPrimitive -> value.asString
            else -> value.toString()
        }

        private fun number(value: JsonElement?): Double =
            value?.takeIf { it.isJsonPrimitive }?.asString?.toDoubleOrNull()
                ?.takeIf { it.isFinite() } ?: 0.0
    }
}
