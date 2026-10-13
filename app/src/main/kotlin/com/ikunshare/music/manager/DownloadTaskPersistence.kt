package com.ikunshare.music.manager

import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.OutputStream

/** Preserve the last complete queue when serialization, storage, or cancellation interrupts a save. */
internal class AtomicDownloadTaskFile(
    private val file: File,
    private val writeContent: (OutputStream, String) -> Unit = { stream, json -> stream.write(json.toByteArray(Charsets.UTF_8)) }
) {
    private val atomic = AtomicFile(file)

    fun read(): String? {
        if (!file.exists() && !File("${file.path}.bak").exists()) return null
        return atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun write(json: String, beforeCommit: () -> Unit = {}) {
        val stream = atomic.startWrite()
        try {
            writeContent(stream, json)
            beforeCommit()
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }
}

internal class DebouncedDownloadTaskSaver(
    private val scope: CoroutineScope,
    private val file: AtomicDownloadTaskFile,
    private val encode: () -> String,
    private val onFailure: (Exception) -> Unit,
    private val delayMs: Long = 500
) {
    private var pending: Job? = null
    private val writes = Mutex()

    @Synchronized
    fun request(): Job {
        pending?.cancel()
        val next = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                delay(delayMs)
                writes.withLock {
                    currentCoroutineContext().ensureActive()
                    val json = encode()
                    currentCoroutineContext().ensureActive()
                    val execution = currentCoroutineContext()
                    file.write(json) { execution.ensureActive() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { onFailure(failure) }
        }
        pending = next
        next.start()
        return next
    }
}
