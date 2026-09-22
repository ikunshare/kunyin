package com.ikunshare.sound.platform.kw

import com.google.gson.JsonObject
import com.ikunshare.sound.model.CommonFields
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer

/**
 * 酷我音乐单曲数据类。所有行为（歌词 / 更新详情 / 分享 / 按 id 查详情）都在 [KwProvider] 上。
 *
 * [setCover] 允许搜索后异步补封面（酷我搜索接口不直接返回封面）。
 */
class KuwoMusicItem(
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
    mvid: String? = null
) : MusicItem(id, title, artist, album, cover, duration, qualities, tags, albumId, singers, mvid) {

    private var _cover: String = cover
    override val cover: String get() = _cover
    fun setCover(url: String) {
        _cover = url
    }

    override fun getTypeDiscriminator(): String = "kw"

    companion object {
        fun fromJson(json: JsonObject): KuwoMusicItem {
            val c: CommonFields = parseCommon(json)
            return KuwoMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                albumId = c.albumId, singers = c.singers, mvid = c.mvid
            )
        }
    }
}
