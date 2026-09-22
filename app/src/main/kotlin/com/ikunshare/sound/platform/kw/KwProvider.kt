package com.ikunshare.sound.platform.kw

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.Singer
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.AlbumSearchResult
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.ArtistMvItem
import com.ikunshare.sound.platform.base.ArtistMvResult
import com.ikunshare.sound.platform.base.ArtistSearchResult
import com.ikunshare.sound.platform.base.BaseProvider
import com.ikunshare.sound.platform.base.MusicListResult
import com.ikunshare.sound.platform.base.MvQuality
import com.ikunshare.sound.platform.base.MvUrlResult
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.platform.base.SearchType
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.lyric.LrcNative
import com.ikunshare.sound.utils.lyric.LrcParser
import com.ikunshare.sound.utils.lyric.toLyric
import java.net.URLEncoder

class KwProvider : BaseProvider("kw") {

    override val displayName = "酷我音乐"
    override val shortTag = "kw"
    override val supportsImportPlaylist = true
    override val supportedSearchTypes: List<SearchType> =
        listOf(
            SearchType.SONG,
            SearchType.ALBUM,
            SearchType.ARTIST
        )

    companion object {
        private const val TAG = "KwProvider"
        private val MINFO_REGEX =
            Regex("""level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)""")
    }

    override fun search(keyword: String, page: Int, size: Int): MusicListResult {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?" +
                "client=kt&all=$encoded&pn=$page&rn=$size" +
                "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
                "&show_copyright_off=1&newver=1&ft=music&cluster=0" +
                "&strategy=2012&encoding=utf8&rformat=json&vermerge=1" +
                "&mobi=1&issubtitle=1"

        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())

            val total = json.get("TOTAL")?.asString?.toIntOrNull() ?: 0
            val show = json.get("SHOW")?.asString?.toIntOrNull() ?: 0

            if (total != 0 && show == 0) {
                return MusicListResult(source, false, page, size, emptyList())
            }

            val abslist = json.getAsJsonArray("abslist")
                ?: return MusicListResult(source, false, page, size, emptyList())

            val items = abslist.mapNotNull { elem ->
                parseSearchItem(elem.asJsonObject)
            }
            fetchCovers(items)

            val hasNext = (page + 1) * size < total
            MusicListResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun parseSearchItem(info: JsonObject): KuwoMusicItem? {
        val musicRid = info.get("MUSICRID")?.asString ?: return null
        val songId = musicRid.replace("MUSIC_", "").toLongOrNull() ?: return null
        val nMinfo = info.get("N_MINFO")?.asString
        if (nMinfo.isNullOrEmpty()) return null

        val qualities = mutableMapOf<String, Quality>()
        for (segment in nMinfo.split(";")) {
            val match = MINFO_REGEX.find(segment) ?: continue
            val bitrate = match.groupValues[2]
            val displaySize = match.groupValues[4]
            when (bitrate) {
                "128" -> qualities["128k"] = Quality(
                    id = "128k", name = "普通音质 128K", filesize = 0,
                    bitrate = 128, displaySize = displaySize.uppercase()
                )

                "320" -> qualities["320k"] = Quality(
                    id = "320k", name = "高品音质 320K", filesize = 0,
                    bitrate = 320, displaySize = displaySize.uppercase()
                )

                "2000" -> qualities["flac"] = Quality(
                    id = "flac", name = "无损音质 FLAC", filesize = 0,
                    bitrate = 2000, displaySize = displaySize.uppercase()
                )

                "4000" -> qualities["hires"] = Quality(
                    id = "hires", name = "无损音质 Hi-Res", filesize = 0,
                    bitrate = 4000, displaySize = displaySize.uppercase()
                )

                "20201" -> qualities["atmos"] = Quality(
                    id = "atmos", name = "至臻全景声", filesize = 0,
                    bitrate = 20201, displaySize = displaySize.uppercase()
                )

                "20501" -> qualities["atmos_plus"] = Quality(
                    id = "atmos_plus", name = "至臻音质2.0", filesize = 0,
                    bitrate = 20501, displaySize = displaySize.uppercase()
                )

                "20900" -> qualities["master"] = Quality(
                    id = "master", name = "至臻母带", filesize = 0,
                    bitrate = 20900, displaySize = displaySize.uppercase()
                )
            }
        }

        val artistStr = info.get("ARTIST")?.asString ?: ""
        val duration = info.get("DURATION")?.asString?.toLongOrNull() ?: 0

        return KuwoMusicItem(
            id = songId,
            title = info.get("SONGNAME")?.asString ?: "",
            artist = artistStr,
            album = info.get("ALBUM")?.asString ?: "",
            cover = "",
            duration = duration * 1000,
            qualities = qualities,
            albumId = info.get("ALBUMID")?.asString,
            singers = artistStr.split("&").map { Singer(name = it.trim()) }
        )
    }

    private fun fetchCovers(items: List<KuwoMusicItem>) {
        if (items.isEmpty()) return
        val ids = items.map { it.id }.joinToString(",")
        try {
            val url = "https://musicpay.kuwo.cn/music.pay?" +
                    "ver=MUSIC_9.1.1.2_BCS2&src=mbox&op=query&signver=new" +
                    "&action=play&ids=$ids&accttype=1&appuid=38668888"
            val resp = HTTPUtils.get(url, mapOf("User-Agent" to "okhttp/3.10.0"))
            if (!resp.isSuccessful) return
            val json = JsonParser.parseString(resp.body).asJsonObject
            val songs = json.getAsJsonArray("songs") ?: return
            val coverMap = mutableMapOf<Long, String>()
            for (elem in songs) {
                val obj = elem.asJsonObject
                val id = obj.get("id")?.asLong ?: continue
                val pic = obj.get("albumPic")?.asString
                    ?.replace("albumcover/120", "albumcover/500") ?: continue
                coverMap[id] = pic
            }
            items.forEach { item ->
                coverMap[item.id]?.let { item.setCover(it) }
            }
        } catch (_: Exception) {
        }
    }

    override fun getHotSearch(): List<String> {
        return try {
            val url = "http://hotword.kuwo.cn/hotword.s?" +
                    "prod=kwplayer_ar_9.3.1.3&corp=kuwo&newver=2&vipver=1" +
                    "&colorid=6&encoding=utf8"
            val response = HTTPUtils.get(url)
            if (!response.isSuccessful) return emptyList()

            val body = response.body ?: return emptyList()

            // 尝试 JSON 解析
            try {
                val json = JsonParser.parseString(body).asJsonObject
                val tagValue = json.getAsJsonArray("tagvalue") ?: return emptyList()
                return tagValue.mapNotNull { elem ->
                    val key = elem.asJsonObject.get("key")?.asString
                    if (!key.isNullOrBlank()) key else null
                }
            } catch (_: Exception) {
            }

            // 纯文本格式：每行一个关键词，首行可能有 "TEXT=" 前缀
            body.lines()
                .map { it.removePrefix("TEXT=").trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "getHotSearch failed", e)
            emptyList()
        }
    }

    override fun getSearchTip(keyword: String): List<String> {
        return emptyList()
    }

    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? {
        // TODO: Implement playlist info
        return null
    }

    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult {
        // TODO: Fetch playlist songs
        return MusicListResult(source, false, page, size, emptyList())
    }

    // ===== 专辑搜索 =====

    override fun searchAlbum(keyword: String, page: Int, size: Int): AlbumSearchResult {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?" +
                "client=kt&all=$encoded&pn=$page&rn=$size" +
                "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
                "&show_copyright_off=1&newver=3&ft=album&albumver=1&cluster=0" +
                "&strategy=2012&encoding=utf8&rformat=json&mobi=1&correct=1"

        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return AlbumSearchResult(source, false, page, size, emptyList())
        }
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val total = json.get("total")?.asString?.toIntOrNull() ?: 0
            val list = json.getAsJsonArray("albumlist")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val items = list.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseKwAlbumEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchAlbum failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseKwAlbumEntry(obj: JsonObject): AlbumInfoResult? {
        val id = obj.get("albumid")?.asString?.takeIf { it.isNotBlank() }
            ?: obj.get("id")?.asString
            ?: return null
        val name = obj.get("name")?.asString ?: return null
        val info = obj.get("info")?.asString
        val pubDate = obj.get("pub")?.asString
        val publishTime = pubDate?.let { parseKwDate(it) }
        val musicCount = obj.get("musiccnt")?.asString?.toIntOrNull() ?: 0
        return AlbumInfoResult(
            source = source,
            albumId = id,
            name = name,
            cover = obj.get("img")?.asString ?: obj.get("hts_img")?.asString,
            artist = obj.get("artist")?.asString,
            artistId = obj.get("artistid")?.asString,
            publishTime = publishTime,
            company = obj.get("company")?.asString,
            subType = obj.get("startype")?.asString,
            description = info,
            size = musicCount
        )
    }

    private fun parseKwDate(raw: String): Long? {
        if (raw.isBlank() || raw.startsWith("0000")) return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .parse(raw)?.time
        } catch (_: Exception) {
            null
        }
    }

    // ===== 歌手搜索 =====

    override fun searchArtist(keyword: String, page: Int, size: Int): ArtistSearchResult {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val url = "http://search.kuwo.cn/r.s?" +
                "client=kt&all=$encoded&pn=$page&rn=$size" +
                "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
                "&show_copyright_off=0&newver=3&ft=artist&cluster=0" +
                "&encoding=utf8&rformat=json&mobi=1"

        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return ArtistSearchResult(source, false, page, size, emptyList())
        }
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val total = json.get("TOTAL")?.asString?.toIntOrNull()
                ?: json.get("HIT")?.asString?.toIntOrNull() ?: 0
            val list = json.getAsJsonArray("abslist")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val items = list.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseKwArtistEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            ArtistSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchArtist failed", e)
            ArtistSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseKwArtistEntry(obj: JsonObject): ArtistInfoResult? {
        val artistId = obj.get("ARTISTID")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val name = obj.get("ARTIST")?.asString ?: return null
        val avatar = obj.get("hts_PICPATH")?.asString?.takeIf { it.isNotBlank() }
        return ArtistInfoResult(
            source = source,
            artistId = artistId,
            name = name,
            avatar = avatar,
            albumCount = obj.get("ALBUMNUM")?.asString?.toIntOrNull() ?: 0,
            musicCount = obj.get("SONGNUM")?.asString?.toIntOrNull() ?: 0,
            fansCount = 0,
            description = obj.get("desc")?.asString?.takeIf { it.isNotBlank() }
        )
    }

    override fun getArtistSongs(artistId: String, page: Int, size: Int): MusicListResult {
        val start = page * size
        val url = "http://mobi.kuwo.cn/mobi.s?f=web&type=music_list" +
                "&id=$artistId&key=artist&start=$start&count=$size" +
                "&uid=794762570&prod=kwplayer_ar_9.2.2.1&vipver=1" +
                "&presell=1&apiv=2&epaor=1&hasmv=1&hasinner=1"
        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return MusicListResult(source, false, page, size, emptyList())
        }
        return try {
            val body = response.body
                ?: return MusicListResult(source, false, page, size, emptyList())
            val totalMatch = Regex("""total="(\d+)"""").find(body)
            val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val musicRegex = Regex("""<music\s[^>]+/>""")
            val items = musicRegex.findAll(body).mapNotNull { match ->
                parseXmlMusicElement(match.value)
            }.toList()
            val hasNext = start + size < total
            MusicListResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistSongs failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun parseXmlMusicElement(xml: String): KuwoMusicItem? {
        fun attr(name: String): String? {
            val m = Regex("""\b$name="([^"]*)"""").find(xml) ?: return null
            return m.groupValues[1].takeIf { it.isNotEmpty() }
        }

        val rid = attr("rid")?.toLongOrNull() ?: return null
        val name = attr("name")?.replace("&amp;", "&")?.replace("&lt;", "<")
            ?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")
            ?: return null
        val nMinfo = attr("n_minfo") ?: attr("minfo") ?: return null

        val qualities = mutableMapOf<String, Quality>()
        for (segment in nMinfo.split(";")) {
            val match = MINFO_REGEX.find(segment) ?: continue
            val bitrate = match.groupValues[2]
            val displaySize = match.groupValues[4]
            when (bitrate) {
                "128" -> qualities["128k"] = Quality(
                    id = "128k", name = "普通音质 128K", filesize = 0,
                    bitrate = 128, displaySize = displaySize.uppercase()
                )

                "320" -> qualities["320k"] = Quality(
                    id = "320k", name = "高品音质 320K", filesize = 0,
                    bitrate = 320, displaySize = displaySize.uppercase()
                )

                "2000" -> qualities["flac"] = Quality(
                    id = "flac", name = "无损音质 FLAC", filesize = 0,
                    bitrate = 2000, displaySize = displaySize.uppercase()
                )

                "4000" -> qualities["hires"] = Quality(
                    id = "hires", name = "无损音质 Hi-Res", filesize = 0,
                    bitrate = 4000, displaySize = displaySize.uppercase()
                )

                "20201" -> qualities["atmos"] = Quality(
                    id = "atmos", name = "至臻全景声", filesize = 0,
                    bitrate = 20201, displaySize = displaySize.uppercase()
                )

                "20501" -> qualities["atmos_plus"] = Quality(
                    id = "atmos_plus", name = "至臻音质2.0", filesize = 0,
                    bitrate = 20501, displaySize = displaySize.uppercase()
                )

                "20900" -> qualities["master"] = Quality(
                    id = "master", name = "至臻母带", filesize = 0,
                    bitrate = 20900, displaySize = displaySize.uppercase()
                )
            }
        }
        if (qualities.isEmpty()) return null

        val artist = attr("artist")?.replace("&amp;", "&") ?: ""
        val album = attr("album")?.replace("&amp;", "&") ?: ""
        val duration = attr("duration")?.toLongOrNull()?.times(1000) ?: 0L
        val cover = attr("img") ?: ""
        val albumId = attr("albumid")
        val mvid = attr("vid")?.takeIf { it != "0" && it.isNotBlank() }

        return KuwoMusicItem(
            id = rid,
            title = name,
            artist = artist,
            album = album,
            cover = cover,
            duration = duration,
            qualities = qualities,
            albumId = albumId,
            mvid = mvid
        )
    }

    override fun supportsArtistAlbums(): Boolean = true
    override fun supportsArtistMvs(): Boolean = true

    override fun createMvItem(vid: String, title: String, cover: String): MusicItem =
        KuwoMusicItem(
            id = vid.toLongOrNull() ?: 0L,
            title = title,
            artist = "",
            album = "",
            cover = cover,
            duration = 0,
            qualities = mapOf(
                "128k" to Quality(id = "128k", name = "普通音质 128K", filesize = 0, bitrate = 128)
            ),
            mvid = vid
        )

    override fun getArtistAlbums(artistId: String, page: Int, size: Int): AlbumSearchResult {
        val start = page * size
        val url = "http://mobi.kuwo.cn/mobi.s?f=web&type=album_list" +
                "&artist_id=$artistId&start=$start&count=$size" +
                "&uid=794762570&prod=kwplayer_ar_9.2.2.1&vipver=1" +
                "&presell=1&hasmv=1&hasinner=1"
        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return AlbumSearchResult(source, false, page, size, emptyList())
        }
        return try {
            val body = decodeMobiResponse(response.bodyBytes)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val totalMatch = Regex("""total="(\d+)"""").find(body)
            val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val albumRegex = Regex("""<album\s[^>]+/>""")
            val items = albumRegex.findAll(body).mapNotNull { match ->
                parseXmlAlbumElement(match.value)
            }.toList()
            val hasNext = start + size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAlbums failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    override fun getArtistMvs(artistId: String, page: Int, size: Int): ArtistMvResult {
        val start = page * size
        val url = "http://mobi.kuwo.cn/mobi.s?f=web&type=music_list" +
                "&id=$artistId&key=mv&start=$start&count=$size" +
                "&uid=794762570&prod=kwplayer_ar_9.2.2.1&vipver=1" +
                "&presell=1&apiv=2&hasmv=1&hasinner=1"
        val response = HTTPUtils.get(url)
        if (!response.isSuccessful) {
            return ArtistMvResult(source, false, page, size, 0, emptyList())
        }
        return try {
            val body = response.body
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            val totalMatch = Regex("""total="(\d+)"""").find(body)
            val total = totalMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val mvRegex = Regex("""<mv\s[^>]+/>""")
            val items = mvRegex.findAll(body).mapNotNull { match ->
                parseXmlMvElement(match.value)
            }.toList()
            val hasNext = start + size < total
            ArtistMvResult(source, hasNext, page, size, total, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistMvs failed", e)
            ArtistMvResult(source, false, page, size, 0, emptyList())
        }
    }

    private fun parseXmlMvElement(xml: String): ArtistMvItem? {
        fun attr(name: String): String? {
            val m = Regex("""\b$name="([^"]*)"""").find(xml) ?: return null
            return m.groupValues[1].takeIf { it.isNotEmpty() }
        }

        val rid = attr("rid") ?: return null
        val vid = attr("vid")?.takeIf { it != "0" } ?: return null
        val title = attr("name")?.replace("&amp;", "&") ?: ""
        val cover = attr("img") ?: ""
        val duration = attr("duration")?.toLongOrNull()?.times(1000) ?: 0L
        val playCount = attr("listencnt")?.toLongOrNull() ?: 0L
        return ArtistMvItem(
            source = source,
            vid = rid,
            title = title,
            cover = cover,
            duration = duration,
            playCount = playCount,
            pubTime = 0
        )
    }

    private fun parseXmlAlbumElement(xml: String): AlbumInfoResult? {
        fun attr(name: String): String? {
            val m = Regex("""\b$name="([^"]*)"""").find(xml) ?: return null
            return m.groupValues[1].takeIf { it.isNotEmpty() }
        }

        val id = attr("id") ?: return null
        val name = attr("name")?.replace("&amp;", "&") ?: return null
        val pubDate = attr("publish")
        return AlbumInfoResult(
            source = source,
            albumId = id,
            name = name,
            cover = attr("img"),
            artist = attr("artist")?.replace("&amp;", "&"),
            artistId = attr("artistid"),
            publishTime = pubDate?.let { parseKwDate(it) },
            company = attr("company")?.replace("&amp;", "&"),
            subType = null,
            description = null,
            size = attr("musiccnt")?.toIntOrNull() ?: 0
        )
    }

    /**
     * 酷我 mobi.s 部分接口返回 zlib 压缩格式：
     * `sig=\r\n` + 4字节压缩长度(LE) + 4字节解压长度(LE) + zlib payload
     */
    private fun decodeMobiResponse(raw: ByteArray?): String? {
        if (raw == null || raw.size < 16) return null
        val headerEnd = findCrLf(raw) ?: return null
        if (headerEnd + 8 >= raw.size) return null
        val compSize = readIntLE(raw, headerEnd)
        val decompSize = readIntLE(raw, headerEnd + 4)
        if (compSize <= 0 || decompSize <= 0) return null
        val payloadStart = headerEnd + 8
        if (payloadStart + compSize > raw.size) return null
        val inflater = java.util.zip.Inflater()
        inflater.setInput(raw, payloadStart, compSize)
        return try {
            val output = ByteArray(decompSize)
            val len = inflater.inflate(output)
            String(output, 0, len, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "decodeMobiResponse inflate failed", e)
            null
        } finally {
            inflater.end()
        }
    }

    private fun findCrLf(data: ByteArray): Int? {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0x0D.toByte() && data[i + 1] == 0x0A.toByte()) return i + 2
        }
        return null
    }

    private fun readIntLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

    // ===== 专辑详情(返回 XML) =====

    override fun getAlbumInfo(albumId: String): AlbumInfoResult? {
        // 仅返回 size,详细元信息缺失;实际页面会另外缓存上一次搜索结果
        val songs = getAlbumSongs(albumId).result
        if (songs.isEmpty()) return null
        val first = songs.first() as? KuwoMusicItem ?: return null
        return AlbumInfoResult(
            source = source,
            albumId = albumId,
            name = first.album,
            cover = first.cover.takeIf { it.isNotBlank() },
            artist = first.artist,
            artistId = null,
            publishTime = null,
            company = null,
            subType = null,
            description = null,
            size = songs.size
        )
    }

    override fun getAlbumSongs(albumId: String): MusicListResult {
        val url = "http://searchlist.kuwo.cn/r.s?" +
                "f=web&prod=kwplayer_ar_9.2.2.1&corp=kuwo&newver=3&vipver=1" +
                "&type=music_list&id=$albumId&key=album&apiv=2&order=5&epaor=1" +
                "&start=0&count=200&hasmv=1&hasinner=1&p2p=1"
        val resp = try {
            HTTPUtils.get(url)
        } catch (e: Exception) {
            Log.e(TAG, "getAlbumSongs http failed", e)
            return MusicListResult(source, false, 0, 200, emptyList())
        }
        if (!resp.isSuccessful) return MusicListResult(source, false, 0, 200, emptyList())
        val body = resp.body ?: return MusicListResult(source, false, 0, 200, emptyList())
        return try {
            val items = parseKwAlbumXml(body)
            fetchCovers(items)
            MusicListResult(source, false, 0, items.size.coerceAtLeast(1), items)
        } catch (e: Exception) {
            Log.e(TAG, "parse album xml failed", e)
            MusicListResult(source, false, 0, 200, emptyList())
        }
    }

    private fun parseKwAlbumXml(xml: String): List<KuwoMusicItem> {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        val items = mutableListOf<KuwoMusicItem>()
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "music") {
                buildKwAlbumSong(parser)?.let { items += it }
            }
            event = parser.next()
        }
        return items
    }

    private fun buildKwAlbumSong(parser: org.xmlpull.v1.XmlPullParser): KuwoMusicItem? {
        val rid = parser.getAttributeValue(null, "rid")?.toLongOrNull() ?: return null
        val name = parser.getAttributeValue(null, "name") ?: return null
        val artistStr = parser.getAttributeValue(null, "artist") ?: ""
        val album = parser.getAttributeValue(null, "album") ?: ""
        val img = parser.getAttributeValue(null, "img") ?: ""
        val durationSec = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0
        val albumIdAttr = parser.getAttributeValue(null, "albumid")
        val nMinfo = parser.getAttributeValue(null, "n_minfo")
            ?: parser.getAttributeValue(null, "minfo")
            ?: ""

        val qualities = parseKwMinfoToQualities(nMinfo)

        return KuwoMusicItem(
            id = rid,
            title = name,
            artist = artistStr,
            album = album,
            cover = img,
            duration = durationSec * 1000,
            qualities = qualities,
            albumId = albumIdAttr,
            singers = artistStr.split("&").map { Singer(name = it.trim()) }
        )
    }

    private fun parseKwMinfoToQualities(minfo: String): Map<String, Quality> {
        val qualities = mutableMapOf<String, Quality>()
        for (segment in minfo.split(";")) {
            val match = MINFO_REGEX.find(segment) ?: continue
            val bitrate = match.groupValues[2]
            val displaySize = match.groupValues[4]
            when (bitrate) {
                "128" -> qualities["128k"] = Quality(
                    id = "128k", name = "普通音质 128K", filesize = 0,
                    bitrate = 128, displaySize = displaySize.uppercase()
                )

                "320" -> qualities["320k"] = Quality(
                    id = "320k", name = "高品音质 320K", filesize = 0,
                    bitrate = 320, displaySize = displaySize.uppercase()
                )

                "2000" -> qualities["flac"] = Quality(
                    id = "flac", name = "无损音质 FLAC", filesize = 0,
                    bitrate = 2000, displaySize = displaySize.uppercase()
                )

                "4000" -> qualities["hires"] = Quality(
                    id = "hires", name = "无损音质 Hi-Res", filesize = 0,
                    bitrate = 4000, displaySize = displaySize.uppercase()
                )

                "20201" -> qualities["atmos"] = Quality(
                    id = "atmos", name = "至臻全景声", filesize = 0,
                    bitrate = 20201, displaySize = displaySize.uppercase()
                )

                "20501" -> qualities["atmos_plus"] = Quality(
                    id = "atmos_plus", name = "至臻音质2.0", filesize = 0,
                    bitrate = 20501, displaySize = displaySize.uppercase()
                )

                "20900" -> qualities["master"] = Quality(
                    id = "master", name = "至臻母带", filesize = 0,
                    bitrate = 20900, displaySize = displaySize.uppercase()
                )
            }
        }
        return qualities
    }

    override fun getUserPlaylist(): List<PlayListInfoResult> {
        return emptyList() // 酷我不支持
    }

    override fun getUserInfo(): UserInfo? = null // 酷我不支持

    // ---- MV ----

    override fun supportsMv(item: MusicItem): Boolean = item.id > 0

    private val kuwoMvMapping = listOf(
        "MP4BD" to "蓝光画质",
        "MP4UL" to "超清画质",
        "MP4HV" to "高清画质",
        "MP4" to "标清画质"
    )

    override fun getMvQualities(item: MusicItem): List<MvQuality> {
        return try {
            // 通过 musicpay 的 mvquality 字段确定可用清晰度（管道分隔，例: "MP4|MP4HV|MP4UL|MP4BD|..."）
            val payUrl = "https://musicpay.kuwo.cn/music.pay?" +
                    "ver=MUSIC_9.1.1.2_BCS2&src=mbox&op=query&signver=new" +
                    "&action=play&ids=${item.id}&accttype=1&appuid=38668888"
            val payResp = HTTPUtils.get(payUrl, mapOf("User-Agent" to "okhttp/3.10.0"))
            val supportedSet: Set<String> =
                if (payResp.isSuccessful && !payResp.body.isNullOrBlank()) {
                    try {
                        val obj = JsonParser.parseString(payResp.body).asJsonObject
                        val songs = obj.getAsJsonArray("songs")
                        val mvQualityStr = if (songs != null && songs.size() > 0) {
                            songs[0].asJsonObject.get("mvquality")?.asString ?: ""
                        } else ""
                        mvQualityStr.split("|").filter { it.isNotBlank() }.toSet()
                    } catch (_: Exception) {
                        emptySet()
                    }
                } else emptySet()

            val results = mutableListOf<MvQuality>()
            for ((q, name) in kuwoMvMapping) {
                if (supportedSet.isEmpty() && q != "MP4HV") continue // 兜底：mvquality 为空时尝试 MP4HV
                if (supportedSet.isNotEmpty() && q !in supportedSet) continue
                results.add(MvQuality(quality = q, displayName = name))
            }
            results
        } catch (e: Exception) {
            Log.w(TAG, "Kuwo MV qualities failed", e)
            emptyList()
        }
    }

    override fun getMvUrl(item: MusicItem, quality: String): MvUrlResult {
        return try {
            val url = "http://anymatch.kuwo.cn/mobi.s?f=web&user=0" +
                    "&prod=kwplayer_ar_12.1.0.1&corp=kuwo" +
                    "&type=convert_mv_url2&rid=${item.id}&format=mp4&quality=$quality"
            val resp = HTTPUtils.get(url, mapOf("User-Agent" to "okhttp/3.10.0"))
            if (!resp.isSuccessful) return MvUrlResult(source, null, quality, "HTTP ${resp.status}")
            val body = resp.body ?: return MvUrlResult(source, null, quality, "空响应")
            if (body.startsWith("DC ") || body.contains("data error")) {
                return MvUrlResult(source, null, quality, "无效 vid")
            }
            // 文本格式: key=value 多行，含 url=、bitrate=
            val urlLine =
                Regex("""(?:^|\n)url=([^\n\r]+)""").find(body)?.groupValues?.get(1)?.trim()
            val bitrate =
                Regex("""(?:^|\n)bitrate=(\d+)""").find(body)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 0
            if (urlLine.isNullOrBlank() || bitrate <= 1) {
                MvUrlResult(source, null, quality, "返回无效")
            } else {
                MvUrlResult(source, urlLine, quality)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Kuwo MV url failed", e)
            MvUrlResult(source, null, quality, e.message ?: "请求异常")
        }
    }

    // ── 单曲详情 / 歌词 / 更新 / 分享 ──

    /** 通过数字 ID 构造单个 KuwoMusicItem，失败返回 null */
    fun fromID(id: Long): KuwoMusicItem? = fromIDs(listOf(id)).firstOrNull()

    /** 批量按 id 获取 KuwoMusicItem（本地歌单导入会用到） */
    fun fromIDs(ids: List<Long>): List<KuwoMusicItem> {
        if (ids.isEmpty()) return emptyList()
        val results = mutableListOf<KuwoMusicItem>()
        for (batch in ids.chunked(100)) {
            val idsParam = batch.joinToString(",")
            val url = "https://musicpay.kuwo.cn/music.pay?" +
                    "ver=MUSIC_9.1.1.2_BCS2&src=mbox&op=query&signver=new" +
                    "&action=play&ids=$idsParam&accttype=1&appuid=38668888"
            val resp = HTTPUtils.get(url, mapOf("User-Agent" to "okhttp/3.10.0"))
            if (!resp.isSuccessful) continue
            try {
                val json = JsonParser.parseString(resp.body).asJsonObject
                val songs = json.getAsJsonArray("songs") ?: continue
                for (elem in songs) {
                    parseMusicPayItem(elem.asJsonObject)?.let { results.add(it) }
                }
            } catch (_: Exception) {
            }
        }
        return results
    }

    private fun parseMusicPayItem(info: JsonObject): KuwoMusicItem? {
        val songId = info.get("id")?.asLong ?: return null
        val title = info.get("name")?.asString ?: return null
        val artistStr = info.get("artist")?.asString ?: ""
        val album = info.get("album")?.asString ?: ""
        val duration = info.get("duration")?.asLong ?: 0
        val albumId = info.get("albumid")?.asString
        val cover = info.get("albumPic")?.asString
            ?.replace("albumcover/120", "albumcover/500") ?: ""

        val nMinfo = info.get("N_MINFO")?.asString
        val qualities = parseMinfoQualities(nMinfo)
        if (qualities.isEmpty()) return null

        return KuwoMusicItem(
            id = songId,
            title = title,
            artist = artistStr,
            album = album,
            cover = cover,
            duration = duration * 1000,
            qualities = qualities,
            albumId = albumId,
            singers = artistStr.split("&").map { Singer(name = it.trim()) }
        )
    }

    private fun parseMinfoQualities(nMinfo: String?): Map<String, Quality> {
        if (nMinfo.isNullOrEmpty()) return emptyMap()
        val qualities = mutableMapOf<String, Quality>()
        for (segment in nMinfo.split(";")) {
            val match = KW_MINFO_REGEX.find(segment) ?: continue
            val bitrate = match.groupValues[2]
            val displaySize = match.groupValues[4]
            when (bitrate) {
                "128" -> qualities["128k"] = Quality(
                    id = "128k", name = "普通音质 128K", filesize = 0,
                    bitrate = 128, displaySize = displaySize.uppercase()
                )

                "320" -> qualities["320k"] = Quality(
                    id = "320k", name = "高品音质 320K", filesize = 0,
                    bitrate = 320, displaySize = displaySize.uppercase()
                )

                "2000" -> qualities["flac"] = Quality(
                    id = "flac", name = "无损音质 FLAC", filesize = 0,
                    bitrate = 2000, displaySize = displaySize.uppercase()
                )

                "4000" -> qualities["hires"] = Quality(
                    id = "hires", name = "无损音质 Hi-Res", filesize = 0,
                    bitrate = 4000, displaySize = displaySize.uppercase()
                )

                "20201" -> qualities["atmos"] = Quality(
                    id = "atmos", name = "至臻全景声", filesize = 0,
                    bitrate = 20201, displaySize = displaySize.uppercase()
                )

                "20501" -> qualities["atmos_plus"] = Quality(
                    id = "atmos_plus", name = "至臻音质2.0", filesize = 0,
                    bitrate = 20501, displaySize = displaySize.uppercase()
                )

                "20900" -> qualities["master"] = Quality(
                    id = "master", name = "至臻母带", filesize = 0,
                    bitrate = 20900, displaySize = displaySize.uppercase()
                )
            }
        }
        return qualities
    }

    override fun updateInfo(item: MusicItem): MusicItem {
        val kw = item as? KuwoMusicItem ?: return item
        return fromID(kw.id) ?: kw
    }

    override fun share(item: MusicItem): String {
        return "${item.title} - ${item.artist}\nhttps://www.kuwo.cn/play_detail/${item.id}\n(@酷我音乐)"
    }

    override fun getLyric(item: MusicItem): Lyric {
        return try {
            val params = LrcNative.buildKuwoParams(item.id.toString())
            val url = "http://newlyric.kuwo.cn/newlyric.lrc?$params"
            val response = HTTPUtils.get(url)
            if (response.isSuccessful && response.bodyBytes != null) {
                LrcParser.parseKw(response.bodyBytes).toLyric()
            } else {
                Lyric()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Lyric()
        }
    }
}

private val KW_MINFO_REGEX =
    Regex("""level:(\w+),bitrate:(\d+),format:(\w+),size:([\w.]+)""")
