package com.ikunshare.sound.sync

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.platform.kg.KugouMusicItem
import com.ikunshare.sound.platform.kw.KuwoMusicItem
import com.ikunshare.sound.platform.qq.QQMusicItem
import com.ikunshare.sound.platform.wy.NeteaseMusicItem

/**
 * LX Music 歌曲/歌单在线同步使用的 JSON 编解码。与 `.lxmc` 导入/导出格式同源。
 *
 * 解码（LX → MusicItem）：支持 tx/kg/wy/kw 四家。输入若缺字段可能返回 null，调用方需过滤。
 * 编码（MusicItem → LX）：生成与 LX 客户端兼容的歌曲项；本地缺失的字段会以空字符串/占位给出。
 */
object LxCodec {

    // ─────────────── 解码 ───────────────

    fun songFromJson(o: JsonObject): MusicItem? {
        val source = o.get("source")?.asString ?: return null
        val title = o.get("name")?.asString ?: return null
        val artist = o.get("singer")?.asString ?: "Unknown"
        val duration = parseInterval(o.get("interval")?.asString)
        val meta = o.getAsJsonObject("meta") ?: return null
        return when (source) {
            "tx" -> buildTx(meta, title, artist, duration)
            "kg" -> buildKg(meta, title, artist, duration)
            "wy" -> buildWy(meta, title, artist, duration)
            "kw" -> buildKw(meta, title, artist, duration)
            else -> null
        }
    }

    private fun buildTx(
        m: JsonObject,
        title: String,
        artist: String,
        duration: Long
    ): QQMusicItem? {
        val id = m.get("id")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        val mid = m.get("songId")?.asString ?: ""
        val albumMid = m.get("albumMid")?.asString ?: ""
        val mediaMid = m.get("strMediaMid")?.asString ?: ""
        val albumName = m.get("albumName")?.asString ?: ""
        val cover = m.get("picUrl")?.asString ?: ""
        return QQMusicItem(
            id = id, title = title, artist = artist, album = albumName,
            cover = cover, duration = duration,
            qualities = parseTxQualities(m, mediaMid),
            mid = mid, albumMid = albumMid, mediaMid = mediaMid,
            vs = emptyList(), size_new = emptyList(),
            albumId = asString(m.get("albumId"))
        )
    }

    private fun buildKg(
        m: JsonObject,
        title: String,
        artist: String,
        duration: Long
    ): KugouMusicItem? {
        val id = m.get("songId")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        val qs = m.getAsJsonObject("_qualitys")
        val baseHash =
            m.get("hash")?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
                ?: qs?.getAsJsonObject("128k")?.get("hash")?.asString
                ?: return null
        val allHashes = LinkedHashSet<String>()
        qs?.entrySet()?.forEach { e ->
            val h = e.value.asJsonObject.get("hash")?.asString
            if (!h.isNullOrBlank()) allHashes += h
        }
        allHashes += baseHash
        return KugouMusicItem(
            id = id, title = title, artist = artist,
            album = m.get("albumName")?.asString ?: "",
            cover = m.get("picUrl")?.asString ?: "",
            duration = duration,
            qualities = parseKgQualities(qs),
            mixsongmid = 0L, hash = baseHash,
            allHash = allHashes.toList(), audioId = "",
            albumId = asString(m.get("albumId"))
        )
    }

    private fun buildWy(
        m: JsonObject,
        title: String,
        artist: String,
        duration: Long
    ): NeteaseMusicItem? {
        val id = m.get("songId")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        return NeteaseMusicItem(
            id = id, title = title, artist = artist,
            album = m.get("albumName")?.asString ?: "",
            cover = m.get("picUrl")?.asString ?: "",
            duration = duration,
            qualities = parseStdQualities(m.getAsJsonObject("_qualitys")),
            albumId = asString(m.get("albumId"))
        )
    }

    private fun buildKw(
        m: JsonObject,
        title: String,
        artist: String,
        duration: Long
    ): KuwoMusicItem? {
        val id = m.get("songId")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        return KuwoMusicItem(
            id = id, title = title, artist = artist,
            album = m.get("albumName")?.asString ?: "",
            cover = m.get("picUrl")?.asString ?: "",
            duration = duration,
            qualities = parseStdQualities(m.getAsJsonObject("_qualitys")),
            albumId = asString(m.get("albumId"))
        )
    }

    private fun parseTxQualities(m: JsonObject, mediaMid: String): Map<String, Quality> {
        val out = LinkedHashMap<String, Quality>()
        val qs = m.getAsJsonObject("_qualitys") ?: return out
        qs.entrySet().forEach { (k, v) ->
            val obj = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val disp = obj.get("size")?.asString
            val sz = disp?.let { parseSizeString(it) } ?: 0L
            when (k) {
                "128k" -> out["128k"] =
                    Quality("128k", "普通音质 128K", sz, mediaInfo = mediaMid, displaySize = disp)

                "320k" -> out["320k"] =
                    Quality("320k", "高品音质 320K", sz, mediaInfo = mediaMid, displaySize = disp)

                "flac" -> out["flac"] =
                    Quality("flac", "无损音质 FLAC", sz, mediaInfo = mediaMid, displaySize = disp)

                "flac24bit", "hires" -> out["hires"] =
                    Quality("hires", "无损音质 HiRes", sz, mediaInfo = mediaMid, displaySize = disp)

                "master" -> out["master"] =
                    Quality("master", "臻品母带", sz, mediaInfo = mediaMid, displaySize = disp)

                "atmos" -> out["atmos"] =
                    Quality("atmos", "臻品全景声", sz, mediaInfo = mediaMid, displaySize = disp)

                "atmos_plus" -> out["atmos_plus"] =
                    Quality(
                        "atmos_plus",
                        "臻品全景声 2.0",
                        sz,
                        mediaInfo = mediaMid,
                        displaySize = disp
                    )
            }
        }
        return out
    }

    private fun parseKgQualities(qs: JsonObject?): Map<String, Quality> {
        val out = LinkedHashMap<String, Quality>()
        qs ?: return out
        qs.entrySet().forEach { (k, v) ->
            val obj = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val disp = obj.get("size")?.asString
            val sz = disp?.let { parseSizeString(it) } ?: 0L
            when (k) {
                "128k" -> out["128k"] =
                    Quality("128k", "普通音质 128K", sz, bitrate = 128, displaySize = disp)

                "320k" -> out["320k"] =
                    Quality("320k", "高品音质 320K", sz, bitrate = 320, displaySize = disp)

                "flac" -> out["flac"] =
                    Quality("flac", "无损音质 FLAC", sz, bitrate = 2000, displaySize = disp)

                "atmos" -> out["atmos"] = Quality("atmos", "全景声音质", sz, displaySize = disp)
                "master" -> out["master"] =
                    Quality("master", "臻品母带", sz, bitrate = 20900, displaySize = disp)
            }
        }
        return out
    }

    private fun parseStdQualities(qs: JsonObject?): Map<String, Quality> {
        val out = LinkedHashMap<String, Quality>()
        qs ?: return out
        qs.entrySet().forEach { (k, v) ->
            val obj = v.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val disp = obj.get("size")?.asString
            val sz = disp?.let { parseSizeString(it) } ?: 0L
            when (k) {
                "128k" -> out["128k"] = Quality("128k", "普通音质 128K", sz, displaySize = disp)
                "320k" -> out["320k"] = Quality("320k", "高品音质 320K", sz, displaySize = disp)
                "flac" -> out["flac"] = Quality("flac", "无损音质 FLAC", sz, displaySize = disp)
                "hires", "flac24bit" -> out["hires"] =
                    Quality("hires", "无损音质 HiRes", sz, displaySize = disp)

                "master" -> out["master"] = Quality("master", "臻品母带", sz, displaySize = disp)
            }
        }
        return out
    }

    private fun parseSizeString(s: String): Long {
        val m = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)?""").find(s.trim()) ?: return 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        return (num * when (m.groupValues[2].uppercase()) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024L
            "GB" -> 1024L * 1024L * 1024L
            else -> 1L
        }).toLong()
    }

    private fun parseInterval(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        val parts = s.split(":")
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: 0L) * 60_000L + (parts[1].toLongOrNull() ?: 0L) * 1000L
            3 -> ((parts[0].toLongOrNull() ?: 0L) * 3600L +
                    (parts[1].toLongOrNull() ?: 0L) * 60L +
                    (parts[2].toLongOrNull() ?: 0L)) * 1000L

            else -> 0L
        }
    }

    private fun asString(v: JsonElement?): String? {
        if (v == null || v.isJsonNull || !v.isJsonPrimitive) return null
        val p = v.asJsonPrimitive
        return when {
            p.isNumber -> p.asLong.toString().takeIf { it != "0" }
            p.isString -> p.asString.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    // ─────────────── 编码 ───────────────

    fun songToJson(item: MusicItem): JsonObject {
        return when (item) {
            is QQMusicItem -> txToJson(item)
            is KugouMusicItem -> kgToJson(item)
            is NeteaseMusicItem -> wyToJson(item)
            is KuwoMusicItem -> kwToJson(item)
            else -> null
        } ?: JsonObject()
    }

    private fun lxId(source: String, key: String) = "${source}_$key"

    private fun baseSong(
        id: String, title: String, artist: String, duration: Long, source: String
    ): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", title)
        addProperty("singer", artist)
        addProperty("source", source)
        addProperty("interval", formatInterval(duration))
    }

    private fun formatInterval(durationMs: Long): String {
        val s = durationMs / 1000
        val mm = s / 60
        val ss = s % 60
        return "%02d:%02d".format(mm, ss)
    }

    private fun txToJson(it: QQMusicItem): JsonObject {
        val lxId = lxId("tx", it.mid.ifBlank { it.id.toString() })
        val root = baseSong(lxId, it.title, it.artist, it.duration, "tx")
        val meta = JsonObject().apply {
            addProperty("songId", it.mid)
            addProperty("strMediaMid", it.mediaMid)
            addProperty("albumMid", it.albumMid)
            addProperty("albumName", it.album)
            it.albumId?.let { aid -> addProperty("albumId", aid) }
            addProperty("id", it.id)
            addProperty("picUrl", it.cover)
            add("_qualitys", txQualitiesJson(it.qualities))
            add("qualitys", txQualityListJson(it.qualities))
        }
        root.add("meta", meta)
        return root
    }

    private fun kgToJson(it: KugouMusicItem): JsonObject {
        val lxId = "${it.id}_${it.hash}"
        val root = baseSong(lxId, it.title, it.artist, it.duration, "kg")
        val meta = JsonObject().apply {
            addProperty("songId", it.id)
            addProperty("hash", it.hash)
            addProperty("albumName", it.album)
            it.albumId?.let { aid -> addProperty("albumId", aid) }
            addProperty("picUrl", it.cover)
            add("_qualitys", kgQualitiesJson(it.qualities))
            add("qualitys", kgQualityListJson(it.qualities))
        }
        root.add("meta", meta)
        return root
    }

    private fun wyToJson(it: NeteaseMusicItem): JsonObject {
        val root = baseSong("wy_${it.id}", it.title, it.artist, it.duration, "wy")
        val meta = JsonObject().apply {
            addProperty("songId", it.id)
            addProperty("albumName", it.album)
            it.albumId?.let { aid -> addProperty("albumId", aid) }
            addProperty("picUrl", it.cover)
            add("_qualitys", stdQualitiesJson(it.qualities))
            add("qualitys", stdQualityListJson(it.qualities))
        }
        root.add("meta", meta)
        return root
    }

    private fun kwToJson(it: KuwoMusicItem): JsonObject {
        val root = baseSong("kw_${it.id}", it.title, it.artist, it.duration, "kw")
        val meta = JsonObject().apply {
            addProperty("songId", it.id)
            addProperty("albumName", it.album)
            it.albumId?.let { aid -> addProperty("albumId", aid) }
            addProperty("picUrl", it.cover)
            add("_qualitys", stdQualitiesJson(it.qualities))
            add("qualitys", stdQualityListJson(it.qualities))
        }
        root.add("meta", meta)
        return root
    }

    private fun sizeLabel(bytes: Long): String? {
        if (bytes <= 0L) return null
        val mb = bytes / (1024.0 * 1024.0)
        val kb = bytes / 1024.0
        return if (mb >= 1) "%.2f MB".format(mb) else "%.2f KB".format(kb)
    }

    private fun txQualitiesJson(qs: Map<String, Quality>): JsonObject {
        val out = JsonObject()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            val k = when (id) {
                "atmos" -> "atmos"
                "atmos_plus" -> "atmos_plus"
                "hires" -> "flac24bit"
                else -> id
            }
            out.add(k, JsonObject().apply { addProperty("size", disp) })
        }
        return out
    }

    private fun txQualityListJson(qs: Map<String, Quality>): JsonArray {
        val arr = JsonArray()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            val k = when (id) {
                "hires" -> "flac24bit"
                else -> id
            }
            arr.add(JsonObject().apply {
                addProperty("size", disp)
                addProperty("type", k)
            })
        }
        return arr
    }

    private fun kgQualitiesJson(qs: Map<String, Quality>): JsonObject {
        val out = JsonObject()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            val obj = JsonObject().apply {
                addProperty("size", disp)
                q.mediaInfo?.takeIf { it.isNotBlank() }?.let { addProperty("hash", it) }
            }
            out.add(id, obj)
        }
        return out
    }

    private fun kgQualityListJson(qs: Map<String, Quality>): JsonArray {
        val arr = JsonArray()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            arr.add(JsonObject().apply {
                addProperty("size", disp)
                addProperty("type", id)
                q.mediaInfo?.takeIf { it.isNotBlank() }?.let { addProperty("hash", it) }
            })
        }
        return arr
    }

    private fun stdQualitiesJson(qs: Map<String, Quality>): JsonObject {
        val out = JsonObject()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            val k = if (id == "hires") "flac24bit" else id
            out.add(k, JsonObject().apply { addProperty("size", disp) })
        }
        return out
    }

    private fun stdQualityListJson(qs: Map<String, Quality>): JsonArray {
        val arr = JsonArray()
        qs.forEach { (id, q) ->
            val disp = q.displaySize ?: sizeLabel(q.filesize) ?: return@forEach
            val k = if (id == "hires") "flac24bit" else id
            arr.add(JsonObject().apply {
                addProperty("size", disp); addProperty("type", k)
            })
        }
        return arr
    }
}
