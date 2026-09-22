package com.ikunshare.sound.tool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.manager.AuthManager
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.platform.base.EncryptionInfo
import com.ikunshare.sound.platform.base.MediaInfoResult
import com.ikunshare.sound.platform.joox.JooxMusicItem
import com.ikunshare.sound.platform.kg.KugouMusicItem
import com.ikunshare.sound.platform.qq.QQMusicItem
import com.ikunshare.sound.utils.HTTPUtils

/**
 * 调用自建 IKUN 解析服务获取 URL / ekey。
 *
 * 接口约定（见原版 kunsound/tool/MusicUrlHelper.kt）：
 * - POST https://c.wwwweb.top/app/getUrl
 * - body: { platform, musicId, quality, authst }
 * - platform 取值：qq / kugou / kuwo / wyy / joox（注意和内部 source 代号 qq/kg/kw/wy/joox 的映射）
 * - musicId：kugou 小写 hash，qq/joox 用 mid，其他用 songId
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
        "joox" to "joox"
    )

    fun getMediaInfo(
        sourceId: String,
        item: MusicItem,
        quality: Quality,
        authManager: AuthManager
    ): MediaInfoResult {
        val platform = PLATFORM_MAP[sourceId]
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

            MediaInfoResult(
                source = sourceId,
                playUrl = url,
                backupUrls = null,
                expire = System.currentTimeMillis() + ttlSeconds * 1000L,
                isSuccess = true,
                quality = quality,
                musicItem = item,
                encryptionInfo = encryptionInfo
            )
        } catch (e: Exception) {
            fail(sourceId, item, quality, "解析响应失败: ${e.message}")
        }
    }

    private fun extractMusicId(platform: String, item: MusicItem): String? = when (platform) {
        "qq" -> (item as? QQMusicItem)?.mid
        "kugou" -> (item as? KugouMusicItem)?.hash?.lowercase()
        "joox" -> (item as? JooxMusicItem)?.mid
        "kuwo", "wyy" -> item.id.toString()
        else -> null
    }

    private fun fail(sourceId: String, item: MusicItem, quality: Quality, reason: String) =
        MediaInfoResult(
            source = sourceId, playUrl = null, backupUrls = null,
            expire = null, isSuccess = false, quality = quality,
            musicItem = item, rejectReason = reason
        )
}
