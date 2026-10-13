package com.ikunshare.music.manager.discovery

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared workers survive individual consumers, and only successful values enter the cache. */
internal class DiscoveryMemo<K, V>(
    private val ttlMillis: Long,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
) {
    private data class Hit<V>(val at: Long, val value: V)
    private val mutex = Mutex()
    private val cache = linkedMapOf<K, Hit<V>>()
    private val pending = mutableMapOf<K, Deferred<V>>()

    suspend fun get(key: K, refresh: Boolean = false, load: suspend () -> V): V {
        val task = mutex.withLock {
            if (refresh) cache.remove(key)
            cache[key]?.takeIf { clock() - it.at < ttlMillis }?.let { return it.value }
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val value = load()
                    mutex.withLock {
                        if (cache.size >= 400) cache.keys.take(200).forEach { cache.remove(it) }
                        cache.remove(key)
                        cache[key] = Hit(clock(), value)
                    }
                    value
                } finally { withContext(NonCancellable) { mutex.withLock { pending.remove(key) } } }
            }.also { pending[key] = it }
        }
        return task.await()
    }
}
