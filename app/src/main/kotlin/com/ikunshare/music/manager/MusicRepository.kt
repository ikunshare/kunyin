package com.ikunshare.music.manager

import com.ikunshare.music.model.Lyric
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.Quality
import com.ikunshare.music.model.UserInfo
import com.ikunshare.music.manager.discovery.ChartCatalog
import com.ikunshare.music.manager.discovery.ChartInfo
import com.ikunshare.music.manager.discovery.ChartSongPages
import com.ikunshare.music.manager.discovery.PlaylistCategories
import com.ikunshare.music.manager.discovery.PlaylistCategory
import com.ikunshare.music.manager.discovery.DiscoverPlaylists
import com.ikunshare.music.manager.comments.CommentResult
import com.ikunshare.music.manager.comments.WyComments
import com.ikunshare.music.manager.comments.KwComments
import com.ikunshare.music.manager.comments.QqComments
import com.ikunshare.music.manager.comments.KgComments
import com.ikunshare.music.platform.qq.QQCoverEnrichment
import com.ikunshare.music.platform.base.AlbumInfoResult
import com.ikunshare.music.platform.base.AlbumSearchResult
import com.ikunshare.music.platform.base.ArtistInfoResult
import com.ikunshare.music.platform.base.ArtistMvResult
import com.ikunshare.music.platform.base.ArtistSearchResult
import com.ikunshare.music.platform.base.BaseProvider
import com.ikunshare.music.platform.base.MediaInfoResult
import com.ikunshare.music.platform.base.MusicListResult
import com.ikunshare.music.platform.base.PlayListInfoResult
import com.ikunshare.music.platform.base.PlaylistSearchResult
import com.ikunshare.music.platform.base.SearchType
import com.ikunshare.music.tool.MusicUrlHelper
import com.ikunshare.music.tool.cache.Cache
import com.ikunshare.music.tool.cache.LruCacheWrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MusicRepository - 音乐数据仓库
 *
 * 封装 Provider 调用，提供 LRU 缓存和 Provider 切换功能。
 * URL 获取统一走 MusicUrlHelper（IKUN 解析 API）。
 */
class MusicRepository(
    private val providers: Map<String, BaseProvider>,
    private val playlistCache: Cache<String, PlayListInfoResult> = LruCacheWrapper(
        PLAYLIST_CACHE_SIZE
    ),
    private val lyricCache: Cache<String, Lyric> = LruCacheWrapper(LYRIC_CACHE_SIZE),
    private val mediaInfoCache: Cache<String, MediaInfoResult> = LruCacheWrapper(
        MEDIA_INFO_CACHE_SIZE
    ),
    var authManager: AuthManager? = null,
    var localMusicStore: com.ikunshare.music.database.LocalMusicStore? = null,
    private val lyricFallback: (suspend (MusicItem) -> Lyric)? = null
) {
    private val chartCatalog by lazy { ChartCatalog() }
    private val playlistCategories by lazy { PlaylistCategories() }
    private val discoveryPlaylists by lazy { DiscoverPlaylists() }
    private val wyComments by lazy { WyComments() }
    private val kwComments by lazy { KwComments() }
    private val qqComments by lazy { QqComments() }
    private val kgComments by lazy { KgComments() }

    fun supportsComments(source: String) = source in listOf("wy", "qq", "kg", "kw")

    suspend fun getComments(item: MusicItem, hot: Boolean = true, page: Int = 1, limit: Int = 20): CommentResult = withContext(Dispatchers.IO) {
        when (item.getTypeDiscriminator()) {
            "wy" -> wyComments.comments(item.id, hot, page, limit)
            "kw" -> kwComments.comments(item.id, hot, page, limit)
            "qq" -> qqComments.comments(item.id, hot, page, limit)
            "kg" -> kgComments.comments((item as? com.ikunshare.music.platform.kg.KugouMusicItem)?.hash.orEmpty(), hot, page, limit)
            else -> CommentResult(item.getTypeDiscriminator(), emptyList(), 0, page.coerceAtLeast(1), limit.coerceIn(1, 100))
        }
    }

    suspend fun discoverPlaylists(source: String, category: String = "", order: String = "", page: Int = 0, size: Int = 30): PlaylistSearchResult =
        discoveryPlaylists.playlists(source, category, order, page, size)

    suspend fun getPlaylistCategories(source: String, refresh: Boolean = false): List<PlaylistCategory> =
        playlistCategories.categories(source, refresh)
    private val chartSongPages by lazy {
        ChartSongPages(wySongs = { id, page, size -> getPlaylistSongsForSource("wy", id, page, size) },
            enrich = { result ->
                when (result.source) {
                    "qq" -> QQCoverEnrichment.shared.fill(result.result)
                    "kg" -> (providers["kg"] as? com.ikunshare.music.platform.kg.KgProvider)?.fetchCovers(
                        result.result.filterIsInstance<com.ikunshare.music.platform.kg.KugouMusicItem>().filter { it.cover.isBlank() })
                    "kw" -> (providers["kw"] as? com.ikunshare.music.platform.kw.KwProvider)?.fetchCovers(
                        result.result.filterIsInstance<com.ikunshare.music.platform.kw.KuwoMusicItem>().filter { it.cover.isBlank() })
                }
            })
    }

    suspend fun getCharts(source: String, refresh: Boolean = false): List<ChartInfo> =
        chartCatalog.charts(source, refresh)

    suspend fun getChartSongs(source: String, id: String, page: Int = 0, size: Int = 100, period: String = ""): MusicListResult =
        chartSongPages.songs(source, id, page, size, period)
    companion object {
        const val PLAYLIST_CACHE_SIZE = 20
        const val LYRIC_CACHE_SIZE = 100
        const val MEDIA_INFO_CACHE_SIZE = 100
        private const val DEFAULT_PAGE_SIZE = 20
    }

    private var currentProvider: BaseProvider = providers["wy"]
        ?: providers.values.firstOrNull()
        ?: throw IllegalStateException("No providers available")

    private var currentProviderKey: String = providers.entries
        .firstOrNull { it.value === currentProvider }?.key ?: ""

    val currentKey: String get() = currentProviderKey

    fun setProvider(source: String) {
        currentProvider = providers[source]
            ?: throw IllegalArgumentException("Unknown provider: $source. Available providers: ${providers.keys}")
        currentProviderKey = source
    }

    suspend fun search(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
        source: String = currentKey
    ): MusicListResult {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) {
            provider.searchAsync(keyword, page, size).withQqCovers()
        }
    }

    suspend fun searchPlaylist(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
        source: String = currentKey
    ): PlaylistSearchResult {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) {
            provider.searchPlaylist(keyword, page, size)
        }
    }

    suspend fun searchAlbum(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
        source: String = currentKey
    ): AlbumSearchResult {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) {
            provider.searchAlbum(keyword, page, size)
        }
    }

    suspend fun searchArtist(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE,
        source: String = currentKey
    ): ArtistSearchResult {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) {
            provider.searchArtist(keyword, page, size)
        }
    }

    suspend fun getAlbumInfo(albumId: String): AlbumInfoResult? = withContext(Dispatchers.IO) {
        currentProvider.getAlbumInfo(albumId)
    }

    suspend fun getAlbumSongs(albumId: String): MusicListResult = withContext(Dispatchers.IO) {
        currentProvider.getAlbumSongs(albumId).withQqCovers()
    }

    suspend fun getAlbumInfoForSource(source: String, albumId: String): AlbumInfoResult? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getAlbumInfo(albumId) }
    }

    suspend fun getAlbumSongsForSource(source: String, albumId: String): MusicListResult {
        val provider = providers[source]
            ?: return MusicListResult(source, false, 0, DEFAULT_PAGE_SIZE, emptyList())
        return withContext(Dispatchers.IO) { provider.getAlbumSongs(albumId).withQqCovers() }
    }

    suspend fun getArtistInfoForSource(source: String, artistId: String): ArtistInfoResult? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getArtistInfo(artistId) }
    }

    suspend fun getArtistSongsForSource(
        source: String,
        artistId: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): MusicListResult {
        val provider = providers[source]
            ?: return MusicListResult(source, false, page, size, emptyList())
        return withContext(Dispatchers.IO) { provider.getArtistSongs(artistId, page, size).withQqCovers() }
    }

    suspend fun getArtistAlbumsForSource(
        source: String,
        artistId: String,
        page: Int = 0,
        size: Int = 30
    ): AlbumSearchResult {
        val provider = providers[source]
            ?: return AlbumSearchResult(source, false, page, size, emptyList())
        return withContext(Dispatchers.IO) { provider.getArtistAlbums(artistId, page, size) }
    }

    suspend fun getArtistMvsForSource(
        source: String,
        artistId: String,
        page: Int = 0,
        size: Int = 40
    ): ArtistMvResult {
        val provider = providers[source]
            ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
        return withContext(Dispatchers.IO) { provider.getArtistMvs(artistId, page, size) }
    }

    fun getSupportedSearchTypes(): List<SearchType> = currentProvider.supportedSearchTypes
    fun getDefaultSearchType(): SearchType = currentProvider.defaultSearchType

    suspend fun getHotSearch(source: String = currentKey): List<String> {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) { provider.getHotSearch() }
    }

    suspend fun getSearchTip(keyword: String, source: String = currentKey): List<String> {
        val provider = providers[source] ?: error("Unknown provider: $source")
        return withContext(Dispatchers.IO) { provider.getSearchTip(keyword) }
    }

    /**
     * 获取媒体播放信息：统一走 MusicUrlHelper。
     */
    suspend fun getMediaInfo(item: MusicItem, quality: Quality): MediaInfoResult =
        withContext(Dispatchers.IO) {
            resolveMediaInfo(item.getTypeDiscriminator(), item, quality)
        }

    suspend fun getPlaylistInfo(inputInfo: String): PlayListInfoResult? {
        val cacheKey = "${currentProvider.source}_$inputInfo"
        playlistCache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            currentProvider.getPlayListInfo(inputInfo)?.also {
                playlistCache.put(cacheKey, it)
            }
        }
    }

    suspend fun getPlaylistSongs(
        playlistId: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): MusicListResult = withContext(Dispatchers.IO) {
        currentProvider.getPlayListSongs(playlistId, page, size).withQqCovers()
    }

    private val kgLyrics by lazy { com.ikunshare.music.platform.kg.KgLyrics() }
    private val lyricRequests by lazy {
        LyricRequests(lyricCache,
            native = { item -> providers[item.getTypeDiscriminator()]?.getLyric(item) ?: Lyric() },
            fallback = { item -> lyricFallback?.invoke(item) ?: kgLyrics.fallback(item.title, item.artist, item.duration) })
    }

    suspend fun getLyric(item: MusicItem): Lyric = withContext(Dispatchers.IO) {
        val redirected = localMusicStore?.getRedirect(item) ?: item
        lyricRequests.get(redirected)
    }

    fun clearCache() {
        playlistCache.clear()
        lyricRequests.clear()
        mediaInfoCache.clear()
    }

    fun lyricCacheSize(): Int = lyricCache.size()
    fun mediaInfoCacheSize(): Int = mediaInfoCache.size()
    fun clearLyricCache() {
        lyricRequests.clear()
    }

    fun clearMediaInfoCache() {
        mediaInfoCache.clear()
    }

    fun invalidateMediaInfo(item: MusicItem, quality: Quality) {
        mediaInfoCache.remove("${item.getTypeDiscriminator()}_${item.uniqueKey}_${quality.id}")
    }

    fun invalidateMediaInfoForSource(source: String, item: MusicItem, quality: Quality) {
        val provider = providers[source] ?: return invalidateMediaInfo(item, quality)
        mediaInfoCache.remove("${provider.source}_${item.uniqueKey}_${quality.id}")
    }

    fun getAvailableProviders(): List<String> = providers.keys.toList()
    fun getProvider(source: String): BaseProvider? = providers[source]

    suspend fun getMediaInfoForSource(
        source: String,
        item: MusicItem,
        quality: Quality
    ): MediaInfoResult {
        val provider = providers[source] ?: return getMediaInfo(item, quality)
        return withContext(Dispatchers.IO) {
            resolveMediaInfo(provider.source, item, quality)
        }
    }

    suspend fun getUserInfoForSource(source: String): UserInfo? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getUserInfo() }
    }

    suspend fun getUserPlaylistForSource(source: String): List<PlayListInfoResult> {
        val provider = providers[source] ?: return emptyList()
        return withContext(Dispatchers.IO) { provider.getUserPlaylist() }
    }

    suspend fun getPlaylistSongsForSource(
        source: String,
        playlistId: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): MusicListResult {
        val provider = providers[source]
            ?: return MusicListResult(source, false, page, size, emptyList())
        return withContext(Dispatchers.IO) { provider.getPlayListSongs(playlistId, page, size).withQqCovers() }
    }

    suspend fun getPlaylistInfoForSource(source: String, inputInfo: String): PlayListInfoResult? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getPlayListInfo(inputInfo) }
    }

    suspend fun getCompletePlaylistSongsForSource(
        source: String,
        playlistId: String,
        expectedCount: Int? = null
    ): List<MusicItem> {
        val expected = expectedCount ?: getPlaylistInfoForSource(source, playlistId)?.totalSongs ?: 0
        return loadCompletePlaylist(expected) { page, size ->
            getPlaylistSongsForSource(source, playlistId, page, size)
        }
    }

    private fun resolveMediaInfo(
        sourceId: String,
        item: MusicItem,
        quality: Quality
    ): MediaInfoResult {
        val cacheKey = "${sourceId}_${item.uniqueKey}_${quality.id}"
        mediaInfoCache.get(cacheKey)?.let { cached ->
            if (cached.isSuccess && !isExpired(cached)) return cached
        }

        val provider = providers[sourceId]
        val providerResult = provider?.resolveMediaInfo(item, quality)
        val result = if (providerResult != null) {
            providerResult
        } else {
            val rm = authManager
            if (rm != null) {
                MusicUrlHelper.getMediaInfo(sourceId, item, quality, rm)
            } else {
                MediaInfoResult(
                    source = sourceId,
                    playUrl = null,
                    backupUrls = null,
                    expire = null,
                    isSuccess = false,
                    quality = quality,
                    musicItem = item,
                    rejectReason = "无可用解析服务"
                )
            }
        }
        if (result.isSuccess) mediaInfoCache.put(cacheKey, result)
        return result
    }

    private suspend fun MusicListResult.withQqCovers(): MusicListResult {
        QQCoverEnrichment.shared.fill(result)
        return this
    }

    private fun isExpired(info: MediaInfoResult): Boolean {
        val expire = info.expire ?: return false
        return System.currentTimeMillis() > expire
    }
}
