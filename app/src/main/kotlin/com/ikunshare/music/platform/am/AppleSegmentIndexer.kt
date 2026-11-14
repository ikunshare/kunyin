package com.ikunshare.music.platform.am

import java.io.IOException

data class AppleSegmentIndex(
    val remoteStart: Long,
    val sampleSizes: List<Long>,
    val sampleDurations: List<Long>
)

class AppleSegmentIndexer(
    private val fetch: suspend (String, Long, Long) -> ByteArray,
    private val headWindow: Int = 32 * 1024,
    private val headAttempts: Int = 3
) {
    constructor(client: AppleRangeClient = AppleRangeClient()) : this(client::fetch)

    init {
        require(headWindow > 0) { "分片索引窗口必须大于 0" }
        require(headAttempts >= 0) { "分片索引重试次数不能为负数" }
    }

    suspend fun readSegmentIndex(
        fileUrl: String,
        segment: AppleSegment,
        trex: TrexTable,
        ivSize: Int
    ): AppleSegmentIndex {
        require(segment.start >= 0L && segment.end >= segment.start) { "HLS 分片范围无效" }
        require(ivSize >= 0) { "IV 长度无效" }
        val length = try {
            Math.addExact(segment.end - segment.start, 1L)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("HLS 分片范围无效")
        }
        var window = headWindow.toLong()
        for (attempt in 0..headAttempts) {
            val end = segment.start + minOf(window - 1L, segment.end - segment.start)
            val data = fetch(fileUrl, segment.start, end)
            val info = locateFragment(data, trex, ivSize, segment.start)
            if (info != null) {
                if (info.samples.isEmpty()) throw IOException("HLS 分片没有样本")
                val remoteStart = try {
                    Math.addExact(segment.start, info.moofStart.toLong())
                } catch (_: ArithmeticException) {
                    throw IllegalArgumentException("HLS 分片偏移溢出")
                }
                return AppleSegmentIndex(
                    remoteStart,
                    info.samples.map { it.size },
                    info.samples.map { it.duration }
                )
            }
            if (data.size.toLong() >= length || attempt >= headAttempts) break
            window = if (window > Long.MAX_VALUE / 4L) length else (window * 4L).coerceAtMost(length)
        }
        throw IOException("HLS 分片头解析失败")
    }

    private fun locateFragment(
        data: ByteArray,
        trex: TrexTable,
        ivSize: Int,
        base: Long
    ): AppleFragmentInfo? {
        var position = 0
        while (true) {
            val current = completeBoxOrNull(data, position) ?: return null
            if (current.type == "moof") {
                var at = current.end.toInt()
                while (true) {
                    val next = try {
                        readMp4BoxHeader(data, at)
                    } catch (_: IllegalArgumentException) {
                        return null
                    }
                    if (next.type == "mdat") return parseAppleFragment(data, current, trex, ivSize, base)
                    if (next.type != "emsg" && next.type != "prft") {
                        throw IllegalArgumentException("HLS 分片里 moof 后面是 ${next.type}")
                    }
                    if (next.end > data.size) return null
                    at = next.end.toInt()
                }
            }
            position = current.end.toInt()
        }
    }

    private fun completeBoxOrNull(data: ByteArray, start: Int): Mp4Box? = try {
        readMp4Box(data, start)
    } catch (_: IllegalArgumentException) {
        null
    }
}
