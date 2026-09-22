package com.ikunshare.sound.platform.joox

import com.google.gson.JsonObject
import com.ikunshare.sound.model.CommonFields
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer

/**
 * JOOX 单曲数据类。所有行为（歌词 / 更新详情 / 分享）都在 [JooxProvider] 上。
 */
class JooxMusicItem(
    id: Long,
    title: String,
    artist: String,
    album: String,
    cover: String,
    duration: Long,
    qualities: Map<String, Quality>,
    tags: List<String>? = null,
    val mid: String,
    albumId: String? = null,
    singers: List<Singer>? = null,
    mvid: String? = null
) : MusicItem(id, title, artist, album, cover, duration, qualities, tags, albumId, singers, mvid) {

    override fun getTypeDiscriminator(): String = "joox"

    override fun toJson(): JsonObject = super.toJson().apply {
        addProperty("mid", mid)
    }

    companion object {
        fun fromJson(json: JsonObject): JooxMusicItem {
            val c: CommonFields = parseCommon(json)
            return JooxMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                mid = json.get("mid").asString,
                albumId = c.albumId, singers = c.singers, mvid = c.mvid
            )
        }
    }
}
