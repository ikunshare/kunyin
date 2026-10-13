package com.ikunshare.music.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ikunshare.music.utils.NetworkProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val hasUpdate: Boolean,
    val title: String,
    val log: String,
    val downloadUrl: String,
    val shareUrl: String,
    val isCompulsory: Boolean,
    /** 新版本 APK 的 MD5，下载完成后校验；为空则跳过校验 */
    val fileMd5: String
)

/**
 * 基于自建后端的更新检查。
 *
 * POST [CHECK_UPDATE_URL]，请求体 `{ "md5": "<已安装 APK 的 MD5>" }`。
 * 后端将其与后台配置的最新包 MD5 比对：一致返回 404（已是最新），否则返回 200：
 * { "code": "200", "data": { "version", "update_title", "update_log", "down_url",
 *   "share_url", "compulsory": "yes"|"no", "file_md5" } }
 */
class UpdateChecker(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences("update_checker", Context.MODE_PRIVATE)

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 已安装 APK 的 MD5，进程内只算一次 */
    private val installedMd5: String? by lazy {
        runCatching { File(appContext.applicationInfo.sourceDir).md5() }.getOrNull()
    }

    companion object {
        private const val KEY_SKIPPED_VERSION = "skipped_version"
        const val CHECK_UPDATE_URL = "https://c.wwwweb.top/app/checkUpdate"
    }

    /**
     * 检查更新。
     *
     * @return 有新版本时返回 UpdateInfo；已是最新或请求失败时返回 null
     */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        val md5 = installedMd5 ?: return@withContext null
        val body = JSONObject().put("md5", md5).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(CHECK_UPDATE_URL).post(body).build()

        val data = runCatching {
            NetworkProxy.call(checkClient, request).execute().use { response ->
                // 404 表示已是最新
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body.string())
                if (json.optString("code") != "200") return@use null
                json.optJSONObject("data")
            }
        }.getOrNull() ?: return@withContext null

        val downloadUrl = data.optString("down_url")
        val shareUrl = data.optString("share_url")
        if (downloadUrl.isBlank() && shareUrl.isBlank()) return@withContext null

        UpdateInfo(
            hasUpdate = true,
            title = data.optString("update_title").ifBlank { data.optString("version") },
            log = data.optString("update_log"),
            downloadUrl = downloadUrl,
            shareUrl = shareUrl.ifBlank { downloadUrl },
            isCompulsory = data.optString("compulsory") == "yes",
            fileMd5 = data.optString("file_md5")
        )
    }

    fun isVersionSkipped(version: String): Boolean {
        return prefs.getString(KEY_SKIPPED_VERSION, null) == version
    }

    fun skipVersion(version: String) {
        prefs.edit { putString(KEY_SKIPPED_VERSION, version) }
    }

    /**
     * 下载 APK 文件，并校验 MD5。
     *
     * @param onProgress 进度回调 (0f ~ 1f)
     * @return 下载成功的文件，或 null（下载失败或校验不通过）
     */
    fun downloadApk(
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): File? {
        if (info.downloadUrl.isBlank()) return null
        val outputFile = File(cacheDir, "update.apk")
        onProgress(0f)
        return try {
            val digest = MessageDigest.getInstance("MD5")
            val request = Request.Builder().url(info.downloadUrl).build()
            NetworkProxy.call(downloadClient, request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body
                val contentLength = body.contentLength()
                val inputStream = body.byteStream()

                FileOutputStream(outputFile).use { fos ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalRead = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalRead += bytesRead
                        if (contentLength > 0) {
                            onProgress((totalRead.toFloat() / contentLength).coerceIn(0f, 1f))
                        }
                    }
                    fos.flush()
                }
            }

            val fileMd5 = digest.digest().toHex()
            if (info.fileMd5.isBlank() || fileMd5.equals(info.fileMd5, ignoreCase = true)) {
                outputFile
            } else {
                outputFile.delete()
                null
            }
        } catch (_: Exception) {
            outputFile.delete()
            null
        }
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
