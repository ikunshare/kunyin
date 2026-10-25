package com.ikunshare.music.manager

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.ikunshare.music.common.AppSettings
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.Quality
import com.ikunshare.music.service.DownloadService
import com.ikunshare.music.tool.LyricFormatUtils
import com.ikunshare.music.ui.screens.download.DownloadNamingStyle
import com.ikunshare.music.ui.screens.download.DownloadStatus
import com.ikunshare.music.ui.screens.download.DownloadDisc
import com.ikunshare.music.ui.screens.download.DownloadTask
import com.ikunshare.music.ui.screens.download.downloadIdentity
import com.ikunshare.music.ui.screens.download.downloadTaskKey
import com.ikunshare.music.ui.screens.download.downloadRestorePriority
import com.ikunshare.music.utils.tag.MusicTagger
import com.ikunshare.music.utils.tag.MusicMeta
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

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
        fun getSource(song: MusicItem): String = song.getTypeDiscriminator()
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
    private val songItemCache = ConcurrentHashMap<String, MusicItem>()
    private val qualityCache = ConcurrentHashMap<String, Quality>()

    private var semaphore = Semaphore(appSettingsManager.settings.value.maxConcurrentDownloads)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val scheduleLock = Any()

    private val taskFile = AtomicDownloadTaskFile(File(context.filesDir, TASKS_FILE))
    private val taskSaver = DebouncedDownloadTaskSaver(scope, taskFile, ::encodeTasks,
        { failure -> Log.e(TAG, "Failed to save tasks", failure) })

    @Synchronized
    fun addTask(
        song: MusicItem,
        qualityId: String,
        deferSchedule: Boolean = false,
        subDir: String = "",
        trackNumber: Int = 0,
        disc: DownloadDisc? = null
    ): Boolean {
        val source = getSource(song)
        val identity = downloadIdentity(song)
        val taskKey = downloadTaskKey(source, identity, qualityId)

        if (removingTaskKeys.contains(taskKey)) return false
        val existing = _tasks.value.firstOrNull { it.taskKey == taskKey }
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.WAITING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED -> return false

                DownloadStatus.COMPLETED -> {
                    val settings = appSettingsManager.settings.value
                    if (!settings.downloadOverwriteExisting && isFileAlreadyDownloaded(
                            existing,
                            settings
                        )
                    ) {
                        return false
                    }
                    _tasks.update { tasks -> tasks.filter { it.taskKey != taskKey } }
                    qualityCache.remove(taskKey)
                }

                DownloadStatus.FAILED -> {
                    _tasks.update { tasks -> tasks.filter { it.taskKey != taskKey } }
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
            songIdentity = identity,
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
            trackNumber = trackNumber,
            disc = disc
        )
        _tasks.update { it + task }
        saveTasks()
        if (!deferSchedule) scheduleNext()
        return true
    }

    fun addTaskWithPreferredQuality(
        song: MusicItem,
        preferredQuality: String,
        hideAi: Boolean,
        deferSchedule: Boolean = false,
        subDir: String = "",
        trackNumber: Int = 0,
        disc: DownloadDisc? = null
    ): Boolean {
        val allPriority = if (hideAi) {
            listOf("hires", "flac", "320", "320k", "128", "128k")
        } else {
            listOf("master", "atmos_plus", "atmos", "hires", "flac", "320", "320k", "128", "128k")
        }
        val startIndex = allPriority.indexOf(preferredQuality).coerceAtLeast(0)
        val priority = allPriority.subList(startIndex, allPriority.size)
        val qualityId = priority.firstOrNull { song.qualities.containsKey(it) } ?: return false
        return addTask(song, qualityId, deferSchedule, subDir, trackNumber, disc)
    }

    fun flushSchedule() {
        scheduleNext()
    }

    /**
     * 添加 MV 下载任务；执行和重试时按歌曲及清晰度重新解析直链。
     * qualityId 形如 "mv_<quality>"，下载流程会按 video 分支处理。
     */
    @Synchronized
    fun addVideoTask(
        song: MusicItem,
        sourceTag: String,
        rawQuality: String,
        qualityName: String,
        totalBytes: Long = 0,
        deferSchedule: Boolean = false,
        subDir: String = "",
        trackNumber: Int = 0,
        disc: DownloadDisc? = null
    ): Boolean {
        val qualityId = "mv_$rawQuality"
        val identity = downloadIdentity(song)
        val taskKey = downloadTaskKey(sourceTag, identity, qualityId)

        if (removingTaskKeys.contains(taskKey)) return false
        val existing = _tasks.value.firstOrNull { it.taskKey == taskKey }
        if (existing != null) {
            when (existing.status) {
                DownloadStatus.WAITING,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.PAUSED -> return false

                DownloadStatus.COMPLETED,
                DownloadStatus.FAILED -> {
                    _tasks.update { tasks -> tasks.filter { it.taskKey != taskKey } }
                }
            }
        }

        songItemCache[taskKey] = song

        val task = DownloadTask(
            songId = song.id,
            songIdentity = identity,
            source = sourceTag,
            title = song.title,
            artist = song.artist,
            album = song.album,
            cover = song.cover,
            qualityId = qualityId,
            qualityName = qualityName,
            status = DownloadStatus.WAITING,
            totalBytes = totalBytes,
            subDir = subDir,
            trackNumber = trackNumber,
            disc = disc
        )
        _tasks.update { it + task }
        saveTasks()
        if (!deferSchedule) scheduleNext()
        return true
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

        _tasks.update { tasks -> tasks.filter { it.taskKey != taskKey } }
        songItemCache.remove(taskKey)
        qualityCache.remove(taskKey)
        activeJobs[taskKey]?.cancel()
        saveTasks()
    }

    private val removingTaskKeys = ConcurrentHashMap.newKeySet<String>()

    suspend fun removeTask(taskKey: String, deleteFile: Boolean = false): Boolean {
        val task = synchronized(this) {
            if (!removingTaskKeys.add(taskKey)) return false
            _tasks.value.firstOrNull { it.taskKey == taskKey }
        }
        try {
            if (task == null) return true
            return withContext(Dispatchers.IO) {
                activeJobs[taskKey]?.cancel()
                activeJobs[taskKey]?.join()
                currentCoroutineContext().ensureActive()
                if (deleteFile && task.filePath.isNotBlank()) {
                    val deleted = try {
                        if (task.filePath.startsWith("content://")) deleteViaDocumentFile(task)
                        else {
                            val file = File(task.filePath)
                            if (file.exists()) file.delete()
                            else if (appSettingsManager.settings.value.downloadTreeUri.isNotBlank()) deleteViaDocumentFile(task)
                            else true
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        Log.w(TAG, "Download file deletion failed", failure)
                        false
                    }
                    if (!deleted) return@withContext false
                }
                _tasks.update { tasks -> tasks.filter { it.taskKey != taskKey } }
                songItemCache.remove(taskKey)
                qualityCache.remove(taskKey)
                saveTasks()
                true
            }
        } finally { removingTaskKeys.remove(taskKey) }
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

        synchronized(scheduleLock) {
            for (task in waiting) {
                val key = task.taskKey
                if (key in removingTaskKeys) continue
                if (activeJobs.containsKey(key)) continue
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    semaphore.withPermit { executeDownload(key) }
                }
                activeJobs[key] = job
                job.invokeOnCompletion {
                    // A cancelled run remains registered until it has actually stopped touching files/state.
                    activeJobs.remove(key, job)
                    scheduleNext()
                }
                job.start()
            }
        }
    }

    private suspend fun executeDownload(taskKey: String) {
        currentCoroutineContext().ensureActive()
        val execution = currentCoroutineContext()[Job]!!
        fun updateTask(key: String, transform: (DownloadTask) -> DownloadTask) {
            if (execution.isActive) this@DownloadManager.updateTask(key) { task ->
                if (execution.isActive && task.status in setOf(DownloadStatus.WAITING, DownloadStatus.DOWNLOADING)) transform(task) else task
            }
        }
        val task = _tasks.value.find { it.taskKey == taskKey && it.status == DownloadStatus.WAITING } ?: return
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
            executeVideoDownload(task, song)
            return
        }

        val reservations = mutableListOf<DownloadFileAllocator.Reservation>()
        var ownedTemp: File? = null
        var ownedWorkDir: File? = null
        var writtenDocument: String? = null
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
            val downloadSubDir = downloadDiscDirectory(task, settings.downloadDiscSubDir)
            val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
            val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
            var baseName = buildFileName(task, namingStyle)
            var fileName = "$baseName$extension"

            val treeUri = settings.downloadTreeUri
            val useDocFile = treeUri.isNotBlank()

            val destinationBase = getDownloadDir(settings.downloadPath)
            val destinationDir = if (downloadSubDir.isNotBlank()) File(destinationBase, downloadSubDir) else destinationBase
            // Keep processing files private until decryption, naming and tags are ready.
            val workParent = if (useDocFile) File(context.cacheDir, "dl_work") else destinationDir
            val workDir = File(workParent, ".download-${java.util.UUID.randomUUID()}").also {
                check(it.mkdirs()) { "无法创建下载工作目录" }
                ownedWorkDir = it
            }
            var finalFile = File(workDir, fileName)
            val tempFile = File.createTempFile("download-", ".tmp", workDir)
            ownedTemp = tempFile

            // 流式下载（HTTP 错误时刷新播放链接重试一次）
            withContext(Dispatchers.IO) {
                var currentUrl = downloadUrl
                var currentEkey: String? = encryptedEkey
                val cancellationWatchers = mutableListOf<Job>()

                fun doDownload(url: String): okhttp3.Response {
                    val request = Request.Builder().url(url).build()
                    val call = httpClient.newCall(request)
                    cancellationWatchers += launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    return call.execute()
                }

                try {
                    var response = doDownload(currentUrl)
                    if (!response.isSuccessful) {
                        response.close()
                        Log.w(TAG, "HTTP ${response.code}, refreshing play URL for $taskKey")
                        repository.invalidateMediaInfoForSource(task.source, song, quality)
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
                                    currentCoroutineContext().ensureActive()
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
                } finally {
                    cancellationWatchers.forEach { it.cancel() }
                }

                currentCoroutineContext().ensureActive()
                if (!tempFile.renameTo(finalFile)) throw java.io.IOException("无法准备下载工作文件")

                // 加密内容下载完成后，使用 MflacCrypto 在本地原地解密
                val ekey = currentEkey
                if (!ekey.isNullOrBlank()) {
                    try {
                        com.ikunshare.music.utils.crypto.MflacCrypto.decryptFile(
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
                            if (finalFile.renameTo(renamed)) {
                                Log.d(
                                    TAG,
                                    "Container detected as $actualExt, renamed ${finalFile.name} -> ${renamed.name}"
                                )
                                finalFile = renamed
                                fileName = renamed.name
                            } else {
                                throw java.io.IOException("无法修正解密音频扩展名")
                            }
                        }
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, "Decrypt failed for ${finalFile.name}", t)
                        throw RuntimeException("解密失败: ${t.message}")
                    }
                }

                // 采样率/位深标记：从下载完成的文件头读取，追加到文件名（仅 FLAC 有效）
                if (settings.downloadSampleRateTag) {
                    val tag = com.ikunshare.music.utils.tag.AudioSampleInfo.qualityTag(finalFile)
                    if (tag != null && !baseName.endsWith(tag)) {
                        val curExt = "." + finalFile.name.substringAfterLast(
                            '.',
                            extension.removePrefix(".")
                        )
                        val newBase = "$baseName $tag"
                        val renamed = File(finalFile.parentFile, "$newBase$curExt")
                        if (finalFile.renameTo(renamed)) {
                            Log.d(TAG, "Sample-rate tagged: ${finalFile.name} -> ${renamed.name}")
                            finalFile = renamed
                            baseName = newBase
                            fileName = renamed.name
                        } else {
                            throw java.io.IOException("无法设置采样率文件名")
                        }
                    }
                }

                val actualExtension = "." + finalFile.extension
                val reservation = reserveDownloadFile(settings, downloadSubDir, baseName, actualExtension, reservations)
                fileName = reservation.fileName
                baseName = fileName.removeSuffix(actualExtension)
                val namedWorkFile = File(workDir, fileName)
                if (finalFile != namedWorkFile) {
                    if (!finalFile.renameTo(namedWorkFile)) throw java.io.IOException("无法设置下载文件名")
                    finalFile = namedWorkFile
                }
                currentCoroutineContext().ensureActive()

                // 获取歌词（如果需要写入 meta 或保存 .lrc）
                val needLyricMeta = settings.downloadWriteLyricMeta
                val needLrcFile = settings.downloadLrcFile
                var lyricsContent: String? = null

                // 本地歌单里若设置了歌词/封面重定向，下载时一并应用
                val redirectTarget = runCatching {
                    com.ikunshare.music.MusicApplication.instance
                        ?.localMusicStore?.getRedirect(song)
                }.getOrNull()
                val coverUrl = redirectTarget?.cover?.takeIf { it.isNotBlank() } ?: task.cover

                if (needLyricMeta || needLrcFile) {
                    try {
                        // Share redirect, automatic fallback and cache with the player.
                        val lyric = repository.getLyric(song)
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
                    MusicTagger.fillMissing(finalFile.absolutePath, MusicMeta().apply {
                        title = task.title
                        artist = task.artist
                        album = task.album
                        val disc = task.disc
                        val tagTrack = disc?.trackNumber ?: task.trackNumber
                        if (tagTrack > 0) trackNumber = tagTrack
                        if (disc != null) {
                            discNumber = disc.no
                            discTotal = disc.total
                            disc.name.takeIf { it.isNotBlank() }?.let { discName = it }
                        }
                        if (coverBytes != null) {
                            pictureData = coverBytes
                            pictureMimeType = sniffImageMime(coverBytes)
                        }
                        lyrics = if (needLyricMeta) lyricsContent else null
                    })
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
                if (settings.downloadAlbumCover && downloadSubDir.isNotBlank() && coverBytes != null) {
                    val coverExt = if (sniffImageMime(coverBytes) == "image/png") ".png" else ".jpg"
                    val name = "cover$coverExt"
                    // 内存级 first-write-wins，叠加磁盘存在性检查，规避并发任务重复写入
                    val claimed = albumCoverWrittenDirs.add(downloadSubDir)
                    if (claimed && !albumCoverExists(settings, downloadSubDir, name)) {
                        try {
                            val f = File(workDir, name)
                            f.writeBytes(coverBytes)
                            coverFileName = name
                            coverWorkFile = f
                            Log.d(TAG, "Album cover saved: ${f.absolutePath}")
                        } catch (e: Exception) {
                            albumCoverWrittenDirs.remove(downloadSubDir)
                            Log.w(TAG, "Album cover save failed (non-fatal): ${e.message}")
                        }
                    }
                }

                // 如果使用 DocumentFile，将文件从工作目录复制到最终目标
                if (useDocFile) {
                    writtenDocument = copyToDestination(treeUri, fileName, finalFile, settings, downloadSubDir).toString()
                    finalFile.delete()
                    val lrcFile = File(workDir, "$baseName.lrc")
                    if (lrcFile.exists()) {
                        copyToDestination(treeUri, "$baseName.lrc", lrcFile, settings, downloadSubDir)
                        lrcFile.delete()
                    }
                    if (coverFileName != null && coverWorkFile != null) {
                        copyToDestination(
                            treeUri,
                            coverFileName,
                            coverWorkFile,
                            settings,
                            downloadSubDir
                        )
                        coverWorkFile.delete()
                    }
                } else {
                    val target = File(destinationDir, fileName)
                    commitDownloadFile(finalFile, target, settings.downloadOverwriteExisting)
                    writtenDocument = target.absolutePath
                    val lrcFile = File(workDir, "$baseName.lrc")
                    if (lrcFile.exists()) {
                        try { commitDownloadFile(lrcFile, File(destinationDir, lrcFile.name), settings.downloadOverwriteExisting) }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "LRC publish failed (non-fatal)", e)
                        }
                    }
                    if (coverFileName != null && coverWorkFile != null) {
                        try { commitDownloadFile(coverWorkFile, File(destinationDir, coverFileName), settings.downloadOverwriteExisting) }
                        catch (e: Exception) {
                            if (e is CancellationException) throw e
                            albumCoverWrittenDirs.remove(downloadSubDir)
                            Log.w(TAG, "Album cover publish failed (non-fatal)", e)
                        }
                    }
                }
            }

            val actualFilePath = writtenDocument ?: finalFile.absolutePath

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
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Download failed for $taskKey", e)
            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                )
            }
            saveTasks()
            updateServiceNotification()
        } finally {
            ownedTemp?.delete()
            ownedWorkDir?.let { dir ->
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
            reservations.forEach { it.close() }
        }
    }

    /**
     * 视频下载按持久化歌曲及清晰度取直链，HTTP 失败刷新一次；不解密、不写音频标签。
     */
    private suspend fun resolveVideoAddress(task: DownloadTask, song: MusicItem): String = withContext(Dispatchers.IO) {
        val provider = repository.getProvider(task.source) ?: error("音源不支持 MV")
        val result = provider.getMvUrl(song, task.qualityId.removePrefix("mv_"))
        result.playUrl?.takeIf { it.isNotBlank() } ?: error(result.rejectReason ?: "获取 MV 地址失败")
    }

    private suspend fun executeVideoDownload(task: DownloadTask, song: MusicItem) {
        val execution = currentCoroutineContext()[Job]!!
        fun updateTask(key: String, transform: (DownloadTask) -> DownloadTask) {
            if (execution.isActive) this@DownloadManager.updateTask(key) { current ->
                if (execution.isActive && current.status in setOf(DownloadStatus.WAITING, DownloadStatus.DOWNLOADING)) transform(current) else current
            }
        }
        val taskKey = task.taskKey
        val reservations = mutableListOf<DownloadFileAllocator.Reservation>()
        var ownedTemp: File? = null
        var ownedWorkDir: File? = null
        var writtenDocument: String? = null
        try {
            var downloadUrl = resolveVideoAddress(task, song)

            val settings = appSettingsManager.settings.value
            val downloadSubDir = downloadDiscDirectory(task, settings.downloadDiscSubDir)
            val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
            val baseName = buildFileName(task, namingStyle)
            val reservation = reserveDownloadFile(settings, downloadSubDir, baseName, ".mp4", reservations)
            val fileName = reservation.fileName

            val treeUri = settings.downloadTreeUri
            val useDocFile = treeUri.isNotBlank()
            val workDir = if (useDocFile) {
                File(context.cacheDir, "dl_work/${java.util.UUID.randomUUID()}").also {
                    it.mkdirs(); ownedWorkDir = it
                }
            } else {
                val baseDir = getDownloadDir(settings.downloadPath)
                (if (downloadSubDir.isNotBlank()) File(baseDir, downloadSubDir) else baseDir).also { it.mkdirs() }
            }
            val finalFile = File(workDir, fileName)
            val tempFile = File.createTempFile("download-", ".tmp", workDir)
            ownedTemp = tempFile

            withContext(Dispatchers.IO) {
                var attempt = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val call = httpClient.newCall(Request.Builder().url(downloadUrl).build())
                    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    var failedCode: Int? = null
                    try { call.execute().use { resp ->
                        if (!resp.isSuccessful) { failedCode = resp.code; return@use }
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
                                    currentCoroutineContext().ensureActive()
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
                    } } finally { cancellation.cancel() }
                    if (failedCode == null) break
                    if (attempt++ >= 1) error("HTTP $failedCode")
                    downloadUrl = resolveVideoAddress(task, song)
                }
                currentCoroutineContext().ensureActive()
                if (useDocFile) {
                    if (!tempFile.renameTo(finalFile)) throw java.io.IOException("无法准备 MV 工作文件")
                    writtenDocument = copyToDestination(treeUri, fileName, finalFile, settings, downloadSubDir).toString()
                    finalFile.delete()
                } else {
                    commitDownloadFile(tempFile, finalFile, settings.downloadOverwriteExisting)
                    writtenDocument = finalFile.absolutePath
                }
            }

            val actualFilePath = writtenDocument ?: finalFile.absolutePath

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
            currentCoroutineContext().ensureActive()
            Log.e(TAG, "Video download failed for $taskKey", e)
            updateTask(taskKey) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "下载失败"
                )
            }
            saveTasks()
            updateServiceNotification()
        } finally {
            ownedTemp?.delete()
            ownedWorkDir?.let { dir ->
                dir.listFiles()?.forEach { it.delete() }
                dir.delete()
            }
            reservations.forEach { it.close() }
        }
    }

    fun pauseTask(taskKey: String) {
        updateTask(taskKey) {
            if (it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.WAITING) {
                it.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)
            } else it
        }
        activeJobs[taskKey]?.cancel()
        saveTasks()
        updateServiceNotification()
    }

    // 整专封面已写入的子目录集合，避免并发任务重复下载/写入封面（first-write-wins）
    private val albumCoverWrittenDirs =
        java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val fileAllocator = DownloadFileAllocator()

    private suspend fun reserveDownloadFile(settings: AppSettings, subDir: String, base: String,
                                            extension: String, reservations: MutableList<DownloadFileAllocator.Reservation>): DownloadFileAllocator.Reservation = withContext(Dispatchers.IO) {
        val nativeBase = getDownloadDir(settings.downloadPath)
        val nativeDir = if (subDir.isNotBlank()) File(nativeBase, subDir) else nativeBase
        val treeUri = settings.downloadTreeUri
        val directoryKey = if (treeUri.isBlank()) nativeDir.canonicalPath else "$treeUri/${subDir.replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")}"
        val treeParent = if (treeUri.isNotBlank()) {
            val tree = DocumentFile.fromTreeUri(context, treeUri.toUri())
            tree?.let { downloadDocumentParent(it, subDir, create = false) }
        } else null
        fileAllocator.reserve(directoryKey, base, extension, settings.downloadOverwriteExisting) { name ->
            if (treeUri.isNotBlank()) treeParent?.findFile(name) != null else File(nativeDir, name).exists()
        }.also { reservations += it }
    }

    // Restoring waiting tasks starts coroutines; all state must exist before that happens.
    init { loadTasks() }

    fun resumeTask(taskKey: String) {
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
        _tasks.update { tasks -> tasks.map { task ->
            if (task.status == DownloadStatus.DOWNLOADING || task.status == DownloadStatus.WAITING) {
                task.copy(status = DownloadStatus.PAUSED, speedBytesPerSec = 0)
            } else task
        } }
        toPause.forEach { task -> activeJobs[task.taskKey]?.cancel() }
        saveTasks()
        updateServiceNotification()
    }

    fun resumeAll() {
        _tasks.update { tasks -> tasks.map { task ->
            if (task.status == DownloadStatus.PAUSED) {
                task.copy(status = DownloadStatus.WAITING, progress = 0f, downloadedBytes = 0)
            } else task
        } }
        saveTasks()
        scheduleNext()
    }

    fun retryAllFailed() {
        _tasks.update { tasks -> tasks.map { task ->
            if (task.status == DownloadStatus.FAILED) {
                task.copy(
                    status = DownloadStatus.WAITING,
                    progress = 0f,
                    downloadedBytes = 0,
                    errorMessage = ""
                )
            } else task
        } }
        saveTasks()
        scheduleNext()
    }

    @Synchronized
    fun clearCompleted() {
        val completed = _tasks.value.filter { it.status == DownloadStatus.COMPLETED && it.taskKey !in removingTaskKeys }
        completed.forEach { task ->
            songItemCache.remove(task.taskKey)
            qualityCache.remove(task.taskKey)
        }
        _tasks.update { tasks -> tasks.filter { it.status != DownloadStatus.COMPLETED || it.taskKey in removingTaskKeys } }
        saveTasks()
    }

    private fun updateTask(taskKey: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { tasks ->
            val index = tasks.indexOfFirst { it.taskKey == taskKey }
            if (index < 0) tasks else {
                val updated = transform(tasks[index])
                if (updated === tasks[index]) tasks else tasks.toMutableList().also { it[index] = updated }
            }
        }
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
        val number = if (settings.downloadDiscSubDir) task.disc?.trackNumber ?: task.trackNumber else task.trackNumber
        return if (settings.downloadTrackNumber && number > 0) {
            "${number.toString().padStart(2, '0')}.$base"
        } else {
            base
        }
    }

    private fun isFileAlreadyDownloaded(task: DownloadTask, settings: AppSettings): Boolean {
        if (task.filePath.isNotEmpty()) {
            if (task.filePath.startsWith("content://")) {
                return runCatching { DocumentFile.fromSingleUri(context, task.filePath.toUri())?.exists() == true }.getOrDefault(false)
            }
            val file = File(task.filePath)
            if (file.exists() && file.length() > 0) return true
        }
        val subDir = downloadDiscDirectory(task, settings.downloadDiscSubDir)
        val extension = if (task.qualityId in MP3_QUALITY_IDS) ".mp3" else ".flac"
        val namingStyle = DownloadNamingStyle.fromKey(settings.downloadNamingStyle)
        val baseName = buildFileName(task, namingStyle)
        val fileName = "$baseName$extension"
        val treeUri = settings.downloadTreeUri
        if (treeUri.isNotBlank()) {
            try {
                val treeDoc = DocumentFile.fromTreeUri(context, treeUri.toUri())
                val parent =
                    treeDoc?.let { downloadDocumentParent(it, subDir, create = false) }
                if (parent?.findFile(fileName) != null) return true
            } catch (_: Exception) {
            }
        } else {
            val baseDir = getDownloadDir(settings.downloadPath)
            val targetDir = if (subDir.isNotBlank()) File(baseDir, subDir) else baseDir
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
                val parent = treeDoc?.let { downloadDocumentParent(it, subDir, create = false) }
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

    private suspend fun copyToDestination(
        treeUriString: String, fileName: String, sourceFile: File,
        settings: AppSettings, subDir: String = ""
    ): android.net.Uri {
        val root = DocumentFile.fromTreeUri(context, treeUriString.toUri())
            ?: throw java.io.IOException("下载目录不可用")
        return writeDownloadDocument(root, subDir, fileName, sourceFile, settings.downloadOverwriteExisting) { uri ->
            context.contentResolver.openOutputStream(uri, "wt")
        }
    }

    /**
     * 尝试通过 DocumentFile 删除文件
     */
    private fun deleteViaDocumentFile(task: DownloadTask): Boolean {
        if (task.filePath.startsWith("content://")) {
            val uri = task.filePath.toUri()
            val cursor = context.contentResolver.query(uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?: return false
            cursor.use { if (!it.moveToFirst()) return true }
            return android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
        val treeUri = appSettingsManager.settings.value.downloadTreeUri
        if (treeUri.isBlank()) return false
        val root = DocumentFile.fromTreeUri(context, treeUri.toUri()) ?: return false
        if (!root.canRead()) return false
        val parent = downloadDocumentParent(root, task.subDir, create = false) ?: return true
        val file = parent.findFile(File(task.filePath).name) ?: return true
        return file.delete()
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
        @SerializedName("trackNumber") val trackNumber: Int = 0,
        @SerializedName("songIdentity") val songIdentity: String? = null,
        @SerializedName("disc") val disc: DownloadDisc? = null
    )

    private fun loadTasks() {
        try {
            val json = taskFile.read() ?: return
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

                val song = p.songJson.takeIf { !it.isNullOrBlank() }?.let { raw ->
                    runCatching { MusicItem.fromJsonString(raw) }.getOrElse {
                        Log.w(TAG, "Failed to restore song metadata", it)
                        null
                    }
                }
                val identity = song?.let(::downloadIdentity) ?: p.songIdentity.orEmpty()
                val task = DownloadTask(
                    songId = p.songId,
                    songIdentity = identity,
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
                    trackNumber = p.trackNumber,
                    disc = p.disc
                )

                task to song
            }

            // Preserve group order and prefer a finished file or paused transfer over a duplicate pending task.
            val normalized = restored.groupBy { it.first.taskKey }.values.map { group ->
                group.maxBy { downloadRestorePriority(it.first) }
            }
            normalized.forEach { (task, song) ->
                if (song != null) {
                    songItemCache[task.taskKey] = song
                    song.qualities[task.qualityId]?.let { quality -> qualityCache[task.taskKey] = quality }
                }
            }
            _tasks.value = normalized.map { it.first }
            Log.i(
                TAG,
                "Loaded ${normalized.size} tasks from disk, ${songItemCache.size} songs restored"
            )

            // 恢复后自动继续等待中的任务
            scheduleNext()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load tasks", e)
        }
    }

    private fun saveTasks() = taskSaver.request()

    private fun encodeTasks(): String {
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
                songIdentity = t.songIdentity,
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
                trackNumber = t.trackNumber,
                disc = t.disc
            )
        }
        return gson.toJson(persisted)
    }
}
