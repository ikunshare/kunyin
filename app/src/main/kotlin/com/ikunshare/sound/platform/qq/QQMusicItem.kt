package com.ikunshare.sound.platform.qq

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.ikunshare.sound.model.CommonFields
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer

/**
 * QQ 音乐单曲数据类。所有行为（歌词 / 更新详情 / 分享 / 按 id/mid 查详情）都在 [QQProvider] 上。
 */
class QQMusicItem(
    id: Long,
    title: String,
    artist: String,
    album: String,
    cover: String,
    duration: Long,
    qualities: Map<String, Quality>,
    tags: List<String>? = null,
    val mid: String,
    val albumMid: String,
    val mediaMid: String,
    val vs: List<String>,
    val size_new: List<Int>,
    albumId: String? = null,
    singers: List<Singer>? = null,
    mvid: String? = null
) : MusicItem(id, title, artist, album, cover, duration, qualities, tags, albumId, singers, mvid) {

    override fun getTypeDiscriminator(): String = "qq"

    override fun toJson(): JsonObject = super.toJson().apply {
        addProperty("mid", mid)
        addProperty("albumMid", albumMid)
        addProperty("mediaMid", mediaMid)
        add("vs", JsonArray().apply { vs.forEach { add(it) } })
        add("size_new", JsonArray().apply { size_new.forEach { add(it) } })
    }

    companion object {
        fun fromJson(json: JsonObject): QQMusicItem {
            val c: CommonFields = parseCommon(json)
            return QQMusicItem(
                id = c.id, title = c.title, artist = c.artist, album = c.album,
                cover = c.cover, duration = c.duration, qualities = c.qualities, tags = c.tags,
                mid = json.get("mid").asString,
                albumMid = json.get("albumMid").asString,
                mediaMid = json.get("mediaMid").asString,
                vs = json.getAsJsonArray("vs").map { it.asString },
                size_new = json.getAsJsonArray("size_new").map { it.asInt },
                albumId = c.albumId, singers = c.singers, mvid = c.mvid
            )
        }

        /** 解析 QQ track_info JSON，生成 QQMusicItem。search/歌单/单曲查详情均复用。 */
        fun parseTrackInfo(item: JsonObject): QQMusicItem? {
            val id = item.get("id")?.asLong ?: return null
            val mid = item.get("mid")?.asString ?: ""
            val title = item.get("title")?.asString
                ?: item.get("name")?.asString ?: return null

            val singerArray = item.getAsJsonArray("singer")
            val singerList = singerArray
                ?.mapNotNull { singerElem ->
                    val singerObj = singerElem.takeIf { it.isJsonObject }?.asJsonObject
                        ?: return@mapNotNull null
                    val name = singerObj.get("name")?.takeIf { !it.isJsonNull }?.asString
                        ?: return@mapNotNull null
                    val singerMid = singerObj.get("mid")?.takeIf { !it.isJsonNull }?.asString
                    Singer(
                        name = name,
                        headimg = singerMid?.takeIf { it.isNotBlank() }
                            ?.let { "https://y.gtimg.cn/music/photo_new/T001R800x800M000$it.jpg" },
                        singerId = singerObj.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
                        extra = singerMid?.takeIf { it.isNotBlank() }
                    )
                }
                ?.takeIf { it.isNotEmpty() }
            val artist = singerList?.joinToString("、") { it.name } ?: "Unknown"

            val albumObj = item.getAsJsonObject("album")
            val albumName = albumObj?.get("name")?.asString ?: ""
            val albumMid = albumObj?.get("mid")?.asString ?: ""
            val albumId = albumObj?.get("id")
                ?.takeIf { !it.isJsonNull }
                ?.asLong?.toString()
                ?.takeIf { it != "0" }
                ?: albumMid.takeIf { it.isNotBlank() }

            val cover = if (albumMid.isNotEmpty()) {
                "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
            } else if (!singerList.isNullOrEmpty()) {
                singerList.first().headimg ?: ""
            } else ""

            val interval = item.get("interval")?.asLong ?: 0
            val duration = interval * 1000

            val fileObj = item.getAsJsonObject("file") ?: return null
            val mediaMid = fileObj.get("media_mid")?.asString ?: ""
            val qualities = mutableMapOf<String, Quality>()

            for ((sizeKey, qId, qName) in listOf(
                Triple("size_128mp3", "128k", "普通音质 128K"),
                Triple("size_320mp3", "320k", "高品音质 320K"),
                Triple("size_flac", "flac", "无损音质 FLAC"),
                Triple("size_hires", "hires", "无损音质 Hi-Res")
            )) {
                val size = fileObj.get(sizeKey)?.asLong ?: 0
                if (size > 0) qualities[qId] = Quality(qId, qName, size, mediaInfo = mediaMid)
            }

            val sizeNewArr = fileObj.getAsJsonArray("size_new")
            val vsArr = item.getAsJsonArray("vs")
            if (sizeNewArr != null && vsArr != null) {
                addSpecialQuality(qualities, sizeNewArr, vsArr, 0, 3, "master", "臻品母带")
                addSpecialQuality(qualities, sizeNewArr, vsArr, 1, 4, "atmos", "臻品全景声")
                addSpecialQuality(
                    qualities, sizeNewArr, vsArr, 2, 4, "atmos_plus", "臻品全景声 2.0"
                )
            }

            val vsList = vsArr?.map { it.asString } ?: emptyList()
            val sizeNewList = sizeNewArr?.map { it.asInt } ?: emptyList()

            val mvObj = item.getAsJsonObject("mv")
            val mvid = mvObj?.get("vid")?.asString?.takeIf { it.isNotEmpty() }

            return QQMusicItem(
                id = id, title = title, artist = artist, album = albumName,
                cover = cover, duration = duration, qualities = qualities,
                mid = mid, albumMid = albumMid, mediaMid = mediaMid,
                vs = vsList, size_new = sizeNewList,
                albumId = albumId, singers = singerList, mvid = mvid
            )
        }

        private fun addSpecialQuality(
            map: MutableMap<String, Quality>,
            sizeNewArr: JsonArray,
            vsArr: JsonArray,
            sizeIndex: Int,
            vsIndex: Int,
            id: String,
            name: String
        ) {
            if (sizeIndex < sizeNewArr.size() && vsIndex < vsArr.size()) {
                val size = sizeNewArr[sizeIndex].asLong
                val specialMediaMid = vsArr[vsIndex].asString
                if (size > 0 && specialMediaMid.isNotEmpty()) {
                    map[id] = Quality(id, name, size, mediaInfo = specialMediaMid)
                }
            }
        }
    }
}
