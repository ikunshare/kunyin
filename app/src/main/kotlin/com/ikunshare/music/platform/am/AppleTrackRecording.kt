package com.ikunshare.music.platform.am

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class AppleTrackRecording(
    private val cache: AppleAudioCache,
    private val key: String,
    private val header: AppleTrackHeader,
    private val idleMs: Long = 90_000L,
    private val scheduler: ScheduledExecutorService = sharedScheduler
) {
    private val temporary = File(cache.rootDir, "apple-tmp/hls-${UUID.randomUUID()}.m4a")
    private val recorded = BooleanArray(header.fragments.size)
    private var output: RandomAccessFile? = null
    private var recordedCount = 0
    private var broken = false
    private var idle = false
    private var disposed = false
    private var idleTask: ScheduledFuture<*>? = null

    var finalFile: File? = null
        private set

    init {
        require(key.isNotBlank()) { "Apple 缓存键不能为空" }
        require(idleMs >= 0L) { "临时文件清理延迟不能为负数" }
        try {
            temporary.parentFile?.let { parent ->
                if (!parent.isDirectory && !parent.mkdirs()) {
                    throw IOException("Apple Music 临时目录不可用")
                }
            }
            output = RandomAccessFile(temporary, "rw").apply {
                setLength(0L)
                write(header.bytes)
            }
        } catch (_: Exception) {
            broken = true
            runCatching { output?.close() }
            temporary.delete()
        }
    }

    @Synchronized
    fun record(index: Int, data: ByteArray) {
        if (disposed || broken || finalFile != null || index !in recorded.indices || recorded[index]) return
        if (idle) {
            idleTask?.cancel(false)
            idle = false
        }
        recorded[index] = true
        try {
            val slot = header.fragments[index]
            if (data.size.toLong() != slot.bytes) throw IOException("Apple Music 分片长度不符")
            val file = output ?: RandomAccessFile(temporary, "rw").also { output = it }
            file.seek(slot.virtualStart)
            file.write(data)
            recordedCount++
            if (recordedCount == recorded.size) {
                file.setLength(header.totalSize)
                file.close()
                output = null
                finalFile = cache.store(key, temporary)
            }
        } catch (_: Exception) {
            broken = true
            runCatching { output?.close() }
            output = null
            temporary.delete()
        }
    }

    @Synchronized
    fun close() {
        if (disposed || idle || finalFile != null) return
        idle = true
        runCatching { output?.close() }
        output = null
        if (broken) {
            disposed = true
            temporary.delete()
            return
        }
        runCatching {
            idleTask = scheduler.schedule({ discardPartial() }, idleMs, TimeUnit.MILLISECONDS)
        }.onFailure {
            disposed = true
            temporary.delete()
        }
    }

    @Synchronized
    fun dispose() {
        if (disposed) return
        disposed = true
        idleTask?.cancel(false)
        runCatching { output?.close() }
        output = null
        if (finalFile == null) temporary.delete()
    }

    @Synchronized
    private fun discardPartial() {
        if (disposed || finalFile != null || !idle) return
        runCatching { output?.close() }
        output = null
        temporary.delete()
        disposed = true
    }

    private companion object {
        val sharedScheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "apple-cache-idle").apply { isDaemon = true }
        }
    }
}
