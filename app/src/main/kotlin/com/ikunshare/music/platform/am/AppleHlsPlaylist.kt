package com.ikunshare.music.platform.am

import java.net.URI

data class AppleSegment(
    val uri: String,
    val start: Long,
    val end: Long,
    val keyUri: String
)

data class ApplePlaylist(
    val fileUrl: String,
    val segments: List<AppleSegment>
)

private val KEY_URI = Regex("URI=\"([^\"]+)\"")
private val KEY_FORMAT = Regex("KEYFORMAT=\"com\\.apple\\.streamingkeydelivery\"")

fun parseApplePlaylist(playlistUrl: String, text: String): ApplePlaylist {
    val segments = mutableListOf<AppleSegment>()
    var keyUri = ""
    var pendingLength: Long? = null
    var pendingOffset: Long? = null
    var pending = false
    var nextOffset = 0L

    text.lines().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith("#EXT-X-KEY:") -> {
                val uri = KEY_URI.find(line)?.groupValues?.get(1)
                if (KEY_FORMAT.containsMatchIn(line) && uri != null) {
                    keyUri = uri
                }
            }
            line.startsWith("#EXT-X-BYTERANGE:") -> {
                val value = line.removePrefix("#EXT-X-BYTERANGE:")
                val parts = value.split('@', limit = 2)
                pendingLength = parts[0].toLongOrNull()
                pendingOffset = parts.getOrNull(1)?.let { it.toLongOrNull() ?: -1L }
                pending = true
            }
            line.isNotEmpty() && !line.startsWith('#') && pending -> {
                val length = pendingLength
                val start = pendingOffset ?: nextOffset
                if (length == null || length <= 0L || start < 0L) {
                    throw IllegalArgumentException("HLS 分片字节范围无效")
                }
                val end = try {
                    Math.addExact(start, length - 1L)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("HLS 分片字节范围无效")
                }
                val uri = URI(playlistUrl).resolve(line).toString()
                segments += AppleSegment(uri, start, end, keyUri)
                nextOffset = try {
                    Math.addExact(end, 1L)
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("HLS 分片字节范围无效")
                }
                pending = false
                pendingLength = null
                pendingOffset = null
            }
        }
    }

    if (segments.isEmpty()) throw IllegalArgumentException("HLS 没有可用的字节分片")
    val fileUrl = segments.first().uri
    if (segments.any { it.uri != fileUrl }) {
        throw IllegalArgumentException("暂不支持多个独立音频文件组成的 HLS")
    }
    return ApplePlaylist(fileUrl, segments)
}
