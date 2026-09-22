package com.ikunshare.sound.manager

import android.content.Context
import android.content.SharedPreferences
import com.ikunshare.sound.utils.HTTPUtils
import org.json.JSONObject
import java.security.MessageDigest

data class NoticeInfo(
    val content: String,
    val uri: String,
    val buttonText: String
)

/**
 * 公告 + 状态管理器
 *
 * 拉取 c.wwwweb.top/app/getAppStatus，从 data.notice 子对象中解析公告。
 * 用 MD5 去重，仅当公告内容变化时返回新公告供 UI 弹出。
 */
class NoticeManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("notice", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_MD5 = "last_notice_md5"

        /**
         * 应用状态 / 公告 API。GET 请求。
         * 响应：{ "code": "200", "data": { "notice": { "content", "uri", "button_text" } } }
         */
        const val NOTICE_API_URL = "https://c.wwwweb.top/app/getAppStatus"
    }

    /**
     * 从远程拉取公告。若公告内容（MD5）与上次一致返回 null。
     */
    fun fetchIfUpdated(apiUrl: String, versionName: String, versionCode: Int): NoticeInfo? {
        val info = fetch(apiUrl, versionName, versionCode) ?: return null
        val md5 = md5(info.content)
        val lastMd5 = prefs.getString(KEY_LAST_MD5, null)
        if (md5 == lastMd5) return null
        prefs.edit().putString(KEY_LAST_MD5, md5).apply()
        return info
    }

    /**
     * 直接获取公告（不做去重），用于手动查看。
     */
    fun fetch(
        apiUrl: String, versionName: String,
        versionCode: Int
    ): NoticeInfo? {
        if (apiUrl.isBlank()) return null

        return try {
            val response = HTTPUtils.get(apiUrl)
            if (!response.isSuccessful || response.body.isNullOrEmpty()) return null

            val json = JSONObject(response.body)
            if (json.optString("code") != "200") return null

            val noticeObj = json.optJSONObject("data")?.optJSONObject("notice") ?: return null
            val content = noticeObj.optString("content").orEmpty()
            if (content.isBlank()) return null

            NoticeInfo(
                content = content,
                uri = noticeObj.optString("uri").orEmpty(),
                buttonText = noticeObj.optString("button_text").ifEmpty { "确定" }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
