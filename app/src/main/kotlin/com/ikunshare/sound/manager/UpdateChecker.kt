package com.ikunshare.sound.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
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
    val sha256: String,
    /** 按优先级排列的 APK 下载地址（首个为检查更新时最快的线路），失败时依次回退。 */
    val downloadUrls: List<String> = listOf(downloadUrl)
)

/**
 * 基于 GitHub Releases 的更新检查。
 *
 * CI 在每个 Release 中附带 `update.json`（见 .github/workflows/android.yml）：
 * { "versionName", "versionCode", "apk", "sha256", "notes", "minVersionCode"? }
 *
 * 通过 `releases/latest/download/update.json` 获取，不走 api.github.com，避免未认证 API
 * 每 IP 每小时 60 次的限流（大陆运营商 NAT 下很容易触发）。
 *
 * 大陆网络优化：GitHub 直连与若干 GitHub 加速镜像并发请求，取最先成功的线路，
 * 并将该线路作为 APK 下载的首选，失败时依次回退其余线路。镜像为第三方服务，
 * 下载的 APK 会校验 update.json 中的 SHA-256，且系统安装时会校验签名与已安装版本一致。
 */
class UpdateChecker(context: Context) {

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

    companion object {
        private const val KEY_SKIPPED_VERSION = "skipped_version"

        const val GITHUB_REPO = "ikunshare/kunyin"
        private const val RELEASE_DOWNLOAD_BASE =
            "https://github.com/$GITHUB_REPO/releases/latest/download/"

        /** 线路前缀：空串为 GitHub 直连，其余为「前缀 + 原始 GitHub 地址」形式的加速镜像。 */
        private val MIRROR_PREFIXES = listOf(
            "",
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://ghproxy.net/",
        )
    }

    /**
     * 检查更新。
     *
     * @return 有新版本时返回 UpdateInfo；已是最新或所有线路均失败时返回 null
     */
    suspend fun check(versionCode: Int): UpdateInfo? = withContext(Dispatchers.IO) {
        val manifestUrl = RELEASE_DOWNLOAD_BASE + "update.json"
        val (prefix, json) = raceFirst(MIRROR_PREFIXES) { prefix ->
            fetchManifest(prefix + manifestUrl)
        } ?: return@withContext null

        val latestCode = json.optInt("versionCode", 0)
        if (latestCode <= versionCode) return@withContext null

        val apk = json.optString("apk")
        if (apk.isBlank()) return@withContext null
        val apkUrl = RELEASE_DOWNLOAD_BASE + apk
        val urls = (listOf(prefix) + (MIRROR_PREFIXES - prefix)).map { it + apkUrl }

        UpdateInfo(
            hasUpdate = true,
            title = json.optString("versionName"),
            log = json.optString("notes"),
            downloadUrl = urls.first(),
            shareUrl = urls.first(),
            isCompulsory = versionCode < json.optInt("minVersionCode", 0),
            sha256 = json.optString("sha256"),
            downloadUrls = urls
        )
    }

    fun isVersionSkipped(version: String): Boolean {
        return prefs.getString(KEY_SKIPPED_VERSION, null) == version
    }

    fun skipVersion(version: String) {
        prefs.edit { putString(KEY_SKIPPED_VERSION, version) }
    }

    /**
     * 下载 APK 文件，按 [UpdateInfo.downloadUrls] 顺序尝试，直到某条线路下载完成且 SHA-256 校验通过。
     *
     * @param onProgress 进度回调 (0f ~ 1f)，切换线路时从 0 重新开始
     * @return 下载成功的文件，或 null（所有线路失败）
     */
    fun downloadApk(
        info: UpdateInfo,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): File? {
        val outputFile = File(cacheDir, "update.apk")
        for (url in info.downloadUrls) {
            onProgress(0f)
            if (downloadTo(url, outputFile, info.sha256, onProgress)) return outputFile
            outputFile.delete()
        }
        return null
    }

    private fun downloadTo(
        url: String,
        outputFile: File,
        sha256: String,
        onProgress: (Float) -> Unit
    ): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val request = Request.Builder().url(url).build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

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

            val fileSha256 = digest.digest().joinToString("") { "%02x".format(it) }
            sha256.isBlank() || fileSha256.equals(sha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    private fun fetchManifest(url: String): JSONObject? {
        val request = Request.Builder().url(url).build()
        checkClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val json = JSONObject(response.body.string())
            // 镜像出错时可能返回 200 的 HTML/JSON 错误页，以必需字段判定有效性
            return json.takeIf { it.has("versionCode") && it.has("apk") }
        }
    }

    /** 并发执行 [block]，返回最先得到非空结果的候选项及其结果，其余请求随即取消。 */
    private suspend fun <T : Any> raceFirst(
        candidates: List<String>,
        block: (String) -> T?
    ): Pair<String, T>? = coroutineScope {
        val results = Channel<Pair<String, T>?>(candidates.size)
        val jobs = candidates.map { candidate ->
            launch(Dispatchers.IO) {
                val result = runCatching { runInterruptible { block(candidate) } }.getOrNull()
                results.send(result?.let { candidate to it })
            }
        }
        var winner: Pair<String, T>? = null
        repeat(candidates.size) {
            if (winner == null) winner = results.receive()
        }
        jobs.forEach { it.cancel() }
        winner
    }
}
