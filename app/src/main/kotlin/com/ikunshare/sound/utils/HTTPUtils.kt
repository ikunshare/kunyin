package com.ikunshare.sound.utils

import android.util.Log
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HTTPResponse(
    val status: Int,
    val body: String?,
    val bodyBytes: ByteArray?,
    val headers: Map<String, String>
) {
    private val gson = Gson()
    val isSuccessful: Boolean get() = status in 200..299

    /**
     * 将 body 解析为指定的类对象
     */
    fun <T> json(clazz: Class<T>): T? {
        return try {
            gson.fromJson(body, clazz)
        } catch (e: Exception) {
            null
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as HTTPResponse

        if (status != other.status) return false
        if (body != other.body) return false
        if (!bodyBytes.contentEquals(other.bodyBytes)) return false
        if (headers != other.headers) return false
        if (gson != other.gson) return false
        if (isSuccessful != other.isSuccessful) return false

        return true
    }

    override fun hashCode(): Int {
        var result = status
        result = 31 * result + (body?.hashCode() ?: 0)
        result = 31 * result + (bodyBytes?.contentHashCode() ?: 0)
        result = 31 * result + headers.hashCode()
        result = 31 * result + gson.hashCode()
        result = 31 * result + isSuccessful.hashCode()
        return result
    }
}

class HTTPUtils {
    companion object {
        private const val TAG = "HTTPUtils"
        private val gson = Gson()
        private val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** 调试日志开关，由 AppSettingsManager 控制 */
        @Volatile
        @JvmStatic
        var httpLogEnabled: Boolean = false

        @JvmStatic
        fun get(url: String, headers: Map<String, String>? = null): HTTPResponse {
            val requestBuilder = Request.Builder().url(url)
            headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            return execute(requestBuilder.build())
        }

        @JvmStatic
        fun post(
            url: String,
            headers: Map<String, String>? = null,
            body: Any? = null
        ): HTTPResponse {
            val requestBuilder = Request.Builder().url(url)
            headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }

            val requestBody = when (body) {
                is String -> body.toRequestBody(null)
                is ByteArray -> body.toRequestBody(null)
                null -> "".toRequestBody(null)
                else -> gson.toJson(body).toRequestBody(JSON_MEDIA_TYPE)
            }

            return execute(requestBuilder.post(requestBody).build())
        }

        @JvmStatic
        fun put(url: String, headers: Map<String, String>? = null, body: ByteArray): HTTPResponse {
            val contentType = headers?.get("Content-Type")?.toMediaType()
            val requestBuilder = Request.Builder().url(url)
            headers?.forEach { (k, v) -> if (k != "Content-Type") requestBuilder.addHeader(k, v) }
            return execute(requestBuilder.put(body.toRequestBody(contentType)).build())
        }

        @JvmStatic
        fun getRedirect(url: String, headers: Map<String, String>? = null): String? {
            val noRedirectClient = client.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val requestBuilder = Request.Builder().url(url)
            headers?.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            return try {
                noRedirectClient.newCall(requestBuilder.build()).execute().use { response ->
                    if (response.code in 301..308) response.header("Location") else null
                }
            } catch (_: IOException) {
                null
            }
        }

        private fun execute(request: Request): HTTPResponse {
            if (httpLogEnabled) {
                Log.d(TAG, "→ ${request.method} ${request.url}")
                request.headers.forEach { (k, v) -> Log.d(TAG, "  H: $k: $v") }
            }
            return try {
                client.newCall(request).execute().use { response ->
                    val bytes = response.body.bytes()
                    val bodyString = String(bytes, Charsets.UTF_8)

                    val responseHeaders = mutableMapOf<String, String>()
                    response.headers.forEach { pair ->
                        val key = pair.first.lowercase()
                        val existing = responseHeaders[key]
                        responseHeaders[key] =
                            if (existing != null) "$existing; ${pair.second}" else pair.second
                    }

                    if (httpLogEnabled) {
                        Log.d(TAG, "← ${response.code} ${request.url}")
                        Log.d(TAG, "  Body(${bodyString.length}): ${bodyString.take(500)}")
                    }

                    HTTPResponse(response.code, bodyString, bytes, responseHeaders)
                }
            } catch (e: IOException) {
                if (httpLogEnabled) Log.w(TAG, "✗ ${request.method} ${request.url}: ${e.message}")
                HTTPResponse(0, e.message, null, emptyMap())
            }
        }
    }
}
