package com.ikunshare.sound.manager

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.MusicDatabase
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.platform.kg.KugouMusicItem
import com.ikunshare.sound.platform.kw.KuwoMusicItem
import com.ikunshare.sound.platform.qq.QQMusicItem
import com.ikunshare.sound.platform.wy.NeteaseMusicItem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

data class LxImportPlaylist(
    val rawId: String,
    val displayName: String,
    val systemKind: String?,
    val songs: List<MusicItem>
)

data class LxImportData(val playlists: List<LxImportPlaylist>) {
    fun totalSongs() = playlists.sumOf { it.songs.size }
    fun summary(): String = playlists.joinToString("、") { "${it.displayName}(${it.songs.size})" }
}

data class LxImportResult(
    val favoritesAdded: Int,
    val trialAdded: Int,
    val playlistsCreated: Int,
    val songsAdded: Int,
    val songsSkipped: Int
)

/**
 * 解析并导入 LX Music 的 playList_v2 / playListPart_v2 数据（支持 gzip 压缩的 .lxmc）。
 *
 * 映射策略：
 * - `default` → 「试听列表」系统歌单（合并去重）
 * - `love`    → 「我的收藏」系统歌单（合并去重）
 * - 其他      → 每个都新建同名自定义歌单
 */
class LxMusicImporter(private val store: LocalMusicStore) {

    fun parse(input: InputStream): LxImportData? {
        val raw = input.readBytes()
        val bytes = decompressIfGzip(raw)
        val text = bytes.toString(Charsets.UTF_8).trim()
        val root =
            runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return null
        val type = root.get("type")?.asString ?: return null
        val arr: JsonArray = when (type) {
            "playList_v2" -> root.getAsJsonArray("data") ?: return null
            "playListPart_v2" -> {
                val d = root.get("data") ?: return null
                when {
                    d.isJsonObject -> JsonArray().also { it.add(d.asJsonObject) }
                    d.isJsonArray -> d.asJsonArray
                    else -> return null
                }
            }

            else -> return null
        }
        val lists = mutableListOf<LxImportPlaylist>()
        arr.forEach { el ->
            val pl = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val id = pl.get("id")?.asString ?: return@forEach
            val rawName = pl.get("name")?.asString ?: id
            val items = mutableListOf<MusicItem>()
            (pl.getAsJsonArray("list") ?: JsonArray()).forEach { sEl ->
                val so = sEl.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                runCatching { parseSong(so) }.getOrNull()?.let { items += it }
            }
            val sysKind = when (id) {
                "default" -> MusicDatabase.SYSTEM_KIND_TRIAL
                "love" -> MusicDatabase.SYSTEM_KIND_FAVORITES
                else -> null
            }
            val display = when (sysKind) {
                MusicDatabase.SYSTEM_KIND_TRIAL -> MusicDatabase.SYSTEM_TRIAL_NAME
                MusicDatabase.SYSTEM_KIND_FAVORITES -> MusicDatabase.SYSTEM_FAVORITES_NAME
                else -> when (rawName) {
                    "list__name_default" -> MusicDatabase.SYSTEM_TRIAL_NAME
                    "list__name_love" -> MusicDatabase.SYSTEM_FAVORITES_NAME
                    else -> rawName
                }
            }
            lists += LxImportPlaylist(id, display, sysKind, items)
        }
        if (lists.isEmpty()) return null
        return LxImportData(lists)
    }

    fun import(data: LxImportData): LxImportResult {
        var favAdded = 0
        var trialAdded = 0
        var created = 0
        var songsAdded = 0
        var skipped = 0
        val now = System.currentTimeMillis()
        data.playlists.forEach { pl ->
            when (pl.systemKind) {
                MusicDatabase.SYSTEM_KIND_TRIAL -> {
                    val pid = store.getTrialPlaylistId()
                    if (pid <= 0) {
                        skipped += pl.songs.size; return@forEach
                    }
                    val existing = collectKeys(pid)
                    var nextPos = existing.size
                    pl.songs.forEachIndexed { idx, song ->
                        val k = keyOf(song)
                        if (k in existing) {
                            skipped++; return@forEachIndexed
                        }
                        store.addToPlaylistDirect(
                            pid,
                            song.toJson().toString(),
                            now + idx,
                            nextPos++
                        )
                        existing += k; trialAdded++; songsAdded++
                    }
                }

                MusicDatabase.SYSTEM_KIND_FAVORITES -> {
                    val pid = store.getFavoritesPlaylistId()
                    if (pid <= 0) {
                        skipped += pl.songs.size; return@forEach
                    }
                    val existing = collectKeys(pid)
                    pl.songs.forEachIndexed { idx, song ->
                        val k = keyOf(song)
                        if (k in existing) {
                            skipped++; return@forEachIndexed
                        }
                        store.addFavoriteDirect(song.toJson().toString(), now + idx)
                        existing += k; favAdded++; songsAdded++
                    }
                }

                else -> {
                    val newPid = store.createPlaylistDirect(pl.displayName, now)
                    if (newPid <= 0) {
                        skipped += pl.songs.size; return@forEach
                    }
                    created++
                    pl.songs.forEachIndexed { idx, song ->
                        store.addToPlaylistDirect(newPid, song.toJson().toString(), now + idx, idx)
                        songsAdded++
                    }
                }
            }
        }
        store.refreshAfterRestore()
        return LxImportResult(favAdded, trialAdded, created, songsAdded, skipped)
    }

    // ─── Helpers ───

    private fun collectKeys(pid: Long): MutableSet<String> =
        store.queryPlaylistSongs(pid).mapTo(mutableSetOf()) { keyOf(it) }

    private fun keyOf(i: MusicItem) = "${i.id}_${i.getTypeDiscriminator()}"

    private fun decompressIfGzip(bytes: ByteArray): ByteArray {
        if (bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) {
            return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
        }
        return bytes
    }

    // ─── Song Parsing ───

    private fun parseSong(o: JsonObject): MusicItem? {
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
        val albumId = asString(m.get("albumId"))
        val cover = m.get("picUrl")?.asString ?: ""
        return QQMusicItem(
            id = id, title = title, artist = artist, album = albumName,
            cover = cover, duration = duration,
            qualities = parseTxQualities(m, mediaMid),
            mid = mid, albumMid = albumMid, mediaMid = mediaMid,
            vs = emptyList(), size_new = emptyList(),
            albumId = albumId
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
            qualities = parseStandardQualities(m.getAsJsonObject("_qualitys")),
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
            qualities = parseStandardQualities(m.getAsJsonObject("_qualitys")),
            albumId = asString(m.get("albumId"))
        )
    }

    // ─── Quality Parsing ───

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
                    Quality(
                        "hires",
                        "无损音质 Hi-Res",
                        sz,
                        mediaInfo = mediaMid,
                        displaySize = disp
                    )

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

                "atmos" -> out["atmos"] = Quality("atmos", "蝰蛇全景声", sz, displaySize = disp)
                "master" -> out["master"] =
                    Quality("master", "蝰蛇母带", sz, bitrate = 20900, displaySize = disp)
            }
        }
        return out
    }

    private fun parseStandardQualities(qs: JsonObject?): Map<String, Quality> {
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
                    Quality("hires", "无损音质 Hi-Res", sz, displaySize = disp)

                "master" -> out["master"] = Quality("master", "臻品母带", sz, displaySize = disp)
            }
        }
        return out
    }

    // ─── Misc ───

    private fun asString(v: JsonElement?): String? {
        if (v == null || v.isJsonNull || !v.isJsonPrimitive) return null
        val p = v.asJsonPrimitive
        return when {
            p.isNumber -> p.asLong.toString().takeIf { it != "0" }
            p.isString -> p.asString.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun parseSizeString(s: String): Long {
        val m = Regex("""([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]+)?""").find(s.trim()) ?: return 0L
        val num = m.groupValues[1].toDoubleOrNull() ?: return 0L
        val mul = when (m.groupValues[2].uppercase()) {
            "KB" -> 1024L
            "MB" -> 1024L * 1024L
            "GB" -> 1024L * 1024L * 1024L
            else -> 1L
        }
        return (num * mul).toLong()
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
}
