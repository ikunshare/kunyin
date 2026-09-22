package com.ikunshare.sound.tool.cache

/**
 * 简单的内存缓存实现
 * 用于测试环境，不依赖 Android 框架
 */
class SimpleCache<K, V>(private val maxSize: Int) : Cache<K, V> {

    private val cache = LinkedHashMap<K, V>(maxSize, 0.75f, true)

    override fun get(key: K): V? = cache[key]

    override fun put(key: K, value: V) {
        cache[key] = value
        // 简单的 LRU 淘汰策略
        while (cache.size > maxSize) {
            val eldest = cache.keys.firstOrNull() ?: break
            cache.remove(eldest)
        }
    }

    override fun remove(key: K) {
        cache.remove(key)
    }

    override fun clear() {
        cache.clear()
    }

    override fun size(): Int = cache.size
}
