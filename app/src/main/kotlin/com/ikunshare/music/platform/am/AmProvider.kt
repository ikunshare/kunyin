package com.ikunshare.music.platform.am

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.model.Lyric
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.UserInfo
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumSearchResult
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.ArtistSearchResult
import com.ikunshare.music.platform.base.BaseProvider
import com.ikunshare.music.platform.base.MusicListResult
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.SearchType
import com.ikunshare.music.utils.HTTPResponse
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class AmProvider(
    private val endpoint: String = API_ORIGIN,
    private val authst: () -> String = { MusicApplication.instance?.authManager?.currentAuthst.orEmpty() },
    private val post: suspend (String, Map<String, String>, Map<String, Any?>) -> HTTPResponse =
        { url, headers, body -> HTTPUtils.postCancellable(url, headers, body) }
) : BaseProvider("am") {
    override val displayName = "Apple Music"
    override val shortTag = "am"
    override val supportedSearchTypes = listOf(SearchType.SONG, SearchType.ALBUM, SearchType.ARTIST)

    override fun search(keyword: String, page: Int, size: Int): MusicListResult =
        runBlocking(Dispatchers.IO) { searchAsync(keyword, page, size) }

    override suspend fun searchAsync(keyword: String, page: Int, size: Int): MusicListResult {
        val response = requestOrNull("/music/search", searchBody(keyword, "song", page, size))
        val items = response.rows().mapNotNull { row -> runCatching { AppleMusicItem.parse(row) }.getOrNull() }
        return MusicListResult(source, response.hasNext(), page, size, items, total = response.total())
    }

    override fun searchAlbum(keyword: String, page: Int, size: Int): AlbumSearchResult = runBlocking(Dispatchers.IO) {
        val response = requestOrNull("/music/search", searchBody(keyword, "album", page, size))
        AlbumSearchResult(source, response.hasNext(), page, size,
            response.rows().mapNotNull(::parseAlbum))
    }

    override fun searchArtist(keyword: String, page: Int, size: Int): ArtistSearchResult = runBlocking(Dispatchers.IO) {
        val response = requestOrNull("/music/search", searchBody(keyword, "artist", page, size))
        ArtistSearchResult(source, response.hasNext(), page, size,
            response.rows().mapNotNull(::parseArtist))
    }

    override fun getAlbumInfo(albumId: String): AlbumInfoResult? =
        runBlocking(Dispatchers.IO) { requestOrNull("/music/album", albumBody(albumId, 0, 1)) }
            ?.objectValue("info")?.let(::parseAlbum)

    override fun getAlbumSongs(albumId: String): MusicListResult = runBlocking(Dispatchers.IO) {
        val songs = mutableListOf<AppleMusicItem>()
        var response: JsonObject? = null
        try {
            var currentPage = 0
            while (currentPage < 50) {
                val currentResponse = request("/music/album", albumBody(albumId, currentPage, 100))
                response = currentResponse
                songs += currentResponse.rows().mapNotNull { row ->
                    runCatching { AppleMusicItem.parse(row) }.getOrNull()
                }
                if (!currentResponse.hasNext()) break
                currentPage++
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (response == null) return@runBlocking emptyResult(0, 100)
        }
        val last = response
        MusicListResult(source, false, 0, songs.size, songs,
            discs = last.discs(), total = last?.total() ?: songs.size)
    }

    override fun getArtistInfo(artistId: String): ArtistInfoResult? = runBlocking(Dispatchers.IO) {
        requestOrNull("/music/artist", artistBody(artistId, "songs", 0, 1))
            ?.objectValue("info")?.let(::parseArtist)
    }

    override fun getArtistSongs(artistId: String, page: Int, size: Int): MusicListResult = runBlocking(Dispatchers.IO) {
        val response = requestOrNull("/music/artist", artistBody(artistId, "songs", page, size))
        val items = response.rows().mapNotNull { row -> runCatching { AppleMusicItem.parse(row) }.getOrNull() }
        MusicListResult(source, response.hasNext(), page, size, items, total = response.total())
    }

    override fun supportsArtistAlbums(): Boolean = true

    override fun getArtistAlbums(artistId: String, page: Int, size: Int): AlbumSearchResult = runBlocking(Dispatchers.IO) {
        val response = requestOrNull("/music/artist", artistBody(artistId, "albums", page, size))
        AlbumSearchResult(source, response.hasNext(), page, size,
            response.rows().mapNotNull(::parseAlbum))
    }

    override suspend fun getLyric(item: MusicItem): Lyric {
        if (item !is AppleMusicItem) return Lyric()
        val response = requestOrNull("/music/lyric", mapOf(
            "source" to source, "musicId" to item.id.toString()
        ))?.objectValue("data") ?: return Lyric()
        return Lyric(
            lrc = response.text("lyric"), trans = response.text("trans"),
            roma = response.text("roma"), char = response.text("chase"),
            chroma = response.text("chroma"), ttml = response.text("ttml")
        )
    }

    override fun getHotSearch(): List<String> = emptyList()
    override fun getSearchTip(keyword: String): List<String> = emptyList()
    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? = null
    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult = emptyResult(page, size)
    override fun getUserPlaylist(): List<PlayListInfoResult> = emptyList()
    override fun getUserInfo(): UserInfo? = null

    private fun searchBody(keyword: String, type: String, page: Int, size: Int) = mapOf(
        "source" to source, "keyword" to keyword, "type" to type,
        "page" to page + 1, "limit" to size
    )

    private fun albumBody(id: String, page: Int, size: Int) = mapOf(
        "source" to source, "musicId" to id, "page" to page + 1, "limit" to size
    )

    private fun artistBody(id: String, view: String, page: Int, size: Int) = mapOf(
        "source" to source, "musicId" to id, "view" to view,
        "page" to page + 1, "limit" to size
    )

    private suspend fun requestOrNull(path: String, body: Map<String, Any?>): JsonObject? = try {
        request(path, body)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun request(path: String, body: Map<String, Any?>): JsonObject {
        val response = post(endpoint.trimEnd('/') + path, mapOf("X-Api-Key" to authst()), body)
        if (!response.isSuccessful) throw IOException("Apple Music HTTP ${response.status}")
        val json = response.json(JsonObject::class.java) ?: throw IOException("Apple Music 响应无效")
        if (json.text("code") != "200") {
            throw IOException(json.text("message").ifEmpty { "Apple Music 服务请求失败" })
        }
        return json
    }

    private fun emptyResult(page: Int, size: Int) =
        MusicListResult(source, false, page, size, emptyList())

    private fun JsonObject?.rows(): List<JsonObject> =
        this?.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { row -> row.isJsonObject }?.asJsonObject }.orEmpty()

    private fun JsonObject?.hasNext(): Boolean =
        this?.get("hasNext")?.takeIf { it.isJsonPrimitive }?.asBoolean == true

    private fun JsonObject?.total(): Int? = this?.get("total")?.takeIf { it.isJsonPrimitive }
        ?.asLong?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt()

    private fun JsonObject.objectValue(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject?.discs(): List<AlbumDisc>? = this?.get("discs")
        ?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { element ->
            val disc = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val no = disc.get("no")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            val count = disc.get("count")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@mapNotNull null
            if (no <= 0 || count <= 0) return@mapNotNull null
            AlbumDisc(no, disc.get("name")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty().ifEmpty { "CD$no" }, count)
        }?.takeIf { it.isNotEmpty() }

    companion object {
        internal const val API_ORIGIN = "https://c.wwwweb.top"

        internal fun parseAlbum(raw: JsonObject): AlbumInfoResult? {
            val id = raw.text("id")
            val name = raw.text("name")
            if (id.isEmpty() || name.isEmpty()) return null
            return AlbumInfoResult(
                source = "am", albumId = id, name = name,
                cover = raw.firstText("coverUrl", "cover").ifEmpty { null },
                artist = raw.firstText("artistName", "artist").ifEmpty { null },
                artistId = raw.text("artistId").ifEmpty { null },
                publishTime = timestamp(raw.first("publishTime", "releaseDate")),
                company = raw.firstText("company", "recordLabel").ifEmpty { null },
                subType = raw.text("subType").ifEmpty { null },
                description = raw.text("description").ifEmpty { null },
                size = raw.first("total", "trackCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            )
        }

        internal fun parseArtist(raw: JsonObject): ArtistInfoResult? {
            val id = raw.text("id")
            val name = raw.text("name")
            if (id.isEmpty() || name.isEmpty()) return null
            return ArtistInfoResult(
                source = "am", artistId = id, name = name,
                avatar = raw.firstText("coverUrl", "cover").ifEmpty { null },
                albumCount = raw.get("albumCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                musicCount = raw.get("songCount")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0,
                description = raw.text("description").ifEmpty { null }
            )
        }

        private fun JsonObject.first(vararg keys: String): JsonElement? =
            keys.firstNotNullOfOrNull { get(it)?.takeUnless { value -> value.isJsonNull } }

        private fun JsonObject.firstText(vararg keys: String): String =
            first(*keys)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

        private fun JsonObject.text(key: String): String = firstText(key)

        private fun timestamp(value: JsonElement?): Long? {
            val text = value?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            if (text.isEmpty()) return null
            text.toLongOrNull()?.let { return it }
            return runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(text)?.time
            }.getOrNull()
        }
    }
}
