package com.ikunshare.sound.model

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class Singer(
    val name: String,
    val headimg: String? = null,
    val singerId: Long = 0,
    val extra: String? = null
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        headimg?.let { addProperty("headimg", it) }
        addProperty("singerId", singerId)
        extra?.let { addProperty("extra", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): Singer = Singer(
            name = json.get("name")?.asString ?: "",
            headimg = json.get("headimg")?.takeIf { !it.isJsonNull }?.asString,
            singerId = json.get("singerId")?.asLong ?: 0,
            extra = json.get("extra")?.takeIf { !it.isJsonNull }?.asString
        )
    }
}

/**
 * 纯数据基类：只承载字段 + 序列化。行为（取歌词 / 更新详情 / 分享）由对应 Provider 负责。
 * 平台子类位于 `platform/<source>/`，只需覆盖 [getTypeDiscriminator] 并追加平台特有字段。
 */
abstract class MusicItem(
    open val id: Long,
    open val title: String,
    open val artist: String,
    open val album: String,
    open val cover: String,
    open val duration: Long,
    open val qualities: Map<String, Quality>,
    open val tags: List<String>? = null,
    open val albumId: String? = null,
    open val singers: List<Singer>? = null,
    open val mvid: String? = null
) {
    /**
     * 序列化为 JSON，包含公共字段和 type 标识。
     * 子类覆盖此方法以追加平台特有字段。
     */
    open fun toJson(): JsonObject = JsonObject().apply {
        addProperty("type", getTypeDiscriminator())
        addProperty("id", id)
        addProperty("title", title)
        addProperty("artist", artist)
        addProperty("album", album)
        albumId?.let { addProperty("albumId", it) }
        addProperty("cover", cover)
        addProperty("duration", duration)
        add("qualities", Quality.mapToJson(qualities))
        tags?.let { tagList ->
            add("tags", JsonArray().apply { tagList.forEach { add(it) } })
        }
        singers?.let { singerList ->
            add("singers", JsonArray().apply { singerList.forEach { add(it.toJson()) } })
        }
        mvid?.let { addProperty("mvid", it) }
    }

    /** 类型标识，子类必须实现。写入 toJson 的 `type` 字段，反序列化时用来分发。 */
    abstract fun getTypeDiscriminator(): String

    /**
     * 列表 / Set / Map 用的唯一键。默认 `${type}_${id}`。
     *
     * 酷狗的 `id` 来自 `audioId.toLongOrNull() ?: 0`，同专辑可能撞号（不同 hash 同 audioId，
     * 或缺失 audioId 全为 0），所以 KugouMusicItem 会覆写带上 hash。
     */
    open val uniqueKey: String get() = "${getTypeDiscriminator()}_$id"

    companion object {
        /** internal format context, initialized by native layer */
        @JvmStatic
        internal var _fmt: Any? = null

        /**
         * 根据 JSON 中的 type 字段反序列化为对应子类。
         * 各平台的 companion object 负责反序列化，这里只做分发，避免 model 包反向依赖 platform 包。
         */
        fun fromJson(json: JsonObject): MusicItem {
            return when (val type = json.get("type").asString) {
                "qq" -> com.ikunshare.sound.platform.qq.QQMusicItem.fromJson(json)
                "netease", "wy" -> com.ikunshare.sound.platform.wy.NeteaseMusicItem.fromJson(json)
                "kugou", "kg" -> com.ikunshare.sound.platform.kg.KugouMusicItem.fromJson(json)
                "kuwo", "kw" -> com.ikunshare.sound.platform.kw.KuwoMusicItem.fromJson(json)
                "joox" -> com.ikunshare.sound.platform.joox.JooxMusicItem.fromJson(json)
                else -> throw IllegalArgumentException("Unknown MusicItem type: $type")
            }
        }

        fun fromJsonString(jsonString: String): MusicItem {
            val json = JsonParser.parseString(jsonString).asJsonObject
            return fromJson(json)
        }

        /** 公共字段解析，供各平台 fromJson 使用 */
        fun parseCommon(json: JsonObject): CommonFields {
            val id = json.get("id").asLong
            val title = json.get("title").asString
            val artist = json.get("artist").asString
            val album = json.get("album").asString
            val albumId = json.get("albumId")?.takeIf { !it.isJsonNull }?.asString
            val cover = json.get("cover").asString
            val duration = json.get("duration").asLong
            val qualities = Quality.mapFromJson(json.getAsJsonObject("qualities"))
            val tags = json.get("tags")?.takeIf { !it.isJsonNull }?.asJsonArray?.map { it.asString }
            val singers = json.get("singers")?.takeIf { !it.isJsonNull }?.asJsonArray?.map {
                Singer.fromJson(it.asJsonObject)
            }
            val mvid = json.get("mvid")?.takeIf { !it.isJsonNull }?.asString
            return CommonFields(
                id, title, artist, album, albumId,
                cover, duration, qualities, tags, singers, mvid
            )
        }
    }
}

/**
 * 为网易云封面 URL 追加 ?param=500y500 大小参数，与 QQ 音乐 R800x800 保持一致。
 * 非网易云 URL 原样返回。
 */
fun neteaseCoverUrl(url: String?): String? {
    if (url.isNullOrBlank()) return url
    if (!url.contains("music.126.net")) return url
    if ("param=" in url) return url
    return "${url}?param=800y800"
}

/** 反序列化时暂存公共字段 */
data class CommonFields(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String?,
    val cover: String,
    val duration: Long,
    val qualities: Map<String, Quality>,
    val tags: List<String>?,
    val singers: List<Singer>?,
    val mvid: String?
)
