package com.ikunshare.music.tool

import android.media.MediaDataSource
import java.io.File
import java.io.RandomAccessFile

/**
 * 纯本地分块 MediaDataSource：只读已缓存的 chunk 文件，无网络、无解密。
 * 命中完整磁盘缓存时使用。
 */
class CachedChunkDataSource(
    private val chunkDir: File,
    private val chunkCount: Int,
    private val totalSize: Long
) : MediaDataSource() {

    companion object {
        const val CHUNK_SIZE = 2 * 1024 * 1024
    }

    private var openChunkIndex = -1
    private var openChunk: RandomAccessFile? = null
    private var closed = false

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (closed || position >= totalSize) return -1

        val endPos = minOf(position + size, totalSize)
        val startChunk = (position / CHUNK_SIZE).toInt()
        val endChunk = ((endPos - 1) / CHUNK_SIZE).toInt()
        var bufPos = offset
        var filePos = position

        for (ci in startChunk..endChunk) {
            if (ci >= chunkCount) break
            val chunkFile = File(chunkDir, String.format("chunk_%03d", ci))
            if (!chunkFile.exists()) return if (bufPos > offset) bufPos - offset else -1

            val chunkStart = ci.toLong() * CHUNK_SIZE
            val offsetInChunk = (filePos - chunkStart).toInt()
            val needed = (endPos - filePos).toInt()
            val chunkLen = chunkFile.length().toInt()
            val available = chunkLen - offsetInChunk
            val toCopy = minOf(available, needed)
            if (toCopy <= 0) break

            try {
                val raf = openChunk(ci, chunkFile)
                raf.seek(offsetInChunk.toLong())
                val read = raf.read(buffer, bufPos, toCopy)
                if (read > 0) {
                    bufPos += read
                    filePos += read
                }
            } catch (_: Exception) {
                closeOpenChunk()
                return if (bufPos > offset) bufPos - offset else -1
            }
        }

        val totalRead = bufPos - offset
        return if (totalRead > 0) totalRead else -1
    }

    private fun openChunk(index: Int, file: File): RandomAccessFile {
        if (openChunkIndex == index) openChunk?.let { return it }
        closeOpenChunk()
        return RandomAccessFile(file, "r").also {
            openChunk = it
            openChunkIndex = index
        }
    }

    private fun closeOpenChunk() {
        runCatching { openChunk?.close() }
        openChunk = null
        openChunkIndex = -1
    }

    override fun getSize(): Long = totalSize

    @Synchronized
    override fun close() {
        closed = true
        closeOpenChunk()
    }
}
