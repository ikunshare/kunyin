package com.ikunshare.music.platform.am

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException

class AppleCacheStats(
    val bytes: Long,
    val count: Int
)

class AppleAudioCache(
    internal val rootDir: File,
    private val limitBytes: Long
) {
    internal val enabled: Boolean get() = limitBytes > 0L
    private data class CacheRecord(val path: String, val size: Long, val usedAt: Long)

    private val audioDir = File(rootDir, "apple-audio")
    private val audioDirCanonical by lazy { runCatching { audioDir.canonicalFile }.getOrDefault(audioDir) }
    private val indexFile = File(rootDir, "apple-audio-index.json")
    private val gson = Gson()
    private val records = LinkedHashMap<String, CacheRecord>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    fun get(key: String): File? {
        if (limitBytes <= 0L) return null
        ensureLoaded()
        val record = records[key] ?: return null
        val file = File(record.path)
        if (!validFile(file)) {
            records.remove(key)
            dirty = true
            persist()
            return null
        }
        records.remove(key)
        records[key] = record.copy(usedAt = System.currentTimeMillis())
        dirty = true
        return file
    }

    @Synchronized
    fun store(key: String, source: File): File {
        require(key.isNotBlank()) { "Apple 缓存键不能为空" }
        ensureLoaded()
        if (!source.isFile) throw IOException("Apple Music 缓存源文件不存在")
        if (!audioDir.isDirectory && !audioDir.mkdirs()) throw IOException("Apple Music 缓存目录不可用")
        val target = File(audioDir, "${safeName(key)}.m4a")
        if (target.exists() && !target.delete()) throw IOException("Apple Music 缓存目标不可写")
        if (!source.renameTo(target)) throw IOException("Apple Music 缓存文件提交失败")
        val size = target.length()
        if (size <= 0L) {
            target.delete()
            throw IOException("Apple Music 处理后音频为空")
        }
        if (limitBytes > 0L && size <= limitBytes) {
            records.remove(key)
            records[key] = CacheRecord(target.absolutePath, size, System.currentTimeMillis())
            dirty = true
            evict()
        }
        persist()
        return target
    }

    @Synchronized
    fun drop(key: String) {
        ensureLoaded()
        records.remove(key)?.let {
            File(it.path).delete()
            dirty = true
        }
        persist()
    }

    @Synchronized
    fun stats(): AppleCacheStats {
        ensureLoaded()
        return AppleCacheStats(records.values.sumOf { it.size }, records.size)
    }

    @Synchronized
    fun clear() {
        ensureLoaded()
        records.clear()
        audioDir.deleteRecursively()
        dirty = true
        persist()
    }

    @Synchronized
    fun applyLimit() {
        if (limitBytes <= 0L) {
            clear()
        } else {
            ensureLoaded()
            evict()
        }
    }

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!indexFile.isFile) return
        runCatching {
            val entries = JsonParser.parseString(indexFile.readText()).asJsonArray
            for (entry in entries) {
                val pair = entry as? JsonArray ?: continue
                if (pair.size() != 2 || !pair[0].isJsonPrimitive || !pair[1].isJsonObject) continue
                val data = pair[1].asJsonObject
                val path = data.get("path")?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                val size = data.get("size")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
                val usedAt = data.get("usedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                if (validFile(File(path))) records[pair[0].asString] = CacheRecord(path, size, usedAt)
            }
        }
    }

    private fun validFile(file: File): Boolean = runCatching {
        val canonical = file.canonicalFile
        canonical.parentFile == audioDirCanonical && canonical.isFile && canonical.length() > 0L
    }.getOrDefault(false)

    private fun evict() {
        var total = records.values.sumOf { it.size }
        val iterator = records.entries.iterator()
        while (total > limitBytes && iterator.hasNext()) {
            val record = iterator.next()
            total -= record.value.size
            File(record.value.path).delete()
            iterator.remove()
            dirty = true
        }
        persist()
    }

    private fun persist() {
        if (!dirty) return
        runCatching {
            indexFile.parentFile?.mkdirs()
            val entries = JsonArray()
            for ((key, record) in records) {
                val data = com.google.gson.JsonObject().apply {
                    addProperty("path", record.path)
                    addProperty("size", record.size)
                    addProperty("usedAt", record.usedAt)
                }
                entries.add(JsonArray().apply {
                    add(key)
                    add(data)
                })
            }
            val temporary = File(indexFile.path + ".tmp")
            dirty = false
            temporary.writeText(gson.toJson(entries))
            if (indexFile.exists() && !indexFile.delete()) return@runCatching
            if (!temporary.renameTo(indexFile)) temporary.delete()
        }
    }

    private fun safeName(key: String): String = key.replace(Regex("[^A-Za-z0-9_-]"), "-")
}
