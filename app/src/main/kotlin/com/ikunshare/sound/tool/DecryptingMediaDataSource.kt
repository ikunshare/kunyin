package com.ikunshare.sound.tool

import android.media.MediaDataSource
import android.util.Log
import com.ikunshare.sound.tool.cache.AudioCache
import com.ikunshare.sound.utils.crypto.MflacCrypto
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 分块磁盘下载的 MediaDataSource。
 *
 * 将加密音频文件按 2MB 块下载到磁盘，边下载边解密。
 * 支持 seek 优先下载：拖动进度条到未下载位置时，停止当前下载并优先下载目标块。
 * 内存占用稳定（仅缓存一个 2MB chunk），不随文件大小增长。
 */
class DecryptingMediaDataSource(
    private val url: String,
    ekeyBase64: String,
    private val audioCache: AudioCache? = null,
    private val cacheKey: String? = null
) : MediaDataSource() {

    companion object {
        private const val TAG = "KunSound"
        private const val CHUNK_SIZE = 2 * 1024 * 1024 // 2MB
        private const val READ_BUF_SIZE = 64 * 1024    // 64KB read buffer
        private const val STATE_DONE = 2
    }

    private val crypto = MflacCrypto(ekeyBase64.replace(" ", "+").toByteArray(Charsets.US_ASCII))

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var totalSize: Long = -1L

    @Volatile
    private var chunkCount: Int = 0

    @Volatile
    private var chunkStates: AtomicIntegerArray? = null
    private var chunkDir: File? = null

    @Volatile
    private var closed = false

    @Volatile
    private var error = false

    @Volatile
    private var cacheHit = false

    @Volatile
    private var rangeNotSupported = false

    private val lock = ReentrantLock()
    private val chunkDone = lock.newCondition()

    @Volatile
    private var currentWorker: DownloadWorker? = null

    @Volatile
    private var cachedChunk: CachedChunkData? = null

    private class CachedChunkData(val index: Int, val data: ByteArray)

    init {
        if (audioCache != null && cacheKey != null) {
            chunkDir = audioCache.getChunkDir(cacheKey)

            if (audioCache.isComplete(cacheKey)) {
                val meta = audioCache.readMeta(cacheKey)
                if (meta != null) {
                    totalSize = meta.totalSize
                    chunkCount = meta.chunkCount
                    val dir = chunkDir!!
                    val firstOk = File(dir, chunkFileName(0)).exists()
                    val lastOk =
                        chunkCount <= 1 || File(dir, chunkFileName(chunkCount - 1)).exists()
                    if (firstOk && lastOk) {
                        chunkStates = AtomicIntegerArray(chunkCount).also { states ->
                            for (i in 0..chunkCount) states.set(i, STATE_DONE)
                        }
                        cacheHit = true
                        Log.d(TAG, "DecryptingDataSource: full cache hit, totalSize=$totalSize")
                    } else {
                        Log.w(
                            TAG,
                            "DecryptingDataSource: stale complete marker, chunk files missing"
                        )
                        try {
                            File(dir, ".complete").delete()
                        } catch (_: Exception) {
                        }
                    }
                }
            }

            if (!cacheHit) {
                val meta = audioCache.readMeta(cacheKey)
                if (meta != null) {
                    // 恢复部分缓存
                    totalSize = meta.totalSize
                    chunkCount = meta.chunkCount
                    val states = AtomicIntegerArray(chunkCount)
                    chunkStates = states
                    val dir = chunkDir!!
                    for (i in 0..chunkCount) {
                        val chunkFile = File(dir, chunkFileName(i))
                        if (chunkFile.exists() && chunkFile.length() == expectedChunkSize(i).toLong()) {
                            states.set(i, STATE_DONE)
                        }
                    }
                    val firstIncomplete =
                        (0..chunkCount).firstOrNull { states.get(it) != STATE_DONE }
                    if (firstIncomplete != null) {
                        startWorkerFrom(firstIncomplete)
                    } else {
                        audioCache.markComplete(cacheKey)
                        cacheHit = true
                        Log.d(TAG, "DecryptingDataSource: all chunks present, marking complete")
                    }
                } else {
                    startWorkerFrom(0)
                }
            }
        } else {
            // 无持久缓存：创建临时目录
            chunkDir = File.createTempFile("mdl_chunks_", "").let { tmp ->
                tmp.delete()
                tmp.mkdir()
                tmp
            }
            startWorkerFrom(0)
        }
    }

    private fun chunkFileName(index: Int): String = "chunk_%03d".format(index)

    private fun expectedChunkSize(index: Int): Int {
        if (totalSize <= 0) return CHUNK_SIZE
        return if (index == chunkCount - 1) {
            val remainder = (totalSize % CHUNK_SIZE).toInt()
            if (remainder == 0) CHUNK_SIZE else remainder
        } else {
            CHUNK_SIZE
        }
    }

    @Synchronized
    private fun initChunkInfo(size: Long) {
        if (totalSize > 0) return
        totalSize = size
        chunkCount = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        if (chunkStates == null) {
            chunkStates = AtomicIntegerArray(chunkCount)
        }
        if (audioCache != null && cacheKey != null) {
            audioCache.writeMeta(cacheKey, AudioCache.ChunkMeta(totalSize, CHUNK_SIZE, chunkCount))
        }
        Log.d(
            TAG,
            "DecryptingDataSource: initChunkInfo totalSize=$totalSize chunkCount=$chunkCount"
        )
    }

    fun awaitReady() {
        if (cacheHit) return
        lock.withLock {
            val deadline = System.nanoTime() + 30_000_000_000L
            while (!closed && !error) {
                val states = chunkStates
                if (states != null && states.length() > 0 && states.get(0) == STATE_DONE) return
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("Timed out waiting for first chunk")
                chunkDone.await(remaining, TimeUnit.NANOSECONDS)
            }
        }
        if (error) throw IOException("Download error")
        if (closed) throw IOException("DataSource closed")
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (totalSize in 0..position) return -1
        if (closed) return -1

        // 等待 totalSize 已知
        if (totalSize < 0) {
            lock.withLock {
                val deadline = System.nanoTime() + 15_000_000_000L
                while (!closed && !error && totalSize < 0) {
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) return -1
                    chunkDone.await(remaining, TimeUnit.NANOSECONDS)
                }
            }
            if (closed || error) return -1
            if (totalSize in 0..position) return -1
        }

        val startChunk = (position / CHUNK_SIZE).toInt()
        val actualEnd = minOf(position + size, totalSize)
        if (actualEnd <= position) return -1
        val endChunk = ((actualEnd - 1) / CHUNK_SIZE).toInt()

        var bufPos = offset
        var filePos = position

        for (ci in startChunk..endChunk) {
            if (closed) return if (bufPos > offset) bufPos - offset else -1
            try {
                ensureChunkReady(ci)
            } catch (e: IOException) {
                Log.e(TAG, "readAt: ensureChunkReady($ci) failed", e)
                return if (bufPos > offset) bufPos - offset else -1
            }

            val chunkStart = ci.toLong() * CHUNK_SIZE
            val offsetInChunk = (filePos - chunkStart).toInt()
            val chunkDataSize = expectedChunkSize(ci)
            val available = chunkDataSize - offsetInChunk
            val needed = (actualEnd - filePos).toInt()
            val toCopy = minOf(available, needed)

            if (toCopy <= 0) break

            val chunkData = getChunkData(ci)
                ?: return if (bufPos > offset) bufPos - offset else -1

            System.arraycopy(chunkData, offsetInChunk, buffer, bufPos, toCopy)
            bufPos += toCopy
            filePos += toCopy
        }

        val read = bufPos - offset
        return if (read > 0) read else -1
    }

    private fun getChunkData(index: Int): ByteArray? {
        val cached = cachedChunk
        if (cached != null && cached.index == index) return cached.data

        val dir = chunkDir ?: return null
        val file = File(dir, chunkFileName(index))
        if (!file.exists()) return null

        return try {
            val data = file.readBytes()
            cachedChunk = CachedChunkData(index, data)
            data
        } catch (e: IOException) {
            Log.e(TAG, "getChunkData: failed to read chunk $index", e)
            null
        }
    }

    private fun ensureChunkReady(chunkIndex: Int) {
        val states = chunkStates ?: throw IOException("Not initialized")
        if (chunkIndex >= states.length()) throw IOException("Chunk index out of range: $chunkIndex >= ${states.length()}")
        if (states.get(chunkIndex) == STATE_DONE) return

        // 触发优先下载
        if (!rangeNotSupported) {
            startWorkerFrom(chunkIndex)
        }

        lock.withLock {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (!closed && !error && states.get(chunkIndex) != STATE_DONE) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) throw IOException("Timed out waiting for chunk $chunkIndex")
                chunkDone.await(remaining, TimeUnit.NANOSECONDS)
            }
        }
        if (error) throw IOException("Download error")
        if (closed) throw IOException("DataSource closed")
    }

    @Synchronized
    private fun startWorkerFrom(chunkIndex: Int) {
        if (closed) return
        val old = currentWorker
        if (old != null && old.isAlive) {
            old.shouldStop = true
        }
        val worker = DownloadWorker(chunkIndex)
        currentWorker = worker
        worker.start()
    }

    override fun getSize(): Long = totalSize

    override fun close() {
        closed = true
        lock.withLock { chunkDone.signalAll() }
        currentWorker?.let {
            it.shouldStop = true
            try {
                it.currentCall?.cancel()
            } catch (_: Exception) {
            }
            it.interrupt()
        }
        try {
            crypto.close()
        } catch (_: Exception) {
        }
        if (audioCache == null || cacheKey == null) {
            chunkDir?.deleteRecursively()
        }
    }

    private fun finishChunk(index: Int, data: ByteArray, size: Int) {
        if (closed) return // 防止截断或被丢弃的数据在 close 后意外落盘

        val dir = chunkDir ?: return
        try {
            val file = File(dir, chunkFileName(index))
            if (size == data.size) {
                file.writeBytes(data)
            } else {
                file.writeBytes(data.copyOf(size))
            }
        } catch (e: IOException) {
            Log.e(TAG, "finishChunk: write failed for chunk $index", e)
            error = true
            lock.withLock { chunkDone.signalAll() }
            return
        }

        val states = chunkStates ?: return
        states.set(index, STATE_DONE)
        lock.withLock { chunkDone.signalAll() }
    }

    private fun markAllComplete() {
        val states = chunkStates ?: return
        val allDone = (0 until states.length()).all { states.get(it) == STATE_DONE }
        if (allDone && audioCache != null && cacheKey != null) {
            audioCache.writeMeta(cacheKey, AudioCache.ChunkMeta(totalSize, CHUNK_SIZE, chunkCount))
            audioCache.markComplete(cacheKey)
            audioCache.evict()
            Log.d(TAG, "DecryptingDataSource: all chunks complete, totalSize=$totalSize")
        }
    }

    // ── DownloadWorker ──

    private inner class DownloadWorker(private val startFromChunk: Int) :
        Thread("ChunkDL-$startFromChunk") {
        @Volatile
        var shouldStop = false

        @Volatile
        var currentCall: Call? = null

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                downloadFrom(startFromChunk)
            } catch (_: InterruptedException) {
                // 正常停止
            } catch (e: Exception) {
                if (!shouldStop && !closed) {
                    Log.e(TAG, "DownloadWorker error", e)
                    error = true
                    lock.withLock { chunkDone.signalAll() }
                }
            } finally {
                currentCall = null
            }
        }

        private fun downloadFrom(fromChunk: Int) {
            // 阶段 1：从 fromChunk 下载到文件末尾
            downloadRange(fromChunk, Int.MAX_VALUE)
            if (shouldStop || closed) return

            // 阶段 2：回头填补 0 到 fromChunk 的空隙
            if (fromChunk > 0) {
                downloadRange(0, fromChunk)
                if (shouldStop || closed) return
            }

            markAllComplete()
        }

        /**
         * 在 [fromChunk, limitChunk) 范围内，寻找连续未完成区间并下载。
         * limitChunk = Int.MAX_VALUE 表示下载到文件末尾。
         */
        private fun downloadRange(fromChunk: Int, limitChunk: Int) {
            var pos = fromChunk
            while (!shouldStop && !closed) {
                val effectiveLimit =
                    if (chunkCount > 0) minOf(limitChunk, chunkCount) else limitChunk
                if (pos >= effectiveLimit) break

                // 跳过已完成的 chunk
                val states = chunkStates
                if (states != null && pos < states.length() && states.get(pos) == STATE_DONE) {
                    pos++
                    continue
                }

                // 找连续未完成区间 [pos, endExcl)
                var endExcl = pos + 1
                if (states != null) {
                    while (endExcl < effectiveLimit && endExcl < states.length() && states.get(
                            endExcl
                        ) != STATE_DONE
                    ) {
                        endExcl++
                    }
                }

                downloadContiguousChunks(pos, endExcl)
                if (shouldStop || closed) return
                pos = endExcl
            }
        }

        /**
         * 通过单个 Range GET 下载连续 chunk [fromChunk, toChunkExcl)。
         * 若 totalSize 尚未知（首次请求），从 Content-Range 解析。
         */
        private fun downloadContiguousChunks(fromChunk: Int, toChunkExcl: Int) {
            val rangeStart = fromChunk.toLong() * CHUNK_SIZE
            // 构建 Range header
            val rangeHeader = if (totalSize > 0 && toChunkExcl >= chunkCount) {
                "bytes=$rangeStart-${totalSize - 1}"
            } else if (totalSize > 0) {
                "bytes=$rangeStart-${toChunkExcl.toLong() * CHUNK_SIZE - 1}"
            } else {
                // totalSize 未知，请求开放范围
                "bytes=$rangeStart-"
            }

            val request = Request.Builder()
                .url(url)
                .header("Range", rangeHeader)
                .build()

            val call = client.newCall(request)
            currentCall = call

            val response = try {
                call.execute()
            } catch (e: IOException) {
                if (!shouldStop && !closed) throw e
                return
            } finally {
                currentCall = null // execute 返回后网络流处理阶段不再持有 call
            }

            response.use { resp ->
                if (resp.code == 200) {
                    // 服务器不支持 Range，回退为流式处理整个文件
                    rangeNotSupported = true
                    handleFullResponse(resp)
                    return
                }
                if (resp.code != 206) {
                    throw IOException("Unexpected response code: ${resp.code}")
                }

                // 解析 Content-Range 获取 totalSize
                if (totalSize < 0) {
                    val contentRange = resp.header("Content-Range") // bytes 0-xxx/TOTAL
                    val total = contentRange?.substringAfter("/")?.toLongOrNull()
                    if (total != null && total > 0) {
                        initChunkInfo(total)
                    } else {
                        // 从 Content-Length 推断（不太可靠但作为后备）
                        val cl = resp.body.contentLength()
                        if (cl > 0 && fromChunk == 0) {
                            initChunkInfo(cl)
                        } else {
                            throw IOException("Cannot determine file size")
                        }
                    }
                }

                streamResponseToChunks(resp, fromChunk)
            }
        }

        /**
         * 服务器不支持 Range 时，按顺序处理整个 200 响应。
         */
        private fun handleFullResponse(resp: okhttp3.Response) {
            val contentLength = resp.body.contentLength()
            if (contentLength > 0 && totalSize < 0) {
                initChunkInfo(contentLength)
            }

            streamResponseToChunks(resp, 0)
        }

        /**
         * 从 HTTP 响应流中读取数据，解密后按 CHUNK_SIZE 切分写入磁盘。
         */
        private fun streamResponseToChunks(resp: okhttp3.Response, fromChunk: Int) {
            val input = resp.body.byteStream()
            val readBuf = ByteArray(READ_BUF_SIZE)
            val chunkBuffer = ByteArray(CHUNK_SIZE)
            var chunkBufferPos = 0
            var currentChunk = fromChunk
            var fileOffset = fromChunk.toLong() * CHUNK_SIZE

            while (!shouldStop && !closed) {
                val n = try {
                    input.read(readBuf)
                } catch (e: IOException) {
                    if (shouldStop || closed) return
                    throw e
                }
                if (n == -1) break

                val encrypted = if (n == readBuf.size) readBuf else readBuf.copyOf(n)
                val decrypted = crypto.decryptChunk(encrypted, fileOffset)
                fileOffset += n

                var srcPos = 0
                while (srcPos < decrypted.size) {
                    val expectedSize = expectedChunkSize(currentChunk)
                    val remaining = expectedSize - chunkBufferPos
                    val toCopy = minOf(remaining, decrypted.size - srcPos)
                    System.arraycopy(decrypted, srcPos, chunkBuffer, chunkBufferPos, toCopy)
                    chunkBufferPos += toCopy
                    srcPos += toCopy

                    if (chunkBufferPos >= expectedSize) {
                        finishChunk(currentChunk, chunkBuffer, chunkBufferPos)
                        currentChunk++
                        chunkBufferPos = 0
                        // 完成一个 chunk 后检查是否应停止
                        if (shouldStop || closed) return
                    }
                }
            }

            // 处理最后一个不满的 chunk（文件末尾）
            if (chunkBufferPos > 0 && !shouldStop && !closed) {
                finishChunk(currentChunk, chunkBuffer, chunkBufferPos)
            }
        }
    }
}
