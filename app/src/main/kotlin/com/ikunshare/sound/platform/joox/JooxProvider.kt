package com.ikunshare.sound.platform.joox

import android.text.TextUtils
import android.util.Base64
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.crypto.HashUtil
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.platform.base.BaseProvider
import com.ikunshare.sound.platform.base.MusicListResult
import com.ikunshare.sound.platform.base.PlayListInfoResult
import com.ikunshare.sound.platform.qq.QQProvider
import com.ikunshare.sound.tool.ChineseConverter
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.lyric.LrcParser.LyricResult
import com.ikunshare.sound.utils.lyric.LrcParser.isStandardLrc
import com.ikunshare.sound.utils.lyric.LrcParser.parseTxQrc
import com.ikunshare.sound.utils.lyric.toLyric
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.math.abs

class JooxProvider : BaseProvider("joox") {

    override val displayName = "JOOX"
    override val shortTag = "jx"

    companion object {
        private const val TAG = "JooxProvider"

        /** Base64 解码后转简体 */
        private fun decodeB64(encoded: String?): String {
            if (encoded.isNullOrBlank()) return ""
            return try {
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                ChineseConverter.toSimplified(decoded)
            } catch (_: Exception) {
                encoded
            }
        }

        /** kbps_map JSON key → (Quality ID, Quality Name) */
        private val QUALITY_MAP = mapOf(
            "128" to ("128k" to "普通音质 128K"),
            "320" to ("320k" to "高品音质 320K"),
            "flac" to ("flac" to "无损音质 FLAC"),
            "hires" to ("hires" to "无损音质 Hi-Res"),
            "master_tape" to ("master" to "臻品母带"),
            "stereo_atmos" to ("atmos" to "臻品全景声")
        )
    }

    override fun search(keyword: String, page: Int, size: Int): MusicListResult {
        val sin = page * size

        val body = JsonObject().apply {
            add("header", JsonObject().apply {
                addProperty("iUid", "0")
                addProperty("iSid", "0")
                addProperty("iCv", 671154790)
                addProperty("sPhoneType", "Android-M2012K11AC")
                addProperty("sOpenUdid", "fffffffffda6788700000192e18d016e")
                addProperty("iMcc", "65535")
                addProperty("iMnc", "65535")
                addProperty("sCountry", "HK")
                addProperty("sLang", "zh_CN")
                addProperty("iWmid", "334619483")
                addProperty("iChid", "000")
                addProperty("sBackendCountry", "hk")
                addProperty("iUserType", 2)
                addProperty("sOsVer", "33")
                addProperty("sSkey", "")
                addProperty("iNetType", 1)
                addProperty("iMlid", "0")
                addProperty("iVip", 1)
                addProperty("iVvip", 1)
                addProperty("iAppStoreChannel", 0)
                addProperty("iTerminalType", 1)
                addProperty("sAppid", "1000716")
                addProperty("sDebugInfo", "")
            })
            addProperty("type", 0)
            addProperty("keyword", keyword)
            addProperty("keyword_source", 0)
            addProperty("search_id", "2312821361563828")
            addProperty("sin", sin)
            addProperty("ein", size)
            addProperty("search_channel", "")
            addProperty("nqc_flag", 0)
            add("custom_params", JsonObject().apply {
                addProperty("ambi_data", "")
            })
        }

        val headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to "https://wesing.joox.com?hippy=joox-search&_wv=1&currentTime=46602900"
        )

        return try {
            val response = HTTPUtils.post(
                "http://avatar.api.joox.com/commonCgi/search/get",
                headers,
                body.toString()
            )
            if (!response.isSuccessful) {
                Log.e(TAG, "Search failed: HTTP ${response.status}")
                return MusicListResult(source, false, page, size, emptyList())
            }

            val json = JsonParser.parseString(response.body).asJsonObject
            val sum = json.get("sum")?.asInt ?: 0
            val aggregationList = json.getAsJsonArray("song_aggregation_list")
            if (aggregationList == null || aggregationList.size() == 0) {
                return MusicListResult(source, false, page, size, emptyList())
            }

            val results = mutableListOf<MusicItem>()
            for (aggIdx in 0 until aggregationList.size()) {
                val itemList = aggregationList[aggIdx].asJsonObject
                    .getAsJsonArray("item_list") ?: continue
                for (i in 0 until itemList.size()) {
                    try {
                        val item = itemList[i].asJsonObject
                        val songInfo = item.getAsJsonObject("song_info")
                        val parsed = parseSongInfo(songInfo)
                        if (parsed != null) results.add(parsed)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse song at index $i", e)
                    }
                }
            }

            val hasNext = (sin + results.size) < sum
            MusicListResult(source, hasNext, page, size, results)
        } catch (e: Exception) {
            Log.e(TAG, "Search error", e)
            MusicListResult(source, false, page, size, emptyList())
        }
    }

    private fun parseSongInfo(songInfo: JsonObject): JooxMusicItem? {
        val songId = songInfo.get("songid")?.asString?.toLongOrNull() ?: return null
        val songName = decodeB64(songInfo.get("songname")?.asString)
        val albumName = decodeB64(songInfo.get("albumname")?.asString)
        val albumUrl = songInfo.get("album_url")?.asString ?: ""
        val playtime = songInfo.get("playtime")?.asLong ?: 0
        val songmid = songInfo.get("songmid")?.asString ?: return null

        // 从 singerInfo 拼接歌手名
        val singerInfoArr = songInfo.getAsJsonArray("singerInfo")
        val artist = if (singerInfoArr != null && singerInfoArr.size() > 0) {
            singerInfoArr.mapNotNull { elem ->
                val singerObj = elem.asJsonObject
                val name = decodeB64(singerObj.get("singername")?.asString)
                name.ifBlank { null }
            }.joinToString("、")
        } else {
            decodeB64(songInfo.get("singername")?.asString)
        }

        // 解析 kbps_map
        val qualities = mutableMapOf<String, Quality>()
        val kbpsMapStr = songInfo.get("kbps_map")?.asString
        if (!kbpsMapStr.isNullOrBlank()) {
            try {
                val kbpsMap = JsonParser.parseString(kbpsMapStr).asJsonObject
                for ((apiKey, qualityPair) in QUALITY_MAP) {
                    val fileSize = kbpsMap.get(apiKey)?.asLong ?: 0
                    if (fileSize > 0) {
                        val (qualityId, qualityName) = qualityPair
                        qualities[qualityId] = Quality(qualityId, qualityName, fileSize)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse kbps_map", e)
            }
        }

        return JooxMusicItem(
            id = songId,
            title = songName,
            artist = artist,
            album = albumName,
            cover = albumUrl,
            duration = playtime * 1000, // 转毫秒
            qualities = qualities,
            mid = songmid
        )
    }

    override fun getHotSearch(): List<String> = emptyList()

    override fun getSearchTip(keyword: String): List<String> = emptyList()

    override fun getPlayListInfo(inputInfo: String): PlayListInfoResult? = null

    override fun getPlayListSongs(playListId: String, page: Int, size: Int): MusicListResult {
        return MusicListResult(source, false, page, size, emptyList())
    }

    override fun getUserPlaylist(): List<PlayListInfoResult> = emptyList()

    override fun getUserInfo(): UserInfo? = null

    // ── 单曲行为 ──

    override fun updateInfo(item: MusicItem): MusicItem = item

    override fun share(item: MusicItem): String = "${item.title} - ${item.artist}\n(@JOOX)"

    override fun getLyric(item: MusicItem): Lyric {
        val joox = item as? JooxMusicItem ?: return Lyric()

        // 策略1: 尝试用数字ID查QQ音乐，时长相差1s内视为同一首歌，复用QQ歌词（含逐字）
        try {
            val qqProvider =
                SoundApplication.instance?.musicRepository?.getProvider("qq") as? QQProvider
            val qqItem = qqProvider?.fromID(joox.id)
            if (qqItem != null && abs(joox.duration - qqItem.duration) <= 1000) {
                Log.d(
                    TAG,
                    "QQ音乐匹配成功(时长差=${abs(joox.duration - qqItem.duration)}ms): " +
                            "joox=\"${joox.title}\" qq=\"${qqItem.title}\""
                )
                val lyric = qqProvider.fetchQQMusicLyric(qqItem)
                if (!lyric.isEmpty()) return lyric
            }
        } catch (e: Exception) {
            Log.w(TAG, "QQ音乐歌词回退失败", e)
        }

        return fetchJooxLyric(joox.id)
    }


    fun fetchJooxLyric(songId: Long): Lyric {
        return try {
            val url = buildTrackDetailUrl(songId)
            Log.d(TAG, "请求JOOX歌词: $url")
            val response = HTTPUtils.get(url)
            if (!response.isSuccessful) {
                Log.w(TAG, "JOOX歌词请求失败: HTTP ${response.status}")
                return Lyric()
            }

            val json = JsonParser.parseString(response.body).asJsonObject

            val qrcExist = json.get("qrc_exist")?.asInt ?: 0
            if (qrcExist == 1) {
                val qrcContent = json.get("qrc_content")?.asString
                if (!qrcContent.isNullOrBlank()) {
                    val result = LyricResult()
                    val qrcBytes = Base64.decode(qrcContent, Base64.DEFAULT)
                    val qrcHex = String(qrcBytes, Charset.forName("UTF-8"))
                    if (!TextUtils.isEmpty(qrcHex)) {
                        parseTxQrc(qrcHex, result)
                        if (result.lyric.isEmpty() && isStandardLrc(qrcHex)) {
                            result.lyric = qrcHex
                        }
                    }
                    val lyric = result.toLyric()
                    if (!lyric.isEmpty()) {
                        Log.d(TAG, "JOOX QRC歌词解析成功")
                        return lyric.copy(
                            lrc = ChineseConverter.toSimplified(lyric.lrc),
                            char = ChineseConverter.toSimplified(lyric.char)
                        )
                    }
                }
            }

            val lrcExist = json.get("lrc_exist")?.asInt ?: 0
            if (lrcExist == 1) {
                val lrcContent = json.get("lrc_content")?.asString
                if (!lrcContent.isNullOrBlank()) {
                    val lrcText = String(Base64.decode(lrcContent, Base64.DEFAULT), Charsets.UTF_8)
                    val simplified = ChineseConverter.toSimplified(lrcText)
                    Log.d(TAG, "JOOX LRC歌词获取成功，长度=${simplified.length}")
                    return Lyric(lrc = simplified)
                }
            }

            Log.d(TAG, "JOOX无歌词")
            Lyric()
        } catch (e: Exception) {
            Log.e(TAG, "JOOX歌词获取失败", e)
            Lyric()
        }
    }

    /**
     * 构建 JOOX track detail API URL（secret = MD5("Jo0x@t3Nc3nT" + param_string)）
     */
    private fun buildTrackDetailUrl(songId: Long): String {
        val params = linkedMapOf(
            "country" to "hk",
            "lang" to "zh_TW",
            "lyric" to "1",
            "fs" to "0",
            "im" to "0",
            "id" to songId.toString()
        )
        val paramStr = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        val secret = HashUtil.md5("Jo0x@t3Nc3nT$paramStr")
        params.remove("id")
        params["secret"] = secret

        val queryString = params.entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
        return "https://cache.api.joox.com/openjoox2/v1/track/$songId?$queryString"
    }
}
