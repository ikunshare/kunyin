package com.ikunshare.sound.tool.cache

/**
 * 通用缓存接口
 * 用于抽象缓存实现，支持测试时注入不同的缓存实现
 */
interface Cache<K, V> {
    /**
     * 获取缓存值
     * @param key 缓存键
     * @return 缓存值，如果不存在返回 null
     */
    fun get(key: K): V?

    /**
     * 存储缓存值
     * @param key 缓存键
     * @param value 缓存值
     */
    fun put(key: K, value: V)

    /**
     * 移除指定缓存条目
     * @param key 缓存键
     */
    fun remove(key: K)

    /**
     * 清除所有缓存
     */
    fun clear()

    /**
     * 获取缓存大小
     */
    fun size(): Int
}
