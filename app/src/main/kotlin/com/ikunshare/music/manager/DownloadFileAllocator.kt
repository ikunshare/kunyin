package com.ikunshare.music.manager

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Holds a destination until the whole download (including tags/SAF copy) finishes. */
internal class DownloadFileAllocator {
    private val lock = Any()
    private val occupied = mutableSetOf<Pair<String, String>>()
    private var changed = CompletableDeferred<Unit>()

    inner class Reservation internal constructor(val directory: String, val fileName: String) : AutoCloseable {
        private var closed = false
        override fun close() = synchronized(lock) {
            if (!closed && occupied.remove(directory to fileName)) {
                closed = true
                changed.complete(Unit)
                changed = CompletableDeferred()
            }
            Unit
        }
    }

    suspend fun reserve(directory: String, base: String, extension: String, overwrite: Boolean,
                        exists: (String) -> Boolean): Reservation {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = synchronized(lock) {
                var name = "$base$extension"
                if (overwrite && directory to name in occupied) {
                    changed
                } else {
                    var suffix = 1
                    if (!overwrite) {
                        while (directory to name in occupied || exists(name)) {
                            name = "$base (${++suffix})$extension"
                        }
                    }
                    occupied.add(directory to name)
                    return Reservation(directory, name)
                }
            }
            wait.await()
        }
    }
}
