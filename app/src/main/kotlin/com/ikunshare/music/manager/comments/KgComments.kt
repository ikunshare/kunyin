package com.ikunshare.music.manager.comments

import com.google.gson.JsonObject
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale

internal fun kgCommentUrl(hash: String, hot: Boolean, page: Int, limit: Int, timestamp: Long): String {
    val params = linkedMapOf("dfid" to "0", "mid" to "16249512204336365674023395779019", "clienttime" to timestamp.toString(), "uuid" to "0",
        "extdata" to hash, "appid" to "1005", "code" to "fc4be23b4e972707f36b8a828a93ba8a", "schash" to hash, "clientver" to "11409",
        "p" to page.toString(), "clienttoken" to "", "ver" to "10", "pagesize" to limit.toString(), "kugouid" to "0")
    val salt = "OIlwieks28dk2k092lksi2UIkp"
    val raw = salt + params.toSortedMap().entries.joinToString("") { "${it.key}=${it.value}" } + salt
    params["signature"] = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    return "http://m.comment.service.kugou.com/r/v1/rank/${if (hot) "topliked" else "newest"}?" +
        params.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
}

internal class KgComments(private val transport: suspend (String) -> JsonObject = { url -> withContext(Dispatchers.IO) {
    val response = HTTPUtils.get(url, mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/107.0.0.0 Safari/537.36 Edg/107.0.1418.24"))
    if (!response.isSuccessful) throw IOException("获取评论失败")
    response.json(JsonObject::class.java) ?: throw IOException("评论响应为空")
} }, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun comments(hash: String, hot: Boolean, page: Int = 1, limit: Int = 20): CommentResult {
        require(hash.isNotBlank()) { "该歌曲缺少 hash，无法获取评论" }
        val p = page.coerceAtLeast(1); val size = limit.coerceIn(1, 100)
        val json = transport(kgCommentUrl(hash, hot, p, size, clock()))
        check(json.text("err_code") == "0") { "获取评论失败" }
        return CommentResult("kg", kgCommentRows(json.get("list")), commentTotal(json.long("count")), p, size)
    }
}

private fun entities(text: String) = text.replace("&apos;", "'").replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
internal fun kgCommentRows(value: com.google.gson.JsonElement?): List<CommentItem> = commentObjects(value).mapNotNull { item ->
    val id = item.text("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val dateText = item.text("addtime")
    val position = ParsePosition(0)
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).apply { isLenient = false }.parse(dateText, position)
        ?.takeIf { position.index == dateText.length }?.time ?: 0
    var content = item.text("content")
    commentObjects(item.get("atlist")).forEach { content = content.replace("[at=${it.text("id")}]", "@${it.text("name")} ") }
    val self = CommentItem(id, entities(content), time, commentTime(time), item.text("user_name"), item.text("user_pic").ifBlank { null },
        item.text("user_id").ifBlank { null }, item.text("location").ifBlank { null }, item.obj("like")?.long("likenum") ?: 0,
        commentObjects(item.get("images")).mapNotNull { it.text("url").takeIf { url -> url.isNotBlank() } })
    if (item.text("pcontent").isEmpty()) self else CommentItem(id, entities(item.text("pcontent")), userName = item.text("puser"),
        userId = item.text("puser_id").ifBlank { null }, likedCount = null, reply = listOf(self))
}
