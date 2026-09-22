package com.ikunshare.sound.platform.wy

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
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
import com.ikunshare.sound.platform.wy.utils.NeteaseCrypto
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.lyric.LrcParser
import com.ikunshare.sound.utils.lyric.toLyric

/** Gson 的 get()/getAsJsonObject()/getAsJsonArray() 在 JSON null 时都会出问题 */
private fun JsonElement?.safeString(): String? =
    if (this == null || this.isJsonNull) null else this.asString

private fun JsonElement?.safeInt(default: Int = 0): Int =
    if (this == null || this.isJsonNull) default else this.asInt

private fun JsonElement?.safeLong(): Long? =
    if (this == null || this.isJsonNull) null else this.asLong

private fun JsonObject.safeGetObject(key: String): JsonObject? {
    val elem = get(key)
    return if (elem != null && elem.isJsonObject) elem.asJsonObject else null
}

private fun JsonObject.safeGetArray(key: String): JsonArray? {
    val elem = get(key)
    return if (elem != null && elem.isJsonArray) elem.asJsonArray else null
}

class WyProvider : BaseProvider("wy") {

    override val displayName = "网易云音乐"
    override val shortTag = "wy"
    override val supportsWebView = true
    override val supportsSyncPlaylist = true
    override val supportsImportPlaylist = true
    override val supportedSearchTypes: List<SearchType> =
        listOf(SearchType.SONG, SearchType.ALBUM, SearchType.ARTIST)

    companion object {
        private const val TAG = "WyProvider"
    }

    /** 歌单全量 trackId 缓存：playlistId → 全部曲目 ID 列表 */
    private val playlistCache = mutableMapOf<String, List<Long>>()

    /** 获取用户的 MUSIC_U（从完整 cookie 中提取） */
    private fun getMusicU(): String? {
        val cookie = getCreds<WyCredentials>()?.cookie ?: return null
        return NeteaseCrypto.extractMusicU(cookie) ?: cookie
    }

    /**
     * 批量获取高级音质信息，通过 eapi batch + "/" hack 调用 /api/song/music/detail/get
     * jm → master, je → effect_plus, sk → effect
     */
    private fun enrichWithAdvancedQualities(items: List<NeteaseMusicItem>): List<NeteaseMusicItem> {
        if (items.isEmpty()) return items
        return try {
            val basePath = "/api/song/music/detail/get"
            val apiKeys = mutableListOf<String>()
            val batchParts = mutableListOf<String>()
            items.forEachIndexed { index, item ->
                val key = basePath + "/".repeat(index)
                apiKeys.add(key)
                batchParts.add("\"$key\":\"{'songId':${item.id}}\"")
            }
            val batchDataStr = "{${batchParts.joinToString(",")}}"

            val response = NeteaseCrypto.eapiPost("/api/batch", batchDataStr)
            if (!response.isSuccessful) return items

            val json = response.json(JsonObject::class.java) ?: return items
            if (json.get("code")?.asInt != 200) return items

            items.mapIndexed { index, item ->
                val detail = json.safeGetObject(apiKeys[index])
                if (detail != null) {
                    NeteaseMusicItem.enrichFromQualityDetail(item, detail)
                } else item
            }
        } catch (e: Exception) {
            Log.e(TAG, "enrichWithAdvancedQualities failed", e)
            items
        }
    }

    // ===== search =====

    override fun search(keyword: String, page: Int, size: Int): MusicListResult {
        // 注：网易云搜索接口已切换到 /api/search/song/list/page，
        // 它的页码从 1 起步（offset = limit * (page - 1)），而我们外部约定 page 从 0 起步。
        val apiPage = page + 1
        val data = mapOf(
            "keyword" to keyword,
            "needCorrect" to "1",
            "channel" to "typing",
            "offset" to size * (apiPage - 1),
            "scene" to "normal",
            "total" to (apiPage == 1),
            "limit" to size
        )

        // 搜索不需要 MUSIC_U
        val response = NeteaseCrypto.eapiPost("/api/search/song/list/page", data)
        if (!response.isSuccessful) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            if (json.get("code")?.asInt != 200)
                return MusicListResult(source, false, page, size, emptyList())

            val payload = json.safeGetObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val resources = payload.safeGetArray("resources")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val totalCount = payload.get("totalCount")?.asInt ?: 0

            // 新接口下每个 resource 的真实歌曲数据在 baseInfo.simpleSongData
            val musicItems = resources.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val simple = obj.safeGetObject("baseInfo")?.safeGetObject("simpleSongData")
                    ?: return@mapNotNull null
                NeteaseMusicItem.parseTrackInfo(simple)
            }
            val enriched = enrichWithAdvancedQualities(musicItems)
            val hasNext = apiPage * size < totalCount

            MusicListResult(source, hasNext, page, size, enriched)
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    // ===== getHotSearch =====

    override fun getHotSearch(): List<String> {
        // 热搜不需要 MUSIC_U
        val response = NeteaseCrypto.weapiPost(
            "/api/search/hot", mapOf("type" to 1111)
        )
        if (!response.isSuccessful) return emptyList()

        return try {
            val json = response.json(JsonObject::class.java) ?: return emptyList()
            if (json.get("code")?.asInt != 200) return emptyList()

            val result = json.safeGetObject("result") ?: return emptyList()
            val hots = result.safeGetArray("hots") ?: return emptyList()

            hots.mapNotNull { it.asJsonObject.get("first").safeString() }
        } catch (e: Exception) {
            Log.e(TAG, "getHotSearch failed", e)
            emptyList()
        }
    }

    // ===== getSearchTip =====

    override fun getSearchTip(keyword: String): List<String> {
        val data = mapOf("s" to keyword, "limit" to 8)
        // 搜索建议不需要 MUSIC_U
        val response = NeteaseCrypto.weapiPost(
            "/api/search/suggest/keyword", data
        )
        if (!response.isSuccessful) return emptyList()

        return try {
            val json = response.json(JsonObject::class.java) ?: return emptyList()
            if (json.get("code")?.asInt != 200) return emptyList()

            val result = json.safeGetObject("result") ?: return emptyList()
            val allMatch = result.safeGetArray("allMatch") ?: return emptyList()

            allMatch.mapNotNull { it.asJsonObject.get("keyword").safeString() }
        } catch (e: Exception) {
            Log.e(TAG, "getSearchTip failed", e)
            emptyList()
        }
    }

    // ===== getPlayListInfo =====

    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? {
        val playlistId = extractPlaylistId(inputInfo) ?: return null

        val data = mapOf(
            "id" to (playlistId.toLongOrNull() ?: playlistId),
            "n" to 0,
            "s" to 8
        )

        // 歌单需要 MUSIC_U
        val response = NeteaseCrypto.eapiPost(
            "/api/v6/playlist/detail", data, getMusicU()
        )
        if (!response.isSuccessful) return null

        return try {
            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null

            val playlist = json.safeGetObject("playlist") ?: return null

            PlayListInfoResult(
                source = source,
                img = playlist.get("coverImgUrl").safeString(),
                description = playlist.get("description").safeString(),
                author = playlist.safeGetObject("creator")?.get("nickname").safeString(),
                playListId = playlistId,
                title = playlist.get("name").safeString(),
                totalSongs = playlist.get("trackCount").safeInt()
            )
        } catch (e: Exception) {
            Log.e(TAG, "getPlayListInfo failed", e)
            null
        }
    }

    // ===== getPlayListSongs =====

    /**
     * 获取歌单歌曲列表（支持超过 1000 首的歌单）
     *
     * 两步走：
     * 1. /api/v6/playlist/detail 带 n=0 获取完整 trackIds 列表（缓存）
     * 2. 按 page/size 切片 trackIds，批量调 /api/v3/song/detail 获取详情（每批 100）
     */
    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult {
        // Step 1: 获取全量 trackIds（从缓存或 API）
        val trackIds = playlistCache[playListId] ?: run {
            val ids = fetchAllTrackIds(playListId) ?: return MusicListResult(
                source,
                false,
                page,
                size,
                emptyList()
            )
            Log.d(TAG, "getPlayListSongs: fetched ${ids.size} trackIds for $playListId, caching")
            playlistCache[playListId] = ids
            ids
        }

        // Step 2: 客户端分页切片
        val fromIndex = page * size
        val toIndex = minOf(fromIndex + size, trackIds.size)
        if (fromIndex >= trackIds.size) {
            playlistCache.remove(playListId)
            return MusicListResult(source, false, page, size, emptyList())
        }
        val pageIds = trackIds.subList(fromIndex, toIndex)

        // Step 3: 批量获取歌曲详情（每批最多 100 首）
        val items = fetchSongDetails(pageIds)
        val enriched = enrichWithAdvancedQualities(items)

        val hasNext = toIndex < trackIds.size
        if (!hasNext) {
            playlistCache.remove(playListId)
            Log.d(TAG, "getPlayListSongs: last page for $playListId, cache cleared")
        }

        return MusicListResult(source, hasNext, page, size, enriched)
    }

    /**
     * 获取歌单的全量 trackIds（n=0 不拉详情，突破 1000 限制）
     */
    private fun fetchAllTrackIds(playlistId: String): List<Long>? {
        return try {
            val data = mapOf(
                "id" to (playlistId.toLongOrNull() ?: playlistId),
                "n" to 0,
                "s" to 0
            )
            val response = NeteaseCrypto.eapiPost("/api/v6/playlist/detail", data, getMusicU())
            if (!response.isSuccessful) return null

            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null

            val playlist = json.safeGetObject("playlist") ?: return null
            val trackIdsArr = playlist.safeGetArray("trackIds") ?: return null

            trackIdsArr.mapNotNull { elem ->
                if (elem.isJsonObject) elem.asJsonObject.get("id")?.asLong else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchAllTrackIds failed", e)
            null
        }
    }

    /**
     * 批量获取歌曲详情（每批最多 100 首，对应参考项目的 Info.GetInfos）
     */
    private fun fetchSongDetails(ids: List<Long>): List<NeteaseMusicItem> {
        val allItems = mutableListOf<NeteaseMusicItem>()
        ids.chunked(100).forEach { chunk ->
            try {
                val c = chunk.joinToString(",") { """{"id":$it}""" }
                val data = mapOf("c" to "[$c]")
                val response = NeteaseCrypto.weapiPost("/api/v3/song/detail", data)
                if (!response.isSuccessful) return@forEach

                val json = response.json(JsonObject::class.java) ?: return@forEach
                if (json.get("code")?.asInt != 200) return@forEach

                val songs = json.safeGetArray("songs") ?: return@forEach
                songs.forEach { song ->
                    if (song.isJsonObject) {
                        NeteaseMusicItem.parseTrackInfo(song.asJsonObject)?.let { allItems.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "fetchSongDetails batch failed", e)
            }
        }
        return allItems
    }

    // ===== 专辑搜索 =====

    override fun searchAlbum(keyword: String, page: Int, size: Int): AlbumSearchResult {
        val data = mapOf(
            "s" to keyword,
            "limit" to size.toString(),
            "offset" to (page * size).toString(),
            "channel" to "typing",
            "queryCorrect" to "false",
            "q_scene" to "typing",
            "checkToken" to "firstRequest",
            "sub" to "false"
        )

        val response = NeteaseCrypto.eapiPost("/api/v1/search/album/get", data)
        if (!response.isSuccessful) {
            return AlbumSearchResult(source, false, page, size, emptyList())
        }

        return try {
            val json = response.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            if (json.get("code")?.asInt != 200)
                return AlbumSearchResult(source, false, page, size, emptyList())

            val result = json.safeGetObject("result")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val albums = result.safeGetArray("albums") ?: return AlbumSearchResult(
                source, false, page, size, emptyList()
            )
            val totalCount = result.get("albumCount").safeInt()

            val list = albums.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseAlbumSearchEntry(obj)
            }
            val hasNext = (page + 1) * size < totalCount

            AlbumSearchResult(source, hasNext, page, size, list)
        } catch (e: Exception) {
            Log.e(TAG, "searchAlbum failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseAlbumSearchEntry(obj: JsonObject): AlbumInfoResult? {
        val id = obj.get("id").safeLong() ?: return null
        val name = obj.get("name").safeString() ?: return null
        val artistObj = obj.safeGetObject("artist")
        return AlbumInfoResult(
            source = source,
            albumId = id.toString(),
            name = name,
            cover = obj.get("picUrl").safeString(),
            artist = artistObj?.get("name").safeString(),
            artistId = artistObj?.get("id")?.safeLong()?.toString(),
            publishTime = obj.get("publishTime").safeLong(),
            company = obj.get("company").safeString(),
            subType = obj.get("type").safeString(),
            description = obj.get("description").safeString(),
            size = obj.get("size").safeInt()
        )
    }

    // ===== 专辑详情 =====

    /**
     * 调用 https://interface3.music.163.com/api/v1/album/{id}
     * 一次性返回 album 元信息 + songs 列表
     */
    private fun fetchAlbumPayload(albumId: String): JsonObject? {
        val id = albumId.toLongOrNull() ?: return null
        return try {
            val resp = HTTPUtils.get("https://interface3.music.163.com/api/v1/album/$id")
            if (!resp.isSuccessful) return null
            val body = resp.body ?: return null
            val root = com.google.gson.JsonParser.parseString(body)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            if (obj.get("code")?.asInt != 200) return null
            obj
        } catch (e: Exception) {
            Log.e(TAG, "fetchAlbumPayload failed", e)
            null
        }
    }

    override fun getAlbumInfo(albumId: String): AlbumInfoResult? {
        val payload = fetchAlbumPayload(albumId) ?: return null
        val album = payload.safeGetObject("album") ?: return null
        val artistObj = album.safeGetObject("artist")
        return AlbumInfoResult(
            source = source,
            albumId = albumId,
            name = album.get("name").safeString() ?: return null,
            cover = album.get("picUrl").safeString(),
            artist = artistObj?.get("name").safeString(),
            artistId = artistObj?.get("id")?.safeLong()?.toString(),
            publishTime = album.get("publishTime").safeLong(),
            company = album.get("company").safeString(),
            subType = album.get("subType").safeString() ?: album.get("type").safeString(),
            description = album.get("description").safeString(),
            size = album.get("size").safeInt()
        )
    }

    override fun getAlbumSongs(albumId: String): MusicListResult {
        val payload = fetchAlbumPayload(albumId)
            ?: return MusicListResult(source, false, 0, 100, emptyList())
        val songs = payload.safeGetArray("songs")
            ?: return MusicListResult(source, false, 0, 100, emptyList())
        val items = songs.mapNotNull { el ->
            if (el.isJsonObject) NeteaseMusicItem.parseTrackInfo(el.asJsonObject) else null
        }
        val enriched = enrichWithAdvancedQualities(items)
        return MusicListResult(source, false, 0, enriched.size, enriched)
    }

    // ===== 歌手搜索 =====

    override fun searchArtist(keyword: String, page: Int, size: Int): ArtistSearchResult {
        val data = mapOf(
            "s" to keyword,
            "limit" to size.toString(),
            "offset" to (page * size).toString(),
            "channel" to "defaultquery",
            "queryCorrect" to "false",
            "q_scene" to "defaultquery",
            "checkToken" to "firstRequest",
            "sub" to "false",
            "e_r" to false
        )

        val response = NeteaseCrypto.eapiPost("/api/v1/search/artist/get", data)
        if (!response.isSuccessful) {
            return ArtistSearchResult(source, false, page, size, emptyList())
        }

        return try {
            val json = response.json(JsonObject::class.java)
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            if (json.get("code")?.asInt != 200)
                return ArtistSearchResult(source, false, page, size, emptyList())

            val result = json.safeGetObject("result")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val artists = result.safeGetArray("artists")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val totalCount = result.get("artistCount").safeInt()

            val list = artists.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseArtistEntry(obj)
            }
            val hasNext = (page + 1) * size < totalCount

            ArtistSearchResult(source, hasNext, page, size, list)
        } catch (e: Exception) {
            Log.e(TAG, "searchArtist failed", e)
            ArtistSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseArtistEntry(obj: JsonObject): ArtistInfoResult? {
        val id = obj.get("id").safeLong() ?: return null
        val name = obj.get("name").safeString() ?: return null
        val avatar = obj.get("picUrl").safeString()
            ?: obj.get("img1v1Url").safeString()
        return ArtistInfoResult(
            source = source,
            artistId = id.toString(),
            name = name,
            avatar = avatar,
            albumCount = obj.get("albumSize").safeInt(),
            musicCount = obj.get("musicSize").safeInt(),
            fansCount = obj.get("fansSize")?.safeLong() ?: 0,
            description = null
        )
    }

    // ===== 歌手详情 =====

    override fun getArtistInfo(artistId: String): ArtistInfoResult? {
        val id = artistId.toLongOrNull() ?: return null
        return try {
            val batchDataStr =
                """{"e_r":false,"/api/artist/head/info/get":"{\"id\":\"$id\"}","/api/artist/follow/count/get":"{\"id\":\"$id\"}"}"""
            val response = NeteaseCrypto.eapiPost("/api/batch", batchDataStr)
            if (!response.isSuccessful) return null

            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null

            val headInfo = json.safeGetObject("/api/artist/head/info/get") ?: return null
            if (headInfo.get("code")?.asInt != 200) return null
            val data = headInfo.safeGetObject("data") ?: return null
            val artist = data.safeGetObject("artist") ?: return null

            val name = artist.get("name").safeString() ?: return null
            val avatar = artist.get("avatar").safeString()
                ?: artist.get("cover").safeString()
            val briefDesc = artist.get("briefDesc").safeString()

            val followInfo = json.safeGetObject("/api/artist/follow/count/get")
            val fansCount = followInfo?.safeGetObject("data")?.get("fansCnt")?.safeLong() ?: 0

            ArtistInfoResult(
                source = source,
                artistId = artistId,
                name = name,
                avatar = avatar,
                albumCount = artist.get("albumSize").safeInt(),
                musicCount = artist.get("musicSize").safeInt(),
                fansCount = fansCount,
                description = briefDesc
            )
        } catch (e: Exception) {
            Log.e(TAG, "getArtistInfo failed", e)
            null
        }
    }

    override fun getArtistSongs(artistId: String, page: Int, size: Int): MusicListResult {
        val id = artistId.toLongOrNull()
            ?: return MusicListResult(source, false, page, size, emptyList())
        val data = mapOf(
            "id" to id.toString(),
            "order" to "hot",
            "top" to size.toString(),
            "work_type" to "5",
            "e_r" to false
        )
        return try {
            val response = NeteaseCrypto.eapiPost("/api/v1/artist/top/song", data)
            if (!response.isSuccessful)
                return MusicListResult(source, false, page, size, emptyList())

            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            if (json.get("code")?.asInt != 200)
                return MusicListResult(source, false, page, size, emptyList())

            val songs = json.safeGetArray("songs")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val hasMore = json.get("more")?.asBoolean ?: false

            val items = songs.mapNotNull { el ->
                if (el.isJsonObject) NeteaseMusicItem.parseTrackInfo(el.asJsonObject) else null
            }
            val enriched = enrichWithAdvancedQualities(items)

            MusicListResult(source, hasMore, page, size, enriched)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistSongs failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    override fun supportsArtistAlbums(): Boolean = true
    override fun supportsArtistMvs(): Boolean = true

    override fun getArtistAlbums(artistId: String, page: Int, size: Int): AlbumSearchResult {
        val id = artistId.toLongOrNull()
            ?: return AlbumSearchResult(source, false, page, size, emptyList())
        val data = mapOf(
            "id" to id,
            "offset" to page * size,
            "limit" to size,
            "total" to true
        )
        return try {
            val response = NeteaseCrypto.eapiPost("/api/artist/albums/$id", data)
            if (!response.isSuccessful)
                return AlbumSearchResult(source, false, page, size, emptyList())

            val json = response.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            if (json.get("code")?.asInt != 200)
                return AlbumSearchResult(source, false, page, size, emptyList())

            val hotAlbums = json.safeGetArray("hotAlbums")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val artistObj = json.safeGetObject("artist")
            val total = artistObj?.get("albumSize").safeInt()

            val list = hotAlbums.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseAlbumSearchEntry(obj)
            }
            val hasNext = (page + 1) * size < total

            AlbumSearchResult(source, hasNext, page, size, list)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAlbums failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    override fun getArtistMvs(artistId: String, page: Int, size: Int): ArtistMvResult {
        val id = artistId.toLongOrNull()
            ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
        val data = mapOf(
            "artistId" to id,
            "offset" to page * size,
            "limit" to size,
            "total" to true
        )
        return try {
            val response = NeteaseCrypto.eapiPost("/api/artist/mvs", data)
            if (!response.isSuccessful)
                return ArtistMvResult(source, false, page, size, 0, emptyList())

            val json = response.json(JsonObject::class.java)
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            if (json.get("code")?.asInt != 200)
                return ArtistMvResult(source, false, page, size, 0, emptyList())

            val mvs = json.safeGetArray("mvs")
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            val hasMore = json.get("hasMore")?.asBoolean ?: false
            val total = json.get("total").safeInt()

            val items = mvs.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val mvId = obj.get("id").safeLong() ?: return@mapNotNull null
                val title = obj.get("name").safeString() ?: ""
                val cover = obj.get("imgurl16v9").safeString()
                    ?: obj.get("imgurl").safeString() ?: ""
                val duration = obj.get("duration").safeLong() ?: 0L
                val playCount = obj.get("playCount").safeLong() ?: 0L
                val pubTime = obj.get("publishTime").safeString()?.let {
                    try {
                        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                            .parse(it)?.time
                    } catch (_: Exception) {
                        null
                    }
                } ?: 0L
                ArtistMvItem(
                    source = source,
                    vid = mvId.toString(),
                    title = title,
                    cover = cover,
                    duration = duration,
                    playCount = playCount,
                    pubTime = pubTime
                )
            }

            ArtistMvResult(source, hasMore, page, size, total, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistMvs failed", e)
            ArtistMvResult(source, false, page, size, 0, emptyList())
        }
    }

    override fun createMvItem(vid: String, title: String, cover: String): MusicItem =
        NeteaseMusicItem(
            id = 0L,
            title = title,
            artist = "",
            album = "",
            cover = cover,
            duration = 0,
            qualities = mapOf(
                "standard" to com.ikunshare.sound.model.Quality(
                    id = "standard", name = "标准", filesize = 0, bitrate = 128
                )
            ),
            singers = null,
            mvid = vid
        )

    // ===== getUserPlaylist =====

    override fun getUserPlaylist(): List<PlayListInfoResult> {
        val musicU = getMusicU() ?: run {
            Log.w(TAG, "getUserPlaylist: no MUSIC_U")
            return emptyList()
        }

        // 先获取用户 uid
        val accountResponse = NeteaseCrypto.eapiPost(
            "/api/nuser/account/get", emptyMap<String, Any>(), musicU
        )
        if (!accountResponse.isSuccessful) {
            Log.w(TAG, "getUserPlaylist: account request failed ${accountResponse.status}")
            return emptyList()
        }

        return try {
            val accountJson = accountResponse.json(JsonObject::class.java) ?: run {
                Log.w(TAG, "getUserPlaylist: account response parse failed")
                return emptyList()
            }
            if (accountJson.get("code")?.asInt != 200) {
                Log.w(TAG, "getUserPlaylist: account code=${accountJson.get("code")}")
                return emptyList()
            }

            val account = accountJson.safeGetObject("account") ?: run {
                Log.w(TAG, "getUserPlaylist: no account object in response")
                return emptyList()
            }
            val uid = account.get("id")?.asLong ?: run {
                Log.w(TAG, "getUserPlaylist: no uid in account")
                return emptyList()
            }
            Log.d(TAG, "getUserPlaylist: uid=$uid")

            // 一次性获取所有歌单
            val data = mapOf(
                "uid" to uid,
                "limit" to 100,
                "offset" to 0,
                "includeVideo" to true
            )
            val response = NeteaseCrypto.eapiPost("/api/user/playlist", data, musicU)
            if (!response.isSuccessful) {
                Log.w(TAG, "getUserPlaylist: playlist request failed ${response.status}")
                return emptyList()
            }

            val json = response.json(JsonObject::class.java) ?: run {
                Log.w(TAG, "getUserPlaylist: playlist response parse failed")
                return emptyList()
            }
            if (json.get("code")?.asInt != 200) {
                Log.w(TAG, "getUserPlaylist: playlist code=${json.get("code")}")
                return emptyList()
            }

            val playlists = json.safeGetArray("playlist") ?: run {
                Log.w(TAG, "getUserPlaylist: no playlist array")
                return emptyList()
            }
            Log.d(TAG, "getUserPlaylist: got ${playlists.size()} playlists")

            var likedPlaylist: PlayListInfoResult? = null
            val otherPlaylists = mutableListOf<PlayListInfoResult>()

            playlists.forEach { elem ->
                try {
                    val pl = elem.asJsonObject
                    val specialType = pl.get("specialType").safeInt()
                    val plId = pl.get("id").safeLong()?.toString() ?: return@forEach

                    if (specialType == 5 && likedPlaylist == null) {
                        // "XX喜欢的音乐" 置顶（使用 API 返回的实际名称）
                        likedPlaylist = PlayListInfoResult(
                            source = source,
                            img = pl.get("coverImgUrl").safeString(),
                            description = pl.get("description").safeString(),
                            author = pl.safeGetObject("creator")?.get("nickname").safeString(),
                            playListId = plId,
                            title = pl.get("name").safeString() ?: "我喜欢的音乐",
                            totalSongs = pl.get("trackCount").safeInt()
                        )
                    } else {
                        otherPlaylists.add(
                            PlayListInfoResult(
                                source = source,
                                img = pl.get("coverImgUrl").safeString(),
                                description = pl.get("description").safeString(),
                                author = pl.safeGetObject("creator")?.get("nickname").safeString(),
                                playListId = plId,
                                title = pl.get("name").safeString(),
                                totalSongs = pl.get("trackCount").safeInt()
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getUserPlaylist: parse playlist item failed", e)
                }
            }

            Log.d(
                TAG,
                "getUserPlaylist: liked=${likedPlaylist != null}, others=${otherPlaylists.size}"
            )

            if (likedPlaylist != null) {
                listOf(likedPlaylist) + otherPlaylists
            } else {
                otherPlaylists
            }
        } catch (e: Exception) {
            Log.e(TAG, "getUserPlaylist failed", e)
            emptyList()
        }
    }

    // ===== getUserInfo =====

    override fun getUserInfo(): UserInfo? {
        val musicU = getMusicU() ?: return null

        val response = NeteaseCrypto.eapiPost(
            "/api/nuser/account/get", emptyMap<String, Any>(), musicU
        )
        if (!response.isSuccessful) return null

        return try {
            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null

            val profile = json.safeGetObject("profile") ?: return null

            UserInfo(
                source = "netease",
                name = profile.get("nickname").safeString() ?: return null,
                avatar = profile.get("avatarUrl").safeString() ?: ""
            )
        } catch (e: Exception) {
            Log.e(TAG, "getUserInfo failed", e)
            null
        }
    }

    // ===== 歌单 ID 提取 =====

    private fun extractPlaylistId(input: String): String? {
        // 纯数字
        if (input.trim().matches(Regex("^\\d+$"))) return input.trim()

        // 从文本中提取 URL
        val url = Regex("https?://\\S+").find(input)?.value ?: input

        // 尝试多种 URL 格式
        Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/playlist/(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("#/playlist\\?id=(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }
        Regex("/discover/toplist\\?id=(\\d+)").find(url)?.groupValues?.get(1)?.let { return it }

        // 163 短链接跟踪
        if (url.contains("163.com") && (url.contains("/m/") || url.contains("163cn.tv"))) {
            val location = HTTPUtils.getRedirect(url)
            if (location != null) {
                return extractPlaylistId(location)
            }
        }

        return null
    }

    // ---- MV ----

    override fun supportsMv(item: MusicItem): Boolean =
        !item.mvid.isNullOrBlank() && item.mvid != "0"

    private val neteaseBrMapping = listOf(
        1080 to "蓝光画质",
        720 to "超清画质",
        480 to "高清画质",
        240 to "标清画质"
    )

    override fun getMvQualities(item: MusicItem): List<MvQuality> {
        val mvid = item.mvid?.takeIf { it.isNotBlank() && it != "0" } ?: return emptyList()
        return try {
            val resp = HTTPUtils.get("https://music.163.com/api/v1/mv/detail?id=$mvid")
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body ?: return emptyList()
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val brs = root.getAsJsonObject("data")?.getAsJsonArray("brs") ?: return emptyList()
            val results = mutableListOf<MvQuality>()
            for (elem in brs) {
                val o = elem.asJsonObject
                val br = o.get("br")?.asInt ?: continue
                val name = neteaseBrMapping.firstOrNull { it.first == br }?.second ?: "其他($br)"
                val sizeBytes = o.get("size")?.asLong ?: 0L
                val size = if (sizeBytes > 0) {
                    val mb = sizeBytes / 1024.0 / 1024.0
                    if (mb >= 1024) "%.2fGB".format(mb / 1024) else "%.1fMB".format(mb)
                } else ""
                results.add(
                    MvQuality(
                        quality = br.toString(),
                        displayName = name,
                        displaySize = size
                    )
                )
            }
            results.sortedBy { quality -> neteaseBrMapping.indexOfFirst { it.first.toString() == quality.quality } }
        } catch (e: Exception) {
            Log.w(TAG, "Netease MV qualities failed", e)
            emptyList()
        }
    }

    override fun getMvUrl(item: MusicItem, quality: String): MvUrlResult {
        val mvid = item.mvid?.takeIf { it.isNotBlank() && it != "0" }
            ?: return MvUrlResult(source, null, quality, "无 mvid")
        return try {
            val resp =
                HTTPUtils.get("https://music.163.com/api/song/enhance/play/mv/url?id=$mvid&r=$quality")
            if (!resp.isSuccessful) return MvUrlResult(source, null, quality, "HTTP ${resp.status}")
            val body = resp.body ?: return MvUrlResult(source, null, quality, "空响应")
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            val data =
                root.getAsJsonObject("data") ?: return MvUrlResult(source, null, quality, "无 data")
            val url = data.get("url")?.takeIf { !it.isJsonNull }?.asString
            if (url.isNullOrBlank()) MvUrlResult(source, null, quality, "url 为空")
            else MvUrlResult(source, url, quality)
        } catch (e: Exception) {
            Log.w(TAG, "Netease MV url failed", e)
            MvUrlResult(source, null, quality, e.message ?: "请求异常")
        }
    }

    // ── 单曲详情 / 歌词 / 更新 / 分享 ──

    /** 通过数字 ID 构造 NeteaseMusicItem，失败返回 null */
    fun fromID(id: Long): NeteaseMusicItem? {
        val item = fetchSongDetail(id) ?: return null
        return enrichSingleWithEapi(item)
    }

    private fun fetchSongDetail(id: Long): NeteaseMusicItem? {
        return try {
            val data = mapOf("c" to """[{"id":$id}]""")
            val response = NeteaseCrypto.weapiPost("/api/v3/song/detail", data)
            if (!response.isSuccessful) return null

            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null

            val songs = json.safeGetArray("songs")
            if (songs == null || songs.size() == 0) return null

            NeteaseMusicItem.parseTrackInfo(songs[0].asJsonObject)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSongDetail failed", e)
            null
        }
    }

    /** 单首歌通过 eapi batch 获取高级音质 */
    private fun enrichSingleWithEapi(item: NeteaseMusicItem): NeteaseMusicItem {
        return try {
            val batchDataStr = """{"/api/song/music/detail/get":"{'songId':${item.id}}"}"""
            val response = NeteaseCrypto.eapiPost("/api/batch", batchDataStr)
            if (!response.isSuccessful) return item

            val json = response.json(JsonObject::class.java) ?: return item
            if (json.get("code")?.asInt != 200) return item

            val qualityResult = json.safeGetObject("/api/song/music/detail/get") ?: return item
            NeteaseMusicItem.enrichFromQualityDetail(item, qualityResult)
        } catch (e: Exception) {
            Log.e(TAG, "enrichSingleWithEapi failed", e)
            item
        }
    }

    override fun updateInfo(item: MusicItem): MusicItem {
        val wy = item as? NeteaseMusicItem ?: return item
        return fromID(wy.id) ?: wy
    }

    override fun share(item: MusicItem): String {
        return "${item.title} - ${item.artist}\nhttps://music.163.com/song?id=${item.id}\n(@网易云音乐)"
    }

    override fun getLyric(item: MusicItem): Lyric {
        val params = mapOf(
            "id" to item.id.toString(),
            "cp" to false,
            "tv" to 0,
            "lv" to 0,
            "rv" to 0,
            "kv" to 0,
            "yv" to 0,
            "ytv" to 0,
            "yrv" to 0
        )

        val response = NeteaseCrypto.eapiPost("/api/song/lyric/v1", params)
        if (!response.isSuccessful) {
            Log.w(TAG, "getLyric http failed: ${response.status}")
            return Lyric()
        }

        val json = response.json(JsonObject::class.java) ?: return Lyric()
        if (!json.has("lrc")) return Lyric()

        val lrcObj = json.safeGetObject("lrc")
        val lyricRaw = lrcObj?.get("lyric").safeString()
        val yrcRaw =
            if (json.has("yrc")) json.safeGetObject("yrc")?.get("lyric").safeString() else null
        val transRaw = if (json.has("tlyric")) json.safeGetObject("tlyric")?.get("lyric")
            .safeString() else null
        val romaRaw = if (json.has("romalrc")) json.safeGetObject("romalrc")?.get("lyric")
            .safeString() else null
        val yTransRaw = if (json.has("ytlrc")) json.safeGetObject("ytlrc")?.get("lyric")
            .safeString() else null
        val yRomaRaw = if (json.has("yromalrc")) json.safeGetObject("yromalrc")?.get("lyric")
            .safeString() else null

        return LrcParser.parseWy(yrcRaw, yTransRaw, yRomaRaw, lyricRaw, transRaw, romaRaw)
            .toLyric()
    }
}
