package com.ikunshare.sound.manager

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.service.DownloadService
import com.ikunshare.sound.tool.LyricFormatUtils
import com.ikunshare.sound.ui.screens.download.DownloadNamingStyle
import com.ikunshare.sound.ui.screens.download.DownloadStatus
import com.ikunshare.sound.ui.screens.download.DownloadTask
import com.ikunshare.sound.utils.tag.MusicTagger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class DownloadManager(
    private val repository: MusicRepository,
    private val appSettingsManager: AppSettingsManager,
    private val context: Context
) {
    companion object {
        private const val TAG = "DownloadManager"
        private const val TASKS_FILE = "download_tasks.json"
        private val MP3_QUALITY_IDS = setOf("128", "320", "128k", "320k", "mp3")

        /** 默认下载目录：外部存储根目录下的 KUNSOUND，与设置页显示的默认值保持一致 */
        fun defaultDownloadDir(): File =
            Environment.getExternalStorageDirectory().resolve("KUNSOUND")

        /** 从 MusicItem 提取平台 source 标识 */
        fun getSource(song: MusicItem): String = song.toJson().get("type").asString
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    // 缓存使用 taskKey 作为 key
    private val songItemCache = mutableMapOf<String, MusicItem>()
    private val qualityCache = mutableMapOf<String, Quality>()

    /** MV 任务专用 URL 缓存：taskKey -> 直链 */
    private val videoUrlCache = mutableMapOf<String, String>()

    private var semaphore = Semaphore(appSettingsManager.settings.value.maxConcurrentDownloads)
    private val activeJobs = mutableMapOf<String, Job>()

    // 防抖保存
    private var saveJob: Job? = null

    init {
        loadTasks()
    }

    fun addTask(
        song: MusicItem,
        qualityId: String,
        deferSchedule: Boolean = false,
        subDir: String = "",
        trackNumber: Int = 0
    ) {
        val source = getSource(song)
        val taskKey = "${source}_${song.id}_${qualityId}"

        val existing = _tasks.value.firstOrNull { it.taskKey == taskKey }
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.WAITING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED -> return

                DownloadStatus.COMPLETED -> {
                    val settings = appSettingsManager.settings.value
                    if (!settings.downloadOverwriteExisting && isFileAlreadyDownloaded(
                            existing,
                            settings
                        )
                    ) {
                        return
                    }
                    _tasks.value = _tasks.value.filter { it.taskKey != taskKey }
                    qualityCache.remove(taskKey)
                }

                DownloadStatus.FAILED -> {
                    _tasks.value = _tasks.value.filter { it.taskKey != taskKey }
                    qualityCache.remove(taskKey)
                }
            }
        }

        songItemCache[taskKey] = song
        val quality = song.qualities[qualityId]
        if (quality != null) {
            qualityCache[taskKey] = quality
        }

        val task = DownloadTask(
            songId = song.id,
            source = source,
            title = song.title,
            artist = song.artist,
            album = song.album,
            cover = song.cover,
            qualityId = qualityId,
            qualityName = quality?.name ?: qualityId,
            status = DownloadStatus.WAITING,
            totalBytes = quality?.filesize ?: 0,
            subDir = subDir,
            trackNumber = trackNumber
        )
        _tasks.value += task
        saveTasks()
        if (!deferSchedule) scheduleNext()
    }

    fun addTaskWithPreferredQuality(
        song: MusicItem,
        preferredQuality: String,
        hideAi: Boolean,
        deferSchedule: Boolean = false,
        subDir: String = "",
        trackNumber: Int = 0
    ) {
        val allPriority = if (hideAi) {
            listOf("hires", "flac", "320", "320k", "128", "128k")
        } else {
            listOf("master", "atmos_plus", "atmos", "hires", "flac", "320", "320k", "128", "128k")
        }
        val startIndex = allPriority.indexOf(preferredQuality).coerceAtLeast(0)
        val priority = allPriority.subList(startIndex, allPriority.size)
        val qualityId = priority.firstOrNull { song.qualities.containsKey(it) } ?: return
        addTask(song, qualityId, deferSchedule, subDir, trackNumber)
    }

    fun flushSchedule() {
        scheduleNext()
    }

    /**
     * 添加 MV 下载任务。直接给定直链与质量信息，不再走 provider 解析。
     * qualityId 形如 "mv_<quality>"，下载流程会按 video 分支处理。
     */
    fun addVideoTask(
        song: MusicItem,
        sourceTag: String,
        rawQuality: String,
        qualityName: String,
        url: String,
        totalBytes: Long = 0
    ) {
        val qualityId = "mv_$rawQuality"
        val taskKey = "${sourceTag}_${song.id}_${qualityId}"

        val existing = _tasks.value.firstOrNull { it.taskKey == taskKey }
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.WAITING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED -> return

                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED -> {
                    _tasks.value = _tasks.value.filter { it.taskKey != taskKey }
                    videoUrlCache.remove(taskKey)
                }
            }
        }

        songItemCache[taskKey] = song
        videoUrlCache[taskKey] = url

        val task = DownloadTask(
            songId = song.id,
            source = sourceTag,
            title = song.title,
            artist = song.artist,
            album = song.album,
            cover = song.cover,
            qualityId = qualityId,
            qualityName = qualityName,
            status = DownloadStatus.WAITING,
            totalBytes = totalBytes
        )
        _tasks.value += task
        saveTasks()
        scheduleNext()
    }

    fun retryTask(taskKey: String) {
        updateTask(taskKey) {
            if (it.status == DownloadStatus.FAILED) it.copy(
                status = DownloadStatus.WAITING,
                progress = 0f,
                downloadedBytes = 0,
                errorMessage = ""
            ) else it
        }
        saveTasks()
        scheduleNext()
    }

    fun cancelTask(taskKey: String) {
        activeJobs[taskKey]?.cancel()
        activeJobs.remove(taskKey)

        // 删除临时文件
        val task = _tasks.value.find { it.taskKey == taskKey }
        if (task != null) {
            deleteTempFile(task)
        }

        _tasks.value = _tasks.value.filter { it.taskKey != taskKey }
        songItemCache.remove(taskKey)
        qualityCache.remove(taskKey)
        saveTasks()
    }

    fun removeTask(taskKey: String, deleteFile: Boolean = false) {
        // 如果正在下载，先取消
        activeJobs[taskKey]?.cancel()
        activeJobs.remove(taskKey)

        val task = _tasks.value.find { it.taskKey == taskKey }
        if (task != null && deleteFile) {
            // 删除最终文件
            if (task.filePath.isNotEmpty()) {
                val file = File(task.filePath)
                if (file.exists()) {
                    file.delete()
                } else {
                    // File API 删除失败或文件不存在于该路径，尝试 DocumentFile
                    tryDeleteViaDocumentFile(task)
                }
            }
            // 也尝试删除临时文件
            deleteTempFile(task)
        }

        _tasks.value = _tasks.value.filter { it.taskKey != taskKey }
        songItemCache.remove(taskKey)
        qualityCache.remove(taskKey)
        saveTasks()
    }

    private fun deleteTempFile(task: DownloadTask) {
        try {
            val settings = appSettingsManager.settings.value
            val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
            val baseName =
                buildFileName(task, DownloadNamingStyle.fromKey(settings.downloadNamingStyle))
            val fileName = "$baseName$extension.tmp"

            // 尝试两个可能的位置：缓存工作目录和目标下载目录
            val cacheTemp = File(File(context.cacheDir, "dl_work"), fileName)
            if (cacheTemp.exists()) cacheTemp.delete()

            val downloadDir = getDownloadDir(settings.downloadPath)
            val dirTemp = File(downloadDir, fileName)
            if (dirTemp.exists()) dirTemp.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete temp file: ${e.message}")
        }
    }

    private fun scheduleNext() {
        val waiting = _tasks.value.filter { it.status == DownloadStatus.WAITING }
        val downloading = _tasks.value.filter { it.status == DownloadStatus.DOWNLOADING }

        // 启动/停止前台服务
        if (waiting.isNotEmpty() || downloading.isNotEmpty()) {
            try {
                DownloadService.start(context)
            } catch (_: Exception) {
            }
        } else if (activeJobs.isEmpty()) {
            try {
                DownloadService.stop(context)
            } catch (_: Exception) {
            }
        }

        for (task in waiting) {
            val key = task.taskKey
            if (activeJobs.containsKey(key)) continue
            val job = scope.launch {
                semaphore.acquire()
                try {
                    executeDownload(key)
                } finally {
                    semaphore.release()
                    activeJobs.remove(key)
                    scheduleNext()
                }
            }
            activeJobs[key] = job
        }
    }

    private suspend fun executeDownload(taskKey: String) {
        val task = _tasks.value.find { it.taskKey == taskKey } ?: return
        val song = songItemCache[taskKey]
        if (song == null) {
            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "歌曲信息丢失，请删除后重新添加"
                )
            }
            saveTasks()
            return
        }

        updateTask(taskKey) { it.copy(status = DownloadStatus.DOWNLOADING, progress = 0f) }
        saveTasks()

        // MV 视频任务：跳过解析/加密代理/标签写入
        val isVideo = task.qualityId.startsWith("mv_")
        if (isVideo) {
            executeVideoDownload(task)
            return
        }

        try {
            // 获取 quality 对象
            val quality = qualityCache[taskKey]
                ?: song.qualities[task.qualityId]
                ?: run {
                    updateTask(taskKey) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            errorMessage = "音质信息不可用"
                        )
                    }
                    saveTasks()
                    return
                }

            // 获取 MediaInfo（使用正确的 provider，含凭据管理和 Resolver 降级）
            val mediaInfo = withContext(Dispatchers.IO) {
                repository.getMediaInfoForSource(task.source, song, quality)
            }
            if (!mediaInfo.isSuccess || mediaInfo.playUrl.isNullOrBlank()) {
                val reason = mediaInfo.rejectReason ?: "获取播放链接失败"
                updateTask(taskKey) {
                    it.copy(
                        status = DownloadStatus.FAILED,
                        errorMessage = reason
                    )
                }
                saveTasks()
                return
            }

            // 加密内容直接下载原始密文，下载完成后用 MflacCrypto 在本地解密
            val encryptedEkey = mediaInfo.encryptionInfo
                ?.takeIf { it.isEncrypt && !it.ekey.isNullOrBlank() }
                ?.ekey
            val downloadUrl = mediaInfo.playUrl

            // 确定下载目录和文件名
            val settings = appSettingsManager.settings.value
            val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
            val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
            var baseName = buildFileName(task, namingStyle)
            var fileName = "$baseName$extension"

            val treeUri = settings.downloadTreeUri
            val useDocFile = treeUri.isNotBlank()

            // 工作目录：使用 DocumentFile 时在缓存目录操作，否则直接在目标目录
            val workDir = if (useDocFile) {
                File(context.cacheDir, "dl_work").also { it.mkdirs() }
            } else {
                val baseDir = getDownloadDir(settings.downloadPath)
                val targetDir =
                    if (task.subDir.isNotBlank()) File(baseDir, task.subDir) else baseDir
                targetDir.also { it.mkdirs() }
            }

            var finalFile = File(workDir, fileName)
            val tempFile = File(workDir, "$fileName.tmp")

            // 清理上次中断遗留的工作文件（暂停/取消后可能残留）
            val isResumed = resumedTaskKeys.remove(taskKey)
            tempFile.delete()
            if (useDocFile) {
                // SAF 模式下 workDir 是缓存目录，安全删除
                finalFile.delete()
            } else if (isResumed) {
                // 非 SAF 模式下，恢复任务时清理上次中断遗留的 finalFile
                finalFile.delete()
            }

            // 同名文件处理：自动追加序号避免冲突（恢复任务跳过）
            if (!isResumed && !settings.downloadOverwriteExisting) {
                if (useDocFile) {
                    try {
                        val treeDoc = DocumentFile.fromTreeUri(context, treeUri.toUri())
                        val checkParent = if (task.subDir.isNotBlank()) {
                            treeDoc?.findFile(task.subDir) ?: treeDoc
                        } else treeDoc
                        if (checkParent != null) {
                            var suffix = 1
                            while (checkParent.findFile(fileName) != null) {
                                suffix++
                                fileName = "$baseName ($suffix)$extension"
                            }
                            finalFile = File(workDir, fileName)
                        }
                    } catch (_: Exception) { /* DocumentFile 检查失败，继续 */
                    }
                } else {
                    var suffix = 1
                    while (finalFile.exists()) {
                        suffix++
                        fileName = "$baseName ($suffix)$extension"
                        finalFile = File(workDir, fileName)
                    }
                }
            }

            // 流式下载（HTTP 错误时刷新播放链接重试一次）
            withContext(Dispatchers.IO) {
                var currentUrl = downloadUrl
                var currentEkey: String? = encryptedEkey
                var retried = false

                fun doDownload(url: String): okhttp3.Response {
                    val request = Request.Builder().url(url).build()
                    return httpClient.newCall(request).execute()
                }

                var response = doDownload(currentUrl)
                if (!response.isSuccessful && !retried) {
                    response.close()
                    retried = true
                    Log.w(TAG, "HTTP ${response.code}, refreshing play URL for $taskKey")
                    val refreshedInfo = repository.getMediaInfoForSource(task.source, song, quality)
                    if (refreshedInfo.isSuccess && !refreshedInfo.playUrl.isNullOrBlank()) {
                        currentUrl = refreshedInfo.playUrl
                        currentEkey = refreshedInfo.encryptionInfo
                            ?.takeIf { it.isEncrypt && !it.ekey.isNullOrBlank() }
                            ?.ekey
                        response = doDownload(currentUrl)
                    }
                }

                response.use { resp ->
                    if (!resp.isSuccessful) {
                        throw RuntimeException("HTTP ${resp.code}")
                    }

                    val body = resp.body
                    val contentLength =
                        body.contentLength().let { if (it > 0) it else task.totalBytes }

                    if (contentLength > 0) {
                        updateTask(taskKey) { it.copy(totalBytes = contentLength) }
                    }

                    FileOutputStream(tempFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastUpdateTime = System.currentTimeMillis()
                        var lastDownloaded = 0L

                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                val elapsed = now - lastUpdateTime
                                if (elapsed >= 500) {
                                    val speed = ((downloaded - lastDownloaded) * 1000) / elapsed
                                    val progress =
                                        if (contentLength > 0) downloaded.toFloat() / contentLength else 0f
                                    updateTask(taskKey) {
                                        it.copy(
                                            progress = progress.coerceIn(0f, 1f),
                                            speedBytesPerSec = speed,
                                            downloadedBytes = downloaded
                                        )
                                    }
                                    updateServiceNotification()
                                    lastUpdateTime = now
                                    lastDownloaded = downloaded
                                }
                            }
                        }

                        updateTask(taskKey) {
                            it.copy(
                                progress = 1f,
                                downloadedBytes = downloaded,
                                speedBytesPerSec = 0
                            )
                        }
                    }
                }

                // 先重命名为最终文件（确保扩展名正确，AudioFileIO 依赖扩展名识别格式）
                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    Log.w(TAG, "Rename failed, copying instead")
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                // 加密内容下载完成后，使用 MflacCrypto 在本地原地解密
                val ekey = currentEkey
                if (!ekey.isNullOrBlank()) {
                    try {
                        com.ikunshare.sound.utils.crypto.MflacCrypto.decryptFile(
                            finalFile.absolutePath, ekey
                        )
                        Log.d(TAG, "Decrypted ${finalFile.name}")

                        // mflac/mgg/mogg 解密后真实容器可能是 Ogg 而扩展名仍是 .flac，
                        // 若不修正，audiotagger 会按 FLAC 写入 ID3/FLAC 头，破坏 Ogg 文件。
                        val actualExt = sniffAudioExtension(finalFile)
                        if (actualExt != null &&
                            !finalFile.name.endsWith(actualExt, ignoreCase = true)
                        ) {
                            val renamed = File(finalFile.parentFile, "$baseName$actualExt")
                            if (renamed.exists()) renamed.delete()
                            if (finalFile.renameTo(renamed)) {
                                Log.d(
                                    TAG,
                                    "Container detected as $actualExt, renamed ${finalFile.name} -> ${renamed.name}"
                                )
                                finalFile = renamed
                                fileName = renamed.name
                            } else {
                                Log.w(TAG, "Failed to rename ${finalFile.name} to ${renamed.name}")
                            }
                        }
                    } catch (t: Throwable) {
                        Log.e(TAG, "Decrypt failed for ${finalFile.name}", t)
                        throw RuntimeException("解密失败: ${t.message}")
                    }
                }

                // 采样率/位深标记：从下载完成的文件头读取，追加到文件名（仅 FLAC 有效）
                if (settings.downloadSampleRateTag) {
                    val tag = com.ikunshare.sound.utils.tag.AudioSampleInfo.qualityTag(finalFile)
                    if (tag != null && !baseName.endsWith(tag)) {
                        val curExt = "." + finalFile.name.substringAfterLast(
                            '.',
                            extension.removePrefix(".")
                        )
                        val newBase = "$baseName $tag"
                        val renamed = File(finalFile.parentFile, "$newBase$curExt")
                        if (renamed.exists()) renamed.delete()
                        if (finalFile.renameTo(renamed)) {
                            Log.d(TAG, "Sample-rate tagged: ${finalFile.name} -> ${renamed.name}")
                            finalFile = renamed
                            baseName = newBase
                            fileName = renamed.name
                        } else {
                            Log.w(TAG, "Failed to rename for sample-rate tag: ${finalFile.name}")
                        }
                    }
                }

                // 获取歌词（如果需要写入 meta 或保存 .lrc）
                val needLyricMeta = settings.downloadWriteLyricMeta
                val needLrcFile = settings.downloadLrcFile
                var lyricsContent: String? = null

                // 本地歌单里若设置了歌词/封面重定向，下载时一并应用
                val redirectTarget = runCatching {
                    com.ikunshare.sound.SoundApplication.instance
                        ?.localMusicStore?.getRedirect(song)
                }.getOrNull()
                val lyricSource = redirectTarget ?: song
                val coverUrl = redirectTarget?.cover?.takeIf { it.isNotBlank() } ?: task.cover

                if (needLyricMeta || needLrcFile) {
                    try {
                        // 直接委托给对应 provider 取歌词，避免依赖 currentProvider（用户可能已切换平台）
                        val provider = repository.getProvider(lyricSource.getTypeDiscriminator())
                        val lyric = provider?.getLyric(lyricSource)
                            ?: com.ikunshare.sound.model.Lyric()
                        if (!lyric.isEmpty()) {
                            lyricsContent = LyricFormatUtils.formatLyricsForExport(lyric, settings)
                            Log.d(
                                TAG,
                                "Lyrics fetched for $taskKey: ${lyricsContent.length} chars"
                            )
                        } else {
                            Log.d(TAG, "Lyrics empty for $taskKey")
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.w(TAG, "Lyric fetch failed (non-fatal) for $taskKey: ${t.message}", t)
                    }
                }

                // 写入标签到最终文件（扩展名正确，AudioFileIO 可正常识别）
                // task.cover 是远程 URL，audiotagger 把 picture 字段当本地文件路径解析，
                // 必须先下载成字节并通过 pictureData/pictureMimeType 写入。
                // coverBytes 提升到外层以便整专封面文件复用，避免重复下载。
                val coverBytes = fetchCoverBytes(coverUrl)
                try {
                    Log.d(
                        TAG,
                        "Writing tags to ${finalFile.name}: cover=${coverBytes != null} (${coverBytes?.size ?: 0}B), lyrics=${needLyricMeta && !lyricsContent.isNullOrBlank()}"
                    )
                    MusicTagger.edit(
                        finalFile.absolutePath
                    ) {
                        title = task.title
                        artist = task.artist
                        album = task.album
                        if (task.trackNumber > 0) {
                            trackNumber = task.trackNumber
                        }
                        if (coverBytes != null) {
                            pictureData = coverBytes
                            pictureMimeType = sniffImageMime(coverBytes)
                        }
                        lyrics = if (needLyricMeta) lyricsContent else null
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.w(TAG, "Tag writing failed (non-fatal) for $taskKey: ${t.message}", t)
                }

                // 保存 .lrc 文件（到工作目录）
                if (needLrcFile && !lyricsContent.isNullOrBlank()) {
                    try {
                        val lrcFile = File(workDir, "$baseName.lrc")
                        lrcFile.writeText(lyricsContent)
                        Log.d(TAG, "LRC file saved: ${lrcFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.w(TAG, "LRC file save failed (non-fatal): ${e.message}")
                    }
                }

                // 整专下载时单独保存一份专辑封面（命名 cover.jpg/cover.png），每个专辑文件夹仅一次
                var coverFileName: String? = null
                var coverWorkFile: File? = null
                if (settings.downloadAlbumCover && task.subDir.isNotBlank() && coverBytes != null) {
                    val coverExt = if (sniffImageMime(coverBytes) == "image/png") ".png" else ".jpg"
                    val name = "cover$coverExt"
                    // 内存级 first-write-wins，叠加磁盘存在性检查，规避并发任务重复写入
                    val claimed = albumCoverWrittenDirs.add(task.subDir)
                    if (claimed && !albumCoverExists(settings, task.subDir, name)) {
                        try {
                            val f = File(workDir, name)
                            f.writeBytes(coverBytes)
                            coverFileName = name
                            coverWorkFile = f
                            Log.d(TAG, "Album cover saved: ${f.absolutePath}")
                        } catch (e: Exception) {
                            albumCoverWrittenDirs.remove(task.subDir)
                            Log.w(TAG, "Album cover save failed (non-fatal): ${e.message}")
                        }
                    }
                }

                // 如果使用 DocumentFile，将文件从工作目录复制到最终目标
                if (useDocFile) {
                    copyToDestination(treeUri, fileName, finalFile, settings, task.subDir)
                    finalFile.delete()
                    val lrcFile = File(workDir, "$baseName.lrc")
                    if (lrcFile.exists()) {
                        copyToDestination(treeUri, "$baseName.lrc", lrcFile, settings, task.subDir)
                        lrcFile.delete()
                    }
                    if (coverFileName != null && coverWorkFile != null) {
                        copyToDestination(
                            treeUri,
                            coverFileName,
                            coverWorkFile,
                            settings,
                            task.subDir
                        )
                        coverWorkFile.delete()
                    }
                }
            }

            // 最终文件路径
            val actualFilePath = if (useDocFile) {
                val basePath = treeUriToDisplayPath(treeUri)
                if (task.subDir.isNotBlank()) "$basePath/${task.subDir}/$fileName"
                else "$basePath/$fileName"
            } else {
                finalFile.absolutePath
            }

            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    filePath = actualFilePath,
                    progress = 1f
                )
            }
            saveTasks()
            updateServiceNotification()
        } catch (_: CancellationException) {
            // 暂停或取消导致的协程取消，不标记为失败
            Log.d(TAG, "Download cancelled for $taskKey (pause/cancel)")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $taskKey", e)
            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                )
            }
            saveTasks()
            updateServiceNotification()
        }
    }

    /**
     * 视频（MV）下载分支：直接使用 videoUrlCache 中的直链，不做加密代理、不写标签。
     */
    private suspend fun executeVideoDownload(task: DownloadTask) {
        val taskKey = task.taskKey
        try {
            val downloadUrl = videoUrlCache[taskKey]
                ?: throw RuntimeException("视频地址丢失，请重新发起下载")

            val settings = appSettingsManager.settings.value
            val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
            val baseName = "${buildFileName(task, namingStyle)}_${task.qualityName}"
            val fileName = "$baseName.mp4"

            val treeUri = settings.downloadTreeUri
            val useDocFile = treeUri.isNotBlank()
            val workDir = if (useDocFile) {
                File(context.cacheDir, "dl_work").also { it.mkdirs() }
            } else {
                getDownloadDir(settings.downloadPath).also { it.mkdirs() }
            }
            val finalFile = File(workDir, fileName)
            val tempFile = File(workDir, "$fileName.tmp")
            tempFile.delete()
            if (useDocFile) finalFile.delete()

            withContext(Dispatchers.IO) {
                val request = Request.Builder().url(downloadUrl).build()
                httpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
                    val body = resp.body
                    val contentLength =
                        body.contentLength().let { if (it > 0) it else task.totalBytes }
                    if (contentLength > 0) {
                        updateTask(taskKey) { it.copy(totalBytes = contentLength) }
                    }
                    FileOutputStream(tempFile).use { fos ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        var lastUpdateTime = System.currentTimeMillis()
                        var lastDownloaded = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                fos.write(buffer, 0, read)
                                downloaded += read

                                val now = System.currentTimeMillis()
                                val elapsed = now - lastUpdateTime
                                if (elapsed >= 500) {
                                    val speed = ((downloaded - lastDownloaded) * 1000) / elapsed
                                    val progress =
                                        if (contentLength > 0) downloaded.toFloat() / contentLength else 0f
                                    updateTask(taskKey) {
                                        it.copy(
                                            progress = progress.coerceIn(0f, 1f),
                                            speedBytesPerSec = speed,
                                            downloadedBytes = downloaded
                                        )
                                    }
                                    updateServiceNotification()
                                    lastUpdateTime = now
                                    lastDownloaded = downloaded
                                }
                            }
                        }
                        updateTask(taskKey) {
                            it.copy(
                                progress = 1f,
                                downloadedBytes = downloaded,
                                speedBytesPerSec = 0
                            )
                        }
                    }
                }

                if (finalFile.exists()) finalFile.delete()
                if (!tempFile.renameTo(finalFile)) {
                    tempFile.copyTo(finalFile, overwrite = true)
                    tempFile.delete()
                }

                if (useDocFile) {
                    copyToDestination(treeUri, fileName, finalFile, settings)
                    finalFile.delete()
                }
            }

            val actualFilePath = if (useDocFile) {
                "${treeUriToDisplayPath(treeUri)}/$fileName"
            } else finalFile.absolutePath

            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    filePath = actualFilePath,
                    progress = 1f
                )
            }
            saveTasks()
            updateServiceNotification()
        } catch (_: CancellationException) {
            Log.d(TAG, "Video download cancelled for $taskKey")
        } catch (e: Exception) {
            Log.e(TAG, "Video download failed for $taskKey", e)
            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                )
            }
            saveTasks()
            updateServiceNotification()
        }
    }

    fun pauseTask(taskKey: String) {
        activeJobs[taskKey]?.cancel()
        activeJobs.remove(taskKey)
        updateTask(taskKey) {
            if (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING) {
                it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)
            } else it
        }
        saveTasks()
        updateServiceNotification()
    }

    // 标记从 PAUSED 恢复的任务，跳过重复文件检查
    private val resumedTaskKeys = mutableSetOf<String>()

    // 整专封面已写入的子目录集合，避免并发任务重复下载/写入封面（first-write-wins）
    private val albumCoverWrittenDirs =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun resumeTask(taskKey: String) {
        resumedTaskKeys.add(taskKey)
        updateTask(taskKey) {
            if (it.status == DownloadStatus.PAUSED) {
                it.copy(status = DownloadStatus.WAITING, progress = 0f, downloadedBytes = 0)
            } else it
        }
        saveTasks()
        scheduleNext()
    }

    fun pauseAll() {
        val toPause =
            _tasks.value.filter { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING }
        toPause.forEach { task ->
            activeJobs[task.taskKey]?.cancel()
            activeJobs.remove(task.taskKey)
        }
        _tasks.value = _tasks.value.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.WAITING) {
                task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)
            } else task
        }
        saveTasks()
        updateServiceNotification()
    }

    fun resumeAll() {
        _tasks.value = _tasks.value.map { task ->
            if (task.status == DownloadStatus.PAUSED) {
                resumedTaskKeys.add(task.taskKey)
                task.copy(status = DownloadStatus.WAITING, progress = 0f, downloadedBytes = 0)
            } else task
        }
        saveTasks()
        scheduleNext()
    }

    fun retryAllFailed() {
        _tasks.value = _tasks.value.map { task ->
            if (task.status == DownloadStatus.FAILED) {
                task.copy(
                    status = DownloadStatus.WAITING,
                    progress = 0f,
                    downloadedBytes = 0,
                    errorMessage = ""
                )
            } else task
        }
        saveTasks()
        scheduleNext()
    }

    fun clearCompleted() {
        val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED }
        completed.forEach { task ->
            songItemCache.remove(task.taskKey)
            qualityCache.remove(task.taskKey)
        }
        _tasks.value = _tasks.value.filter { it.status != DownloadStatus.COMPLETED }
        saveTasks()
    }

    private fun updateTask(taskKey: String, transform: (DownloadTask) -> DownloadTask) {
        val list = _tasks.value
        val idx = list.indexOfFirst { it.taskKey == taskKey }
        if (idx < 0) return
        val updated = transform(list[idx])
        if (updated === list[idx]) return
        val next = list.toMutableList()
        next[idx] = updated
        _tasks.value = next
    }

    private fun updateServiceNotification() {
        val tasks = _tasks.value
        val downloadingCount = tasks.count { it.status == DownloadStatus.DOWNLOADING }
        val waitingCount = tasks.count { it.status == DownloadStatus.WAITING }
        val downloadingTasks = tasks.filter { it.status == DownloadStatus.DOWNLOADING }
        val avgProgress = if (downloadingTasks.isNotEmpty()) {
            downloadingTasks.map { it.progress }.average().toFloat()
        } else 0f
        DownloadService.getInstance()
            ?.updateNotification(downloadingCount, waitingCount, avgProgress)
    }

    private fun getDownloadDir(settingsPath: String): File {
        return if (settingsPath.isNotBlank()) {
            File(settingsPath)
        } else {
            defaultDownloadDir()
        }
    }

    private fun buildFileName(task: DownloadTask, style: DownloadNamingStyle): String {
        val sanitize = { s: String -> s.replace(Regex("[\\\\/:*?\"<>|]"), "_") }
        val base = when (style) {
            DownloadNamingStyle.ARTIST_TITLE -> "${sanitize(task.artist)} - ${sanitize(task.title)}"
            DownloadNamingStyle.TITLE_ARTIST -> "${sanitize(task.title)} - ${sanitize(task.artist)}"
            DownloadNamingStyle.TITLE_ONLY -> sanitize(task.title)
        }
        val settings = appSettingsManager.settings.value
        return if (settings.downloadTrackNumber && task.trackNumber > 0) {
            "${task.trackNumber.toString().padStart(2, '0')}.$base"
        } else {
            base
        }
    }

    private fun isFileAlreadyDownloaded(task: DownloadTask, settings: AppSettings): Boolean {
        if (task.filePath.isNotEmpty()) {
            val file = File(task.filePath)
            if (file.exists() && file.length() > 0) return true
        }
        val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
        val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
        val baseName = buildFileName(task, namingStyle)
        val fileName = "$baseName$extension"
        val treeUri = settings.downloadTreeUri
        if (treeUri.isNotBlank()) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri.toUri())
                val parent =
                    if (task.subDir.isNotBlank()) treeDoc?.findFile(task.subDir) else treeDoc
                if (parent?.findFile(fileName) != null) return true
            } catch (_: Exception) {
            }
        } else {
            val baseDir = getDownloadDir(settings.downloadPath)
            val targetDir = if (task.subDir.isNotBlank()) File(baseDir, task.subDir) else baseDir
            if (File(targetDir, fileName).exists()) return true
        }
        return false
    }

    /**
     * 检查目标专辑文件夹内是否已存在指定封面文件（避免整专重复写入）。
     */
    private fun albumCoverExists(
        settings: AppSettings,
        subDir: String,
        coverName: String
    ): Boolean {
        val treeUri = settings.downloadTreeUri
        if (treeUri.isNotBlank()) {
            return try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri.toUri())
                val parent = if (subDir.isNotBlank()) treeDoc?.findFile(subDir) else treeDoc
                parent?.findFile(coverName) != null
            } catch (_: Exception) {
                false
            }
        }
        val baseDir = getDownloadDir(settings.downloadPath)
        val targetDir = if (subDir.isNotBlank()) File(baseDir, subDir) else baseDir
        return File(targetDir, coverName).exists()
    }

    /** 下载封面 URL 为字节。失败返回 null，调用方决定如何降级。 */
    private fun fetchCoverBytes(coverUrl: String): ByteArray? {
        if (coverUrl.isBlank()) return null
        return try {
            val request = Request.Builder().url(coverUrl).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Cover fetch HTTP ${resp.code} for $coverUrl")
                    return null
                }
                val bytes = resp.body.bytes()
                if (bytes.isEmpty()) null else bytes
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cover fetch failed for $coverUrl: ${e.message}")
            null
        }
    }

    /** 通过 magic bytes 嗅探图片 MIME（仅区分 JPEG/PNG，与 audiotagger 的 ImageParser 保持一致）。 */
    private fun sniffImageMime(data: ByteArray): String = when {
        data.size > 8 &&
                (data[0].toInt() and 0xFF) == 0x89 &&
                (data[1].toInt() and 0xFF) == 0x50 -> "image/png"

        else -> "image/jpeg"
    }

    /**
     * 嗅探音频文件真实容器，返回对应扩展名（含点）；无法识别返回 null。
     * QQ 的 .mflac/.mgg/.mogg 解密后实际可能是 Ogg Vorbis；按扩展名分发的 tagger
     * 会把 Ogg 当 FLAC 写入 ID3/FLAC 头，导致文件损坏。
     */
    private fun sniffAudioExtension(file: File): String? {
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(12)
                val read = input.read(header)
                if (read < 4) return null
                when {
                    // "OggS"
                    header[0] == 0x4F.toByte() && header[1] == 0x67.toByte() &&
                            header[2] == 0x67.toByte() && header[3] == 0x53.toByte() -> ".ogg"
                    // "fLaC"
                    header[0] == 0x66.toByte() && header[1] == 0x4C.toByte() &&
                            header[2] == 0x61.toByte() && header[3] == 0x43.toByte() -> ".flac"
                    // "ID3" or MPEG sync 0xFFEx
                    (header[0] == 0x49.toByte() && header[1] == 0x44.toByte() &&
                            header[2] == 0x33.toByte()) ||
                            (header[0] == 0xFF.toByte() &&
                                    (header[1].toInt() and 0xE0) == 0xE0) -> ".mp3"

                    else -> null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio sniff failed for ${file.name}: ${e.message}")
            null
        }
    }


    // ===== DocumentFile 辅助方法 =====

    /**
     * 将文件复制到最终目标目录。
     * 优先使用 DocumentFile（SAF），失败则降级到 File API。
     */
    private fun copyToDestination(
        treeUriString: String,
        fileName: String,
        sourceFile: File,
        settings: AppSettings,
        subDir: String = ""
    ) {
        // 优先尝试 DocumentFile
        try {
            val treeUri = treeUriString.toUri()
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (treeDoc != null && treeDoc.canWrite()) {
                val targetParent = if (subDir.isNotBlank()) {
                    treeDoc.findFile(subDir)
                        ?: treeDoc.createDirectory(subDir)
                        ?: treeDoc
                } else treeDoc
                // 删除已有同名文件
                targetParent.findFile(fileName)?.delete()

                val mimeType = when {
                    fileName.endsWith(".mp3") -> "audio/mpeg"
                    fileName.endsWith(".flac") -> "audio/flac"
                    fileName.endsWith(".mp4") -> "video/mp4"
                    else -> "application/octet-stream"
                }

                val targetDoc = targetParent.createFile(mimeType, fileName)
                if (targetDoc != null) {
                    context.contentResolver.openOutputStream(targetDoc.uri)?.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "File written via DocumentFile: $fileName")
                    return
                }
            }
        } catch (e: Exception) {
            Log.w(
                TAG,
                "DocumentFile write failed for $fileName, falling back to File API: ${e.message}"
            )
        }

        // 降级到 File API
        val downloadDir = getDownloadDir(settings.downloadPath)
        downloadDir.mkdirs()
        val targetFile = File(downloadDir, fileName)
        if (targetFile.exists()) targetFile.delete()
        sourceFile.copyTo(targetFile, overwrite = true)
        Log.d(TAG, "File written via File API: ${targetFile.absolutePath}")
    }

    /**
     * 尝试通过 DocumentFile 删除文件
     */
    private fun tryDeleteViaDocumentFile(task: DownloadTask) {
        try {
            val treeUri = appSettingsManager.settings.value.downloadTreeUri
            if (treeUri.isBlank()) return
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return
            val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
            val settings = appSettingsManager.settings.value
            val baseName =
                buildFileName(task, DownloadNamingStyle.fromKey(settings.downloadNamingStyle))
            treeDoc.findFile("$baseName$extension")?.delete()
        } catch (e: Exception) {
            Log.w(TAG, "DocumentFile delete failed: ${e.message}")
        }
    }

    /**
     * 从 tree URI 获取可显示的路径
     */
    private fun treeUriToDisplayPath(treeUriString: String): String {
        return try {
            val uri = treeUriString.toUri()
            val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            if (docId.startsWith("primary:")) {
                val relative = docId.removePrefix("primary:")
                Environment.getExternalStorageDirectory().resolve(relative).absolutePath
            } else {
                val colonIndex = docId.indexOf(':')
                if (colonIndex > 0) {
                    val volumeId = docId.substring(0, colonIndex)
                    val relative = docId.substring(colonIndex + 1)
                    "/storage/$volumeId" + if (relative.isNotEmpty()) "/$relative" else ""
                } else {
                    docId
                }
            }
        } catch (_: Exception) {
            treeUriString
        }
    }

    // ===== 持久化 =====

    /**
     * 用于 JSON 序列化的精简任务数据（不含运行时瞬时字段）
     */
    private data class PersistedTask(
        @SerializedName("songId") val songId: Long,
        @SerializedName("source") val source: String,
        @SerializedName("title") val title: String,
        @SerializedName("artist") val artist: String,
        @SerializedName("album") val album: String,
        @SerializedName("cover") val cover: String,
        @SerializedName("qualityId") val qualityId: String,
        @SerializedName("qualityName") val qualityName: String,
        @SerializedName("status") val status: String,
        @SerializedName("totalBytes") val totalBytes: Long,
        @SerializedName("filePath") val filePath: String,
        @SerializedName("errorMessage") val errorMessage: String,
        @SerializedName("songJson") val songJson: String = "",
        @SerializedName("subDir") val subDir: String = "",
        @SerializedName("trackNumber") val trackNumber: Int = 0
    )

    private fun loadTasks() {
        try {
            val file = File(context.filesDir, TASKS_FILE)
            if (!file.exists()) return

            val json = file.readText()
            if (json.isBlank()) return

            val type = object : TypeToken<List<PersistedTask>>() {}.type
            val persisted: List<PersistedTask> = gson.fromJson(json, type) ?: return

            val restored = persisted.mapNotNull { p ->
                val status = try {
                    DownloadStatus.valueOf(p.status)
                } catch (_: Exception) {
                    return@mapNotNull null
                }

                // DOWNLOADING 恢复为 WAITING（中断需重新开始）
                val restoredStatus =
                    if (status == DownloadStatus.DOWNLOADING) DownloadStatus.WAITING else status

                val task = DownloadTask(
                    songId = p.songId,
                    source = p.source,
                    title = p.title,
                    artist = p.artist,
                    album = p.album,
                    cover = p.cover,
                    qualityId = p.qualityId,
                    qualityName = p.qualityName,
                    status = restoredStatus,
                    totalBytes = p.totalBytes,
                    filePath = p.filePath,
                    errorMessage = p.errorMessage,
                    subDir = p.subDir,
                    trackNumber = p.trackNumber
                )

                // 从持久化的 songJson 恢复 MusicItem 缓存
                if (p.songJson.isNotBlank()) {
                    try {
                        val song = MusicItem.fromJsonString(p.songJson)
                        songItemCache[task.taskKey] = song
                        // 恢复 quality 缓存
                        song.qualities[p.qualityId]?.let { quality ->
                            qualityCache[task.taskKey] = quality
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to restore song for ${task.taskKey}: ${e.message}")
                    }
                }

                task
            }

            _tasks.value = restored
            Log.i(
                TAG,
                "Loaded ${restored.size} tasks from disk, ${songItemCache.size} songs restored"
            )

            // 恢复后自动继续等待中的任务
            scheduleNext()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tasks", e)
        }
    }

    private fun saveTasks() {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(500) // 防抖 500ms
            try {
                val persisted = _tasks.value.map { t ->
                    // 序列化 MusicItem 以便重启后恢复
                    val songJson = songItemCache[t.taskKey]?.let {
                        try {
                            it.toJson().toString()
                        } catch (_: Exception) {
                            ""
                        }
                    } ?: ""

                    PersistedTask(
                        songId = t.songId,
                        source = t.source,
                        title = t.title,
                        artist = t.artist,
                        album = t.album,
                        cover = t.cover,
                        qualityId = t.qualityId,
                        qualityName = t.qualityName,
                        status = t.status.name,
                        totalBytes = t.totalBytes,
                        filePath = t.filePath,
                        errorMessage = t.errorMessage,
                        songJson = songJson,
                        subDir = t.subDir,
                        trackNumber = t.trackNumber
                    )
                }
                val json = gson.toJson(persisted)
                File(context.filesDir, TASKS_FILE).writeText(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save tasks", e)
            }
        }
    }
}
