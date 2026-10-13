package com.ikunshare.music.manager.comments

import com.google.gson.JsonObject
import com.ikunshare.music.platform.wy.utils.NeteaseCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class WyComments(
    private val transport: suspend (String, Map<String, Any>) -> JsonObject = { path, payload ->
        withContext(Dispatchers.IO) {
            val response = NeteaseCrypto.weapiPost(path, payload)
            if (!response.isSuccessful) throw IOException("获取评论失败")
            response.json(JsonObject::class.java) ?: throw IOException("评论响应为空")
        }
    },
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Cursor(val page: Int, val cursor: Long, val previous: Long, val order: Int, val offset: Long)
    private class Session { val mutex = Mutex(); var cursor: Cursor? = null }
    private val sessions = ConcurrentHashMap<Pair<Long, Int>, Session>()

    suspend fun comments(songId: Long, hot: Boolean, page: Int = 1, limit: Int = 20): CommentResult {
        val p = page.coerceAtLeast(1); val size = limit.coerceIn(1, 100)
        val thread = "R_SO_4_$songId"
        if (hot) {
            val json = transport("/v1/resource/hotcomments/$thread", mapOf("rid" to thread, "limit" to size,
                "offset" to size.toLong() * (p - 1), "beforeTime" to clock().toString()))
            check(json.text("code") == "200") { "获取热门评论失败" }
            return CommentResult("wy", wyCommentRows(json.get("hotComments")), commentTotal(json.long("total")), p, size)
        }
        val session = sessions.getOrPut(songId to size) { Session() }
        return session.mutex.withLock {
            val old = session.cursor
            val cursor: Long; val order: Int; val offset: Long
            when {
                p == 1 || old == null -> { cursor = clock(); order = 1; offset = 0 }
                p > old.page -> { cursor = old.cursor; order = 1; offset = (p.toLong() - old.page - 1) * size }
                p < old.page -> { cursor = old.cursor; order = 0; offset = (old.page.toLong() - p - 1) * size }
                else -> { cursor = old.previous; order = old.order; offset = old.offset }
            }
            val json = transport("/comment/resource/comments/get", mapOf("cursor" to cursor, "offset" to offset,
                "orderType" to order, "pageNo" to p, "pageSize" to size, "rid" to thread, "threadId" to thread))
            check(json.text("code") == "200") { "获取评论失败" }
            val data = json.obj("data") ?: throw IOException("评论数据为空")
            val rows = wyCommentRows(data.get("comments"))
            session.cursor = Cursor(p, data.text("cursor").toLongOrNull() ?: cursor,
                if (p == 1) cursor else old?.cursor ?: cursor, order, offset)
            CommentResult("wy", rows, commentTotal(data.long("totalCount")), p, size)
        }
    }
}

internal fun wyCommentRows(value: com.google.gson.JsonElement?): List<CommentItem> = commentObjects(value).mapNotNull { item ->
    val id = item.text("commentId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
    val time = item.long("time")
    fun map(obj: JsonObject, root: Boolean): CommentItem {
        val user = obj.obj("user")
        return CommentItem(id, wyCommentEmoji(obj.text("content")), time, if (root) "" else commentTime(time),
            user?.text("nickname").orEmpty(), user?.text("avatarUrl")?.ifBlank { null }, user?.text("userId")?.ifBlank { null },
            obj.obj("ipLocation")?.text("location")?.ifBlank { null }, if (root) null else item.long("likedCount"))
    }
    val self = map(item, false)
    commentObjects(item.get("beReplied")).firstOrNull()?.let { map(it, true).copy(reply = listOf(self)) } ?: self
}
