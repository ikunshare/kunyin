package com.ikunshare.sound.platform.kg

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
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
import com.ikunshare.sound.platform.base.ProviderCredentials
import com.ikunshare.sound.platform.base.SearchType
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.lyric.LrcParser
import com.ikunshare.sound.utils.lyric.toLyric
import java.net.URLEncoder

class KgProvider : BaseProvider("kg") {

    override val displayName = "酷狗音乐"
    override val shortTag = "kg"
    override val supportsSyncPlaylist = true
    override val supportsImportPlaylist = true
    override val supportedSearchTypes: List<SearchType> =
        listOf(SearchType.SONG, SearchType.ALBUM, SearchType.ARTIST)

    companion object {
        private const val TAG = "KgProvider"
        private const val SEPARATOR = "、"
        private const val MAX_SINGER_COUNT = 5
        private const val SQ_ZERO_HASH = "00000000000000000000000000000000"

        private val COVER_HEADERS = mapOf(
            "KG-RC" to "1",
            "KG-THash" to "expand_search_manager.cpp:852736169:451",
            "User-Agent" to "KuGou2012-9020-ExpandSearchManager"
        )
    }

    // ===== search =====

    override fun search(keyword: String, page: Int, size: Int): MusicListResult {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://songsearch.kugou.com/song_search_v2?" +
                    "platform=AndroidFilter&iscorrection=1&keyword=$encoded" +
                    "&hifiquality=0&pagesize=$size&PrivilegeFilter=0&page=${page + 1}"
            val response = HTTPUtils.get(url)
            if (!response.isSuccessful) {
                return MusicListResult(source, false, page, size, emptyList())
            }
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())

            val data = json.safeGetObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val lists = data.safeGetArray("lists")
                ?: return MusicListResult(source, false, page, size, emptyList())

            val items = lists.mapNotNull { elem ->
                runCatching { parseSearchItem(elem.asJsonObject) }.getOrNull()
            }

            enrichWithMasterSizes(items)
            fetchCovers(items)

            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: items.size
            val hasNext = (page + 1) * size < total
            MusicListResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun parseSearchItem(obj: JsonObject): KugouMusicItem? {
        val fileHash = obj.get("FileHash")?.asString ?: return null
        if (fileHash.isEmpty()) return null

        val hqHash = obj.get("HQFileHash")?.asString ?: ""
        val sqHash = obj.get("SQFileHash")?.asString ?: ""
        val hrHash = obj.get("ResFileHash")?.asString ?: ""
        val mp3Size = obj.get("FileSize")?.asLong ?: 0L
        val hqSize = obj.get("HQFileSize")?.asLong ?: 0L
        val sqSize = obj.get("SQFileSize")?.asLong ?: 0L
        val hrSize = obj.get("ResFileSize")?.asLong ?: 0L

        val qualities = mutableMapOf<String, Quality>()
        if (mp3Size > 0) qualities["128k"] = Quality(
            id = "128k", name = "普通音质 128K",
            filesize = mp3Size, bitrate = 128, displaySize = formatSize(mp3Size)
        )
        if (hqHash.isNotEmpty() && hqSize > 0) qualities["320k"] = Quality(
            id = "320k", name = "高品音质 320K",
            filesize = hqSize, bitrate = 320, displaySize = formatSize(hqSize)
        )
        if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH && sqSize > 0) {
            qualities["flac"] = Quality(
                id = "flac", name = "无损音质 FLAC",
                filesize = sqSize, bitrate = 2000, displaySize = formatSize(sqSize)
            )
        }
        if (hrHash.isNotEmpty() && hrSize > 0) qualities["hires"] = Quality(
            id = "hires", name = "无损音质 Hi-Res",
            filesize = hrSize, bitrate = 4000, displaySize = formatSize(hrSize)
        )

        if (qualities.isEmpty()) return null

        val rawSongName = obj.get("SongName")?.asString ?: ""
        val suffix = obj.get("Suffix")?.asString ?: ""
        val songName = cleanText(
            if (suffix.isNotEmpty() && !rawSongName.contains(suffix)) "$rawSongName$suffix"
            else rawSongName
        )
        val singerName = limitSingerCount(cleanText(obj.get("SingerName")?.asString ?: ""))
        val album = cleanText(obj.get("AlbumName")?.asString ?: "")
        val duration = obj.get("Duration")?.asLong ?: 0L
        val albumId = obj.get("AlbumID")?.asString
        val audioId = obj.get("MixSongID")?.asString ?: ""
        val mvId = obj.safeGetArray("mvdata")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
            ?.get("id")?.asString

        val allHash = buildList {
            add(fileHash)
            if (hqHash.isNotEmpty()) add(hqHash)
            if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH) add(sqHash)
            if (hrHash.isNotEmpty()) add(hrHash)
        }

        return KugouMusicItem(
            id = audioId.toLongOrNull() ?: 0L,
            title = songName,
            artist = singerName,
            album = album,
            cover = "",
            duration = duration * 1000,
            qualities = qualities,
            mixsongmid = audioId.toLongOrNull() ?: 0L,
            hash = fileHash,
            allHash = allHash,
            audioId = audioId,
            albumId = albumId,
            singers = singerName.split(SEPARATOR).map { Singer(name = it.trim()) },
            mvid = mvId
        )
    }

    // ===== master size enrich（臻品母带）=====

    private fun enrichWithMasterSizes(items: List<KugouMusicItem>) {
        if (items.isEmpty()) return
        try {
            val sizes = fetchBatchMasterSizes(items.map { it.hash })
            for (item in items) {
                val size = sizes[item.hash] ?: continue
                if (size <= 0) continue
                val merged = item.qualities.toMutableMap()
                merged["master"] = Quality(
                    id = "master", name = "蝰蛇超清",
                    filesize = size, bitrate = 20900, displaySize = formatSize(size)
                )
                item.replaceQualities(merged)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enrichWithMasterSizes failed", e)
        }
    }

    private fun fetchBatchMasterSizes(hashList: List<String>): Map<String, Long> {
        if (hashList.isEmpty()) return emptyMap()
        val url = "https://gateway.kugou.com/goodsmstore/v1/get_res_privilege" +
                "?appid=1005&clientver=20049&clienttime=${System.currentTimeMillis()}&mid=NeZha"
        val resourceArray = JsonArray().apply {
            hashList.forEach { hash ->
                add(JsonObject().apply {
                    addProperty("id", 0)
                    addProperty("type", "audio")
                    addProperty("hash", hash)
                })
            }
        }
        val body = JsonObject().apply {
            addProperty("behavior", "play")
            addProperty("clientver", "20049")
            addProperty("area_code", "1")
            addProperty("quality", "128")
            add("resource", resourceArray)
            add("qualities", JsonArray().apply {
                listOf("128", "320", "flac", "high", "viper_clear").forEach { add(it) }
            })
        }
        val resp = HTTPUtils.post(
            url,
            mapOf("Content-Type" to "application/json; charset=utf-8"),
            body.toString()
        )
        if (!resp.isSuccessful) return emptyMap()

        val respJson = JsonParser.parseString(resp.body ?: return emptyMap()).asJsonObject
        if (respJson.get("error_code")?.asInt != 0) return emptyMap()
        val dataArray = respJson.safeGetArray("data") ?: return emptyMap()

        val result = mutableMapOf<String, Long>()
        for (i in 0 until minOf(dataArray.size(), hashList.size)) {
            val songData = dataArray[i]?.asJsonObject ?: continue
            val relateGoods = songData.safeGetArray("relate_goods") ?: continue
            for (j in 0 until relateGoods.size()) {
                val item = relateGoods[j].asJsonObject
                if (item.get("quality")?.asString != "viper_clear") continue
                val info = item.safeGetObject("info") ?: continue
                val filesize = info.get("filesize")?.takeIf { !it.isJsonNull }?.asLong ?: 0
                if (filesize > 0) result[hashList[i]] = filesize
                break
            }
        }
        return result
    }

    // ===== covers =====

    private fun fetchCovers(items: List<KugouMusicItem>) {
        if (items.isEmpty()) return
        try {
            val resourceArray = JsonArray()
            items.forEach { item ->
                resourceArray.add(JsonObject().apply {
                    addProperty(
                        "album_audio_id",
                        item.audioId.ifEmpty { "0" }
                    )
                    addProperty("album_id", item.albumId ?: "")
                    addProperty("hash", item.hash)
                    addProperty("id", 0)
                    addProperty("name", "${item.artist} - ${item.title}.mp3")
                    addProperty("type", "audio")
                })
            }
            val body = JsonObject().apply {
                addProperty("appid", 1001)
                addProperty("area_code", "1")
                addProperty("behavior", "play")
                addProperty("clientver", "9020")
                addProperty("need_hash_offset", 1)
                addProperty("relate", 1)
                add("resource", resourceArray)
                addProperty("token", "")
                addProperty("userid", 2626431536)
                addProperty("vip", 1)
            }
            val resp = HTTPUtils.post(
                "http://media.store.kugou.com/v1/get_res_privilege",
                COVER_HEADERS + ("Content-Type" to "application/json; charset=utf-8"),
                body.toString()
            )
            if (!resp.isSuccessful) return

            val respJson = JsonParser.parseString(resp.body ?: return).asJsonObject
            if (respJson.get("error_code")?.asInt != 0) return
            val dataArray = respJson.safeGetArray("data") ?: return

            for (i in 0 until minOf(dataArray.size(), items.size)) {
                try {
                    val info = dataArray[i].asJsonObject.safeGetObject("info") ?: continue
                    val image = info.get("image")?.takeIf { !it.isJsonNull }?.asString ?: continue
                    if (image.isEmpty()) continue
                    val sizes = info.safeGetArray("imgsize")
                    val imgUrl = if (sizes != null && sizes.size() > 0) {
                        image.replace("{size}", sizes[0].asString)
                    } else image
                    if (imgUrl.isNotEmpty()) items[i].setCover(imgUrl)
                } catch (_: Exception) {
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCovers failed", e)
        }
    }

    // ===== 其它 =====

    /** 通过 hash 查询歌曲信息（用于歌词/封面重定向）。会经过 song_search_v2 + fetchCovers。 */
    fun fromHash(hash: String): KugouMusicItem? {
        if (hash.isBlank()) return null
        return try {
            val res = search(hash, 0, 5)
            val matched = res.result.filterIsInstance<KugouMusicItem>()
                .firstOrNull { it.hash.equals(hash, ignoreCase = true) }
                ?: res.result.filterIsInstance<KugouMusicItem>().firstOrNull()
            matched
        } catch (e: Exception) {
            Log.e(TAG, "fromHash failed", e)
            null
        }
    }

    override fun getHotSearch(): List<String> {
        return try {
            val url = "http://msearch.kugou.com/api/v3/search/hot_tab" +
                    "?signature=ee44edb9d7155821412d220bcaf509dd" +
                    "&appid=1005&clientver=10026&plat=0"
            val headers = mapOf(
                "dfid" to "1sLbSM0a4sRs09lSwl4Kmbi6",
                "mid" to "156798703528610303010610",
                "clienttime" to System.currentTimeMillis().toString(),
                "userid" to "0"
            )
            val resp = HTTPUtils.get(url, headers)
            if (!resp.isSuccessful) return emptyList()
            val json = resp.json(JsonObject::class.java) ?: return emptyList()
            val list = json.safeGetObject("data")?.safeGetArray("list") ?: return emptyList()

            val result = mutableListOf<String>()
            for (i in 0 until list.size()) {
                val keywords = list[i].asJsonObject.safeGetArray("keywords") ?: continue
                for (j in 0 until keywords.size()) {
                    val kw = keywords[j].asJsonObject.get("keyword")?.asString
                    if (!kw.isNullOrBlank()) result.add(kw)
                }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "getHotSearch failed", e)
            emptyList()
        }
    }

    override fun getSearchTip(keyword: String): List<String> {
        return try {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "https://searchtip.kugou.com/getSearchTip" +
                    "?MusicTipCount=10&keyword=$encoded"
            val resp = HTTPUtils.get(url, mapOf("referer" to "https://www.kugou.com/"))
            if (!resp.isSuccessful) return emptyList()
            val json = resp.json(JsonObject::class.java) ?: return emptyList()
            val records = json.safeGetArray("data")
                ?.firstOrNull()?.asJsonObject
                ?.safeGetArray("RecordDatas")
                ?: return emptyList()
            val tips = mutableListOf<String>()
            for (i in 0 until records.size()) {
                val hint = records[i].asJsonObject.get("HintInfo")?.asString
                if (!hint.isNullOrEmpty()) tips.add(hint)
            }
            tips
        } catch (e: Exception) {
            Log.e(TAG, "getSearchTip failed", e)
            emptyList()
        }
    }

    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? = null

    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult {
        val creds = getCreds<KgCredentials>()
            ?: return MusicListResult(source, false, page, size, emptyList())
        return try {
            val resp = KgThirdsso.request(
                "favorite/song", creds,
                mapOf(
                    "userid" to creds.userid,
                    "token" to creds.token,
                    "playlist_id" to playListId,
                    "type" to "self",
                    "page" to (page + 1),
                    "size" to size,
                    "filter_local" to 1
                )
            ) ?: return MusicListResult(source, false, page, size, emptyList())

            if (resp.get("error_code")?.asInt != 0)
                return MusicListResult(source, false, page, size, emptyList())

            val data = resp.safeGetObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val songs = data.safeGetArray("songs")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val total = data.get("total")?.asInt ?: 0

            val items = songs.mapNotNull { elem ->
                runCatching { parsePlaylistSong(elem.asJsonObject) }.getOrNull()
            }
            enrichWithSongInfos(creds, items)
            val hasNext = (page + 1) * size < total
            MusicListResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "getPlayListSongs failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    override fun getUserPlaylist(): List<PlayListInfoResult> {
        val creds = getCreds<KgCredentials>() ?: return emptyList()
        return try {
            val results = mutableListOf<PlayListInfoResult>()
            var page = 1
            while (true) {
                val resp = KgThirdsso.request(
                    "favorite/selfv2/list", creds,
                    mapOf(
                        "userid" to creds.userid,
                        "token" to creds.token,
                        "page" to page,
                        "size" to 20
                    )
                ) ?: break

                if (resp.get("error_code")?.asInt != 0) break
                val data = resp.safeGetObject("data") ?: break
                val playlists = data.safeGetArray("playlists") ?: break

                for (i in 0 until playlists.size()) {
                    val pl = playlists[i].asJsonObject
                    results.add(
                        PlayListInfoResult(
                            source = source,
                            img = pl.get("pic")?.asString?.ifEmpty { null },
                            description = null,
                            author = null,
                            playListId = pl.get("playlist_id")?.asString ?: continue,
                            title = pl.get("playlist_name")?.asString,
                            totalSongs = pl.get("total")?.asInt ?: 0
                        )
                    )
                }

                val total = data.get("total")?.asInt ?: 0
                if (results.size >= total) break
                page++
            }
            results
        } catch (e: Exception) {
            Log.e(TAG, "getUserPlaylist failed", e)
            emptyList()
        }
    }

    override fun getUserInfo(): UserInfo? {
        val creds = getCreds<KgCredentials>() ?: return null
        return KgThirdsso.getUserInfo(creds)
    }

    override fun refreshLogin(): ProviderCredentials? {
        val creds = getCreds<KgCredentials>() ?: return null
        val refreshed = KgThirdsso.refreshToken(creds)
        if (refreshed != null) {
            credentials = refreshed
        }
        return refreshed
    }

    override fun updateInfo(item: MusicItem): MusicItem = item

    override fun share(item: MusicItem): String {
        val hash = (item as? KugouMusicItem)?.hash ?: ""
        return "${item.title} - ${item.artist}\nhttps://www.kugou.com/song/#hash=$hash\n(@酷狗音乐)"
    }

    override fun getLyric(item: MusicItem): Lyric {
        val kg = item as? KugouMusicItem ?: return Lyric()
        return try {
            // 直链重定向：已指定 accesskey + download_id，跳过 song_search 直接下载。
            if (!kg.lyricAccessKey.isNullOrBlank() && !kg.lyricDownloadId.isNullOrBlank()) {
                downloadKgLyric(kg.lyricAccessKey, kg.lyricDownloadId, 0)
                    ?.let { return it }
                // 直链失败则回退到常规搜索流程。
            }

            val candidates = searchKgLyricCandidates(
                keyword = "${kg.artist} - ${kg.title}",
                duration = kg.duration,
                hash = kg.hash,
                audioId = kg.audioId
            )
            val first = candidates.firstOrNull() ?: return Lyric()
            downloadKgLyric(first.accessKey, first.downloadId, first.contenttype) ?: Lyric()
        } catch (e: Exception) {
            Log.e(TAG, "getLyric failed", e)
            Lyric()
        }
    }

    /** 酷狗歌词候选（搜索结果的一条）。[lyricTypes] 为延迟探测的歌词类型（未探测时为 null）。 */
    data class KgLyricCandidate(
        val accessKey: String,
        val downloadId: String,
        val contenttype: Int,
        val song: String,
        val singer: String,
        val language: String,
        val durationMs: Long,
        val score: Int,
        val lyricTypes: KgLyricTypes? = null
    )

    /** 一条歌词包含的内容类型。 */
    data class KgLyricTypes(
        val hasLine: Boolean,      // 逐行（基础 LRC）
        val hasWordByWord: Boolean,// 逐字
        val hasTranslation: Boolean,// 翻译
        val hasRomaji: Boolean,    // 音译
        val hasWordRomaji: Boolean,// 逐字音译
        val hasPhonetic: Boolean   // AI 谐音
    )

    /**
     * 搜索酷狗歌词候选列表。[hash]/[audioId] 可空——为非酷狗源做歌词重定向时只用关键词+时长。
     * 失败或无结果返回空列表。
     */
    fun searchKgLyricCandidates(
        keyword: String,
        duration: Long,
        hash: String? = null,
        audioId: String? = null
    ): List<KgLyricCandidate> {
        val searchParams = sortedMapOf(
            "album_audio_id" to (audioId?.takeIf { it.isNotBlank() } ?: "0"),
            "appid" to "1005",
            "clientver" to "20669",
            "duration" to duration.toString(),
            "keyword" to keyword,
            "lrctxt" to "1",
            "man" to "yes",
            "query_copyright" to "1",
            "vocab" to "0"
        )
        // 源是酷狗歌时带上 hash 提升匹配，非酷狗源不传。
        if (!hash.isNullOrBlank()) searchParams["hash"] = hash.lowercase()

        val searchSig = kgSign(searchParams)
        val searchQuery = searchParams.entries.joinToString("&") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8").replace("+", "%20")}"
        }
        val lyricHeaders = mapOf(
            "clienttime" to (System.currentTimeMillis() / 1000).toString(),
            "mid" to "-",
            "dfid" to "-"
        )

        val hosts = listOf("lyrics2.kugou.com", "lyrics.kugou.com", "krcsretry.kugou.com")
        var searchRes: JsonObject? = null
        for (host in hosts) {
            val url = "https://$host/v1/search?$searchQuery&signature=$searchSig"
            val res = HTTPUtils.get(url, lyricHeaders).json(JsonObject::class.java)
            if (res != null && res.get("status")?.asInt == 200 &&
                res.get("error_code")?.asInt != 20006
            ) {
                searchRes = res
                break
            }
        }
        val candidates = searchRes?.safeGetArray("candidates") ?: return emptyList()

        val out = ArrayList<KgLyricCandidate>(candidates.size())
        for (i in 0 until candidates.size()) {
            val c = candidates[i].asJsonObject
            val accessKey = c.get("accesskey")?.asString ?: continue
            val downloadId = c.get("id")?.asString
                ?: c.get("download_id")?.asString ?: continue
            out.add(
                KgLyricCandidate(
                    accessKey = accessKey,
                    downloadId = downloadId,
                    contenttype = c.get("contenttype")?.asInt ?: 0,
                    song = c.get("song")?.asString ?: "",
                    singer = c.get("singer")?.asString ?: "",
                    language = c.get("language")?.asString ?: "",
                    durationMs = c.get("duration")?.asLong ?: 0L,
                    score = c.get("score")?.asInt ?: 0
                )
            )
        }
        return out
    }

    /**
     * 通过 accesskey + download_id 直接下载并解析酷狗歌词。
     * 成功返回非空 [Lyric]，失败返回 null（调用方决定是否回退）。
     */
    private fun downloadKgLyric(
        accessKey: String,
        downloadId: String,
        contenttype: Int
    ): Lyric? {
        val result = fetchKgLyricResult(accessKey, downloadId, contenttype) ?: return null
        val lyric = result.toLyric()
        return if (lyric.isEmpty()) null else lyric
    }

    /** 下载并解析酷狗歌词，返回原始 [LrcParser.LyricResult]（保留各内容类型字段）。失败返回 null。 */
    private fun fetchKgLyricResult(
        accessKey: String,
        downloadId: String,
        contenttype: Int
    ): LrcParser.LyricResult? {
        return try {
            val lyricHeaders = mapOf(
                "clienttime" to (System.currentTimeMillis() / 1000).toString(),
                "mid" to "-",
                "dfid" to "-"
            )
            val dlParams = sortedMapOf(
                "accesskey" to accessKey,
                "appid" to "1005",
                "clientver" to "20669",
                "contenttype" to contenttype.toString(),
                "download_id" to downloadId
            )
            val dlSig = kgSign(dlParams)
            val dlQuery = dlParams.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
            val hosts = listOf("lyrics2.kugou.com", "lyrics.kugou.com", "krcsretry.kugou.com")
            var dlRes: JsonObject? = null
            for (host in hosts) {
                val url = "https://$host/v2/download?$dlQuery&signature=$dlSig"
                val res = HTTPUtils.get(url, lyricHeaders).json(JsonObject::class.java)
                if (res != null && res.get("status")?.asInt == 1 &&
                    res.get("error_code")?.asInt != 20006
                ) {
                    dlRes = res
                    break
                }
            }
            if (dlRes == null) return null

            val data = dlRes.getAsJsonObject("data") ?: return null
            val base64Content = data.get("content")?.asString ?: return null
            val decoded = Base64.decode(base64Content, Base64.DEFAULT)
            LrcParser.parseKg(decoded)
        } catch (e: Exception) {
            Log.e(TAG, "fetchKgLyricResult failed", e)
            null
        }
    }

    /** 探测一条候选歌词包含的内容类型（需下载解析）。失败返回 null。 */
    fun probeKgLyricTypes(accessKey: String, downloadId: String, contenttype: Int): KgLyricTypes? {
        val r = fetchKgLyricResult(accessKey, downloadId, contenttype) ?: return null
        // 酷狗 KRC 本身是逐字格式：chase 有内容即逐字，lyric 为去掉词时间戳的逐行文本。
        return KgLyricTypes(
            hasLine = r.lyric.isNotBlank(),
            hasWordByWord = r.chase.isNotBlank(),
            hasTranslation = r.trans.isNotBlank(),
            hasRomaji = r.roma.isNotBlank(),
            hasWordRomaji = r.chroma.isNotBlank(),
            hasPhonetic = r.phonetic.isNotBlank()
        )
    }

    // ===== helpers =====

    private fun enrichWithSongInfos(creds: KgCredentials, items: List<KugouMusicItem>) {
        if (items.isEmpty()) return
        try {
            val songIds = items.map { it.audioId }
            val resp = KgThirdsso.request(
                "song/infos", creds,
                mapOf(
                    "userid" to creds.userid,
                    "token" to creds.token,
                    "songs_id" to songIds
                )
            ) ?: return
            if (resp.get("error_code")?.asInt != 0) return
            val data = resp.safeGetObject("data") ?: return
            val songs = data.safeGetArray("songs") ?: return

            val sizeMap = mutableMapOf<String, SongSizes>()
            for (i in 0 until songs.size()) {
                val s = songs[i].asJsonObject
                val id = s.get("song_id")?.asString ?: continue
                sizeMap[id] = SongSizes(
                    lq = s.get("song_size")?.asLong ?: 0,
                    hq = s.get("song_size_hq")?.asLong ?: 0,
                    sq = s.get("song_size_sq")?.asLong ?: 0,
                    pq = s.get("song_size_pq")?.asLong ?: 0,
                    vcq = s.get("song_size_vcq")?.asLong ?: 0,
                    aq = s.get("song_size_aq")?.asLong ?: 0
                )
            }

            for (item in items) {
                val sizes = sizeMap[item.audioId] ?: continue
                val updated = item.qualities.toMutableMap()
                updated["128k"]?.let {
                    updated["128k"] =
                        it.copy(filesize = sizes.lq, displaySize = formatSize(sizes.lq))
                }
                updated["320k"]?.let {
                    updated["320k"] =
                        it.copy(filesize = sizes.hq, displaySize = formatSize(sizes.hq))
                }
                updated["flac"]?.let {
                    updated["flac"] =
                        it.copy(filesize = sizes.sq, displaySize = formatSize(sizes.sq))
                }
                updated["hires"]?.let {
                    updated["hires"] =
                        it.copy(filesize = sizes.vcq, displaySize = formatSize(sizes.vcq))
                }
                updated["master"]?.let {
                    updated["master"] =
                        it.copy(filesize = sizes.aq, displaySize = formatSize(sizes.aq))
                }
                item.replaceQualities(updated)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enrichWithSongInfos failed", e)
        }
    }

    private data class SongSizes(
        val lq: Long, val hq: Long, val sq: Long,
        val pq: Long, val vcq: Long, val aq: Long
    )

    private fun parsePlaylistSong(obj: JsonObject): KugouMusicItem? {
        val hash = obj.get("hash")?.asString ?: return null
        if (hash.isEmpty()) return null

        val songId = obj.get("song_id")?.asString ?: ""
        val songName = obj.get("song_name")?.asString ?: ""
        val singerName = limitSingerCount(obj.get("singer_name")?.asString ?: "")
        val albumName = obj.get("album_name")?.asString ?: ""
        val cover = obj.get("album_img_medium")?.asString ?: ""
        val duration = obj.get("duration")?.asLong ?: 0L

        val singers = obj.safeGetArray("singers")?.mapNotNull { s ->
            val so = s.asJsonObject
            val name = so.get("singer_name")?.asString ?: return@mapNotNull null
            Singer(name = name)
        } ?: singerName.split(SEPARATOR).map { Singer(name = it.trim()) }

        val supportQuality = obj.get("support_quality")?.asString ?: ""
        val qualities = buildQualitiesFromSupport(supportQuality)
        if (qualities.isEmpty()) return null

        return KugouMusicItem(
            id = songId.toLongOrNull() ?: 0L,
            title = songName,
            artist = singerName,
            album = albumName,
            cover = cover,
            duration = duration,
            qualities = qualities,
            mixsongmid = songId.toLongOrNull() ?: 0L,
            hash = hash,
            allHash = listOf(hash),
            audioId = songId,
            albumId = obj.get("album_id")?.asString,
            singers = singers,
            mvid = obj.get("mv_id")?.asString?.ifEmpty { null }
        )
    }

    private fun buildQualitiesFromSupport(support: String): Map<String, Quality> {
        val codes = support.split(",").map { it.trim() }
        val qualities = mutableMapOf<String, Quality>()
        if ("LQ" in codes || "HQ" in codes) {
            qualities["128k"] =
                Quality(id = "128k", name = "普通音质 128K", filesize = 0, bitrate = 128)
        }
        if ("SQ" in codes || "PQ" in codes) {
            qualities["320k"] =
                Quality(id = "320k", name = "高品音质 320K", filesize = 0, bitrate = 320)
        }
        if ("VCQ" in codes) {
            qualities["flac"] =
                Quality(id = "flac", name = "无损音质 FLAC", filesize = 0, bitrate = 2000)
        }
        if ("TQ" in codes) {
            qualities["hires"] =
                Quality(id = "hires", name = "无损音质 Hi-Res", filesize = 0, bitrate = 4000)
        }
        if ("AQ" in codes) {
            qualities["master"] =
                Quality(id = "master", name = "蝰蛇超清", filesize = 0, bitrate = 20900)
        }
        return qualities
    }

    // ===== MV =====

    override fun supportsMv(item: MusicItem): Boolean = !item.mvid.isNullOrBlank()

    override fun getMvQualities(item: MusicItem): List<MvQuality> {
        val videoId = item.mvid?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val mvInfo = fetchMvInfo(videoId) ?: return emptyList()
            buildList {
                mvInfo.getQuality("fhd")?.let { (hash, size) ->
                    add(
                        MvQuality(
                            quality = hash,
                            displayName = "蓝光画质",
                            displaySize = formatSize(size)
                        )
                    )
                }
                mvInfo.getQuality("hd")?.let { (hash, size) ->
                    add(
                        MvQuality(
                            quality = hash,
                            displayName = "超清画质",
                            displaySize = formatSize(size)
                        )
                    )
                }
                mvInfo.getQuality("qhd")?.let { (hash, size) ->
                    add(
                        MvQuality(
                            quality = hash,
                            displayName = "高清画质",
                            displaySize = formatSize(size)
                        )
                    )
                }
                mvInfo.getQuality("sd")?.let { (hash, size) ->
                    add(
                        MvQuality(
                            quality = hash,
                            displayName = "标清画质",
                            displaySize = formatSize(size)
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "KG MV qualities failed", e)
            emptyList()
        }
    }

    override fun getMvUrl(item: MusicItem, quality: String): MvUrlResult {
        val videoId = item.mvid?.takeIf { it.isNotBlank() }
            ?: return MvUrlResult(source, null, quality, "无 mvid")
        return try {
            val hash = quality.lowercase()
            val key = md5("${hash}kugoumvcloud")
            val time = System.currentTimeMillis() / 1000
            val sigRaw = "OIlwieks28dk2k092lksi2UIkp" +
                    "appid=1005" +
                    "backupdomain=1" +
                    "clienttime=$time" +
                    "clientver=20609" +
                    "cmd=123" +
                    "dfid=08TyVG0PFspm0LUMKk2uOiI1" +
                    "ext=mp4" +
                    "hash=$hash" +
                    "key=$key" +
                    "mid=77752093425314814852697061885572080940" +
                    "pid=2" +
                    "token=" +
                    "userid=0" +
                    "uuid=-" +
                    "video_id=$videoId" +
                    "OIlwieks28dk2k092lksi2UIkp"
            val signature = md5(sigRaw)
            val url = "https://trackermv.kugou.com/interface/index?" +
                    "clientver=20609&userid=0&cmd=123&ext=mp4" +
                    "&key=$key" +
                    "&mid=77752093425314814852697061885572080940" +
                    "&pid=2" +
                    "&dfid=08TyVG0PFspm0LUMKk2uOiI1" +
                    "&hash=$hash" +
                    "&uuid=-" +
                    "&appid=1005&token=&backupdomain=1" +
                    "&signature=$signature" +
                    "&clienttime=$time" +
                    "&video_id=$videoId"
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return MvUrlResult(source, null, quality, "HTTP ${resp.status}")
            val body = resp.body ?: return MvUrlResult(source, null, quality, "空响应")
            val json = JsonParser.parseString(body).asJsonObject
            val data = json.getAsJsonObject("data")
                ?: return MvUrlResult(source, null, quality, "无 data")
            val downUrl = data.getAsJsonObject(hash)?.get("downurl")?.asString
                ?: return MvUrlResult(source, null, quality, "无下载链接")
            MvUrlResult(source, downUrl, quality)
        } catch (e: Exception) {
            Log.w(TAG, "KG MV url failed", e)
            MvUrlResult(source, null, quality, e.message ?: "请求异常")
        }
    }

    private fun fetchMvInfo(videoId: String): MvInfoData? {
        val time = System.currentTimeMillis() / 1000
        val postBody = """{"data":[{"video_id":$videoId}]}"""
        val sigRaw = "OIlwieks28dk2k092lksi2UIkp" +
                "appid=1005" +
                "clienttime=$time" +
                "clientver=20609" +
                "dfid=08TyVG0PFspm0LUMKk2uOiI1" +
                "mid=77752093425314814852697061885572080940" +
                "token=" +
                "userid=0" +
                "uuid=6d107aa2f28fa7dbef52d223156d79a1" +
                postBody +
                "OIlwieks28dk2k092lksi2UIkp"
        val signature = md5(sigRaw)
        val url = "https://gateway.kugou.com/openapi/v1/unique/union_mv_play?" +
                "clientver=20609&userid=0" +
                "&mid=77752093425314814852697061885572080940" +
                "&dfid=08TyVG0PFspm0LUMKk2uOiI1" +
                "&uuid=6d107aa2f28fa7dbef52d223156d79a1" +
                "&appid=1005&token=" +
                "&signature=$signature" +
                "&clienttime=$time"
        val resp = HTTPUtils.post(url, mapOf("Content-Type" to "application/json"), postBody)
        if (!resp.isSuccessful) return null
        val body = resp.body ?: return null
        val json = JsonParser.parseString(body).asJsonObject
        val dataArr = json.getAsJsonArray("data") ?: return null
        if (dataArr.size() == 0) return null
        val mvInfo = dataArr[0].asJsonObject
            .getAsJsonObject("mv_info")
            ?.getAsJsonObject("h265") ?: return null
        return MvInfoData(mvInfo)
    }

    private class MvInfoData(private val json: JsonObject) {
        fun getQuality(prefix: String): Pair<String, Long>? {
            val hash = json.get("${prefix}_hash")?.asString?.takeIf { it.isNotBlank() }
                ?: return null
            val size = json.get("${prefix}_filesize")?.asLong ?: 0L
            if (size <= 0) return null
            return hash to size
        }
    }

    private fun md5(input: String): String {
        val md = java.security.MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun cleanText(text: String): String =
        text.replace("<em>", "").replace("</em>", "").replace("/", " ")

    private fun limitSingerCount(names: String): String {
        if (!names.contains(SEPARATOR)) return names
        val list = names.split(SEPARATOR)
        return if (list.size <= MAX_SINGER_COUNT) names
        else list.take(MAX_SINGER_COUNT).joinToString(SEPARATOR)
    }

    @SuppressLint("DefaultLocale")
    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val kb = bytes / 1024.0
        return when {
            kb < 1024 -> String.format("%.1fKB", kb)
            kb < 1024 * 1024 -> String.format("%.1fMB", kb / 1024.0)
            else -> String.format("%.1fGB", kb / (1024.0 * 1024.0))
        }
    }

    private fun JsonObject.safeGetObject(key: String): JsonObject? {
        val e = get(key) ?: return null
        return if (e.isJsonObject) e.asJsonObject else null
    }

    private fun JsonObject.safeGetArray(key: String): JsonArray? {
        val e = get(key) ?: return null
        return if (e.isJsonArray) e.asJsonArray else null
    }

    // ===== 专辑搜索 / 详情 =====

    /** 按 key 排序后拼成 k=v&k=v + body,前后裹 secret,做 MD5 签名(对应 Python 版本 sign()) */
    private fun kgSign(params: Map<String, String>, body: String = ""): String {
        val sorted = params.toSortedMap()
        val joined = sorted.entries.joinToString("") { "${it.key}=${it.value}" }
        val raw = "OIlwieks28dk2k092lksi2UIkp" + joined + body + "OIlwieks28dk2k092lksi2UIkp"
        return md5(raw)
    }

    private fun buildKgQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    override fun searchAlbum(keyword: String, page: Int, size: Int): AlbumSearchResult {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "appid" to "1005",
            "category" to "1",
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "current_mixsongid" to "0",
            "dfid" to "-",
            "iscorrection" to "1",
            "keyword" to keyword,
            "mid" to "-",
            "page" to (page + 1).toString(),
            "pagesize" to size.toString(),
            "platform" to "AndroidFilter",
            "requestid" to "-",
            "search_source" to "手动输入",
            "searchsong" to "0",
            "sorttype" to "0",
            "tag" to "em",
            "token" to "",
            "userid" to "0",
            "uuid" to "-"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://complexsearch.kugou.com/v1/search/album?" + buildKgQuery(signed)

        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return AlbumSearchResult(source, false, page, size, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val data = json.safeGetObject("data")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val lists = data.safeGetArray("lists")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val items = lists.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseKgAlbumEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchAlbum failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseKgAlbumEntry(obj: JsonObject): AlbumInfoResult? {
        val id = obj.get("albumid")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        val rawName = obj.get("albumname")?.asString ?: return null
        val singerRaw = obj.get("singer")?.asString
        val pubDate = obj.get("publish_time")?.asString
        val publishTime = pubDate?.let { parseKgDate(it) }
        return AlbumInfoResult(
            source = source,
            albumId = id.toString(),
            name = cleanText(rawName),
            cover = obj.get("img")?.asString,
            artist = singerRaw?.let { cleanText(it) },
            artistId = obj.get("singerid")?.asString,
            publishTime = publishTime,
            company = obj.get("company")?.asString?.takeIf { it.isNotBlank() },
            subType = null,
            description = obj.get("intro")?.asString?.takeIf { it.isNotBlank() },
            size = obj.get("songcount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        )
    }

    private fun parseKgDate(raw: String): Long? {
        if (raw.isBlank() || raw.startsWith("0000")) return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .parse(raw)?.time
        } catch (_: Exception) {
            null
        }
    }

    override fun getAlbumInfo(albumId: String): AlbumInfoResult? {
        val payload = fetchKgAlbumPayload(albumId) ?: return null
        val info = payload.safeGetObject("album_info") ?: return null
        val publish = info.get("publish_date")?.asString?.let { parseKgDate(it) }
        val cover = info.get("sizable_cover")?.asString
            ?.replace("{size}", "480")
        return AlbumInfoResult(
            source = source,
            albumId = albumId,
            name = info.get("album_name")?.asString ?: return null,
            cover = cover,
            artist = info.get("author_name")?.asString,
            artistId = info.safeGetArray("authors")
                ?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject
                ?.get("author_id")?.asString,
            publishTime = publish,
            company = info.get("publish_company")?.asString,
            subType = info.get("type")?.asString,
            description = info.get("intro")?.asString
                ?: info.get("short_intro_v2")?.asString,
            size = payload.safeGetObject("song_data")?.get("total")
                ?.takeIf { !it.isJsonNull }?.asInt ?: 0
        )
    }

    override fun getAlbumSongs(albumId: String): MusicListResult {
        val payload = fetchKgAlbumPayload(albumId)
            ?: return MusicListResult(source, false, 0, 50, emptyList())
        val songData = payload.safeGetObject("song_data")
            ?: return MusicListResult(source, false, 0, 50, emptyList())
        val list = songData.safeGetArray("song_list")
            ?: return MusicListResult(source, false, 0, 50, emptyList())
        val items = list.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            parseKgAlbumSong(obj)
        }
        enrichWithMasterSizes(items)
        return MusicListResult(source, false, 0, items.size.coerceAtLeast(1), items)
    }

    private fun fetchKgAlbumPayload(albumId: String): JsonObject? {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "album_id" to albumId,
            "appid" to "1005",
            "area_code" to "1",
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "dfid" to "-",
            "is_buy" to "0",
            "mid" to "-",
            "page" to "1",
            "pagesize" to "50",
            "show_album_audios_timelength" to "1",
            "show_album_info" to "1",
            "show_album_tags" to "1",
            "show_awards" to "1",
            "show_classical_author" to "1",
            "show_dycover" to "1",
            "show_short_intro_v2" to "1",
            "userid" to "0"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://openapi.kugou.com/v1/union/album/audios?" + buildKgQuery(signed)
        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return null
            val json = resp.json(JsonObject::class.java) ?: return null
            if (json.get("status")?.asInt != 1) return null
            json.safeGetObject("data")
        } catch (e: Exception) {
            Log.e(TAG, "fetchKgAlbumPayload failed", e)
            null
        }
    }

    private fun parseKgAlbumSong(obj: JsonObject): KugouMusicItem? {
        val base = obj.safeGetObject("base") ?: return null
        val audio = obj.safeGetObject("audio_info") ?: return null
        val albumInfo = obj.safeGetObject("album_info")

        val audioId = base.get("album_audio_id")?.asString ?: ""
        val name = base.get("audio_name")?.asString ?: return null
        val authorName = base.get("author_name")?.asString ?: ""
        val albumIdAttr = base.get("album_id")?.asString
        val albumName = albumInfo?.get("album_name")?.asString ?: ""
        val coverTpl = albumInfo?.get("cover")?.asString
        val cover = coverTpl?.replace("{size}", "480") ?: ""

        val mp3Hash = audio.get("hash_128")?.asString ?: audio.get("hash")?.asString ?: ""
        if (mp3Hash.isEmpty()) return null
        val hqHash = audio.get("hash_320")?.asString ?: ""
        val sqHash = audio.get("hash_flac")?.asString ?: ""
        val hrHash = audio.get("hash_high")?.asString ?: ""

        val mp3Size = audio.get("filesize_128")?.asLong
            ?: audio.get("filesize")?.asLong ?: 0L
        val hqSize = audio.get("filesize_320")?.asLong ?: 0L
        val sqSize = audio.get("filesize_flac")?.asLong ?: 0L
        val hrSize = audio.get("filesize_high")?.asLong ?: 0L

        val qualities = mutableMapOf<String, Quality>()
        if (mp3Size > 0) qualities["128k"] = Quality(
            id = "128k", name = "普通音质 128K",
            filesize = mp3Size, bitrate = 128, displaySize = formatSize(mp3Size)
        )
        if (hqHash.isNotEmpty() && hqSize > 0) qualities["320k"] = Quality(
            id = "320k", name = "高品音质 320K",
            filesize = hqSize, bitrate = 320, displaySize = formatSize(hqSize)
        )
        if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH && sqSize > 0) qualities["flac"] =
            Quality(
                id = "flac", name = "无损音质 FLAC",
                filesize = sqSize, bitrate = 2000, displaySize = formatSize(sqSize)
            )
        if (hrHash.isNotEmpty() && hrSize > 0) qualities["hires"] = Quality(
            id = "hires", name = "无损音质 Hi-Res",
            filesize = hrSize, bitrate = 4000, displaySize = formatSize(hrSize)
        )
        if (qualities.isEmpty()) return null

        val mvId = obj.safeGetArray("mvdata")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
            ?.get("id")?.asString

        val duration = audio.get("duration")?.asLong ?: 0L

        val allHash = buildList {
            add(mp3Hash)
            if (hqHash.isNotEmpty()) add(hqHash)
            if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH) add(sqHash)
            if (hrHash.isNotEmpty()) add(hrHash)
        }

        return KugouMusicItem(
            id = audioId.toLongOrNull() ?: 0L,
            title = cleanText(name),
            artist = limitSingerCount(cleanText(authorName)),
            album = albumName,
            cover = cover,
            duration = duration,
            qualities = qualities,
            mixsongmid = audioId.toLongOrNull() ?: 0L,
            hash = mp3Hash,
            allHash = allHash,
            audioId = audioId,
            albumId = albumIdAttr,
            singers = authorName.split(SEPARATOR).map { Singer(name = it.trim()) },
            mvid = mvId
        )
    }

    // ===== 歌手搜索 / 详情 =====

    override fun searchArtist(keyword: String, page: Int, size: Int): ArtistSearchResult {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "appid" to "1005",
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "current_mixsongid" to "0",
            "dfid" to "-",
            "iscorrection" to "1",
            "keyword" to keyword,
            "mid" to "-",
            "page" to (page + 1).toString(),
            "pagesize" to size.toString(),
            "platform" to "AndroidFilter",
            "requestid" to "-",
            "search_source" to "手动输入",
            "tag" to "em",
            "token" to "",
            "userid" to "0",
            "uuid" to "-"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://complexsearch.kugou.com/v1/search/author?" + buildKgQuery(signed)

        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) {
                return ArtistSearchResult(source, false, page, size, emptyList())
            }
            val json = resp.json(JsonObject::class.java)
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            if (json.get("status")?.asInt != 1) {
                return ArtistSearchResult(source, false, page, size, emptyList())
            }
            val data = json.safeGetObject("data")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val lists = data.safeGetArray("lists")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val items = lists.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseKgAuthorEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            ArtistSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchArtist failed", e)
            ArtistSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseKgAuthorEntry(obj: JsonObject): ArtistInfoResult? {
        val authorId = obj.get("AuthorId")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        if (authorId <= 0) return null
        val name = obj.get("AuthorName")?.asString ?: return null
        val avatar = obj.get("Avatar")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.replace("{size}", "240")
        return ArtistInfoResult(
            source = source,
            artistId = authorId.toString(),
            name = cleanText(name),
            avatar = avatar,
            albumCount = obj.get("AlbumCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            musicCount = obj.get("AudioCount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            fansCount = obj.get("FansNum")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
            description = null
        )
    }

    /**
     * 歌手详情 + 歌曲列表同一个接口（author/audios）：data.author_info 是头部信息，
     * data.songs 是分页歌曲。getArtistInfo / getArtistSongs 各调一次，前者只取一页凑头部。
     */
    private fun fetchKgAuthorPayload(authorId: String, page: Int, pagesize: Int): JsonObject? {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "album_audio_id" to "0",
            "appid" to "1005",
            "area_code" to "1",
            "author_id" to authorId,
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "dfid" to "-",
            "mid" to "-",
            "mvdata_need" to "1",
            "need_song_list" to "1",
            "page" to (page + 1).toString(),
            "pagesize" to pagesize.toString(),
            "replace_api_version" to "1",
            "replace_need" to "1",
            "show_audio_tag" to "1",
            "sort" to "1",
            "uuid" to "-"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://gateway.kugou.com/openapi/v2/union/author/audios?" + buildKgQuery(signed)
        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return null
            val json = resp.json(JsonObject::class.java) ?: return null
            if (json.get("status")?.asInt != 1) return null
            json.safeGetObject("data")
        } catch (e: Exception) {
            Log.e(TAG, "fetchKgAuthorPayload failed", e)
            null
        }
    }

    override fun getArtistInfo(artistId: String): ArtistInfoResult? {
        val data = fetchKgAuthorPayload(artistId, 0, 1) ?: return null
        val authorInfo = data.safeGetObject("author_info") ?: return null
        val base = authorInfo.safeGetObject("base")
        val singer = authorInfo.safeGetObject("singer_info")
        val name = singer?.get("singername")?.asString
            ?: base?.get("author_name")?.asString
            ?: return null
        val avatar = (base?.get("avatar")?.asString ?: singer?.get("imgurl")?.asString)
            ?.takeIf { it.isNotBlank() }
            ?.replace("{size}", "480")
        val intro = singer?.get("intro")?.asString?.takeIf { it.isNotBlank() }
            ?: singer?.get("profile")?.asString?.takeIf { it.isNotBlank() }
        return ArtistInfoResult(
            source = source,
            artistId = artistId,
            name = cleanText(name),
            avatar = avatar,
            albumCount = singer?.get("albumcount")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            musicCount = (singer?.get("songcount") ?: data.get("total"))
                ?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            fansCount = 0,
            description = intro
        )
    }

    override fun supportsArtistAlbums(): Boolean = true
    override fun supportsArtistMvs(): Boolean = true

    override fun createMvItem(vid: String, title: String, cover: String): MusicItem =
        KugouMusicItem(
            id = 0L,
            title = title,
            artist = "",
            album = "",
            cover = cover,
            duration = 0,
            qualities = mapOf(
                "128k" to Quality(id = "128k", name = "普通音质 128K", filesize = 0, bitrate = 128)
            ),
            mixsongmid = 0L,
            hash = "",
            allHash = emptyList(),
            audioId = "",
            albumId = null,
            singers = null,
            mvid = vid
        )

    override fun getArtistAlbums(artistId: String, page: Int, size: Int): AlbumSearchResult {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "appid" to "1005",
            "area_code" to "1",
            "category" to "1",
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "dfid" to "-",
            "mid" to "-",
            "page" to (page + 1).toString(),
            "pagesize" to size.toString(),
            "plat" to "1",
            "show_album_tag" to "0",
            "singerid" to artistId,
            "token" to "",
            "userid" to "0",
            "uuid" to "-",
            "version" to "20669"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://gateway.kugou.com/ocean/v6/singer/album?" + buildKgQuery(signed)
        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return AlbumSearchResult(source, false, page, size, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            if (json.get("status")?.asInt != 1) {
                return AlbumSearchResult(source, false, page, size, emptyList())
            }
            val data = json.safeGetObject("data")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val info = data.safeGetArray("info")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val items = info.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val albumId =
                    obj.get("albumid")?.takeIf { !it.isJsonNull }?.asLong ?: return@mapNotNull null
                val name = obj.get("albumname")?.asString ?: return@mapNotNull null
                val pubTime = obj.get("publishtime")?.asString?.let { parseKgDate(it.take(10)) }
                AlbumInfoResult(
                    source = source,
                    albumId = albumId.toString(),
                    name = name,
                    cover = obj.get("imgurl")?.asString?.replace("{size}", "480"),
                    artist = obj.get("singername")?.asString,
                    artistId = obj.get("singerid")?.asString,
                    publishTime = pubTime,
                    company = null,
                    subType = null,
                    description = null,
                    size = obj.get("songcount")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                )
            }
            val hasNext = (page + 1) * size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAlbums failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    override fun getArtistMvs(artistId: String, page: Int, size: Int): ArtistMvResult {
        val time = System.currentTimeMillis() / 1000
        val params = mapOf(
            "appid" to "1005",
            "author_id" to artistId,
            "clienttime" to time.toString(),
            "clientver" to "20669",
            "dfid" to "-",
            "mid" to "-",
            "page" to (page + 1).toString(),
            "pagesize" to size.toString(),
            "tag_idx" to "",
            "uuid" to "-"
        )
        val signed = params + ("signature" to kgSign(params))
        val url = "https://openapicdnretry.kugou.com/kmr/v1/author/videos?" + buildKgQuery(signed)
        return try {
            val resp = HTTPUtils.get(url)
            if (!resp.isSuccessful) return ArtistMvResult(source, false, page, size, 0, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            if (json.get("status")?.asInt != 1) {
                return ArtistMvResult(source, false, page, size, 0, emptyList())
            }
            val total = json.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val data = json.safeGetArray("data")
                ?: return ArtistMvResult(source, false, page, size, total, emptyList())
            val items = data.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val vid = obj.get("video_id")?.takeIf { !it.isJsonNull }?.asString
                    ?.takeIf { it.isNotBlank() && it != "0" } ?: return@mapNotNull null
                val title = obj.get("video_name")?.asString ?: ""
                val cover = obj.get("hdpic")?.asString
                    ?.replace("{size}", "480") ?: ""
                val duration = obj.get("timelength")?.asLong ?: 0L
                val playCount = obj.get("history_heat")?.asLong ?: 0L
                val pubTime = obj.get("publish_date")?.asString
                    ?.take(10)?.let { parseKgDate(it) } ?: 0L
                ArtistMvItem(
                    source = source,
                    vid = vid,
                    title = title,
                    cover = cover,
                    duration = duration,
                    playCount = playCount,
                    pubTime = pubTime
                )
            }
            val hasNext = (page + 1) * size < total
            ArtistMvResult(source, hasNext, page, size, total, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistMvs failed", e)
            ArtistMvResult(source, false, page, size, 0, emptyList())
        }
    }

    override fun getArtistSongs(artistId: String, page: Int, size: Int): MusicListResult {
        val data = fetchKgAuthorPayload(artistId, page, size)
            ?: return MusicListResult(source, false, page, size, emptyList())
        val songs = data.safeGetArray("songs")
            ?: return MusicListResult(source, false, page, size, emptyList())
        val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val items = songs.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            runCatching { parseKgAuthorSong(obj) }.getOrNull()
        }
        enrichWithMasterSizes(items)
        val hasNext = (page + 1) * size < total
        return MusicListResult(source, hasNext, page, size, items.distinctBy { it.uniqueKey })
    }

    private fun parseKgAuthorSong(obj: JsonObject): KugouMusicItem? {
        val audio = obj.safeGetObject("audio_info") ?: return null
        val mp3Hash = audio.get("hash")?.asString?.takeIf { it.isNotEmpty() } ?: return null

        val hqHash = audio.get("hash_320")?.asString ?: ""
        val sqHash = audio.get("hash_flac")?.asString ?: ""
        val hrHash = audio.get("hash_high")?.asString ?: ""
        val mp3Size = audio.get("filesize")?.asLong ?: 0L
        val hqSize = audio.get("filesize_320")?.asLong ?: 0L
        val sqSize = audio.get("filesize_flac")?.asLong ?: 0L
        val hrSize = audio.get("filesize_high")?.asLong ?: 0L

        val qualities = mutableMapOf<String, Quality>()
        if (mp3Size > 0) qualities["128k"] = Quality(
            id = "128k", name = "普通音质 128K",
            filesize = mp3Size, bitrate = 128, displaySize = formatSize(mp3Size)
        )
        if (hqHash.isNotEmpty() && hqSize > 0) qualities["320k"] = Quality(
            id = "320k", name = "高品音质 320K",
            filesize = hqSize, bitrate = 320, displaySize = formatSize(hqSize)
        )
        if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH && sqSize > 0) qualities["flac"] =
            Quality(
                id = "flac", name = "无损音质 FLAC",
                filesize = sqSize, bitrate = 2000, displaySize = formatSize(sqSize)
            )
        if (hrHash.isNotEmpty() && hrSize > 0) qualities["hires"] = Quality(
            id = "hires", name = "无损音质 Hi-Res",
            filesize = hrSize, bitrate = 4000, displaySize = formatSize(hrSize)
        )
        if (qualities.isEmpty()) return null

        val albumInfo = obj.safeGetObject("album_info")
        val albumAudioId = obj.get("album_audio_id")?.asString
            ?: obj.get("audio_id")?.asString ?: ""
        val rawName = obj.get("audio_name")?.asString ?: return null
        val authorName = obj.get("author_name")?.asString ?: ""
        val songName = cleanText(rawName).let { full ->
            // audio_name 形如 "周杰伦 - 晴天",去掉前缀歌手名只留歌名
            val sep = " - "
            if (full.contains(sep)) full.substringAfter(sep) else full
        }
        val cover = albumInfo?.get("cover")?.asString
            ?.takeIf { it.isNotBlank() }
            ?.replace("{size}", "480") ?: ""
        val duration = audio.get("timelength")?.asLong ?: 0L

        val mvId = obj.get("mv_id")?.asString?.takeIf { it.isNotBlank() && it != "0" }
            ?: obj.safeGetArray("mvdata")
                ?.takeIf { it.size() > 0 }
                ?.get(0)?.asJsonObject
                ?.get("id")?.asString?.takeIf { it.isNotBlank() && it != "0" }

        val allHash = buildList {
            add(mp3Hash)
            if (hqHash.isNotEmpty()) add(hqHash)
            if (sqHash.isNotEmpty() && sqHash != SQ_ZERO_HASH) add(sqHash)
            if (hrHash.isNotEmpty()) add(hrHash)
        }

        return KugouMusicItem(
            id = albumAudioId.toLongOrNull() ?: 0L,
            title = songName,
            artist = limitSingerCount(cleanText(authorName)),
            album = albumInfo?.get("album_name")?.asString ?: "",
            cover = cover,
            duration = duration,
            qualities = qualities,
            mixsongmid = albumAudioId.toLongOrNull() ?: 0L,
            hash = mp3Hash,
            allHash = allHash,
            audioId = albumAudioId,
            albumId = obj.get("album_id")?.asString,
            singers = authorName.split(SEPARATOR).map { Singer(name = it.trim()) },
            mvid = mvId
        )
    }
}
