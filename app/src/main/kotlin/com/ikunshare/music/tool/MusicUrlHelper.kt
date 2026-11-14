package com.ikunshare.music.tool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.music.manager.AuthManager
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.Quality
import com.ikunshare.music.platform.base.EncryptionInfo
import com.ikunshare.music.platform.base.MediaInfoResult
import com.ikunshare.music.platform.am.parseAppleMediaMetadata
import com.ikunshare.music.platform.kg.KugouMusicItem
import com.ikunshare.music.platform.qq.QQMusicItem
import com.ikunshare.music.utils.HTTPUtils

/**
 * 调用自建 IKUN 解析服务获取 URL / ekey。
 *
 * 接口约定（见原版 kunsound/tool/MusicUrlHelper.kt）：
 * - POST https://c.wwwweb.top/app/getUrl
 * - body: { platform, musicId, quality, authst }
 * - platform 取值：qq / kugou / kuwo / wyy（注意和内部 source 代号 qq/kg/kw/wy 的映射）
 * - musicId：kugou 小写 hash，qq 用 mid，其他用 songId
 * - 响应：code=200 → { url, ekey?, _cacheTTL? }；其他 → { message }
 */
object MusicUrlHelper {

    private const val API_URL = "https://c.wwwweb.top/app/getUrl"

    /** 内部 source id → 后端 platform 字段 */
    private val PLATFORM_MAP = mapOf(
        "qq" to "qq",
        "kg" to "kugou",
        "kw" to "kuwo",
        "wy" to "wyy",
        "am" to "apple"
    )

    internal fun backendPlatform(sourceId: String): String? = PLATFORM_MAP[sourceId]

    fun getMediaInfo(
        sourceId: String,
        item: MusicItem,
        quality: Quality,
        authManager: AuthManager
    ): MediaInfoResult {
        val platform = backendPlatform(sourceId)
            ?: return fail(sourceId, item, quality, "不支持的平台: $sourceId")

        val musicId = extractMusicId(platform, item)
            ?: return fail(sourceId, item, quality, "无法提取 musicId")

        val body = JsonObject().apply {
            addProperty("platform", platform)
            addProperty("musicId", musicId)
            addProperty("quality", quality.id)
            addProperty("authst", authManager.currentAuthst)
        }.toString()

        val headers = mapOf("Content-Type" to "application/json")
        val response = HTTPUtils.post(API_URL, headers, body)
        if (!response.isSuccessful) {
            return fail(sourceId, item, quality, "请求失败: HTTP ${response.status}")
        }

        return try {
            val raw = response.body
                ?: return fail(sourceId, item, quality, "无效响应")
            val json = JsonParser.parseString(raw).asJsonObject

            val code = json.get("code")?.takeIf { !it.isJsonNull }?.asInt ?: 0
            if (code != 200) {
                val message = json.get("message")?.takeIf { !it.isJsonNull }?.asString
                    ?: "未知错误"
                return fail(sourceId, item, quality, message)
            }

            val url = json.get("url")?.takeIf { !it.isJsonNull }?.asString
            if (url.isNullOrEmpty()) {
                return fail(sourceId, item, quality, "返回 URL 为空")
            }

            val ekeyRaw = json.get("ekey")?.takeIf { !it.isJsonNull }?.asString
            val ekey = ekeyRaw?.takeIf { it.isNotEmpty() && it != "null" }
            val encryptionInfo = ekey?.let { EncryptionInfo(isEncrypt = true, ekey = it) }

            val ttlSeconds = json.get("_cacheTTL")?.takeIf { !it.isJsonNull }?.asLong ?: 600L
            val apple = if (sourceId == "am") parseAppleMediaMetadata(json) else null

            MediaInfoResult(
                source = sourceId,
                playUrl = url,
                backupUrls = null,
                expire = System.currentTimeMillis() + ttlSeconds * 1000L,
                isSuccess = true,
                quality = quality,
                musicItem = item,
                encryptionInfo = encryptionInfo,
                kind = apple?.kind,
                adamId = apple?.adamId,
                keyEndpoint = apple?.keyEndpoint,
                codec = apple?.codec,
                audioGroup = apple?.audioGroup,
                bitrate = apple?.bitrate,
                sampleRate = apple?.sampleRate,
                bitDepth = apple?.bitDepth
            )
        } catch (e: Exception) {
            fail(sourceId, item, quality, "解析响应失败: ${e.message}")
        }
    }

    internal fun extractMusicId(platform: String, item: MusicItem): String? = when (platform) {
        "qq" -> (item as? QQMusicItem)?.mid
        "kugou" -> (item as? KugouMusicItem)?.hash?.lowercase()
        "kuwo", "wyy", "apple" -> item.id.toString()
        else -> null
    }

    private fun fail(sourceId: String, item: MusicItem, quality: Quality, reason: String) =
        MediaInfoResult(
            source = sourceId, playUrl = null, backupUrls = null,
            expire = null, isSuccess = false, quality = quality,
            musicItem = item, rejectReason = reason
        )
}
