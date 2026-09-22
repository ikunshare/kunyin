package com.ikunshare.sound.platform.kg

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ikunshare.sound.model.CommonFields
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer

/**
 * 酷狗音乐单曲数据类。所有行为（歌词 / 更新详情 / 分享）都在 [KgProvider] 上。
 *
 * id:      audioId.toLongOrNull() ?: 0 —— 仅作 UI Long key 使用
 * hash:    酷狗播放/歌词接口的核心标识
 * audioId: 酷狗接口里的 Audioid / album_audio_id，封面/付费接口要用
 */
class KugouMusicItem(
    id: Long,
    title: String,
    artist: String,
    album: String,
    cover: String,
    duration: Long,
    qualities: Map<String, Quality>,
    tags: List<String>? = null,
    val mixsongmid: Long,
    val hash: String,
    val allHash: List<String>,
    val audioId: String = "",
    albumId: String? = null,
    singers: List<Singer>? = null,
    mvid: String? = null,
    /** 歌词直链重定向：直接指定 accesskey，跳过 song_search 步骤。 */
    val lyricAccessKey: String? = null,
    /** 歌词直链重定向：直接指定 download_id，跳过 song_search 步骤。 */
    val lyricDownloadId: String? = null
) : MusicItem(id, title, artist, album, cover, duration, qualities, tags, albumId, singers, mvid) {

    private var _cover: String = cover
    override val cover: String get() = _cover
    fun setCover(url: String) {
        _cover = url
    }

    private var _qualities: Map<String, Quality> = qualities
    override val qualities: Map<String, Quality> get() = _qualities
    fun replaceQualities(map: Map<String, Quality>) {
        _qualities = map
    }

    override fun getTypeDiscriminator(): String = "kg"

    override val uniqueKey: String
        get() = when {
            hash.isNotEmpty() -> "kg_$hash"
            !lyricDownloadId.isNullOrEmpty() -> "kg_dl_$lyricDownloadId"
            else -> "kg_$id"
        }

    override fun toJson(): JsonObject = super.toJson().apply {
        addProperty("mixsongmid", mixsongmid)
        addProperty("hash", hash)
        add("allHash", JsonArray().apply { allHash.forEach { add(it) } })
        if (audioId.isNotEmpty()) addProperty("audioId", audioId)
        lyricAccessKey?.let { addProperty("lyricAccessKey", it) }
        lyricDownloadId?.let { addProperty("lyricDownloadId", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): KugouMusicItem {
            val c: CommonFields = parseCommon(json)
            return KugouMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                mixsongmid = json.get("mixsongmid").asLong,
                hash = json.get("hash").asString,
                allHash = json.getAsJsonArray("allHash").map { it.asString },
                audioId = json.get("audioId")?.takeIf { !it.isJsonNull }?.asString ?: "",
                albumId = c.albumId, singers = c.singers, mvid = c.mvid,
                lyricAccessKey = json.get("lyricAccessKey")?.takeIf { !it.isJsonNull }?.asString,
                lyricDownloadId = json.get("lyricDownloadId")?.takeIf { !it.isJsonNull }?.asString
            )
        }
    }
}
