package com.ikunshare.music.platform.am

import android.media.MediaDataSource
import kotlinx.coroutines.runBlocking

class AppleMediaDataSource(
    private val reader: AppleVirtualTrackReader
) : MediaDataSource() {
    @Volatile
    private var closed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (closed || position >= reader.size) return -1
        val data = runBlocking { reader.read(position, size) }
        if (data.isEmpty()) return -1
        data.copyInto(buffer, offset)
        return data.size
    }

    override fun getSize(): Long = reader.size

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runBlocking { reader.close() }
    }
}
