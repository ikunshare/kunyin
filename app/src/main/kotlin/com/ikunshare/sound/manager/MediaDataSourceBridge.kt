package com.ikunshare.sound.manager

import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec

@OptIn(UnstableApi::class)
class MediaDataSourceBridgeFactory(
    private val mediaDataSource: MediaDataSource
) : DataSource.Factory {
    override fun createDataSource(): DataSource = MediaDataSourceBridge(mediaDataSource)
}

@OptIn(UnstableApi::class)
private class MediaDataSourceBridge(
    private val source: MediaDataSource
) : BaseDataSource(false) {

    private var position: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        position = dataSpec.position
        val totalSize = source.size
        bytesRemaining = if (totalSize == -1L) {
            C.LENGTH_UNSET.toLong()
        } else {
            totalSize - position
        }
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            length.toLong().coerceAtMost(bytesRemaining).toInt()
        }

        val read = source.readAt(position, buffer, offset, toRead)
        if (read == -1) return C.RESULT_END_OF_INPUT

        position += read
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= read
        }
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri = Uri.EMPTY

    override fun close() {
        // MediaDataSource lifecycle is managed externally
    }
}
