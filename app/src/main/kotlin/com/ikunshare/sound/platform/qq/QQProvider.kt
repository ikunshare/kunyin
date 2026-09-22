package com.ikunshare.sound.platform.qq

import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.ikunshare.sound.SoundApplication
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
import com.ikunshare.sound.platform.base.ProviderCredentials
import com.ikunshare.sound.platform.base.SearchType
import com.ikunshare.sound.platform.joox.JooxProvider
import com.ikunshare.sound.platform.qq.device.QQDeviceManager
import com.ikunshare.sound.platform.qq.utils.QQMusicUtils
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.lyric.LrcParser
import com.ikunshare.sound.utils.lyric.toLyric
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

class QQProvider : BaseProvider("qq") {

    override val displayName = "QQ音乐"
    override val shortTag = "qq"
    override val supportsWebView = true
    override val supportsSyncPlaylist = true
    override val supportsImportPlaylist = true
    override val supportedSearchTypes: List<SearchType> =
        listOf(SearchType.SONG, SearchType.ALBUM, SearchType.ARTIST)

    companion object {
        private const val TAG = "QQProvider"
        private const val SONG_DETAIL_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val STAT_URL = "https://stat6.y.qq.com/android/fcgi-bin/imusic_tj"
        private const val RECENTLY_URL =
            "https://u6.y.qq.com/cgi-bin/musicu.fcg?cgiKey=ReportPlayRecentlyInfo"
        private const val TIMEKEY_SALT = "gk2\$Lh-&l4#!4iow"
        const val PLAYLIST_DAILY30 = "__qq_daily30__"
        const val PLAYLIST_GUESS_LIKE = "__qq_guess_like__"
        const val PLAYLIST_PERSONAL = "__qq_personal__"

        /**
         * 三个推荐入口的元数据（标题/描述/数量/兜底封面）集中定义，避免在 getRecommendList
         * 与 getPlayListInfo 两处重复硬编码。封面优先用推荐列表首歌的真实专辑封面，
         * 取不到时回退到这里的运营图。
         */
        private data class RecommendEntry(
            val id: String,
            val name: String,
            val desc: String,
            val count: Int,
            val fallbackCover: String
        )

        private val RECOMMEND_ENTRIES = listOf(
            RecommendEntry(
                PLAYLIST_DAILY30, "每日30首", "每日个性化推荐30首", 30,
                "https://y.gtimg.cn/music/photo_new/T002R300x300M00000240J6C4ZUKed_1.jpg"
            ),
            RecommendEntry(
                PLAYLIST_GUESS_LIKE, "猜你喜欢", "根据你的口味推荐", 36,
                "https://y.qq.com/music/common/upload/t_cm3_photo_publish/6677478.png"
            ),
            RecommendEntry(
                PLAYLIST_PERSONAL, "专属好歌", "你的专属好歌推荐", 36,
                "https://y.qq.com/music/common/upload/recommend_config/6166681.png"
            )
        )
    }

    /**
     * 一次 RecommendFeed 请求即可拿到三个推荐入口的真实封面：
     * - 每日30首：shelf 中 miscellany.dirid=="202" 的卡的 cover（真专辑图）。
     * - 猜你喜欢 / 专属好歌：feed 首个 type==200 歌曲卡的 cover（"也在听"列表首歌专辑图）。
     * 取不到的入口不放进 map，调用方回退到 RecommendEntry.fallbackCover。
     */
    private fun fetchRecommendCovers(): Map<String, String> {
        val creds = getCreds<QQCredentials>() ?: return emptyMap()
        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11", "cv" to "14090508", "v" to "14090508",
                "tmeAppID" to "qqmusic", "uin" to creds.uin, "authst" to creds.authst
            ),
            "req" to mapOf(
                "module" to "music.recommend.RecommendFeed",
                "method" to "get_recommend_feed",
                "param" to mapOf<String, Any>("id" to 0, "size" to 3, "cmd" to 0)
            )
        )
        return try {
            val response = QQMusicUtils.zzcRequest(reqData)
            if (!response.isSuccessful) return emptyMap()
            val json = response.json(JsonObject::class.java) ?: return emptyMap()
            val shelves = json.getAsJsonObject("req")?.getAsJsonObject("data")
                ?.getAsJsonArray("v_shelf") ?: return emptyMap()
            val covers = mutableMapOf<String, String>()
            for (shelfElem in shelves) {
                val niches = shelfElem.asJsonObject.getAsJsonArray("v_niche") ?: continue
                for (nicheElem in niches) {
                    val cards = nicheElem.asJsonObject.getAsJsonArray("v_card") ?: continue
                    for (cardElem in cards) {
                        val card = cardElem.asJsonObject
                        val cover = card.get("cover")?.asString?.takeIf { it.startsWith("http") }
                            ?: continue
                        val dirid = card.getAsJsonObject("miscellany")?.get("dirid")?.asString
                        if (dirid == "202") covers[PLAYLIST_DAILY30] = cover
                        if (card.get("type")?.asInt == 200) {
                            covers.putIfAbsent(PLAYLIST_GUESS_LIKE, cover)
                            covers.putIfAbsent(PLAYLIST_PERSONAL, cover)
                        }
                    }
                }
            }
            covers
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecommendCovers failed", e)
            emptyMap()
        }
    }

    private fun RecommendEntry.toPlayListInfo(coverOverride: String?): PlayListInfoResult =
        PlayListInfoResult(
            source, coverOverride ?: fallbackCover, desc, null, id, name, count
        )

    private fun authComm(base: Map<String, Any>): Map<String, Any> {
        val creds = getCreds<QQCredentials>() ?: return base
        return base.toMutableMap().apply {
            put("uin", creds.uin)
            put("authst", creds.authst)
        }
    }

    // ===== getUserInfo =====

    override fun getUserInfo(): UserInfo? {
        val creds = getCreds<QQCredentials>() ?: return null

        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11",
                "cv" to "14090508",
                "v" to "14090508",
                "tmeAppID" to "qqmusic",
                "uin" to creds.uin,
                "authst" to creds.authst
            ),
            "req" to mapOf(
                "module" to "music.UnifiedHomepage.UnifiedHomepageSrv",
                "method" to "GetHomepageHeader",
                "param" to mapOf(
                    "IsQueryTabDetail" to 1
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return null

        return try {
            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null

            val reqObj = json.getAsJsonObject("req") ?: return null
            if (reqObj.get("code")?.asInt != 0) return null

            val dataObj = reqObj.getAsJsonObject("data") ?: return null
            val info = dataObj.getAsJsonObject("Info") ?: return null
            val baseInfo = info.getAsJsonObject("BaseInfo") ?: return null

            val name = baseInfo.get("Name")?.asString ?: return null
            val avatar = baseInfo.get("Avatar")?.asString ?: ""
            val encryptedUin = baseInfo.get("EncryptedUin")?.asString ?: ""

            UserInfo(
                source = "qq",
                name = name,
                avatar = avatar,
                extra = if (encryptedUin.isNotEmpty()) mapOf("euin" to encryptedUin) else emptyMap()
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // ===== getUserPlaylist =====

    override fun getUserPlaylist(): List<PlayListInfoResult> {
        val creds = getCreds<QQCredentials>() ?: return emptyList()
        val userInfo = getUserInfo() ?: return emptyList()
        val euin = userInfo.extra["euin"] ?: return emptyList()

        Log.d(TAG, "getUserPlaylist: uin=${creds.uin}, euin=$euin")

        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11",
                "cv" to "14090508",
                "v" to "14090508",
                "tmeAppID" to "qqmusic",
                "uin" to creds.uin,
                "authst" to creds.authst
            ),
            "req1" to mapOf(
                "module" to "music.musicasset.PlaylistBaseRead",
                "method" to "GetPlaylistByUin",
                "param" to emptyMap<String, Any>()
            ),
            "req2" to mapOf(
                "module" to "music.musicasset.PlaylistFavRead",
                "method" to "CgiGetPlaylistFavInfo",
                "param" to mapOf(
                    "uin" to euin,
                    "offset" to 0,
                    "size" to 9999
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return emptyList()

        return try {
            val json = response.json(JsonObject::class.java) ?: return emptyList()
            if (json.get("code")?.asInt != 0) return emptyList()

            val covers = fetchRecommendCovers()
            val result = RECOMMEND_ENTRIES.mapTo(mutableListOf()) {
                it.toPlayListInfo(covers[it.id])
            }

            // 自建歌单
            val req1 = json.getAsJsonObject("req1")
            if (req1 != null && req1.get("code")?.asInt == 0) {
                val data1 = req1.getAsJsonObject("data")
                val vPlaylist = data1?.getAsJsonArray("v_playlist")
                Log.d(TAG, "自建歌单数量: ${vPlaylist?.size() ?: 0}")
                if (vPlaylist != null && vPlaylist.size() > 0) {
                    Log.d(TAG, "自建歌单第一项 keys: ${vPlaylist[0].asJsonObject.keySet()}")
                    for (elem in vPlaylist) {
                        try {
                            val pl = elem.asJsonObject
                            val parsed = parsePlaylistItem(pl)
                            if (parsed != null) result.add(parsed)
                        } catch (_: Exception) {
                        }
                    }
                }
            } else {
                Log.d(TAG, "req1 失败: code=${req1?.get("code")}")
            }

            // 收藏歌单
            val req2 = json.getAsJsonObject("req2")
            if (req2 != null && req2.get("code")?.asInt == 0) {
                val data2 = req2.getAsJsonObject("data")
                // 尝试多种字段名
                val vList = data2?.getAsJsonArray("v_list")
                    ?: data2?.getAsJsonArray("v_playlist")
                Log.d(TAG, "收藏歌单数量: ${vList?.size() ?: 0}, data2 keys: ${data2?.keySet()}")
                if (vList != null && vList.size() > 0) {
                    Log.d(TAG, "收藏歌单第一项 keys: ${vList[0].asJsonObject.keySet()}")
                    for (elem in vList) {
                        try {
                            val pl = elem.asJsonObject
                            val parsed = parsePlaylistItem(pl)
                            if (parsed != null) result.add(parsed)
                        } catch (_: Exception) {
                        }
                    }
                }
            } else {
                Log.d(TAG, "req2 失败: code=${req2?.get("code")}")
            }

            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /**
     * 通用歌单项解析
     * 实际 keys: dirId, dirName, tid, songNum, picUrl, bigpicUrl, albumPicUrl, nick, avatar, desc ...
     */
    private fun parsePlaylistItem(pl: JsonObject): PlayListInfoResult? {
        val tid = pl.get("tid")?.let {
            if (it.isJsonPrimitive) it.asJsonPrimitive.asString else null
        } ?: pl.get("dirId")?.let {
            if (it.isJsonPrimitive) it.asJsonPrimitive.asString else null
        } ?: pl.get("disstid")?.let {
            if (it.isJsonPrimitive) it.asJsonPrimitive.asString else null
        } ?: return null

        val title = pl.get("dirName")?.asString
            ?: pl.get("name")?.asString
            ?: pl.get("diss_name")?.asString
            ?: pl.get("dissname")?.asString
            ?: pl.get("title")?.asString

        val img = pl.get("picUrl")?.asString
            ?: pl.get("bigpicUrl")?.asString
            ?: pl.get("albumPicUrl")?.asString
            ?: pl.get("diss_cover")?.asString
            ?: pl.get("logo")?.asString
            ?: pl.get("coverUrl")?.asString

        val songCnt = pl.get("songNum")?.asInt
            ?: pl.get("song_cnt")?.asInt
            ?: pl.get("songnum")?.asInt
            ?: 0

        return PlayListInfoResult(
            source = source,
            img = img,
            description = pl.get("desc")?.asString,
            author = pl.get("nick")?.asString
                ?: pl.get("nickname")?.asString
                ?: pl.get("creator_name")?.asString,
            playListId = tid,
            title = title,
            totalSongs = songCnt
        )
    }

    // ===== getPlayListSongs =====

    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult {
        if (playListId == PLAYLIST_DAILY30) return getDaily30Songs(page, size)
        if (playListId == PLAYLIST_GUESS_LIKE) return getGuessLikeSongs(page, size)
        if (playListId == PLAYLIST_PERSONAL) return getPersonalSongs(page, size)

        val creds = getCreds<QQCredentials>()

        val comm = mapOf(
            "ct" to "11",
            "cv" to "14090508",
            "v" to "14090508",
            "tmeAppID" to "qqmusic",
            "uin" to (creds?.uin ?: "0"),
            "authst" to (creds?.authst ?: "")
        )

        val reqData = mapOf(
            "comm" to comm,
            "req" to mapOf(
                "module" to "music.srfDissInfo.DissInfo",
                "method" to "CgiGetDiss",
                "param" to mapOf<String, Any>(
                    "disstid" to (playListId.toLongOrNull() ?: playListId),
                    "song_num" to size,
                    "song_begin" to page * size,
                    "from" to 15,
                    "ctx" to 0,
                    "onlysonglist" to 0,
                    "orderlist" to 1,
                    "tag" to 1,
                    "rec_flag" to 1,
                    "new_format" to 1
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())

            if (json.get("code")?.asInt != 0) {
                return MusicListResult(source, false, page, size, emptyList())
            }

            val reqObj = json.getAsJsonObject("req")
                ?: return MusicListResult(source, false, page, size, emptyList())
            if (reqObj.get("code")?.asInt != 0) {
                return MusicListResult(source, false, page, size, emptyList())
            }

            val dataObj = reqObj.getAsJsonObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())

            val songList = mutableListOf<MusicItem>()
            var skippedLocal = 0
            val songListArr = dataObj.getAsJsonArray("songlist")
            if (songListArr != null) {
                for (elem in songListArr) {
                    try {
                        val parsed = QQMusicItem.parseTrackInfo(elem.asJsonObject)
                        if (parsed != null) {
                            songList.add(parsed)
                        } else {
                            // parseSongItem 返回 null 说明是未匹配的本地歌曲
                            skippedLocal++
                        }
                    } catch (_: Exception) {
                    }
                }
            }

            val totalSongs =
                dataObj.get("total_song_num")?.asInt ?: dataObj.get("songnum")?.asInt ?: 0
            val hasNext = (page + 1) * size < totalSongs

            MusicListResult(
                source,
                hasNext,
                page,
                size,
                songList.distinctBy { it.id },
                skippedLocal
            )
        } catch (e: Exception) {
            e.printStackTrace()
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun getDaily30Songs(page: Int, size: Int): MusicListResult {
        if (page > 0) return MusicListResult(source, false, page, size, emptyList())
        val creds = getCreds<QQCredentials>()
            ?: return MusicListResult(source, false, page, size, emptyList())

        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11", "cv" to "14090508", "v" to "14090508",
                "tmeAppID" to "qqmusic", "uin" to creds.uin, "authst" to creds.authst
            ),
            "req" to mapOf(
                "module" to "music.srfDissInfo.DissInfo",
                "method" to "CgiGetDiss",
                "param" to mapOf<String, Any>(
                    "disstid" to 0L,
                    "dirid" to 202,
                    "song_num" to 30,
                    "song_begin" to 0,
                    "from" to 15, "ctx" to 0,
                    "onlysonglist" to 1, "orderlist" to 1,
                    "tag" to 1, "rec_flag" to 1, "new_format" to 1
                )
            )
        )
        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return MusicListResult(source, false, page, size, emptyList())
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            val reqObj = json.getAsJsonObject("req")
                ?: return MusicListResult(source, false, page, size, emptyList())
            if (reqObj.get("code")?.asInt != 0)
                return MusicListResult(source, false, page, size, emptyList())
            val dataObj = reqObj.getAsJsonObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val songList = mutableListOf<MusicItem>()
            val arr = dataObj.getAsJsonArray("songlist")
            if (arr != null) {
                for (elem in arr) {
                    QQMusicItem.parseTrackInfo(elem.asJsonObject)?.let { songList.add(it) }
                }
            }
            MusicListResult(source, false, page, size, songList.distinctBy { it.id })
        } catch (_: Exception) {
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun getGuessLikeSongs(page: Int, size: Int): MusicListResult {
        if (page > 0) return MusicListResult(source, false, page, size, emptyList())
        val creds = getCreds<QQCredentials>()
            ?: return MusicListResult(source, false, page, size, emptyList())

        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11", "cv" to "14090508", "v" to "14090508",
                "tmeAppID" to "qqmusic", "uin" to creds.uin, "authst" to creds.authst
            ),
            "req" to mapOf(
                "module" to "music.recommend.RecommendFeed",
                "method" to "get_recommend_feed",
                "param" to mapOf<String, Any>(
                    "id" to 0, "size" to 1, "cmd" to 0
                )
            )
        )
        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return MusicListResult(source, false, page, size, emptyList())
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            val reqObj = json.getAsJsonObject("req")?.getAsJsonObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val shelves = reqObj.getAsJsonArray("v_shelf")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val songIds = mutableListOf<Long>()
            for (shelfElem in shelves) {
                val shelf = shelfElem.asJsonObject
                val niches = shelf.getAsJsonArray("v_niche") ?: continue
                for (nicheElem in niches) {
                    val cards = nicheElem.asJsonObject.getAsJsonArray("v_card") ?: continue
                    for (cardElem in cards) {
                        val card = cardElem.asJsonObject
                        if (card.get("type")?.asInt == 200) {
                            extractFeedSongId(card)?.let { songIds.add(it) }
                        }
                    }
                }
                if (songIds.isNotEmpty()) break
            }
            val songList = fetchSongDetailsBatch(songIds.distinct())
            MusicListResult(source, false, page, size, songList.distinctBy { it.id })
        } catch (_: Exception) {
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun getPersonalSongs(page: Int, size: Int): MusicListResult {
        if (page > 0) return MusicListResult(source, false, page, size, emptyList())
        val creds = getCreds<QQCredentials>()
            ?: return MusicListResult(source, false, page, size, emptyList())

        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to "11", "cv" to "14090508", "v" to "14090508",
                "tmeAppID" to "qqmusic", "uin" to creds.uin, "authst" to creds.authst
            ),
            "req" to mapOf(
                "module" to "music.recommend.RecommendFeed",
                "method" to "get_recommend_feed",
                "param" to mapOf<String, Any>(
                    "id" to 0, "size" to 3, "cmd" to 0
                )
            )
        )
        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return MusicListResult(source, false, page, size, emptyList())
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            val reqObj = json.getAsJsonObject("req")?.getAsJsonObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val shelves = reqObj.getAsJsonArray("v_shelf")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val songIds = mutableListOf<Long>()
            for (shelfElem in shelves) {
                val shelf = shelfElem.asJsonObject
                val shelfId = shelf.get("id")?.asInt ?: continue
                if (shelfId != 207) continue
                val niches = shelf.getAsJsonArray("v_niche") ?: continue
                for (nicheElem in niches) {
                    val cards = nicheElem.asJsonObject.getAsJsonArray("v_card") ?: continue
                    for (cardElem in cards) {
                        val card = cardElem.asJsonObject
                        if (card.get("type")?.asInt == 200) {
                            extractFeedSongId(card)?.let { songIds.add(it) }
                        }
                    }
                }
                break
            }
            val songList = fetchSongDetailsBatch(songIds.distinct())
            MusicListResult(source, false, page, size, songList.distinctBy { it.id })
        } catch (_: Exception) {
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    /** feed 卡片只带 songId/运营封面，缺播放所需的 mid/mediaMid/qualities，仅提取 songId 后批量补全。 */
    private fun extractFeedSongId(card: JsonObject): Long? =
        card.get("id")?.asString?.toLongOrNull()

    /**
     * 用 songId 列表批量查完整 track_info（含 mid、mediaMid、各音质与正确的专辑封面），
     * 保持输入顺序。用于 feed 推荐补全播放信息。
     */
    private fun fetchSongDetailsBatch(ids: List<Long>): List<QQMusicItem> {
        if (ids.isEmpty()) return emptyList()
        val reqData = mapOf(
            "comm" to authComm(mapOf("ct" to 19, "cv" to 1859, "uin" to 0)),
            "req" to mapOf(
                "module" to "music.trackInfo.UniformRuleCtrl",
                "method" to "CgiGetTrackInfo",
                "param" to mapOf(
                    "ids" to ids,
                    "types" to ids.map { 0 }
                )
            )
        )
        return try {
            val response = QQMusicUtils.zzcRequest(reqData)
            if (!response.isSuccessful) return emptyList()
            val json = response.json(JsonObject::class.java) ?: return emptyList()
            val data = json.getAsJsonObject("req")?.getAsJsonObject("data")
                ?: return emptyList()
            val tracks = data.getAsJsonArray("tracks") ?: return emptyList()
            val byId = mutableMapOf<Long, QQMusicItem>()
            for (elem in tracks) {
                QQMusicItem.parseTrackInfo(elem.asJsonObject)?.let { byId[it.id] = it }
            }
            ids.mapNotNull { byId[it] }
        } catch (e: Exception) {
            Log.e(TAG, "fetchSongDetailsBatch failed", e)
            emptyList()
        }
    }

    // ===== getPlayListInfo =====

    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? {
        RECOMMEND_ENTRIES.find { it.id == inputInfo }?.let { entry ->
            val cover = fetchRecommendCovers()[entry.id]
            return entry.toPlayListInfo(cover)
        }
        // 从输入中提取歌单 ID
        val dissId = extractDissId(inputInfo) ?: return null

        val creds = getCreds<QQCredentials>()
        val comm = mapOf(
            "ct" to "11",
            "cv" to "14090508",
            "v" to "14090508",
            "tmeAppID" to "qqmusic",
            "uin" to (creds?.uin ?: "0"),
            "authst" to (creds?.authst ?: "")
        )

        val reqData = mapOf(
            "comm" to comm,
            "req" to mapOf(
                "module" to "music.srfDissInfo.DissInfo",
                "method" to "CgiGetDiss",
                "param" to mapOf<String, Any>(
                    "disstid" to (dissId.toLongOrNull() ?: dissId),
                    "song_num" to 0,
                    "song_begin" to 0,
                    "from" to 15,
                    "ctx" to 0,
                    "onlysonglist" to 0,
                    "orderlist" to 1,
                    "tag" to 1,
                    "rec_flag" to 1,
                    "new_format" to 1
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) return null

        return try {
            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null

            val reqObj = json.getAsJsonObject("req") ?: return null
            if (reqObj.get("code")?.asInt != 0) return null

            val dataObj = reqObj.getAsJsonObject("data") ?: return null
            val dirInfo = dataObj.getAsJsonObject("dirinfo") ?: dataObj

            PlayListInfoResult(
                source = source,
                img = dirInfo.get("picurl")?.asString ?: dirInfo.get("logo")?.asString,
                description = dirInfo.get("desc")?.asString,
                author = dirInfo.get("creator")?.let { c ->
                    if (c.isJsonObject) c.asJsonObject.get("name")?.asString
                    else c.asString
                },
                playListId = dissId,
                title = dirInfo.get("title")?.asString,
                totalSongs = dataObj.get("total_song_num")?.asInt ?: dirInfo.get("songnum")?.asInt
                ?: 0
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun extractDissId(input: String): String? {
        // 纯数字直接返回
        if (input.trim().matches(Regex("^\\d+$"))) return input.trim()
        // 从文本中提取 URL
        val url = extractUrl(input)
        if (url != null) {
            // 尝试从 URL 中直接提取 ID
            val directId = extractIdFromUrl(url)
            if (directId != null) return directId
            // 包含 qq.com 和 __ 视为短链接，跟踪 Location 后再解析
            if (url.contains("qq.com") && url.contains("__")) {
                val location = HTTPUtils.getRedirect(url)
                if (location != null) {
                    return extractIdFromUrl(location)
                }
            }
        }
        // 没有匹配到 URL，尝试直接从全文本提取 ID（兼容纯 ID 输入）
        return extractIdFromUrl(input)
    }

    private fun extractUrl(text: String): String? {
        val urlMatch = Regex("https?://[^\\s]+").find(text)
        return urlMatch?.value
    }

    private fun extractIdFromUrl(text: String): String? {
        val idMatch = Regex("[?&]id=(\\d+)").find(text)
        if (idMatch != null) return idMatch.groupValues[1]
        val pathMatch = Regex("/playlist/(\\d+)\\.html").find(text)
        if (pathMatch != null) return pathMatch.groupValues[1]
        val pathMatch2 = Regex("/playlist/(\\d+)").find(text)
        if (pathMatch2 != null) return pathMatch2.groupValues[1]
        val dissMatch = Regex("disstid=(\\d+)").find(text)
        if (dissMatch != null) return dissMatch.groupValues[1]
        return null
    }

    override fun search(keyword: String, page: Int, size: Int): MusicListResult {
        val reqData = mapOf(
            "comm" to authComm(
                mapOf(
                    "_channelid" to "0",
                    "_os_version" to "6.2.9200-2",
                    "ct" to "19",
                    "cv" to "2151",
                    "guid" to "1F70E520B2EAA7D25E11760783C53CA9",
                    "patch" to "118",
                    "psrf_access_token_expiresAt" to 0,
                    "psrf_qqaccess_token" to "",
                    "psrf_qqopenid" to "",
                    "psrf_qqunionid" to "",
                    "tmeAppID" to "qqmusic",
                    "tmeLoginType" to 0,
                    "uin" to "0",
                    "wid" to "7223299733393904640"
                )
            ),
            "music.search.SearchCgiService" to mapOf(
                "module" to "music.search.SearchCgiService",
                "method" to "DoSearchForQQMusicDesktop",
                "param" to mapOf(
                    "grp" to 1,
                    "num_per_page" to size,
                    "page_num" to page + 1,
                    "query" to keyword,
                    "remoteplace" to "txt.newclient.top",
                    "search_type" to 0,
                    "searchid" to getPcSearchId()
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        val json = response.json(JsonObject::class.java) ?: return MusicListResult(
            source, false, page, size, emptyList()
        )

        if (json.get("code")?.asInt != 0) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        val reqObj = json.getAsJsonObject("music.search.SearchCgiService")
            ?: json.getAsJsonObject("req")
            ?: return MusicListResult(source, false, page, size, emptyList())

        if (reqObj.get("code")?.asInt != 0) {
            return MusicListResult(source, false, page, size, emptyList())
        }

        val dataObj = reqObj.getAsJsonObject("data")
            ?: return MusicListResult(source, false, page, size, emptyList())
        val bodyObj = dataObj.getAsJsonObject("body")
            ?: return MusicListResult(source, false, page, size, emptyList())

        val songList = mutableListOf<MusicItem>()
        val itemSongArr = bodyObj.getAsJsonObject("song")?.getAsJsonArray("list")
            ?: bodyObj.getAsJsonArray("item_song")

        if (itemSongArr != null) {
            for (elem in itemSongArr) {
                try {
                    val item = elem.asJsonObject
                    val song = QQMusicItem.parseTrackInfo(item) ?: continue
                    songList.add(song)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        val metaObj = dataObj.getAsJsonObject("meta")
        val nextPage = metaObj?.get("nextpage")?.asInt ?: -1
        val hasNext = nextPage > page

        return MusicListResult(source, hasNext, page, size, songList.distinctBy { it.id })
    }

    /**
     * Search ID 生成算法
     */
    private fun getSearchId(): String {
        val random = Random
        val e = random.nextLong(20) + 1
        val t = e * 18014398509481984L
        val nM = random.nextLong(4194305)
        val n = nM * 4294967296L
        val currentTimeMillis = System.currentTimeMillis()
        val r = currentTimeMillis % (24 * 60 * 60 * 1000L)
        val value = t + n + r
        return value.toString()
    }

    // 随机 Long 扩展方法 (兼容低版本 Kotlin/Java)
    private fun Random.nextLong(bound: Long): Long {
        return (nextDouble() * bound).toLong()
    }

    /**
     * PC 客户端版 searchid：32 位大写十六进制 GUID + 5 位零填充随机数 = 37 字符。
     *
     * 对应 PC 端 C++ 实现（CoCreateGuid + swprintf "%08X..." + rand()%100000 "%05d"）。
     * GUID 用 UUID.randomUUID() 复刻——字节序与 Win32 不同，但服务端只需要一个唯一
     * 的 37 位会话 ID，形状一致即等价。与 [getSearchId] 的 web 端算法用于不同端点。
     */
    private fun getPcSearchId(): String {
        val guid = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
        val rand = Random.nextInt(100000).toString().padStart(5, '0')
        return guid + rand
    }

    override fun getHotSearch(): List<String> {
        val reqData = mapOf(
            "comm" to mapOf("ct" to 19, "cv" to 1803, "uin" to 0),
            "hotkey" to mapOf(
                "module" to "tencent_musicsoso_hotkey.HotkeyService",
                "method" to "GetHotkeyForQQMusicPC",
                "param" to mapOf("uin" to 0, "search_id" to "")
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)

        if (!response.isSuccessful) return emptyList()

        try {
            val json = response.json(JsonObject::class.java) ?: return emptyList()

            // 检查业务 code
            val hotkeyObj = json.getAsJsonObject("hotkey")
            if (hotkeyObj == null || hotkeyObj.get("code")?.asInt != 0) return emptyList()

            val vecHotkey = hotkeyObj.getAsJsonObject("data")?.getAsJsonArray("vec_hotkey")
                ?: return emptyList()

            return vecHotkey.mapNotNull {
                it.asJsonObject.get("query")?.asString
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    override fun getSearchTip(keyword: String): List<String> {
        val reqData = mapOf(
            "comm" to mapOf(
                "ct" to 20,
                "cv" to 1859,
                "uin" to "0",
                "format" to "json",
                "platform" to "yqq"
            ),
            "req" to mapOf(
                "module" to "tencent_music_soso_smartbox_cgi.SmartBoxCgi",
                "method" to "GetSmartBoxResultForXiaomi",
                "param" to mapOf(
                    "search_id" to "0",
                    "query" to keyword,
                    "num_per_page" to 15,
                    "page_idx" to 1,
                    "uin" to 0
                )
            )
        )

        val response = QQMusicUtils.zzcRequest(reqData)

        if (!response.isSuccessful) return emptyList()

        try {
            val json = response.json(JsonObject::class.java) ?: return emptyList()

            // 检查业务 code
            val reqObj = json.getAsJsonObject("req")
            if (reqObj == null || reqObj.get("code")?.asInt != 0) return emptyList()

            val items = reqObj.getAsJsonObject("data")?.getAsJsonArray("items")
                ?: return emptyList()

            return items.mapNotNull {
                it.asJsonObject.get("hint")?.asString
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }

    override fun refreshLogin(): ProviderCredentials? {
        val creds = getCreds<QQCredentials>() ?: return null
        val refreshed = MobileQRLogin.refreshCredential(creds)
        if (refreshed != null) {
            credentials = refreshed
        }
        return refreshed
    }

    // ---- MV ----

    override fun supportsMv(item: MusicItem): Boolean = !item.mvid.isNullOrBlank()

    private fun fetchQQMvMp4Array(vid: String): com.google.gson.JsonArray? {
        val req = mapOf(
            "comm" to authComm(
                mapOf(
                    "ct" to 11,
                    "cv" to "21030600",
                    "v" to "1003006",
                    "tmeAppID" to "qqmusiclight",
                    "tmeLoginType" to "2"
                )
            ),
            "request" to mapOf(
                "module" to "gosrf.Stream.MvUrlProxy",
                "method" to "GetMvUrls",
                "param" to mapOf(
                    "vids" to listOf(vid),
                    "request_type" to 10003,
                    "videoformat" to 1,
                    "filetype" to 30,
                    "format" to 265,
                    "use_new_domain" to 1
                )
            )
        )
        val resp = QQMusicUtils.zzcRequest(req)
        if (!resp.isSuccessful) return null
        val body = resp.body ?: return null
        return try {
            val root = com.google.gson.JsonParser.parseString(body).asJsonObject
            // 响应结构: request.data[<vid>].mp4 是数组
            val data = root.getAsJsonObject("request")?.getAsJsonObject("data") ?: return null
            data.getAsJsonObject(vid)?.getAsJsonArray("mp4")
        } catch (e: Exception) {
            Log.w(TAG, "QQ MV parse failed", e)
            null
        }
    }

    private val qqFiletypeMapping = listOf(
        40 to "蓝光画质",
        30 to "超清画质",
        20 to "高清画质",
        10 to "标清画质"
    )

    override fun getMvQualities(item: MusicItem): List<MvQuality> {
        val vid = item.mvid?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            val mp4Arr = fetchQQMvMp4Array(vid) ?: return emptyList()
            val results = mutableListOf<MvQuality>()
            for (elem in mp4Arr) {
                val o = elem.asJsonObject
                val ft = o.get("filetype")?.asInt ?: continue
                // code != 0 表示该清晰度不可用（无版权/未提供）
                val code = o.get("code")?.asInt ?: 0
                if (code != 0) continue
                // 必须有可用 freeflow_url
                val urls = o.getAsJsonArray("freeflow_url")
                if (urls == null || urls.size() == 0) continue
                val name = qqFiletypeMapping.firstOrNull { it.first == ft }?.second ?: continue
                val sizeBytes = o.get("fileSize")?.asLong ?: 0L
                val size = if (sizeBytes > 0) formatSize(sizeBytes) else ""
                results.add(
                    MvQuality(
                        quality = ft.toString(),
                        displayName = name,
                        displaySize = size
                    )
                )
            }
            results.sortedBy { quality ->
                qqFiletypeMapping.indexOfFirst { it.first.toString() == quality.quality }
            }
        } catch (e: Exception) {
            Log.w(TAG, "QQ MV qualities failed", e)
            emptyList()
        }
    }

    override fun getMvUrl(item: MusicItem, quality: String): MvUrlResult {
        val vid = item.mvid?.takeIf { it.isNotBlank() }
            ?: return MvUrlResult(source, null, quality, "无 vid")
        return try {
            val mp4Arr = fetchQQMvMp4Array(vid)
                ?: return MvUrlResult(source, null, quality, "解析响应失败")
            for (elem in mp4Arr) {
                val o = elem.asJsonObject
                val ft = o.get("filetype")?.asInt?.toString() ?: continue
                if (ft != quality) continue
                val urls = o.getAsJsonArray("freeflow_url") ?: continue
                // 优先 https 链接
                val url = urls.map { it.asString }
                    .firstOrNull { it.startsWith("https://") }
                    ?: urls.firstOrNull()?.asString
                if (!url.isNullOrBlank()) return MvUrlResult(source, url, quality)
            }
            MvUrlResult(source, null, quality, "未找到清晰度")
        } catch (e: Exception) {
            Log.w(TAG, "QQ MV url failed", e)
            MvUrlResult(source, null, quality, e.message ?: "请求异常")
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val mb = bytes / 1024.0 / 1024.0
        return if (mb >= 1024) "%.2fGB".format(mb / 1024) else "%.1fMB".format(mb)
    }

    // ── 单曲详情 / 歌词 / 更新 / 分享 ──

    fun fromID(id: Long): QQMusicItem? = fetchSongDetail(songId = id, songMid = "")

    fun fromMid(mid: String): QQMusicItem? = fetchSongDetail(songId = 0, songMid = mid)

    private fun fetchSongDetail(songId: Long, songMid: String): QQMusicItem? {
        val reqData = mapOf(
            "comm" to mapOf("ct" to "19", "cv" to "1859", "uin" to "0"),
            "req" to mapOf(
                "module" to "music.pf_song_detail_svr",
                "method" to "get_song_detail_yqq",
                "param" to mapOf(
                    "song_type" to 0,
                    "song_id" to songId,
                    "song_mid" to songMid
                )
            )
        )

        return try {
            val response = HTTPUtils.post(SONG_DETAIL_URL, body = reqData)
            if (!response.isSuccessful) return null

            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null

            val reqObj = json.getAsJsonObject("req") ?: return null
            if (reqObj.get("code")?.asInt != 0) return null

            val data = reqObj.getAsJsonObject("data") ?: return null
            val trackInfo = data.getAsJsonObject("track_info") ?: return null

            QQMusicItem.parseTrackInfo(trackInfo)
        } catch (e: Exception) {
            Log.e(TAG, "fetchSongDetail failed", e)
            null
        }
    }

    override fun getLyric(item: MusicItem): Lyric {
        // 1) 主路径：musichallSong.PlayLyricInfo（含 QRC 逐字 / 翻译 / 音译）
        try {
            val primary = fetchQQMusicLyric(item)
            if (!primary.isEmpty()) return primary
        } catch (e: Exception) {
            Log.w(TAG, "QQ 主歌词接口异常", e)
        }

        // 2) 旧接口回退：fcg_query_lyric_new.fcg（base64 lyric/trans）
        try {
            val legacy = fetchLegacyLyric(item)
            if (!legacy.isEmpty()) return legacy
        } catch (e: Exception) {
            Log.w(TAG, "QQ 旧歌词接口异常", e)
        }

        // 3) JOOX 兜底：手动用同一个 songId 调 JOOX 歌词
        try {
            val jooxProvider = SoundApplication.instance?.musicRepository
                ?.getProvider("joox") as? JooxProvider
            val jooxLyric = jooxProvider?.fetchJooxLyric(item.id)
            if (jooxLyric != null && !jooxLyric.isEmpty()) {
                Log.d(TAG, "QQ 歌词回退至 JOOX 成功: id=${item.id}")
                return jooxLyric
            }
        } catch (e: Exception) {
            Log.w(TAG, "QQ 歌词 JOOX 兜底失败", e)
        }

        return Lyric()
    }

    override fun updateInfo(item: MusicItem): MusicItem {
        val qq = item as? QQMusicItem ?: return item
        return fetchSongDetail(songId = qq.id, songMid = "") ?: qq
    }

    override fun share(item: MusicItem): String {
        return "${item.title} - ${item.artist}\nhttps://y.qq.com/n/yqq/song/${item.id}.html\n(@QQ音乐)"
    }

    fun fetchQQMusicLyric(item: MusicItem): Lyric {
        val requestData = mapOf(
            "comm" to authComm(mapOf("ct" to 19, "cv" to 1, "uin" to 0)),
            "req" to mapOf(
                "method" to "GetPlayLyricInfo",
                "module" to "music.musichallSong.PlayLyricInfo",
                "param" to mapOf(
                    "format" to "json", "crypt" to 1,
                    "ct" to 19, "cv" to 1873,
                    "interval" to 0, "lrc_t" to 0,
                    "qrc" to 1, "qrc_t" to 0,
                    "roma" to 1, "roma_t" to 0,
                    "songID" to item.id,
                    "trans" to 1, "trans_t" to 0,
                    "type" to -1
                )
            )
        )

        Log.d(TAG, "请求歌词: songID=${item.id}, title=${item.title}")
        val response = HTTPUtils.post(SONG_DETAIL_URL, body = requestData)

        if (!response.isSuccessful) {
            Log.w(TAG, "歌词 HTTP 失败: ${response.status}")
            return Lyric()
        }
        val root = response.json(JsonObject::class.java) ?: return Lyric()
        val code = root.get("code")?.asInt
        val req = root.getAsJsonObject("req")
        val reqCode = req?.get("code")?.asInt
        if (code != 0 || reqCode != 0) {
            Log.w(TAG, "歌词接口异常: code=$code, req.code=$reqCode")
            return Lyric()
        }

        val data = req.getAsJsonObject("data") ?: return Lyric()
        val lyricHex = data.get("lyric")?.asString
        val transHex = data.get("trans")?.asString
        val romaHex = data.get("roma")?.asString
        return LrcParser.parseTx(lyricHex, transHex, romaHex).toLyric()
    }

    /**
     * 旧版歌词接口回退：仅返回 base64 编码的 LRC 主歌词 + 翻译（无逐字）。
     * 用 songmid 走 c.y.qq.com，需要伪造 y.qq.com Referer。
     */
    private fun fetchLegacyLyric(item: MusicItem): Lyric {
        val mid = (item as? QQMusicItem)?.mid?.takeIf { it.isNotBlank() }
            ?: fetchSongDetail(songId = item.id, songMid = "")?.mid?.takeIf { it.isNotBlank() }
            ?: return Lyric()

        val url = "https://c.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg" +
                "?songmid=$mid&g_tk=5381&loginUin=0&hostUin=0" +
                "&format=json&inCharset=utf8&outCharset=utf-8&platform=yqq"

        Log.d(TAG, "请求旧版歌词: mid=$mid")
        val response = HTTPUtils.get(
            url,
            mapOf("Referer" to "https://y.qq.com/portal/player.html")
        )
        if (!response.isSuccessful) {
            Log.w(TAG, "旧版歌词 HTTP 失败: ${response.status}")
            return Lyric()
        }
        val json = response.json(JsonObject::class.java) ?: return Lyric()
        if (json.get("code")?.asInt != 0) return Lyric()

        val lyric = decodeLegacyLyric(json.get("lyric")?.asString)
        val trans = decodeLegacyLyric(json.get("trans")?.asString)
        if (lyric.isEmpty() && trans.isEmpty()) return Lyric()
        return Lyric(lrc = lyric, trans = trans)
    }

    /** 旧版歌词 base64 解码 + HTML 实体反转义 */
    private fun decodeLegacyLyric(b64: String?): String {
        if (b64.isNullOrBlank()) return ""
        return try {
            val decoded = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
            decoded
                .replace("&apos;", "'")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&#10;", "\n")
                .replace("&#13;", "\r")
                .replace("&#32;", " ")
                .replace("&#160;", " ")
                .replace("&nbsp;", " ")
        } catch (e: Exception) {
            Log.w(TAG, "旧版歌词 base64 解码失败", e)
            ""
        }
    }

    // ===== 专辑搜索 / 详情 =====

    override fun searchAlbum(keyword: String, page: Int, size: Int): AlbumSearchResult {
        val reqData = mapOf(
            "comm" to authComm(
                mapOf(
                    "_channelid" to "0",
                    "_os_version" to "6.2.9200-2",
                    "ct" to "19",
                    "cv" to "2111",
                    "guid" to "1F70E520B2EAA7D25E11760783C53CA9",
                    "patch" to "118",
                    "psrf_access_token_expiresAt" to 0,
                    "psrf_qqaccess_token" to "",
                    "psrf_qqopenid" to "",
                    "psrf_qqunionid" to "",
                    "tmeAppID" to "qqmusic",
                    "tmeLoginType" to 0,
                    "wid" to "7192224010323313664"
                )
            ),
            "music.search.SearchCgiService" to mapOf(
                "module" to "music.search.SearchCgiService",
                "method" to "DoSearchForQQMusicDesktop",
                "param" to mapOf(
                    "grp" to 1,
                    "num_per_page" to size,
                    "page_num" to page + 1,
                    "query" to keyword,
                    "remoteplace" to "sizer.newclient.album",
                    "search_type" to 2,
                    "searchid" to ""
                )
            )
        )
        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) {
            return AlbumSearchResult(source, false, page, size, emptyList())
        }
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val data = json.getAsJsonObject("music.search.SearchCgiService")
                ?.getAsJsonObject("data")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val list = data.getAsJsonObject("body")
                ?.getAsJsonObject("album")
                ?.getAsJsonArray("list")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val total = data.getAsJsonObject("meta")
                ?.get("sum")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val items = list.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseQqAlbumEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchAlbum failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseQqAlbumEntry(obj: JsonObject): AlbumInfoResult? {
        val albumMid = obj.get("albumMID")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val rawName = obj.get("albumName")?.asString ?: return null
        val pubDate = obj.get("publicTime")?.asString?.trim()
        val publishTime = pubDate?.let { parseQqDate(it) }
        val cover = obj.get("albumPic")?.asString
            ?: "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg"
        return AlbumInfoResult(
            source = source,
            albumId = albumMid,
            name = stripHilight(rawName),
            cover = cover,
            artist = obj.get("singerName")?.asString,
            artistId = obj.get("singerID")?.takeIf { !it.isJsonNull }?.asString,
            publishTime = publishTime,
            company = null,
            subType = null,
            description = null,
            size = obj.get("song_count")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        )
    }

    private fun stripHilight(s: String): String =
        s.replace("<em>", "").replace("</em>", "")

    private fun parseQqDate(raw: String): Long? {
        if (raw.isBlank() || raw.startsWith("0000")) return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                .parse(raw)?.time
        } catch (_: Exception) {
            null
        }
    }

    override fun getAlbumInfo(albumId: String): AlbumInfoResult? {
        val albumMid = albumId
        val payload = fetchQqAlbumPayload(albumMid) ?: return null
        val data = payload.getAsJsonObject("req_1")?.getAsJsonObject("data") ?: return null
        val basic = data.getAsJsonObject("basicInfo") ?: return null
        val singerNode = data.getAsJsonObject("singer")
            ?.getAsJsonArray("singerList")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
        val publish = basic.get("publishDate")?.asString?.let { parseQqDate(it) }
        val total = payload.getAsJsonObject("req_2")
            ?.getAsJsonObject("data")
            ?.get("totalNum")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        return AlbumInfoResult(
            source = source,
            albumId = albumMid,
            name = basic.get("albumName")?.asString ?: return null,
            cover = "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg",
            artist = singerNode?.get("name")?.asString,
            artistId = singerNode?.get("singerID")?.takeIf { !it.isJsonNull }?.asString,
            publishTime = publish,
            company = data.getAsJsonObject("company")?.get("name")?.asString,
            subType = basic.get("albumType")?.asString,
            description = basic.get("desc")?.asString,
            size = total
        )
    }

    override fun getAlbumSongs(albumId: String): MusicListResult {
        val albumMid = albumId
        val payload = fetchQqAlbumPayload(albumMid)
            ?: return MusicListResult(source, false, 0, 1000, emptyList())
        val songList = payload.getAsJsonObject("req_2")
            ?.getAsJsonObject("data")
            ?.getAsJsonArray("songList")
            ?: return MusicListResult(source, false, 0, 1000, emptyList())
        val items = songList.mapNotNull { el ->
            val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val songInfo = obj.getAsJsonObject("songInfo") ?: return@mapNotNull null
            QQMusicItem.parseTrackInfo(songInfo)
        }
        return MusicListResult(source, false, 0, items.size.coerceAtLeast(1), items)
    }

    private fun fetchQqAlbumPayload(albumMid: String): JsonObject? {
        val reqData = mapOf(
            "comm" to authComm(
                mapOf(
                    "g_tk" to 5381,
                    "uin" to 0,
                    "format" to "json",
                    "ct" to 20,
                    "cv" to 2111,
                    "platform" to "wk_v17",
                    "uid" to "6660007954",
                    "guid" to "1F70E520B2EAA7D25E11760783C53CA9"
                )
            ),
            "req_1" to mapOf(
                "module" to "music.musichallAlbum.AlbumInfoServer",
                "method" to "GetAlbumDetail",
                "param" to mapOf("albumMid" to albumMid)
            ),
            "req_2" to mapOf(
                "module" to "music.musichallAlbum.AlbumSongList",
                "method" to "GetAlbumSongList",
                "param" to mapOf(
                    "albumMid" to albumMid,
                    "begin" to 0,
                    "num" to 1000,
                    "order" to 2
                )
            )
        )
        return try {
            val resp = QQMusicUtils.zzcRequest(reqData)
            if (!resp.isSuccessful) return null
            val json = resp.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null
            json
        } catch (e: Exception) {
            Log.e(TAG, "fetchQqAlbumPayload failed", e)
            null
        }
    }

    // ===== 歌手搜索 / 详情 =====

    override fun searchArtist(keyword: String, page: Int, size: Int): ArtistSearchResult {
        val reqData = mapOf(
            "comm" to authComm(
                mapOf(
                    "_channelid" to "0",
                    "_os_version" to "6.2.9200-2",
                    "ct" to "19",
                    "cv" to "2151",
                    "guid" to "1F70E520B2EAA7D25E11760783C53CA9",
                    "patch" to "118",
                    "tmeAppID" to "qqmusic",
                    "wid" to "7223299733393904640"
                )
            ),
            "music.search.SearchCgiService" to mapOf(
                "module" to "music.search.SearchCgiService",
                "method" to "DoSearchForQQMusicDesktop",
                "param" to mapOf(
                    "grp" to 1,
                    "num_per_page" to size,
                    "page_num" to page + 1,
                    "query" to keyword,
                    "remoteplace" to "sizer.newclient.singer",
                    "search_type" to 1,
                    "searchid" to getPcSearchId()
                )
            )
        )
        val response = QQMusicUtils.zzcRequest(reqData)
        if (!response.isSuccessful) {
            return ArtistSearchResult(source, false, page, size, emptyList())
        }
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val data = json.getAsJsonObject("music.search.SearchCgiService")
                ?.getAsJsonObject("data")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val list = data.getAsJsonObject("body")
                ?.getAsJsonObject("singer")
                ?.getAsJsonArray("list")
                ?: return ArtistSearchResult(source, false, page, size, emptyList())
            val total = data.getAsJsonObject("meta")
                ?.get("sum")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val items = list.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                parseQqSingerEntry(obj)
            }
            val hasNext = (page + 1) * size < total
            ArtistSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "searchArtist failed", e)
            ArtistSearchResult(source, false, page, size, emptyList())
        }
    }

    private fun parseQqSingerEntry(obj: JsonObject): ArtistInfoResult? {
        val singerMid = obj.get("singerMID")?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val name = obj.get("singerName")?.asString ?: return null
        return ArtistInfoResult(
            source = source,
            artistId = singerMid,
            name = stripHilight(name),
            avatar = obj.get("singerPic")?.asString,
            albumCount = obj.get("albumNum")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            musicCount = obj.get("songNum")?.takeIf { !it.isJsonNull }?.asInt ?: 0,
            fansCount = 0,
            description = null
        )
    }

    override fun getArtistInfo(artistId: String): ArtistInfoResult? {
        val payload = fetchQqSingerDetail(artistId) ?: return null
        val singer = payload.getAsJsonObject("req_0")
            ?.getAsJsonObject("data")
            ?.getAsJsonArray("singer_list")
            ?.takeIf { it.size() > 0 }
            ?.get(0)?.asJsonObject
            ?: return null
        val basic = singer.getAsJsonObject("basic_info")
        val name = basic?.get("name")?.asString ?: return null
        val singerMid = basic.get("singer_mid")?.asString ?: artistId
        val desc = singer.getAsJsonObject("ex_info")?.get("desc")
            ?.takeIf { !it.isJsonNull }?.asString
        val avatar = singer.getAsJsonObject("pic")?.get("pic")
            ?.takeIf { !it.isJsonNull }?.asString
            ?: "https://y.gtimg.cn/music/photo_new/T001R300x300M000$singerMid.jpg"
        val songCount = payload.getAsJsonObject("req_1")
            ?.getAsJsonObject("data")
            ?.get("totalNum")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        val albumCount = payload.getAsJsonObject("req_2")
            ?.getAsJsonObject("data")
            ?.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
        return ArtistInfoResult(
            source = source,
            artistId = singerMid,
            name = name,
            avatar = avatar,
            albumCount = albumCount,
            musicCount = songCount,
            fansCount = fetchQqSingerFansCount(singerMid),
            description = desc
        )
    }

    override fun getArtistSongs(artistId: String, page: Int, size: Int): MusicListResult {
        val reqData = mapOf(
            "comm" to authComm(
                mapOf(
                    "g_tk" to 5381,
                    "uin" to 0,
                    "format" to "json",
                    "ct" to 20,
                    "cv" to 2151,
                    "platform" to "wk_v17",
                    "uid" to "6660007954",
                    "guid" to "1F70E520B2EAA7D25E11760783C53CA9"
                )
            ),
            "req_0" to mapOf(
                "module" to "music.musichallSong.SongListInter",
                "method" to "GetSingerSongList",
                "param" to mapOf(
                    "singerMid" to artistId,
                    "begin" to page * size,
                    "num" to size,
                    "order" to 1
                )
            )
        )
        return try {
            val resp = QQMusicUtils.zzcRequest(reqData)
            if (!resp.isSuccessful) return MusicListResult(source, false, page, size, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return MusicListResult(source, false, page, size, emptyList())
            val data = json.getAsJsonObject("req_0")?.getAsJsonObject("data")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val total = data.get("totalNum")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val songList = data.getAsJsonArray("songList")
                ?: return MusicListResult(source, false, page, size, emptyList())
            val items = songList.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val songInfo = obj.getAsJsonObject("songInfo") ?: return@mapNotNull null
                QQMusicItem.parseTrackInfo(songInfo)
            }
            val hasNext = (page + 1) * size < total
            MusicListResult(source, hasNext, page, size, items.distinctBy { it.id })
        } catch (e: Exception) {
            Log.e(TAG, "getArtistSongs failed", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    override fun supportsArtistAlbums(): Boolean = true
    override fun supportsArtistMvs(): Boolean = true

    override fun getArtistAlbums(artistId: String, page: Int, size: Int): AlbumSearchResult {
        val reqData = mapOf(
            "comm" to qqWkComm(),
            "req_0" to mapOf(
                "module" to "music.musichallAlbum.AlbumListServer",
                "method" to "GetAlbumList",
                "param" to mapOf(
                    "singerMid" to artistId,
                    "order" to 0,
                    "num" to size,
                    "begin" to page * size
                )
            )
        )
        return try {
            val resp = QQMusicUtils.zzcRequest(reqData)
            if (!resp.isSuccessful) return AlbumSearchResult(source, false, page, size, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val data = json.getAsJsonObject("req_0")?.getAsJsonObject("data")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val list = data.getAsJsonArray("albumList")
                ?: return AlbumSearchResult(source, false, page, size, emptyList())
            val items = list.mapNotNull { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject?.let { parseQqSingerAlbumEntry(it) }
            }
            val hasNext = (page + 1) * size < total
            AlbumSearchResult(source, hasNext, page, size, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAlbums failed", e)
            AlbumSearchResult(source, false, page, size, emptyList())
        }
    }

    /** GetAlbumList 的条目字段（albumMid/publishDate/albumType）与搜索结果不同，单独解析。 */
    private fun parseQqSingerAlbumEntry(obj: JsonObject): AlbumInfoResult? {
        val albumMid = obj.get("albumMid")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val name = obj.get("albumName")?.asString ?: return null
        return AlbumInfoResult(
            source = source,
            albumId = albumMid,
            name = name,
            cover = "https://y.gtimg.cn/music/photo_new/T002R800x800M000$albumMid.jpg",
            artist = obj.get("singerName")?.asString,
            artistId = null,
            publishTime = obj.get("publishDate")?.asString?.let { parseQqDate(it) },
            company = null,
            subType = obj.get("albumType")?.asString,
            description = null,
            size = 0
        )
    }

    private fun qqWkComm(): Map<String, Any> = authComm(
        mapOf(
            "format" to "json",
            "ct" to 20,
            "cv" to 2151,
            "platform" to "wk_v17",
            "uid" to "6660007954",
            "guid" to "1F70E520B2EAA7D25E11760783C53CA9"
        )
    )

    override fun getArtistMvs(artistId: String, page: Int, size: Int): ArtistMvResult {
        val reqData = mapOf(
            "comm" to qqWkComm(),
            "req_1" to mapOf(
                "module" to "MvService.MvInfoProServer",
                "method" to "GetSingerMvList",
                "param" to mapOf(
                    "singermid" to artistId,
                    "tagid" to 0,
                    "start" to page * size,
                    "count" to size,
                    "order" to 0
                )
            )
        )
        return try {
            val resp = QQMusicUtils.zzcRequest(reqData)
            if (!resp.isSuccessful) return ArtistMvResult(source, false, page, size, 0, emptyList())
            val json = resp.json(JsonObject::class.java)
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            val data = json.getAsJsonObject("req_1")?.getAsJsonObject("data")
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            val total = data.get("total")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            val list = data.getAsJsonArray("list")
                ?: return ArtistMvResult(source, false, page, size, 0, emptyList())
            val items = list.mapNotNull { el ->
                val obj = el.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val vid = obj.get("vid")?.asString?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                ArtistMvItem(
                    source = source,
                    vid = vid,
                    title = obj.get("title")?.asString ?: "",
                    cover = obj.get("picurl")?.asString ?: "",
                    duration = obj.get("duration")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
                    playCount = obj.get("playcnt")?.takeIf { !it.isJsonNull }?.asLong ?: 0,
                    pubTime = obj.get("pubdate")?.takeIf { !it.isJsonNull }?.asLong ?: 0
                )
            }
            val hasNext = (page + 1) * size < total
            ArtistMvResult(source, hasNext, page, size, total, items)
        } catch (e: Exception) {
            Log.e(TAG, "getArtistMvs failed", e)
            ArtistMvResult(source, false, page, size, 0, emptyList())
        }
    }

    override fun createMvItem(vid: String, title: String, cover: String): MusicItem =
        QQMusicItem(
            id = 0,
            title = title,
            artist = "",
            album = "",
            cover = cover,
            duration = 0,
            qualities = emptyMap(),
            mid = "",
            albumMid = "",
            mediaMid = "",
            vs = emptyList(),
            size_new = emptyList(),
            mvid = vid
        )

    /** 匿名拉取歌手粉丝数；失败返回 0（头部不显示粉丝行）。 */
    private fun fetchQqSingerFansCount(singerMid: String): Long {
        return try {
            val url = "https://c6.y.qq.com/rsc/fcgi-bin/fcg_order_singer_getnum.fcg" +
                    "?g_tk=5381&uin=0&format=json&inCharset=utf-8&outCharset=utf-8" +
                    "&notice=0&platform=wk_v17&needNewCode=0&utf8=1&singermid=$singerMid"
            val resp = HTTPUtils.get(
                url,
                mapOf(
                    "Referer" to "https://y.qq.com/wk_v17/",
                    "Accept" to "application/json"
                )
            )
            if (!resp.isSuccessful) return 0
            val json = resp.json(JsonObject::class.java) ?: return 0
            json.get("num")?.takeIf { !it.isJsonNull }?.asLong ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "fetchQqSingerFansCount failed", e)
            0
        }
    }

    // ===== 听歌上报 =====

    /**
     * 上报听歌记录，影响 QQ 音乐推荐算法。
     * @param song 当前播放的 QQ 歌曲
     * @param playTimeMs 实际播放时长（毫秒）
     * @param playList 当前播放列表中所有 QQ 歌曲的 songId 列表
     */
    fun reportListening(song: QQMusicItem, playTimeMs: Long, playList: List<Long>): Boolean {
        val creds = getCreds<QQCredentials>() ?: return false
        QQDeviceManager.ensureQimeiReady()

        // 统一使用持久设备 comm，叠加该上报接口特有的 guid/patch
        val comm = QQDeviceManager.buildComm(creds).apply {
            put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
            put("patch", "118")
        }

        val reqData = mapOf(
            "comm" to comm,
            "music.richFlag.listening.ListeningMusicReport" to mapOf(
                "method" to "ListeningMusicReport",
                "module" to "music.richFlag.listening",
                "param" to mapOf(
                    "pauseFlag" to false,
                    "playList" to playList,
                    "remainingTime" to playTimeMs,
                    "songPlayTime" to playTimeMs,
                    "songid" to 0,
                    "songmid" to song.mid,
                    "songtype" to 1,
                    "speed" to 1
                )
            )
        )

        return try {
            val response = QQMusicUtils.zzcRequest(reqData)
            if (!response.isSuccessful) return false
            val json = response.json(JsonObject::class.java) ?: return false
            json.get("code")?.asInt == 0
        } catch (e: Exception) {
            Log.w(TAG, "reportListening failed", e)
            false
        }
    }

    // ===== 最近播放上报 =====

    /**
     * 上报"最近播放"记录（ReportPlayRecentlyInfo），同步到 QQ 音乐客户端的最近播放列表。
     * 走 musicu.fcg，无需 sign/mask。设备信息由 [QQDeviceManager] 持久化提供。
     * @param song 播放的歌曲
     */
    fun reportPlayRecently(song: QQMusicItem): Boolean {
        val creds = getCreds<QQCredentials>() ?: return false
        // QIMEI 首次访问时联网补齐，失败有兜底，不阻塞上报
        QQDeviceManager.ensureQimeiReady()

        val comm = QQDeviceManager.buildComm(creds)
        val lastTime = System.currentTimeMillis() / 1000

        val auxillaryId = song.albumId?.takeIf { it.isNotBlank() } ?: "0"
        val item = mutableMapOf<String, Any>(
            "id" to song.id.toString(),
            "type" to 2,
            "lastTime" to lastTime,
            "listenCnt" to 1,
            "auxillaryID" to auxillaryId,
            "auxillaryDict" to mapOf("vip" to "1")
        )

        val reqData = mapOf(
            "comm" to comm,
            "music.musicasset.PlayRecentlyWrite.ReportPlayRecentlyInfo" to mapOf(
                "module" to "music.musicasset.PlayRecentlyWrite",
                "method" to "ReportPlayRecentlyInfo",
                "param" to mapOf("data" to listOf(item))
            )
        )

        return try {
            val headers = mapOf(
                "Content-Type" to "application/json",
                "Referer" to "https://y.qq.com/"
            )
            val response = HTTPUtils.post(RECENTLY_URL, headers, reqData)
            if (!response.isSuccessful) return false
            val json = response.json(JsonObject::class.java) ?: return false
            json.get("code")?.asInt == 0
        } catch (e: Exception) {
            Log.w(TAG, "reportPlayRecently failed", e)
            false
        }
    }

    // ===== 播放流水上报 =====

    /**
     * 上报播放流水（imusic_tj），用于听歌时长统计。
     * @param song 播放的歌曲
     * @param playTimeSec 播放时长（秒）
     */
    fun reportPlayStream(song: QQMusicItem, playTimeSec: Long): Boolean {
        val creds = getCreds<QQCredentials>() ?: return false
        QQDeviceManager.ensureQimeiReady()
        val timestamp = System.currentTimeMillis() / 1000
        val timeStr = playTimeSec.toString()
        val timekey = md5("$timestamp$timeStr${creds.uin}$TIMEKEY_SALT").uppercase()

        val uid = randomDigits(10)
        val xml = buildStatXml(song, creds, timestamp, timeStr, timekey, uid)

        val gzipped = gzipCompress(xml.toByteArray(Charsets.UTF_8))

        val headers = mapOf(
            "User-Agent" to "QQMusic 12030508(android 12)",
            "Host" to "stat6.y.qq.com",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Content-Encoding" to "gzip",
            "Connection" to "Keep-Alive"
        )

        return try {
            val response = HTTPUtils.post(STAT_URL, headers, gzipped)
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "reportPlayStream failed", e)
            false
        }
    }

    private fun buildStatXml(
        song: QQMusicItem,
        creds: QQCredentials,
        timestamp: Long,
        time: String,
        timekey: String,
        uid: String
    ): String {
        val device = QQDeviceManager.getDevice()
        // 设备指纹取自持久设备，保证同一安装上报一致
        val openUDID = device.openUDID2
        val aid = device.androidId
        val qimei36 = device.qimei?.q36 ?: ""
        val phoneType = device.model
        val osVer = device.version.release
        val deviceLevel = device.version.sdk
        // 会话级随机字段（非持久设备指纹）
        val taid = randomHex(88)
        val tid = randomDigits(19)
        val sid = java.text.SimpleDateFormat("yyyyMMddHHmmss", java.util.Locale.US)
            .format(java.util.Date()) + uid
        val traceid = "11_${randomDigits(11)}_$timestamp"
        val vkey = randomHex(32)
        val v4ip = "${Random.nextInt(1, 224)}.${Random.nextInt(0, 256)}.${
            Random.nextInt(
                0,
                256
            )
        }.${Random.nextInt(1, 255)}"

        return """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<root>""" +
                """<OpenUDID>$openUDID</OpenUDID>""" +
                """<udid>$openUDID</udid>""" +
                """<ct>11</ct><cv>12030508</cv><v>12030508</v>""" +
                """<chid>74648</chid><os_ver>$osVer</os_ver>""" +
                """<aid>$aid</aid><phonetype>$phoneType</phonetype>""" +
                """<devicelevel>$deviceLevel</devicelevel><newdevicelevel>$deviceLevel</newdevicelevel>""" +
                """<deviceScore>800.0</deviceScore>""" +
                """<QIMEI36>$qimei36</QIMEI36>""" +
                """<taid>$taid</taid>""" +
                """<tmeAppID>qqmusic</tmeAppID><tid>$tid</tid>""" +
                """<modeSwitch>6</modeSwitch><teenMode>0</teenMode>""" +
                """<uid>$uid</uid><sid>$sid</sid>""" +
                """<OpenUDID2>$openUDID</OpenUDID2>""" +
                """<ui_mode>1</ui_mode><nettype>1030</nettype>""" +
                """<tmeLoginType>${creds.loginType}</tmeLoginType><tmeLoginMethod>2</tmeLoginMethod>""" +
                """<fPersonality>0</fPersonality><wid>$uid</wid>""" +
                """<v4ip>$v4ip</v4ip>""" +
                """<qq>${creds.uin}</qq>""" +
                """<authst>${creds.authst}</authst>""" +
                """<psrf_qqopenid>${creds.openid}</psrf_qqopenid>""" +
                """<psrf_access_token_expiresAt>${creds.expiredAt}</psrf_access_token_expiresAt>""" +
                """<psrf_qqaccess_token>${creds.accessToken}</psrf_qqaccess_token>""" +
                """<hotfix>100000000</hotfix>""" +
                """<traceid>$traceid</traceid>""" +
                """<cid>228</cid>""" +
                """<item cmd="1" optime="$timestamp" nettype="1030" """ +
                """QQ="${creds.uin}" uid="$uid" os="$osVer" model="$phoneType" """ +
                """version="12.3.5.8" songtype="1" playtype="4" """ +
                """from="1,132,151," dts="0" openstore="0" crytype="5" """ +
                """paytype="3" desktoplyric="0" playdevice="0" """ +
                """playlist_mode="0" outdev="0" url="26" playmode="1" """ +
                """repeat_times="0" cdn="" cdnip="" """ +
                """hasFirstBuffer="1" hijackflag="0" filetype="4" """ +
                """err="0" size="0" time="$time" retry="0" """ +
                """issoftdecode="1" component_type="2" """ +
                """bandwidth_policy="0" secondCacheCount="0" """ +
                """wait_time="0" player_retry="0" """ +
                """audiotime="${song.duration}" """ +
                """timekey="$timekey" """ +
                """vkey="$vkey" """ +
                """play_duration_mi="${playTimeMi(time)}" """ +
                """play_speed="1.0" vip_level="65552" """ +
                """audio_effect="0:0" """ +
                """songid="${song.id}" singerid="0" fversion="0"/>""" +
                """</root>"""
    }

    private fun playTimeMi(timeSec: String): String {
        val sec = timeSec.toLongOrNull() ?: 0
        return (sec * 1000).toString()
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun randomHex(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars[Random.nextInt(chars.length)] }.joinToString("")
    }

    private fun randomDigits(len: Int): String {
        return (1..len).map { Random.nextInt(10) }.joinToString("")
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun fetchQqSingerDetail(singerMid: String): JsonObject? {
        val reqData = mapOf(
            "comm" to qqWkComm(),
            "req_0" to mapOf(
                "module" to "music.musichallSinger.SingerInfoInter",
                "method" to "GetSingerDetail",
                "param" to mapOf(
                    "singer_mids" to listOf(singerMid),
                    "pic" to 1,
                    "group_singer" to 1,
                    "wiki_singer" to 1,
                    "ex_singer" to 1
                )
            ),
            "req_1" to mapOf(
                "module" to "music.musichallSong.SongListInter",
                "method" to "GetSingerSongList",
                "param" to mapOf(
                    "singerMid" to singerMid,
                    "begin" to 0,
                    "num" to 1,
                    "order" to 1
                )
            ),
            "req_2" to mapOf(
                "module" to "music.musichallAlbum.AlbumListServer",
                "method" to "GetAlbumList",
                "param" to mapOf(
                    "singerMid" to singerMid,
                    "order" to 1,
                    "num" to 1,
                    "begin" to 0
                )
            )
        )
        return try {
            val resp = QQMusicUtils.zzcRequest(reqData)
            if (!resp.isSuccessful) return null
            val json = resp.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 0) return null
            json
        } catch (e: Exception) {
            Log.e(TAG, "fetchQqSingerDetail failed", e)
            null
        }
    }
}
