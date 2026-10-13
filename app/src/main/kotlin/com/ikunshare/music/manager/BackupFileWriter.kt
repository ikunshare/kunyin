package com.ikunshare.music.manager

import android.util.AtomicFile
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/** AtomicFile owns the descriptor, including when the serializer closes its writer. */
internal suspend fun writeBackupAtomically(target: File, serialize: (OutputStream) -> Unit) {
    coroutineContext.ensureActive()
    if (target.exists() && !target.isFile) throw IOException("Backup target is not a file")
    val atomic = AtomicFile(target)
    val stream = try { atomic.startWrite() } catch (failure: Throwable) {
        // Older AtomicFile moves the base to .bak before opening the replacement descriptor.
        // Opening for read recovers that backup when descriptor creation itself fails.
        runCatching { atomic.openRead().use { } }
        throw failure
    }
    val writingContext = coroutineContext
    val digest = MessageDigest.getInstance("SHA-256")
    try {
        val borrowed = object : FilterOutputStream(stream) {
            override fun write(value: Int) {
                writingContext.ensureActive(); stream.write(value); digest.update(value.toByte())
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                writingContext.ensureActive()
                stream.write(bytes, offset, length)
                digest.update(bytes, offset, length)
            }
            override fun close() { flush() }
        }
        serialize(borrowed)
        borrowed.flush()
        coroutineContext.ensureActive()
        atomic.finishWrite(stream)
    } catch (failure: Throwable) {
        atomic.failWrite(stream)
        throw failure
    }
    // AtomicFile.finishWrite logs some rename failures instead of throwing. Do not report success
    // when the destination still contains the older backup (also reproducible on Windows JVM tests).
    val committed = MessageDigest.getInstance("SHA-256")
    target.inputStream().use { input ->
        val bytes = ByteArray(8192)
        while (true) { val read = input.read(bytes); if (read < 0) break; committed.update(bytes, 0, read) }
    }
    if (!digest.digest().contentEquals(committed.digest())) throw IOException("Backup file was not committed")
}
