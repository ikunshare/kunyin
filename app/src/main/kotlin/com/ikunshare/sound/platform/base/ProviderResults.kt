package com.ikunshare.sound.platform.base

import com.google.gson.annotations.SerializedName
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality

data class MusicListResult(
    @SerializedName("source") val source: String,
    @SerializedName("hasNext") val hasNext: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("result") val result: List<MusicItem>,
    @SerializedName("skippedLocalSongs") val skippedLocalSongs: Int = 0
)

data class EncryptionInfo(
    @SerializedName("isEncrypt") val isEncrypt: Boolean,
    @SerializedName("ekey") val ekey: String?
)

data class MediaInfoResult(
    @SerializedName("source") val source: String,
    @SerializedName("playUrl") val playUrl: String?,
    @SerializedName("backupUrls") val backupUrls: List<String>?,
    @SerializedName("expire") val expire: Long?,
    @SerializedName("isSuccess") val isSuccess: Boolean,
    @SerializedName("quality") val quality: Quality,
    @SerializedName("musicItem") val musicItem: MusicItem,
    @SerializedName("rejectReason") val rejectReason: String? = null,
    @SerializedName("encryptionInfo") val encryptionInfo: EncryptionInfo? = null
)

data class PlayListInfoResult(
    @SerializedName("source") val source: String,
    @SerializedName("img") val img: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("author") val author: String?,
    @SerializedName("playListId") val playListId: String,
    @SerializedName("title") val title: String?,
    @SerializedName("totalSongs") val totalSongs: Int
)

data class MvQuality(
    @SerializedName("quality") val quality: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("displaySize") val displaySize: String = ""
)

data class MvUrlResult(
    @SerializedName("source") val source: String,
    @SerializedName("playUrl") val playUrl: String?,
    @SerializedName("quality") val quality: String,
    @SerializedName("rejectReason") val rejectReason: String? = null
)

enum class SearchType(val displayName: String) {
    SONG("单曲"),
    PLAYLIST("歌单"),
    ALBUM("专辑"),
    ARTIST("歌手")
}

data class PlaylistSearchResult(
    @SerializedName("source") val source: String,
    @SerializedName("hasNext") val hasNext: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("result") val result: List<PlayListInfoResult>
)

data class AlbumInfoResult(
    @SerializedName("source") val source: String,
    @SerializedName("albumId") val albumId: String,
    @SerializedName("name") val name: String,
    @SerializedName("cover") val cover: String?,
    @SerializedName("artist") val artist: String?,
    @SerializedName("artistId") val artistId: String?,
    @SerializedName("publishTime") val publishTime: Long?,
    @SerializedName("company") val company: String?,
    @SerializedName("subType") val subType: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("size") val size: Int
)

data class AlbumSearchResult(
    @SerializedName("source") val source: String,
    @SerializedName("hasNext") val hasNext: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("result") val result: List<AlbumInfoResult>
)

data class ArtistInfoResult(
    @SerializedName("source") val source: String,
    @SerializedName("artistId") val artistId: String,
    @SerializedName("name") val name: String,
    @SerializedName("avatar") val avatar: String?,
    @SerializedName("albumCount") val albumCount: Int = 0,
    @SerializedName("musicCount") val musicCount: Int = 0,
    @SerializedName("fansCount") val fansCount: Long = 0,
    @SerializedName("description") val description: String? = null
)

data class ArtistSearchResult(
    @SerializedName("source") val source: String,
    @SerializedName("hasNext") val hasNext: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("result") val result: List<ArtistInfoResult>
)

/** 歌手 MV 列表条目。vid 用于复用 [MvUrlResult] 取流播放。 */
data class ArtistMvItem(
    @SerializedName("source") val source: String,
    @SerializedName("vid") val vid: String,
    @SerializedName("title") val title: String,
    @SerializedName("cover") val cover: String,
    @SerializedName("duration") val duration: Long = 0,
    @SerializedName("playCount") val playCount: Long = 0,
    @SerializedName("pubTime") val pubTime: Long = 0
)

data class ArtistMvResult(
    @SerializedName("source") val source: String,
    @SerializedName("hasNext") val hasNext: Boolean,
    @SerializedName("page") val page: Int,
    @SerializedName("size") val size: Int,
    @SerializedName("total") val total: Int,
    @SerializedName("result") val result: List<ArtistMvItem>
)
