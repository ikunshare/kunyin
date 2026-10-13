package com.ikunshare.music.manager.comments

import com.google.gson.JsonObject
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLDecoder

internal fun kwCommentUrl(songId: Long, hot: Boolean, page: Int, limit: Int) =
    "http://ncomment.kuwo.cn/com.s?f=web&type=${if (hot) "get_rec_comment" else "get_comment"}&aapiver=1&prod=kwplayer_ar_10.5.2.0" +
        "&digest=15&sid=$songId&start=${limit.toLong() * (page - 1)}&msgflag=1&count=$limit&newver=3&uid=0"

internal class KwComments(private val transport: suspend (String) -> JsonObject = { url -> withContext(Dispatchers.IO) {
    val response = HTTPUtils.get(url, mapOf("User-Agent" to "Dalvik/2.1.0 (Linux; U; Android 9;)"))
    if (!response.isSuccessful) throw IOException("获取评论失败")
    response.json(JsonObject::class.java) ?: throw IOException("评论响应为空")
} }) {
    suspend fun comments(songId: Long, hot: Boolean, page: Int = 1, limit: Int = 20): CommentResult {
        val p = page.coerceAtLeast(1); val size = limit.coerceIn(1, 100)
        val json = transport(kwCommentUrl(songId, hot, p, size))
        check(json.text("code") == "200") { "获取评论失败" }
        return CommentResult("kw", kwCommentRows(json.get(if (hot) "hot_comments" else "comments")),
            commentTotal(json.long(if (hot) "hot_comments_counts" else "comments_counts")), p, size)
    }
}

internal fun kwCommentRows(value: com.google.gson.JsonElement?): List<CommentItem> = commentObjects(value).mapNotNull { item ->
    val id = item.text("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val time = commentSeconds(item.text("time"))
    val picture = item.text("mpic").takeIf { it.isNotEmpty() }?.let { raw ->
        runCatching { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
    }
    CommentItem(id, item.text("msg"), time, commentTime(time), item.text("u_name"), item.text("u_pic").ifBlank { null },
        item.text("u_id").ifBlank { null }, likedCount = item.long("like_num"), images = listOfNotNull(picture),
        reply = kwCommentRows(item.get("child_comments")))
}
