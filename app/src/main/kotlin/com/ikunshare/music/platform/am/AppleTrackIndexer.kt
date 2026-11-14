package com.ikunshare.music.platform.am

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class AppleFragmentSlot(
    val remoteStart: Long,
    val remoteEnd: Long,
    val sampleStart: Long,
    val bytes: Long
)

data class AppleTrackIndex(
    val sampleSizes: List<Long>,
    val sampleDurations: List<Long>,
    val fragments: List<AppleFragmentSlot>,
    val sampleBytes: Long,
    val trackDuration: Long
)

class AppleTrackIndexer(
    private val indexer: AppleSegmentIndexer = AppleSegmentIndexer(),
    private val concurrency: Int = 4
) {
    init {
        require(concurrency > 0) { "分片索引并发数必须大于 0" }
    }

    suspend fun readTrackIndex(
        fileUrl: String,
        segments: List<AppleSegment>,
        trex: TrexTable,
        ivSize: Int
    ): AppleTrackIndex {
        val indexes = supervisorScope {
            val results = arrayOfNulls<AppleSegmentIndex>(segments.size)
            val workerCount = minOf(concurrency, segments.size)
            val next = AtomicInteger(workerCount)
            val failure = AtomicReference<Throwable>()
            val workers = List(workerCount) { initialPosition ->
                launch(Dispatchers.IO) {
                    var position = initialPosition
                    while (true) {
                        try {
                            results[position] = indexer.readSegmentIndex(
                                fileUrl,
                                segments[position],
                                trex,
                                ivSize
                            )
                        } catch (error: Throwable) {
                            failure.compareAndSet(null, error)
                            break
                        }
                        if (failure.get() != null) break
                        position = next.getAndIncrement()
                        if (position >= segments.size) break
                    }
                }
            }
            workers.joinAll()
            currentCoroutineContext().ensureActive()
            failure.get()?.let { throw it }
            results.map { requireNotNull(it) }
        }
        return buildAppleTrackIndex(segments, indexes)
    }
}

fun buildAppleTrackIndex(
    segments: List<AppleSegment>,
    indexes: List<AppleSegmentIndex>
): AppleTrackIndex {
    require(segments.isNotEmpty()) { "HLS 清单没有分片" }
    require(segments.size == indexes.size) { "HLS 分片索引数量不一致" }
    val sampleSizes = mutableListOf<Long>()
    val sampleDurations = mutableListOf<Long>()
    val fragments = mutableListOf<AppleFragmentSlot>()
    var sampleBytes = 0L
    var trackDuration = 0L
    for (position in segments.indices) {
        val segment = segments[position]
        val index = indexes[position]
        require(index.remoteStart >= segment.start && index.remoteStart <= segment.end) {
            "HLS 分片远端起点无效"
        }
        require(index.sampleSizes.isNotEmpty()) { "HLS 分片没有样本" }
        require(index.sampleSizes.size == index.sampleDurations.size) { "HLS 分片样本表不一致" }
        require(index.sampleSizes.all { it > 0L }) { "HLS 分片样本大小无效" }
        require(index.sampleDurations.all { it >= 0L }) { "HLS 分片样本时长无效" }
        var bytes = 0L
        for (size in index.sampleSizes) bytes = checkedAdd(bytes, size)
        for (duration in index.sampleDurations) trackDuration = checkedAdd(trackDuration, duration)
        fragments += AppleFragmentSlot(index.remoteStart, segment.end, sampleBytes, bytes)
        sampleSizes += index.sampleSizes
        sampleDurations += index.sampleDurations
        sampleBytes = checkedAdd(sampleBytes, bytes)
    }
    return AppleTrackIndex(sampleSizes, sampleDurations, fragments, sampleBytes, trackDuration)
}

private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("Apple 曲目索引溢出")
}
