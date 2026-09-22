package com.ikunshare.sound.tool.cache

import android.util.LruCache

/**
 * Android LruCache 的包装器
 * 实现 Cache 接口，用于生产环境
 */
class LruCacheWrapper<K : Any, V : Any>(maxSize: Int) : Cache<K, V> {

    private val cache = LruCache<K, V>(maxSize)

    override fun get(key: K): V? = cache.get(key)

    override fun put(key: K, value: V) {
        cache.put(key, value)
    }

    override fun remove(key: K) {
        cache.remove(key)
    }

    override fun clear() {
        cache.evictAll()
    }

    override fun size(): Int = cache.size()
}
