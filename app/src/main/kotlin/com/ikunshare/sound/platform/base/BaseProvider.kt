package com.ikunshare.sound.platform.base

import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.UserInfo

abstract class ProviderCredentials

abstract class BaseProvider(val source: String) {

    abstract val displayName: String
    abstract val shortTag: String?

    open val supportsWebView: Boolean = false
    open val supportsSyncPlaylist: Boolean = false
    open val supportsImportPlaylist: Boolean = false
    open val supportedSearchTypes: List<SearchType> = listOf(SearchType.SONG)
    open val defaultSearchType: SearchType = SearchType.SONG

    var credentials: ProviderCredentials? = null

    abstract fun search(keyword: String, page: Int = 0, size: Int = 20): MusicListResult
    open fun searchPlaylist(keyword: String, page: Int = 0, size: Int = 20): PlaylistSearchResult {
        return PlaylistSearchResult(source, false, page, size, emptyList())
    }

    open fun searchAlbum(keyword: String, page: Int = 0, size: Int = 20): AlbumSearchResult {
        return AlbumSearchResult(source, false, page, size, emptyList())
    }

    open fun getAlbumInfo(albumId: String): AlbumInfoResult? = null
    open fun getAlbumSongs(albumId: String): MusicListResult =
        MusicListResult(source, false, 0, 20, emptyList(), 0)

    open fun searchArtist(keyword: String, page: Int = 0, size: Int = 20): ArtistSearchResult {
        return ArtistSearchResult(source, false, page, size, emptyList())
    }

    open fun getArtistInfo(artistId: String): ArtistInfoResult? = null
    open fun getArtistSongs(artistId: String, page: Int = 0, size: Int = 20): MusicListResult =
        MusicListResult(source, false, page, size, emptyList(), 0)

    /** 歌手详情页是否展示「专辑」tab。 */
    open fun supportsArtistAlbums(): Boolean = false

    /** 歌手详情页是否展示「视频/MV」tab。 */
    open fun supportsArtistMvs(): Boolean = false

    open fun getArtistAlbums(artistId: String, page: Int = 0, size: Int = 30): AlbumSearchResult =
        AlbumSearchResult(source, false, page, size, emptyList())

    open fun getArtistMvs(artistId: String, page: Int = 0, size: Int = 40): ArtistMvResult =
        ArtistMvResult(source, false, page, size, 0, emptyList())

    /**
     * 由 [ArtistMvItem] 构造一个最小的占位 [MusicItem]（仅 mvid/title/cover 有值），
     * 用于复用 [MvDialog] + [getMvQualities]/[getMvUrl] 播放。不支持则返回 null。
     */
    open fun createMvItem(vid: String, title: String, cover: String): MusicItem? = null

    abstract fun getHotSearch(): List<String>
    abstract fun getSearchTip(keyword: String): List<String>
    abstract fun getPlayListInfo(inputInfo: String): PlayListInfoResult?
    abstract fun getPlayListSongs(
        playListId: String,
        page: Int = 0,
        size: Int = 20
    ): MusicListResult

    abstract fun getUserPlaylist(): List<PlayListInfoResult>
    abstract fun getUserInfo(): UserInfo?

    open fun getLyric(item: MusicItem): Lyric = Lyric()

    open fun resolveMediaInfo(item: MusicItem, quality: Quality): MediaInfoResult? = null

    /**
     * 从远端刷新歌曲详情（封面、音质等）。失败应返回原 item，不返回 null。
     */
    open fun updateInfo(item: MusicItem): MusicItem = item

    /**
     * 构造歌曲分享文案（包含外链）。
     */
    open fun share(item: MusicItem): String = "${item.title} - ${item.artist}"

    open fun supportsMv(item: MusicItem): Boolean = false
    open fun getMvQualities(item: MusicItem): List<MvQuality> = emptyList()
    open fun getMvUrl(item: MusicItem, quality: String): MvUrlResult =
        MvUrlResult(source, null, quality, "未实现")

    protected inline fun <reified T : ProviderCredentials> getCreds(): T? {
        return credentials as? T
    }

    open fun refreshLogin(): ProviderCredentials? = null
}
