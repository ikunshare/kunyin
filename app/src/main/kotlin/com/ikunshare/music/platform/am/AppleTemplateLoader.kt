package com.ikunshare.music.platform.am

import com.google.gson.JsonObject
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.utils.HTTPResponse
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

interface AppleSampleDecryptor : AutoCloseable {
    fun decrypt(sample: ByteArray): ByteArray
}

data class AppleAudioSpec(
    val url: String,
    val adamId: String,
    val keyEndpoint: String
)

class AppleTemplateLoader(
    private val factory: (JsonObject) -> AppleSampleDecryptor,
    private val endpoint: String = AmProvider.API_ORIGIN,
    private val authst: () -> String = { MusicApplication.instance?.authManager?.currentAuthst.orEmpty() },
    private val post: suspend (String, Map<String, String>, Map<String, Any?>) -> HTTPResponse =
        { url, headers, body -> HTTPUtils.postCancellable(url, headers, body) }
) {
    suspend fun create(spec: AppleAudioSpec, segments: List<AppleSegment>): Map<String, AppleSampleDecryptor> {
        val templates = linkedMapOf<String, AppleSampleDecryptor>()
        try {
            for (segment in segments) {
                if (templates.containsKey(segment.keyUri)) continue
                coroutineContext.ensureActive()
                require(segment.keyUri.isNotEmpty()) { "HLS 分片缺少密钥 URI" }
                val musicId = if (segment.keyUri == PUBLIC_SEGMENT_KEY_URI) "0" else spec.adamId
                val response = post(keyUrl(spec.keyEndpoint), mapOf("X-Api-Key" to authst()), mapOf(
                    "source" to "am",
                    "musicId" to musicId,
                    "uri" to segment.keyUri
                ))
                coroutineContext.ensureActive()
                if (!response.isSuccessful) throw IOException("Apple Music HTTP ${response.status}")
                val json = response.json(JsonObject::class.java) ?: throw IOException("Apple Music 响应无效")
                if (json.text("code") != "200") {
                    throw IOException(json.text("message").ifEmpty { "Apple Music 服务请求失败" })
                }
                val data = json.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw IOException("后端没有返回有效的 Temari 密钥模板")
                templates[segment.keyUri] = factory(data)
            }
            return templates
        } catch (error: Throwable) {
            closeAppleTemplates(templates, error)
            throw error
        }
    }

    private fun keyUrl(keyEndpoint: String): String {
        val path = keyEndpoint.trim()
        require(path.isNotEmpty()) { "Apple Music 缺少密钥接口" }
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return endpoint.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun JsonObject.text(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private companion object {
        const val PUBLIC_SEGMENT_KEY_URI = "skd://itunes.apple.com/P000000000/s1/e1"
    }
}
