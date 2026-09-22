package com.ikunshare.sound.platform.wy

import com.google.gson.JsonObject
import com.ikunshare.sound.model.CommonFields
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer
import com.ikunshare.sound.platform.wy.utils.safeGetArray
import com.ikunshare.sound.platform.wy.utils.safeGetObject
import com.ikunshare.sound.platform.wy.utils.safeLong
import com.ikunshare.sound.platform.wy.utils.safeString

/**
 * 网易云音乐单曲数据类。所有行为（歌词 / 更新详情 / 分享 / 按 id 查详情）都在 [WyProvider] 上。
 */
class NeteaseMusicItem(
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

    override fun getTypeDiscriminator(): String = "wy"

    companion object {
        fun fromJson(json: JsonObject): NeteaseMusicItem {
            val c: CommonFields = parseCommon(json)
            return NeteaseMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                albumId = c.albumId, singers = c.singers, mvid = c.mvid
            )
        }

        /**
         * 解析网易云歌曲 JSON（song detail / cloudsearch 返回的 songs 元素）。
         * 音质 ID 统一: l→128k, h→320k, sq→flac, hr→hires。
         */
        fun parseTrackInfo(song: JsonObject): NeteaseMusicItem? {
            val id = song.get("id").safeLong(-1)
            if (id == -1L) return null
            val name = song.get("name").safeString() ?: return null

            val artists = song.safeGetArray("ar")
            val singerList = artists
                ?.mapNotNull {
                    val artistObj =
                        it.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val artistName = artistObj.get("name").safeString() ?: return@mapNotNull null
                    Singer(
                        name = artistName,
                        headimg = artistObj.get("picUrl").safeString(),
                        singerId = artistObj.get("id").safeLong(),
                        extra = null
                    )
                }
                ?.takeIf { it.isNotEmpty() }
            val artist = singerList?.joinToString("、") { it.name } ?: "Unknown"

            val albumObj = song.safeGetObject("al")
            val albumName = albumObj?.get("name").safeString() ?: ""
            val albumId = albumObj?.get("id")?.let {
                if (it.isJsonNull) null else it.asLong.toString()
            }
            val cover = albumObj?.get("picUrl").safeString() ?: ""

            val duration = song.get("dt").safeLong()
            val mvid = song.get("mv").safeLong().takeIf { it != 0L }?.toString()

            val qualities = mutableMapOf<String, Quality>()
            song.safeGetObject("l")?.let { q ->
                val size = q.get("size").safeLong()
                if (size > 0) qualities["128k"] = Quality("128k", "普通音质 128K", size)
            }
            song.safeGetObject("h")?.let { q ->
                val size = q.get("size").safeLong()
                if (size > 0) qualities["320k"] = Quality("320k", "高品音质 320K", size)
            }
            song.safeGetObject("sq")?.let { q ->
                val size = q.get("size").safeLong()
                if (size > 0) qualities["flac"] = Quality("flac", "无损音质 FLAC", size)
            }
            song.safeGetObject("hr")?.let { q ->
                val size = q.get("size").safeLong()
                if (size > 0) qualities["hires"] = Quality("hires", "无损音质 HiRes", size)
            }

            return NeteaseMusicItem(
                id = id, title = name, artist = artist, album = albumName,
                cover = cover, duration = duration, qualities = qualities,
                albumId = albumId, singers = singerList, mvid = mvid
            )
        }

        /**
         * 从 /api/song/music/detail/get 响应中提取高级音质信息，补充到 item。
         * jm → master, je → effect_plus, sk → effect
         */
        fun enrichFromQualityDetail(
            item: NeteaseMusicItem,
            response: JsonObject
        ): NeteaseMusicItem {
            try {
                val data = response.safeGetObject("data") ?: return item
                val mutableQualities = item.qualities.toMutableMap()

                data.safeGetObject("jm")?.let { q ->
                    val size = q.get("size").safeLong()
                    if (size > 0) mutableQualities["master"] = Quality("master", "鲸云母带", size)
                }
                data.safeGetObject("je")?.let { q ->
                    val size = q.get("size").safeLong()
                    if (size > 0) mutableQualities["atmos_plus"] =
                        Quality("atmos_plus", "沉浸环绕声", size)
                }
                data.safeGetObject("sk")?.let { q ->
                    val size = q.get("size").safeLong()
                    if (size > 0) mutableQualities["atmos"] = Quality("atmos", "高清臻音", size)
                }

                if (mutableQualities.size == item.qualities.size) return item

                return NeteaseMusicItem(
                    id = item.id, title = item.title, artist = item.artist,
                    album = item.album, cover = item.cover, duration = item.duration,
                    qualities = mutableQualities, tags = item.tags,
                    albumId = item.albumId, singers = item.singers, mvid = item.mvid
                )
            } catch (_: Exception) {
                return item
            }
        }
    }
}
