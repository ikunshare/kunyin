package com.ikunshare.sound.tool.cache

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import com.ikunshare.sound.model.MusicItem
import java.io.File
import java.lang.reflect.Type

/**
 * 磁盘持久化 LRU 缓存
 *
 * 内存使用 LinkedHashMap(accessOrder=true) 做 LRU，
 * 磁盘使用单个 JSON 文件持久化。
 * put/remove/clear 时同步写盘（调用方已在 Dispatchers.IO）。
 */
class DiskCache<V : Any>(
    private val cacheFile: File,
    private val maxSize: Int,
    private val valueType: Type,
    private val gson: Gson
) : Cache<String, V> {

    private val map = LinkedHashMap<String, V>(16, 0.75f, true)

    init {
        loadFromDisk()
    }

    @Synchronized
    override fun get(key: String): V? = map[key]

    @Synchronized
    override fun put(key: String, value: V) {
        map[key] = value
        trimToSize()
        saveToDisk()
    }

    @Synchronized
    override fun remove(key: String) {
        if (map.remove(key) != null) saveToDisk()
    }

    @Synchronized
    override fun clear() {
        map.clear()
        cacheFile.delete()
    }

    @Synchronized
    override fun size(): Int = map.size

    private fun trimToSize() {
        val it = map.entries.iterator()
        while (map.size > maxSize && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    private fun loadFromDisk() {
        if (!cacheFile.exists()) return
        try {
            val obj = JsonParser.parseString(cacheFile.readText()).asJsonObject
            for ((k, v) in obj.entrySet()) {
                map[k] = gson.fromJson(v, valueType)
            }
        } catch (_: Exception) {
            cacheFile.delete()
        }
    }

    private fun saveToDisk() {
        try {
            cacheFile.parentFile?.mkdirs()
            val obj = JsonObject()
            for ((k, v) in map) {
                obj.add(k, gson.toJsonTree(v, valueType))
            }
            cacheFile.writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * 创建支持 MusicItem 多态序列化的 Gson 实例
         */
        fun createCacheGson(): Gson = GsonBuilder()
            .registerTypeHierarchyAdapter(MusicItem::class.java, MusicItemTypeAdapter())
            .create()
    }
}

/**
 * MusicItem 自定义 Gson TypeAdapter
 *
 * MusicItem 是抽象类，委托给 MusicItem.toJson() / MusicItem.fromJson() 进行序列化。
 */
class MusicItemTypeAdapter : JsonSerializer<MusicItem>, JsonDeserializer<MusicItem> {
    override fun serialize(
        src: MusicItem,
        typeOfSrc: Type,
        context: JsonSerializationContext
    ): JsonElement = src.toJson()

    override fun deserialize(
        json: JsonElement,
        typeOfT: Type,
        context: JsonDeserializationContext
    ): MusicItem = MusicItem.fromJson(json.asJsonObject)
}
