package com.ikunshare.music.platform.wy

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ikunshare.music.platform.wy.utils.NeteaseCrypto
import com.ikunshare.music.platform.wy.utils.buildNcblRecord
import com.ikunshare.music.platform.wy.utils.encryptNcbl
import com.ikunshare.music.utils.HTTPResponse
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToLong
import kotlin.random.Random

internal data class WyReportSong(
    val id: Long,
    val title: String,
    val artist: String,
    val level: String,
    val bitrate: Int,
    val time: Long
)

internal data class WyReportSource(
    val id: String,
    val type: String = "track",
    val name: String = "likeMusic"
)

internal interface WyClientIdStore {
    fun read(): String?
    fun write(value: String)
}

private class SharedPreferencesWyClientIdStore(context: Context) : WyClientIdStore {
    private val preferences = context.applicationContext.getSharedPreferences("wy_listen_report", Context.MODE_PRIVATE)
    override fun read(): String? = preferences.getString("wnmcid", null)
    override fun write(value: String) = preferences.edit().putString("wnmcid", value).apply()
}

internal class WyListenReporter(
    private val clientIdStore: WyClientIdStore,
    private val uploadUrl: String = UPLOAD_URL,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val deviceId: () -> String = NeteaseCrypto::getDeviceId,
    private val accountRequest: suspend (String) -> HTTPResponse = { musicU ->
        NeteaseCrypto.eapiPostCancellable("/api/nuser/account/get", emptyMap<String, Any>(), musicU)
    },
    private val uploadRequest: suspend (String, Map<String, String>, ByteArray) -> HTTPResponse =
        { url, headers, body -> HTTPUtils.postCancellable(url, headers, body) },
    private val encodePayload: (String, String) -> ByteArray = ::encryptNcbl,
    private val diagnosticLog: (String) -> Unit = { Log.d(TAG, it) }
) {
    constructor(context: Context) : this(SharedPreferencesWyClientIdStore(context))

    private val uploadSequence = AtomicInteger()

    @Volatile
    private var vipTypeCache: VipTypeCache? = null

    suspend fun playStart(
        cookie: String?,
        song: NeteaseMusicItem,
        qualityId: String,
        sourceId: String?
    ): Boolean {
        val musicU = loginMusicU(cookie) ?: return false
        if (song.id == 0L) return false
        val reportSong = toReportSong(song, qualityId)
        val source = WyReportSource(sourceId?.takeIf { it.isNotBlank() } ?: "0")
        return upload(
            musicU,
            nowMs() / 1000L,
            "_plv",
            buildPlv(reportSong, source, vipType(musicU))
        )
    }

    suspend fun playEnd(
        cookie: String?,
        song: NeteaseMusicItem,
        qualityId: String,
        sourceId: String?,
        playedSec: Long
    ): Boolean {
        val musicU = loginMusicU(cookie) ?: return false
        if (song.id == 0L || playedSec <= 0L) return false
        val reportSong = toReportSong(song, qualityId)
        val source = WyReportSource(sourceId?.takeIf { it.isNotBlank() } ?: "0")
        return upload(
            musicU,
            nowMs() / 1000L,
            "_pld",
            buildPld(reportSong, source, playedSec, vipType(musicU))
        )
    }

    private fun loginMusicU(cookie: String?): String? {
        if (cookie.isNullOrBlank()) return null
        return NeteaseCrypto.extractMusicU(cookie) ?: cookie
    }

    @Synchronized
    private fun clientId(): String {
        clientIdStore.read()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val prefix = buildString(6) { repeat(6) { append(LETTERS[Random.nextInt(LETTERS.length)]) } }
        val value = "$prefix.${nowMs()}.01.0"
        clientIdStore.write(value)
        return value
    }

    private suspend fun vipType(musicU: String): String {
        vipTypeCache?.takeIf { it.musicU == musicU }?.let { return it.value }
        return try {
            val response = accountRequest(musicU)
            val json = response.body?.let { Gson().fromJson(it, JsonObject::class.java) }
            if (!response.isSuccessful || json?.get("code")?.asInt != 200) return ""
            val value = json.getAsJsonObject("account")?.get("vipType")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
            vipTypeCache = VipTypeCache(musicU, value)
            value
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    private fun identity(musicU: String): LinkedHashMap<String, String> = linkedMapOf(
        "JSESSIONID-WYYY" to "",
        "MUSIC_U" to musicU,
        "NMTID" to "",
        "WEVNSM" to "1.0.0",
        "WNMCID" to clientId(),
        "__csrf" to "",
        "_iuqxldmzr_" to "33",
        "_ntes_nnid" to ",",
        "_ntes_nuid" to "",
        "appver" to "$APP_VERSION.$APP_VERSION_CODE",
        "channel" to CHANNEL,
        "clientSign" to "",
        "deviceId" to deviceId(),
        "mode" to "",
        "ntes_kaola_ad" to "1",
        "os" to "pc",
        "osver" to NeteaseCrypto.OS_VER
    )

    private suspend fun upload(musicU: String, time: Long, action: String, data: Map<String, Any?>): Boolean {
        return try {
            val identity = identity(musicU)
            val record = buildNcblRecord(time, action, Gson().toJson(data))
            val payload = encodePayload(Gson().toJson(identity), record)
            val filename = buildFileName()
            val multipart = buildMultipart(filename, payload)
            val headers = linkedMapOf(
                "Referer" to "https://music.163.com/di",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 " +
                    "NeteaseMusicDesktop/$APP_VERSION",
                "Accept-Language" to "zh-CN,zh;q=0.8",
                "Content-Type" to "multipart/form-data; boundary=${multipart.boundary}",
                "Cookie" to identity.entries.joinToString("; ") { "${it.key}=${it.value}" }
            )
            val response = uploadRequest("$uploadUrl?multiupload=true", headers, multipart.body)
            val json = response.body?.let { Gson().fromJson(it, JsonObject::class.java) }
            val acknowledged = json?.getAsJsonObject("data")?.getAsJsonArray("successfiles")?.any {
                !it.isJsonNull && it.asString == filename
            } == true
            diagnosticLog("$action HTTP ${response.status}, acknowledged=$acknowledged")
            response.isSuccessful && json?.get("code")?.asInt == 200 && acknowledged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Netease listen report failed: $action", e)
            false
        }
    }

    private fun buildFileName(): String =
        "op_${Random.nextInt(10_000, 100_000)}_${uploadSequence.getAndIncrement()}_" +
            Random.nextLong(1L, 4_294_967_296L)

    private fun buildMultipart(filename: String, payload: ByteArray): MultipartBody {
        val boundary = UUID.randomUUID().toString()
        val head = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n" +
            "Content-Type: multipart/form-data\r\n\r\n"
        val tail = "\r\n--$boundary--\r\n"
        return MultipartBody(
            ByteArrayOutputStream(head.length + payload.size + tail.length).apply {
                writeBytes(head.toByteArray(Charsets.UTF_8))
                writeBytes(payload)
                writeBytes(tail.toByteArray(Charsets.UTF_8))
            }.toByteArray(),
            boundary
        )
    }

    private data class MultipartBody(val body: ByteArray, val boundary: String)
    private data class VipTypeCache(val musicU: String, val value: String)

    companion object {
        private const val TAG = "WyListenReporter"
        private const val UPLOAD_URL = "https://clientlog3.music.163.com/api/clientlog/encrypt/upload"
        private const val APP_VERSION = "3.1.35"
        private const val APP_VERSION_CODE = "205293"
        private const val CHANNEL = "netease"
        private const val LETTERS = "abcdefghijklmnopqrstuvwxyz"
    }
}

internal fun toReportSong(item: NeteaseMusicItem, qualityId: String): WyReportSong {
    val (level, bitrate) = when (qualityId) {
        "128k" -> "standard" to 128
        "flac" -> "lossless" to 999
        "hires" -> "hires" to 1999
        "atmos_plus" -> "jyeffect" to 1999
        "atmos" -> "sky" to 1999
        "master" -> "jymaster" to 1999
        "dolby" -> "dolby" to 1999
        else -> "exhigh" to 320
    }
    return WyReportSong(
        item.id,
        item.title,
        item.artist,
        level,
        bitrate,
        (item.duration / 1000.0).roundToLong().coerceAtLeast(0L)
    )
}

private fun buildPlv(song: WyReportSong, source: WyReportSource, vipType: String): Map<String, Any?> {
    val now = System.currentTimeMillis()
    val version = "3.1.35"
    val versionCode = "205293"
    val buildHash = "c9156c3"
    val addRefer = "[F:63][$now#933#$version#$versionCode#$buildHash][e][2][23]" +
        "[cell_pc_songlist_song:2|page_pc_songlist_songflow|page_mine_like_music]" +
        "[${song.id}:song:x:x|:::|${source.id}:list::]"
    return linkedMapOf(
        "mode" to "circulation", "download" to 0, "alg" to "", "status" to "front",
        "id" to song.id.toString(), "bitrate" to song.bitrate, "type" to "song",
        "is_listentogether" to 0, "source" to source.name, "is_heart" to 0,
        "resource_ratio" to "", "resource_time" to song.time, "musiceffect_id" to "",
        "app_mode" to 2, "bitrate_level" to song.level, "_addrefer" to addRefer,
        "_multirefers" to listOf(
            "[F:26][s][18][_ai]", "[F:26][s][12][_ai]",
            "[F:63][$now#933#$version#$versionCode#$buildHash][e][2][8]" +
                "[cell_pc_main_tab_entrance:6|page_pc_main_tab][我喜欢的音乐:spm::|:::]",
            "[F:26][s][5][_ai]", "[F:26][s][0][_ai]"
        ),
        "vipType" to vipType, "fee" to 1, "file" to 4, "rightSource" to 0,
        "sourceId" to source.id, "sourcetype" to source.type, "libra_abt" to "",
        "channel" to "netease", "curStartChannel" to ""
    )
}

private fun buildPld(
    song: WyReportSong,
    source: WyReportSource,
    played: Long,
    vipType: String
): Map<String, Any?> {
    val now = System.currentTimeMillis()
    val addRefer = "[F:63][$now#616#3.1.35#205293#c9156c3][e][2][92]" +
        "[btn_pc_cover_play|cell_pc_songlist_song:6|page_pc_songlist_songflow|page_mine_like_music]" +
        "[:::|${song.id}:song:x:x|:::|${source.id}:list::]"
    return linkedMapOf(
        "mode" to "circulation", "download" to 0, "alg" to "", "status" to "front",
        "id" to song.id.toString(), "time" to played, "type" to "song",
        "is_listentogether" to 0, "source" to source.name, "is_heart" to 0,
        "realtime" to played, "resource_ratio" to "", "resource_time" to song.time,
        "musiceffect_id" to "1001", "app_mode" to 1, "lyriceffect" to "default",
        "displayMode" to "classic", "bitrate" to song.bitrate, "bitrate_level" to song.level,
        "_addrefer" to addRefer,
        "_multirefers" to listOf(
            "[F:26][s][87][_ai]", "[F:26][s][81][_ai]", "[F:26][s][75][_ai]",
            "[F:26][s][69][_ai]", "[F:26][s][63][_ai]"
        ),
        "vipType" to vipType, "fee" to 8, "file" to 4, "rightSource" to 0,
        "sourceId" to source.id, "sourcetype" to source.type, "end" to "interrupt",
        "libra_abt" to "", "channel" to "netease", "curStartChannel" to ""
    )
}
