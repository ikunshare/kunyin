package com.ikunshare.music.platform.am

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.runBlocking
import java.io.IOException

@OptIn(UnstableApi::class)
class AppleDataSource(
    private val reader: AppleVirtualTrackReader
) : BaseDataSource(false) {
    private var opened = false
    private var closed = false
    private var position = 0L
    private var remaining = 0L
    private var uri = Uri.EMPTY

    override fun open(dataSpec: DataSpec): Long {
        if (closed) throw IOException("Apple DataSource 已关闭")
        if (opened) throw IOException("Apple DataSource 已打开")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position.coerceIn(0L, reader.size)
        val available = reader.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            minOf(available, dataSpec.length)
        }
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("Apple DataSource 已关闭")
        if (!opened) throw IOException("Apple DataSource 尚未打开")
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val data = runBlocking { reader.read(position, minOf(length.toLong(), remaining).toInt()) }
        if (data.isEmpty()) {
            remaining = 0L
            return C.RESULT_END_OF_INPUT
        }
        data.copyInto(buffer, offset)
        position += data.size
        remaining -= data.size
        bytesTransferred(data.size)
        return data.size
    }

    override fun getUri(): Uri = uri

    override fun close() {
        if (closed) return
        closed = true
        if (opened) {
            opened = false
            transferEnded()
        }
        runBlocking { reader.close() }
    }
}

@OptIn(UnstableApi::class)
class AppleDataSourceFactory(
    private val readerFactory: () -> AppleVirtualTrackReader
) : DataSource.Factory {
    override fun createDataSource(): DataSource = AppleDataSource(readerFactory())
}
