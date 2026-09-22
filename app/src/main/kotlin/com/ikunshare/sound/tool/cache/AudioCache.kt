package com.ikunshare.sound.tool.cache

import android.content.Context
import android.util.Log
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.tool.CachedChunkDataSource
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 磁盘 LRU 音频缓存。
 *
 * 条目是分块目录，格式：
 * ```
 * audio_cache/
 *   qq_12345_flac/
 *     .meta        ← 3 行：totalSize / chunkSize / chunkCount
 *     .complete    ← 空文件，存在 = 完整
 *     chunk_000
 *     chunk_001
 *     ...
 * ```
 *
 * 加密条目由 DecryptingMediaDataSource 边下载边解密边写入；
 * 非加密条目由 [downloadToChunks] 后台裸 GET 写入。
 * 读取统一通过 [getCachedChunks] 返回 [CachedChunkDataSource]。
 */
class AudioCache(
    context: Context,
    private val appSettingsManager: AppSettingsManager
) {
    companion object {
        private const val TAG = "KunSound"
        private const val DIR_NAME = "audio_cache"
        private const val META_FILE = ".meta"
        private const val COMPLETE_FILE = ".complete"
        private const val CHUNK_SIZE = CachedChunkDataSource.CHUNK_SIZE
    }

    data class ChunkMeta(val totalSize: Long, val chunkSize: Int, val chunkCount: Int)

    private val cacheDir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val maxSizeBytes: Long
        get() {
            val mb = appSettingsManager.settings.value.maxCacheSizeMb
            return when {
                mb <= 0 -> Long.MAX_VALUE
                mb == 1 -> 1L
                else -> mb.toLong() * 1024L * 1024L
            }
        }

    fun buildKey(source: String, songId: String, qualityId: String): String =
        "${source}_${songId}_${qualityId}"

    fun getChunkDir(key: String): File {
        val dir = File(cacheDir, key)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun isComplete(key: String): Boolean =
        File(File(cacheDir, key), COMPLETE_FILE).exists()

    fun markComplete(key: String) {
        try {
            val dir = File(cacheDir, key)
            if (dir.isDirectory) File(dir, COMPLETE_FILE).createNewFile()
        } catch (e: Exception) {
            Log.e(TAG, "AudioCache: markComplete failed key=$key", e)
        }
    }

    fun readMeta(key: String): ChunkMeta? {
        val metaFile = File(File(cacheDir, key), META_FILE)
        if (!metaFile.exists()) return null
        return try {
            val lines = metaFile.readLines()
            if (lines.size >= 3) {
                ChunkMeta(
                    totalSize = lines[0].toLong(),
                    chunkSize = lines[1].toInt(),
                    chunkCount = lines[2].toInt()
                )
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "AudioCache: readMeta failed key=$key", e)
            null
        }
    }

    fun writeMeta(key: String, meta: ChunkMeta) {
        try {
            val dir = File(cacheDir, key)
            if (!dir.exists()) dir.mkdirs()
            File(dir, META_FILE)
                .writeText("${meta.totalSize}\n${meta.chunkSize}\n${meta.chunkCount}")
        } catch (e: Exception) {
            Log.e(TAG, "AudioCache: writeMeta failed key=$key", e)
        }
    }

    /**
     * 完整缓存命中 → 返回纯本地 DataSource；否则 null。
     * 会校验所有 chunk 文件是否存在，缺任何一块即视为缓存损坏并清理。
     */
    fun getCachedChunks(key: String): CachedChunkDataSource? {
        val dir = File(cacheDir, key)
        if (!dir.isDirectory || !isComplete(key)) return null
        val meta = readMeta(key) ?: return null

        for (i in 0 until meta.chunkCount) {
            if (!File(dir, String.format("chunk_%03d", i)).exists()) {
                dir.deleteRecursively()
                return null
            }
        }
        dir.setLastModified(System.currentTimeMillis())
        return CachedChunkDataSource(dir, meta.chunkCount, meta.totalSize)
    }

    /**
     * 对非加密 URL 发起一次裸 GET，流式切成 2MB chunk 写盘，全部完成后 markComplete。
     * 阻塞调用；调用方自行放到 IO 协程。出错或外部 cancel 时清理半成品目录。
     *
     * @param onCallCreated 在 [okhttp3.Call] 创建后立刻回调，调用方可保存引用以便外部 cancel。
     */
    fun downloadToChunks(
        url: String,
        key: String,
        onCallCreated: ((okhttp3.Call) -> Unit)? = null
    ) {
        val dir = getChunkDir(key)
        dir.listFiles()?.forEach { it.delete() }

        val request = Request.Builder().url(url).build()
        val call = httpClient.newCall(request)
        onCallCreated?.invoke(call)

        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    dir.deleteRecursively()
                    return
                }

                val input = response.body.byteStream()

                val readBuf = ByteArray(8192)
                val chunkBuf = ByteArray(CHUNK_SIZE)
                var chunkPos = 0
                var chunkIndex = 0
                var totalSize = 0L

                while (true) {
                    val n = input.read(readBuf)
                    if (n == -1) break

                    var srcPos = 0
                    while (srcPos < n) {
                        val space = CHUNK_SIZE - chunkPos
                        val toCopy = minOf(space, n - srcPos)
                        System.arraycopy(readBuf, srcPos, chunkBuf, chunkPos, toCopy)
                        chunkPos += toCopy
                        srcPos += toCopy

                        if (chunkPos >= CHUNK_SIZE) {
                            writeChunkFile(dir, chunkIndex, chunkBuf, 0, chunkPos)
                            totalSize += chunkPos
                            chunkIndex++
                            chunkPos = 0
                        }
                    }
                }

                if (chunkPos > 0) {
                    writeChunkFile(dir, chunkIndex, chunkBuf, 0, chunkPos)
                    totalSize += chunkPos
                    chunkIndex++
                }

                if (chunkIndex > 0) {
                    writeMeta(key, ChunkMeta(totalSize, CHUNK_SIZE, chunkIndex))
                    markComplete(key)
                    evict()
                } else {
                    dir.deleteRecursively()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioCache: downloadToChunks failed key=$key", e)
            dir.deleteRecursively()
        }
    }

    fun getCacheSize(): Long =
        cacheDir.listFiles()?.sumOf { entrySize(it) } ?: 0L

    fun clearCache() {
        cacheDir.listFiles()?.forEach { entry ->
            if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
        }
        Log.d(TAG, "AudioCache: cache cleared")
    }

    fun evict() {
        val max = maxSizeBytes
        if (max == Long.MAX_VALUE) return

        val entries = cacheDir.listFiles()?.toMutableList() ?: return
        var totalSize = entries.sumOf { entrySize(it) }
        if (totalSize <= max) return

        entries.sortBy { it.lastModified() }
        for (entry in entries) {
            if (totalSize <= max) break
            val size = entrySize(entry)
            val deleted = if (entry.isDirectory) entry.deleteRecursively() else entry.delete()
            if (deleted) {
                totalSize -= size
                Log.d(TAG, "AudioCache: evicted ${entry.name} (${size / 1024}KB)")
            }
        }
    }

    private fun entrySize(entry: File): Long =
        if (entry.isDirectory)
            entry.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        else entry.length()

    private fun writeChunkFile(dir: File, index: Int, data: ByteArray, offset: Int, size: Int) {
        FileOutputStream(File(dir, String.format("chunk_%03d", index))).use {
            it.write(data, offset, size)
        }
    }
}
