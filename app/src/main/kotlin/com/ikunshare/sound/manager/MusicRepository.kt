package com.ikunshare.sound.manager

import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.platform.base.AlbumInfoResult
import com.ikunshare.sound.platform.base.AlbumSearchResult
import com.ikunshare.sound.platform.base.ArtistInfoResult
import com.ikunshare.sound.platform.base.ArtistMvResult
import com.ikunshare.sound.platform.base.ArtistSearchResult
import com.ikunshare.sound.platform.base.BaseProvider
import com.ikunshare.sound.platform.base.MediaInfoResult
import com.ikunshare.sound.platform.base.MusicListResult
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.platform.base.PlaylistSearchResult
import com.ikunshare.sound.platform.base.SearchType
import com.ikunshare.sound.tool.MusicUrlHelper
import com.ikunshare.sound.tool.cache.Cache
import com.ikunshare.sound.tool.cache.LruCacheWrapper
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
    var localMusicStore: com.ikunshare.sound.database.LocalMusicStore? = null
) {
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
        size: Int = DEFAULT_PAGE_SIZE
    ): MusicListResult = withContext(Dispatchers.IO) {
        MusicItem._fmt.hashCode()
        currentProvider.search(keyword, page, size)
    }

    suspend fun searchPlaylist(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): PlaylistSearchResult = withContext(Dispatchers.IO) {
        currentProvider.searchPlaylist(keyword, page, size)
    }

    suspend fun searchAlbum(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): AlbumSearchResult = withContext(Dispatchers.IO) {
        currentProvider.searchAlbum(keyword, page, size)
    }

    suspend fun searchArtist(
        keyword: String,
        page: Int = 0,
        size: Int = DEFAULT_PAGE_SIZE
    ): ArtistSearchResult = withContext(Dispatchers.IO) {
        currentProvider.searchArtist(keyword, page, size)
    }

    suspend fun getAlbumInfo(albumId: String): AlbumInfoResult? = withContext(Dispatchers.IO) {
        currentProvider.getAlbumInfo(albumId)
    }

    suspend fun getAlbumSongs(albumId: String): MusicListResult = withContext(Dispatchers.IO) {
        currentProvider.getAlbumSongs(albumId)
    }

    suspend fun getAlbumInfoForSource(source: String, albumId: String): AlbumInfoResult? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getAlbumInfo(albumId) }
    }

    suspend fun getAlbumSongsForSource(source: String, albumId: String): MusicListResult {
        val provider = providers[source]
            ?: return MusicListResult(source, false, 0, DEFAULT_PAGE_SIZE, emptyList())
        return withContext(Dispatchers.IO) { provider.getAlbumSongs(albumId) }
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
        return withContext(Dispatchers.IO) { provider.getArtistSongs(artistId, page, size) }
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

    suspend fun getHotSearch(): List<String> = withContext(Dispatchers.IO) {
        currentProvider.getHotSearch()
    }

    suspend fun getSearchTip(keyword: String): List<String> = withContext(Dispatchers.IO) {
        currentProvider.getSearchTip(keyword)
    }

    /**
     * 获取媒体播放信息：统一走 MusicUrlHelper。
     */
    suspend fun getMediaInfo(item: MusicItem, quality: Quality): MediaInfoResult =
        withContext(Dispatchers.IO) {
            MusicItem._fmt!!
            resolveMediaInfo(currentProvider.source, item, quality)
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
        currentProvider.getPlayListSongs(playlistId, page, size)
    }

    suspend fun getLyric(item: MusicItem): Lyric {
        val redirected = localMusicStore?.getRedirect(item) ?: item
        val source = redirected.getTypeDiscriminator()
        val provider = providers[source] ?: currentProvider
        val cacheKey = "${provider.source}_${redirected.uniqueKey}"
        lyricCache.get(cacheKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            provider.getLyric(redirected).also {
                if (!it.isEmpty()) lyricCache.put(cacheKey, it)
            }
        }
    }

    fun clearCache() {
        playlistCache.clear()
        lyricCache.clear()
        mediaInfoCache.clear()
    }

    fun lyricCacheSize(): Int = lyricCache.size()
    fun mediaInfoCacheSize(): Int = mediaInfoCache.size()
    fun clearLyricCache() {
        lyricCache.clear()
    }

    fun clearMediaInfoCache() {
        mediaInfoCache.clear()
    }

    fun invalidateMediaInfo(item: MusicItem, quality: Quality) {
        mediaInfoCache.remove("${currentProvider.source}_${item.id}_${quality.id}")
    }

    fun invalidateMediaInfoForSource(source: String, item: MusicItem, quality: Quality) {
        val provider = providers[source] ?: return invalidateMediaInfo(item, quality)
        mediaInfoCache.remove("${provider.source}_${item.id}_${quality.id}")
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
        return withContext(Dispatchers.IO) { provider.getPlayListSongs(playlistId, page, size) }
    }

    suspend fun getPlaylistInfoForSource(source: String, inputInfo: String): PlayListInfoResult? {
        val provider = providers[source] ?: return null
        return withContext(Dispatchers.IO) { provider.getPlayListInfo(inputInfo) }
    }

    private fun resolveMediaInfo(
        sourceId: String,
        item: MusicItem,
        quality: Quality
    ): MediaInfoResult {
        val cacheKey = "${sourceId}_${item.id}_${quality.id}"
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

    private fun isExpired(info: MediaInfoResult): Boolean {
        val expire = info.expire ?: return false
        return System.currentTimeMillis() > expire
    }
}
