package com.ikunshare.music.manager.comments

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommentItem(
    val id: String, val text: String, val time: Long = 0, val timeStr: String = "",
    val userName: String = "", val avatar: String? = null, val userId: String? = null,
    val location: String? = null, val likedCount: Long? = 0,
    val images: List<String> = emptyList(), val reply: List<CommentItem> = emptyList()
)
data class CommentResult(val source: String, val comments: List<CommentItem>, val total: Int,
                         val page: Int, val limit: Int, val maxPage: Int = commentMaxPage(total, limit))

internal fun commentMaxPage(total: Int, limit: Int) = ((total.coerceAtLeast(0).toLong() + limit - 1) / limit.coerceAtLeast(1)).coerceAtLeast(1).toInt()
internal fun commentTime(ms: Long): String = if (ms == 0L || ms !in -8_640_000_000_000_000L..8_640_000_000_000_000L) ""
    else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
internal fun commentSeconds(value: String): Long = if (value.length < 10 || !value.all { it in '0'..'9' }) 0
    else runCatching { Math.multiplyExact(value.toLong(), 1000L) }.getOrDefault(0)
internal fun JsonObject.text(key: String) = get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
internal fun JsonObject.long(key: String) = text(key).toLongOrNull() ?: 0
internal fun JsonObject.obj(key: String) = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
internal fun commentObjects(value: JsonElement?) = value?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull { it.takeIf { row -> row.isJsonObject }?.asJsonObject }.orEmpty()
internal fun commentTotal(value: Long) = value.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
