package com.ikunshare.music.manager.comments

import com.google.gson.JsonObject
import com.ikunshare.music.utils.HTTPUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URLEncoder

internal data class QqCommentRequest(val songId: Long, val hot: Boolean, val page: Int, val limit: Int)
internal fun qqLatestCommentForm(q: QqCommentRequest): String = linkedMapOf("uin" to "0", "format" to "json", "cid" to "205360772",
    "reqtype" to "2", "biztype" to "1", "topid" to q.songId.toString(), "cmd" to "8", "needmusiccrit" to "1",
    "pagenum" to (q.page - 1).toString(), "pagesize" to q.limit.toString()).entries.joinToString("&") {
        "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }

internal fun qqHotCommentPayload(q: QqCommentRequest): Map<String, Any> = mapOf(
    "comm" to mapOf("cv" to 4747474, "ct" to 24, "format" to "json", "inCharset" to "utf-8", "outCharset" to "utf-8",
        "notice" to 0, "platform" to "yqq.json", "needNewCode" to 1, "uin" to 0),
    "req" to mapOf("module" to "music.globalComment.CommentRead", "method" to "GetHotCommentList",
        "param" to mapOf("BizType" to 1, "BizId" to q.songId.toString(), "LastCommentSeqNo" to "", "PageSize" to q.limit,
            "PageNum" to q.page - 1, "HotType" to 1, "WithAirborne" to 0, "PicEnable" to 1)))

internal class QqComments(private val transport: suspend (QqCommentRequest) -> JsonObject = { q -> withContext(Dispatchers.IO) {
    val response = if (q.hot) HTTPUtils.post("https://u.y.qq.com/cgi-bin/musicu.fcg",
        mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "referer" to "https://y.qq.com/", "origin" to "https://y.qq.com"), qqHotCommentPayload(q))
    else HTTPUtils.post("http://c.y.qq.com/base/fcgi-bin/fcg_global_comment_h5.fcg",
        mapOf("User-Agent" to "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)",
            "Content-Type" to "application/x-www-form-urlencoded"), qqLatestCommentForm(q))
    if (!response.isSuccessful) throw IOException("获取评论失败")
    response.json(JsonObject::class.java) ?: throw IOException("评论响应为空")
} }) {
    suspend fun comments(songId: Long, hot: Boolean, page: Int = 1, limit: Int = 20): CommentResult {
        val q = QqCommentRequest(songId, hot, page.coerceAtLeast(1), limit.coerceIn(1, 100))
        val json = transport(q)
        check(json.text("code") == "0") { "获取评论失败" }
        if (hot) {
            val req = json.obj("req") ?: error("热门评论响应为空")
            check(req.text("code") == "0") { "获取热门评论失败" }
            val list = req.obj("data")?.obj("CommentList")
            return CommentResult("qq", qqHotCommentRows(list?.get("Comments")), commentTotal(list?.long("Total") ?: 0), q.page, q.limit)
        }
        val list = json.obj("comment")
        return CommentResult("qq", qqLatestCommentRows(list?.get("commentlist")), commentTotal(list?.long("commenttotal") ?: 0), q.page, q.limit)
    }
}

internal fun qqLatestCommentRows(value: com.google.gson.JsonElement?): List<CommentItem> = commentObjects(value).mapNotNull { item ->
    val id = item.text("commentid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val rootId = item.text("rootcommentid")
    val time = commentSeconds(item.text("time"))
    val middle = commentObjects(item.get("middlecommentcontent")).map { it.deepCopy() }.toMutableList()
    if (middle.isNotEmpty()) {
        middle.first().add("avatarurl", item.get("avatarurl"))
        middle.first().add("praisenum", item.get("praisenum"))
        middle.reverse()
    }
    val replies = middle.map { sub ->
        val current = sub.text("subcommentid") == id
        CommentItem("sub_${rootId}_${sub.text("subcommentid")}", qqCommentText(sub.text("subcommentcontent")),
            if (current) time else 0, if (current) commentTime(time) else "", sub.text("replynick").drop(1),
            sub.text("avatarurl").ifBlank { null }, sub.text("encrypt_replyuin").ifBlank { null }, likedCount = sub.long("praisenum"))
    }
    CommentItem("${rootId}_$id", qqCommentText(item.text("rootcommentcontent")), if (rootId == id) time else 0,
        if (rootId == id) commentTime(time) else "", item.text("rootcommentnick").drop(1),
        item.text("avatarurl").takeIf { middle.isEmpty() && it.isNotBlank() }, item.text("encrypt_rootcommentuin").ifBlank { null },
        likedCount = if (middle.isEmpty()) item.long("praisenum") else null, reply = replies)
}

internal fun qqHotCommentRows(value: com.google.gson.JsonElement?): List<CommentItem> = commentObjects(value).mapNotNull { item ->
    if (item.text("CmId").isEmpty()) return@mapNotNull null
    fun map(obj: JsonObject, sub: Boolean): CommentItem {
        val time = commentSeconds(obj.text("PubTime"))
        return CommentItem("${if (sub) "sub_" else ""}${obj.text("SeqNo")}_${obj.text("CmId")}", qqCommentText(obj.text("Content")), time, commentTime(time),
            obj.text("Nick"), obj.text("Avatar").ifBlank { null }, obj.text("EncryptUin").ifBlank { null }, obj.text("Location").ifBlank { null },
            obj.long("PraiseNum"), listOfNotNull(obj.text("Pic").takeIf { it.isNotBlank() }))
    }
    map(item, false).copy(reply = commentObjects(item.get("SubComments")).map { map(it, true) })
}
