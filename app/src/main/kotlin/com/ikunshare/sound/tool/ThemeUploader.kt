package com.ikunshare.sound.tool

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.utils.HTTPUtils

/**
 * 主题分享用文件上传：
 *   POST {BASE}/app/upload  body: {"filename": ...}  -> {cdn_addr, bucket_upload_url, upload_config:{headers}}
 *   PUT  <bucket_upload_url>  headers: upload_config.headers  body: <file bytes>
 */
object ThemeUploader {

    private const val BASE_URL = "https://c.wwwweb.top"

    data class UploadResult(
        val isSuccess: Boolean,
        val cdnUrl: String = "",
        val error: String = ""
    )

    fun upload(data: ByteArray, filename: String): UploadResult = try {
        val reqJson = JsonObject().apply { addProperty("filename", filename) }
        val req = HTTPUtils.post(
            url = "$BASE_URL/app/upload",
            headers = mapOf("Content-Type" to "application/json"),
            body = reqJson.toString()
        )
        if (!req.isSuccessful || req.body.isNullOrBlank()) {
            UploadResult(false, error = "获取上传地址失败: ${req.status}")
        } else {
            val obj = JsonParser.parseString(req.body).asJsonObject
            val cdn = obj.get("cdn_addr")?.asString.orEmpty()
            val bucketUrl = obj.get("bucket_upload_url")?.asString.orEmpty()
            val uploadHeaders = mutableMapOf<String, String>()
            obj.getAsJsonObject("upload_config")?.getAsJsonObject("headers")
                ?.entrySet()?.forEach { (k, v) -> uploadHeaders[k] = v.asString }

            if (cdn.isBlank() || bucketUrl.isBlank()) {
                UploadResult(false, error = "上传地址响应字段缺失")
            } else {
                val put = HTTPUtils.put(bucketUrl, uploadHeaders, data)
                if (put.isSuccessful) UploadResult(true, cdnUrl = cdn)
                else UploadResult(false, error = "上传到Bucket失败: ${put.status}")
            }
        }
    } catch (e: Exception) {
        UploadResult(false, error = e.message ?: "未知错误")
    }
}
