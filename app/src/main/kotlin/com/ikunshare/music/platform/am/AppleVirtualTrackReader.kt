package com.ikunshare.music.platform.am

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.coroutines.coroutineContext

private const val CHUNK_BYTES = 256 * 1024

class AppleVirtualTrackReader(
    private val prepared: PreparedAppleTrack,
    private val fetch: suspend (String, Long, Long) -> ByteArray,
    private val poolLimit: Long = 24 * 1024 * 1024,
    private val recording: AppleTrackRecording? = null
) {
    constructor(
        prepared: PreparedAppleTrack,
        client: AppleRangeClient = AppleRangeClient(),
        poolLimit: Long = 24 * 1024 * 1024,
        recording: AppleTrackRecording? = null
    ) : this(prepared, client::fetch, poolLimit, recording)

    private data class CachedFragment(val data: ByteArray)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheMutex = Mutex()
    private val closeMutex = Mutex()
    private val cache = LinkedHashMap<Int, CachedFragment>(16, 0.75f, true)
    private val inflight = mutableMapOf<Int, Deferred<ByteArray>>()
    private var poolBytes = 0L

    @Volatile
    private var closed = false

    val size: Long get() = prepared.header.totalSize

    init {
        require(poolLimit > 0L) { "分片缓存上限必须大于 0" }
        require(prepared.playlist.segments.size == prepared.header.fragments.size) {
            "HLS 分片数与虚拟索引不一致"
        }
        var expected = prepared.header.bytes.size.toLong()
        for (fragment in prepared.header.fragments) {
            require(fragment.virtualStart == expected && fragment.bytes > 0L &&
                expected <= Long.MAX_VALUE - fragment.bytes) { "虚拟分片范围无效" }
            expected += fragment.bytes
        }
        require(expected == size) { "虚拟分片长度与文件大小不一致" }
    }

    suspend fun read(start: Long, maxBytes: Int = CHUNK_BYTES): ByteArray {
        require(maxBytes > 0) { "读取长度必须大于 0" }
        ensureOpen()
        coroutineContext.ensureActive()
        val from = start.coerceIn(0L, size)
        val end = from + minOf(maxBytes.toLong(), CHUNK_BYTES.toLong(), size - from)
        val output = ByteArray((end - from).toInt())
        var position = from
        while (position < end) {
            if (position < prepared.header.bytes.size) {
                val count = minOf(prepared.header.bytes.size.toLong() - position, end - position)
                prepared.header.bytes.copyInto(
                    output,
                    (position - from).toInt(),
                    position.toInt(),
                    (position + count).toInt()
                )
                position += count
                continue
            }
            val index = fragmentAt(position)
            val slot = prepared.header.fragments[index]
            val plain = loadFragment(index)
            val offset = position - slot.virtualStart
            if (offset < 0L || offset > plain.size) throw IOException("Apple Music 虚拟样本偏移无效")
            val available = plain.size.toLong() - offset
            if (available == 0L) {
                position = slot.virtualStart + slot.bytes
                continue
            }
            val count = minOf(available, end - position)
            plain.copyInto(
                output,
                (position - from).toInt(),
                offset.toInt(),
                (offset + count).toInt()
            )
            position += count
        }
        return output
    }

    suspend fun close() {
        closeMutex.withLock {
            if (closed) return@withLock
            closed = true
            scope.cancel()
            scope.coroutineContext[Job]?.children?.toList()?.joinAll()
            cacheMutex.withLock {
                cache.clear()
                inflight.clear()
                poolBytes = 0L
            }
            recording?.close()
            prepared.close()
        }
    }

    private fun fragmentAt(position: Long): Int {
        val fragments = prepared.header.fragments
        var low = 0
        var high = fragments.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (fragments[middle].virtualStart <= position) low = middle
            else high = middle - 1
        }
        return low
    }

    private suspend fun loadFragment(index: Int): ByteArray {
        val deferred = cacheMutex.withLock {
            ensureOpen()
            val cached = cache[index]
            if (cached != null) return cached.data
            inflight.getOrPut(index) { scope.async { fetchFragment(index) } }
        }
        try {
            return deferred.await()
        } finally {
            if (deferred.isCompleted) {
                withContext(NonCancellable) {
                    cacheMutex.withLock { inflight.remove(index, deferred) }
                }
            }
        }
    }

    private suspend fun fetchFragment(index: Int): ByteArray {
        val slot = prepared.header.fragments[index]
        val segment = prepared.playlist.segments[index]
        val template = prepared.templates[segment.keyUri] ?: throw IOException("缺少 Temari 密钥模板")
        val data = fetch(prepared.fileUrl, slot.remoteStart, slot.remoteEnd)
        currentCoroutineContext().ensureActive()
        val moof = readMp4Box(data, 0)
        require(moof.type == "moof") { "Apple Music 分片起点不是 moof" }
        val info = parseAppleFragment(data, moof, prepared.init.trex, prepared.init.ivSize, slot.remoteStart)
        val plain = decryptAppleSamples(data, info, template)
        require(plain.size.toLong() == slot.bytes) { "Apple Music 分片解密后长度不符" }
        recording?.record(index, plain)
        cacheMutex.withLock {
            if (!closed) putCache(index, plain)
        }
        return plain
    }

    private fun putCache(index: Int, data: ByteArray) {
        cache.remove(index)?.let { poolBytes -= it.data.size }
        cache[index] = CachedFragment(data)
        poolBytes += data.size
        while (poolBytes > poolLimit && cache.size > 1) {
            val iterator = cache.entries.iterator()
            val oldest = iterator.next()
            poolBytes -= oldest.value.data.size
            iterator.remove()
        }
    }

    private fun ensureOpen() {
        if (closed) throw IOException("Apple Music 流已释放")
    }
}
