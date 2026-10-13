package com.ikunshare.music.manager

import android.util.AtomicFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal suspend fun copyDownloadBytes(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(8192)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) break
        output.write(buffer, 0, count)
    }
}

/** API 24-compatible replacement, preserving the previous file if copying is interrupted. */
internal suspend fun commitDownloadFile(source: File, target: File, overwrite: Boolean,
    copy: suspend (InputStream, OutputStream) -> Unit = ::copyDownloadBytes) {
    currentCoroutineContext().ensureActive()
    if (!overwrite && target.exists()) throw IOException("目标文件已存在: ${target.name}")
    val atomic = AtomicFile(target)
    val output = atomic.startWrite()
    try {
        source.inputStream().use { copy(it, output) }
        currentCoroutineContext().ensureActive()
        atomic.finishWrite(output)
    } catch (failure: Throwable) {
        atomic.failWrite(output)
        throw failure
    }
    source.delete()
}
