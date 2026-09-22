package com.ikunshare.sound.ui.screens.settings

import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.common.CustomThemeEntry
import com.ikunshare.sound.tool.ThemeUploader
import com.ikunshare.sound.utils.HTTPUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 主题分享工具
 *
 * 流程:
 * 1. 导出: 构建主题 JSON → 有背景图先上传得 CDN URL → 主题 JSON 上传到 bucket → 分享码 = `mpdl:t/<base64(gzip(cdnUrl))>`
 * 2. 导入: 解码得 CDN URL → 下载 JSON → 解析主题数据 → 有背景图 URL 再下载
 *
 * JSON 结构: {"n":"主题名","c":"colorsJson","b":"背景图CDN URL","e":"背景图扩展名"}
 */
object ThemeShareHelper {

    private const val PREFIX = "mpdl:t/"

    data class ShareResult(val shareCode: String?, val error: String?)

    data class ImportData(
        val name: String,
        val colorsJson: String,
        val bgUrl: String,
        val bgExt: String
    )

    fun exportTheme(theme: CustomThemeEntry): ShareResult = try {
        val json = JsonObject().apply {
            addProperty("n", theme.name)
            addProperty("c", theme.colorsJson)
        }

        if (theme.bgImagePath.isNotBlank()) {
            val file = File(theme.bgImagePath)
            if (file.isFile && file.length() > 0) {
                val bgResult = ThemeUploader.upload(
                    file.readBytes(),
                    "bg_${System.currentTimeMillis()}.png"
                )
                if (bgResult.isSuccess) {
                    json.addProperty("b", bgResult.cdnUrl)
                    json.addProperty("e", file.extension.ifBlank { "jpg" })
                }
            }
        }

        val jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        val jsonResult = ThemeUploader.upload(
            jsonBytes,
            "theme_${System.currentTimeMillis()}.json"
        )
        if (!jsonResult.isSuccess) {
            ShareResult(null, jsonResult.error)
        } else {
            val urlBytes = jsonResult.cdnUrl.toByteArray(Charsets.UTF_8)
            val compressed = ByteArrayOutputStream().use { bos ->
                GZIPOutputStream(bos).use { it.write(urlBytes) }
                bos.toByteArray()
            }
            val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP or Base64.URL_SAFE)
            ShareResult("$PREFIX$encoded", null)
        }
    } catch (e: Exception) {
        ShareResult(null, e.message ?: "未知错误")
    }

    fun parseShareCode(code: String): ImportData? {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return try {
            val encoded = trimmed.removePrefix(PREFIX)
            val compressed = Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE)
            val urlBytes = ByteArrayInputStream(compressed).use { bis ->
                GZIPInputStream(bis).use { it.readBytes() }
            }
            val cdnUrl = String(urlBytes, Charsets.UTF_8)

            val resp = HTTPUtils.get(cdnUrl)
            if (!resp.isSuccessful || resp.body.isNullOrBlank()) null
            else {
                val json = JsonParser.parseString(resp.body).asJsonObject
                ImportData(
                    name = json.get("n")?.asString ?: "",
                    colorsJson = json.get("c")?.asString ?: "",
                    bgUrl = json.get("b")?.asString ?: "",
                    bgExt = json.get("e")?.asString ?: "jpg"
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
