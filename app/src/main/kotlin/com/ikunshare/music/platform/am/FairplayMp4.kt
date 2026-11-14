package com.ikunshare.music.platform.am

data class Mp4Box(
    val type: String,
    val start: Int,
    val end: Long,
    val header: Int,
    val body: Int
)

data class TrexInfo(
    val defaultSampleSize: Long,
    val defaultSampleDuration: Long
)

typealias TrexTable = Map<Long, TrexInfo>

data class AppleSubsample(
    val clearBytes: Int,
    val protectedBytes: Long
)

data class AppleFragmentSample(
    val offset: Long,
    val size: Long,
    val duration: Long
)

data class AppleFragmentInfo(
    val trackId: Long,
    val moofStart: Int,
    val payloadStart: Int,
    val payloadSize: Long,
    val samples: List<AppleFragmentSample>,
    val subsamples: List<List<AppleSubsample>>
)

private data class MutableSample(
    var size: Long,
    var duration: Long
)

private data class TrunInfo(
    val dataOffset: Int,
    val samples: List<MutableSample>
)

private data class TrafInfo(
    val trackId: Long,
    val baseDataOffset: Long?,
    val defaultSampleSize: Long,
    val defaultSampleDuration: Long,
    val truns: List<TrunInfo>,
    val subsamples: List<List<AppleSubsample>>
)

private val containers = setOf(
    "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "sinf", "schi"
)
private val piffSencUuid = byteArrayOf(
    0xa2.toByte(), 0x39, 0x4f, 0x52, 0x5a, 0x9b.toByte(), 0x4f, 0x14,
    0xa2.toByte(), 0x44, 0x6c, 0x42, 0x7c, 0x64, 0x8d.toByte(), 0xf4.toByte()
)

fun readMp4Box(data: ByteArray, start: Int, limit: Int = data.size): Mp4Box =
    readBox(data, start, limit, false)

internal fun readMp4BoxHeader(data: ByteArray, start: Int): Mp4Box =
    readBox(data, start, data.size, true)

private fun readBox(data: ByteArray, start: Int, limit: Int, headerOnly: Boolean): Mp4Box {
    require(limit >= 0 && limit <= data.size) { "MP4 盒边界无效" }
    require(start >= 0 && start <= limit - 8) { "MP4 盒头不完整" }
    val size32 = u32(data, start, limit)
    val type = String(data, start + 4, 4, Charsets.ISO_8859_1)
    var size = size32
    var header = 8
    when (size32) {
        1L -> {
            require(start <= limit - 16) { "$type 扩展盒头不完整" }
            size = u64(data, start + 8, limit)
            require(size >= 0L) { "$type 盒长度超出支持范围" }
            header = 16
        }
        0L -> size = (limit - start).toLong()
    }
    require(size >= header) { "$type 盒长度越界" }
    require(size <= Long.MAX_VALUE - start) { "$type 盒长度溢出" }
    val end = start + size
    require(headerOnly || end <= limit) { "$type 盒长度越界" }
    return Mp4Box(type, start, end, header, start + header)
}

fun mp4Children(data: ByteArray, parent: Mp4Box, from: Int = parent.body): Sequence<Mp4Box> = sequence {
    require(parent.end <= data.size) { "${parent.type} 容器不完整" }
    require(from >= 0 && from <= parent.end) { "${parent.type} 子盒起点无效" }
    var position = from
    while (parent.end - position >= 8L) {
        val child = readMp4Box(data, position, parent.end.toInt())
        yield(child)
        position = child.end.toInt()
    }
}

internal fun isMp4Container(type: String): Boolean = type in containers

fun makeMp4Box(type: String, vararg parts: ByteArray): ByteArray {
    require(type.length == 4 && type.all { it.code <= 0xff }) { "MP4 盒类型必须是四个单字节字符" }
    val size = 8L + parts.sumOf { it.size.toLong() }
    require(size <= Int.MAX_VALUE) { "MP4 盒过大: $size" }
    val output = ByteArray(size.toInt())
    output[0] = (size ushr 24).toByte()
    output[1] = (size ushr 16).toByte()
    output[2] = (size ushr 8).toByte()
    output[3] = size.toByte()
    type.toByteArray(Charsets.ISO_8859_1).copyInto(output, 4)
    var offset = 8
    for (part in parts) {
        part.copyInto(output, offset)
        offset += part.size
    }
    return output
}

fun findMp4Boxes(data: ByteArray, parent: Mp4Box, type: String, from: Int = parent.body): List<Mp4Box> {
    val result = mutableListOf<Mp4Box>()
    for (child in mp4Children(data, parent, from)) {
        if (child.type == type) result += child
        if (child.type in containers || child.type == "stsd" || child.type == "enca") {
            val nestedFrom = when (child.type) {
                "stsd" -> child.body + 8
                "enca" -> child.body + 28
                else -> child.body
            }
            result += findMp4Boxes(data, child, type, nestedFrom)
        }
    }
    return result
}

fun readTrexTable(data: ByteArray, moov: Mp4Box): TrexTable {
    val table = mutableMapOf<Long, TrexInfo>()
    for (trex in findMp4Boxes(data, moov, "trex")) {
        val limit = trex.end.toInt()
        requireBytes(data, trex.body, 20, limit)
        table[u32(data, trex.body + 4, limit)] = TrexInfo(
            defaultSampleDuration = u32(data, trex.body + 12, limit),
            defaultSampleSize = u32(data, trex.body + 16, limit)
        )
    }
    return table
}

fun parseAppleFragment(
    data: ByteArray,
    moof: Mp4Box,
    trex: TrexTable,
    ivSize: Int,
    base: Long
): AppleFragmentInfo {
    require(moof.type == "moof") { "Apple Music 分片起点不是 moof" }
    require(ivSize >= 0) { "IV 长度无效" }
    require(base >= 0L) { "MP4 基准偏移无效" }
    require(moof.end <= Int.MAX_VALUE) { "moof 偏移超出支持范围" }
    var next = readMp4BoxHeader(data, moof.end.toInt())
    while (next.type == "emsg" || next.type == "prft") {
        require(next.end <= Int.MAX_VALUE) { "${next.type} 偏移超出支持范围" }
        next = readMp4BoxHeader(data, next.end.toInt())
    }
    require(next.type == "mdat") { "moof 后缺少 mdat" }

    val samples = mutableListOf<AppleFragmentSample>()
    val subsamples = mutableListOf<List<AppleSubsample>>()
    var trackId = 0L
    for (traf in mp4Children(data, moof)) {
        if (traf.type != "traf") continue
        val info = parseTraf(data, traf, trex, ivSize)
        if (info.truns.isEmpty()) continue
        trackId = info.trackId
        var sampleIndex = 0
        for (trun in info.truns) {
            val origin = info.baseDataOffset ?: checkedAdd(base, moof.start.toLong())
            var offset = checkedAdd(origin, trun.dataOffset.toLong()) - base
            for (sample in trun.samples) {
                samples += AppleFragmentSample(offset, sample.size, sample.duration)
                subsamples += info.subsamples.getOrElse(sampleIndex++) { emptyList() }
                offset = checkedAdd(offset, sample.size)
            }
        }
    }
    return AppleFragmentInfo(
        trackId = trackId,
        moofStart = moof.start,
        payloadStart = next.body,
        payloadSize = next.end - next.body,
        samples = samples,
        subsamples = subsamples
    )
}

private fun parseTraf(data: ByteArray, traf: Mp4Box, trex: TrexTable, ivSize: Int): TrafInfo {
    var trackId = 0L
    var baseDataOffset: Long? = null
    var defaultSampleSize = 0L
    var defaultSampleDuration = 0L
    val truns = mutableListOf<TrunInfo>()
    var senc: Mp4Box? = null

    for (child in mp4Children(data, traf)) {
        val limit = child.end.toInt()
        when (child.type) {
            "tfhd" -> {
                val tfhdFlags = flags(data, child.body, limit)
                trackId = u32(data, child.body + 4, limit)
                var at = child.body + 8
                requireBytes(data, at, 0, limit)
                if (tfhdFlags and 1L != 0L) {
                    val offset = u64(data, at, limit)
                    require(offset >= 0L) { "tfhd 基准偏移超出支持范围" }
                    baseDataOffset = offset
                    at += 8
                }
                if (tfhdFlags and 2L != 0L) {
                    requireBytes(data, at, 4, limit)
                    at += 4
                }
                if (tfhdFlags and 8L != 0L) {
                    defaultSampleDuration = u32(data, at, limit)
                    at += 4
                }
                if (tfhdFlags and 16L != 0L) {
                    defaultSampleSize = u32(data, at, limit)
                }
            }
            "trun" -> {
                val trunFlags = flags(data, child.body, limit)
                val count = u32(data, child.body + 4, limit)
                var at = child.body + 8
                val dataOffset = if (trunFlags and 1L != 0L) {
                    i32(data, at, limit).also { at += 4 }
                } else 0
                if (trunFlags and 4L != 0L) {
                    requireBytes(data, at, 4, limit)
                    at += 4
                }
                val fieldBytes = listOf(0x100L, 0x200L, 0x400L, 0x800L)
                    .count { trunFlags and it != 0L } * 4
                val available = (limit - at).coerceAtLeast(0)
                val maximumCount = if (fieldBytes == 0) data.size.toLong() else available / fieldBytes.toLong()
                require(count <= maximumCount && count <= Int.MAX_VALUE) { "trun 样本数越界" }
                val samples = mutableListOf<MutableSample>()
                repeat(count.toInt()) {
                    var duration = 0L
                    var size = 0L
                    if (trunFlags and 0x100L != 0L) {
                        duration = u32(data, at, limit)
                        at += 4
                    }
                    if (trunFlags and 0x200L != 0L) {
                        size = u32(data, at, limit)
                        at += 4
                    }
                    if (trunFlags and 0x400L != 0L) at += 4
                    if (trunFlags and 0x800L != 0L) at += 4
                    samples += MutableSample(size, duration)
                }
                truns += TrunInfo(dataOffset, samples)
            }
            "senc" -> senc = child
            "uuid" -> {
                requireBytes(data, child.body, 16, limit)
                if (data.copyOfRange(child.body, child.body + 16).contentEquals(piffSencUuid)) {
                    senc = child
                }
            }
        }
    }

    val defaults = trex[trackId]
    if (defaultSampleSize == 0L) defaultSampleSize = defaults?.defaultSampleSize ?: 0L
    if (defaultSampleDuration == 0L) defaultSampleDuration = defaults?.defaultSampleDuration ?: 0L
    for (trun in truns) {
        for (sample in trun.samples) {
            if (sample.size == 0L) sample.size = defaultSampleSize
            if (sample.duration == 0L) sample.duration = defaultSampleDuration
        }
    }
    for (index in truns.indices) {
        val current = truns[index]
        if (current.samples.isEmpty() || current.samples.all { it.size > 0L } || index + 1 >= truns.size) {
            continue
        }
        val delta = truns[index + 1].dataOffset.toLong() - current.dataOffset.toLong()
        require(delta >= 0L && delta % current.samples.size == 0L) { "trun 样本大小无法推导" }
        val size = delta / current.samples.size
        for (sample in current.samples) {
            if (sample.size == 0L) sample.size = size
        }
    }
    val sampleCount = truns.sumOf { it.samples.size }
    val subsamples = senc?.let { parseSenc(data, it, sampleCount, ivSize) }
        ?: List(sampleCount) { emptyList() }
    return TrafInfo(
        trackId,
        baseDataOffset,
        defaultSampleSize,
        defaultSampleDuration,
        truns,
        subsamples
    )
}

private fun parseSenc(
    data: ByteArray,
    box: Mp4Box,
    sampleCount: Int,
    ivSize: Int
): List<List<AppleSubsample>> {
    val at = box.body + if (box.type == "uuid") 16 else 0
    val limit = box.end.toInt()
    val boxFlags = flags(data, at, limit)
    val count = u32(data, at + 4, limit)
    require(count == sampleCount.toLong()) { "senc 与 trun 样本数不一致" }
    if (boxFlags and 2L == 0L) return List(sampleCount) { emptyList() }
    requireBytes(data, at, 8, limit)
    val bytes = data.copyOfRange(at + 8, limit)
    val candidates = linkedSetOf(ivSize, 0, 8, 16)
    for (candidate in candidates) {
        var offset = 0
        val result = mutableListOf<List<AppleSubsample>>()
        var valid = true
        for (index in 0 until sampleCount) {
            if (candidate > bytes.size - offset) {
                valid = false
                break
            }
            offset += candidate
            if (bytes.size - offset < 2) {
                valid = false
                break
            }
            val count = u16(bytes, offset)
            offset += 2
            if (count > (bytes.size - offset) / 6) {
                valid = false
                break
            }
            val patterns = mutableListOf<AppleSubsample>()
            repeat(count) {
                patterns += AppleSubsample(u16(bytes, offset), u32(bytes, offset + 2, bytes.size))
                offset += 6
            }
            result += patterns
        }
        if (valid && offset == bytes.size && result.size == sampleCount) return result
    }
    throw IllegalArgumentException("senc 子样本信息解析失败")
}

private fun flags(data: ByteArray, at: Int, limit: Int): Long = u32(data, at, limit) and 0xff_ffffL

private fun u16(data: ByteArray, at: Int): Int =
    (data[at].toInt() and 0xff shl 8) or (data[at + 1].toInt() and 0xff)

private fun u32(data: ByteArray, at: Int, limit: Int): Long {
    requireBytes(data, at, 4, limit)
    var value = 0L
    repeat(4) { value = (value shl 8) or (data[at + it].toLong() and 0xffL) }
    return value
}

private fun i32(data: ByteArray, at: Int, limit: Int): Int = u32(data, at, limit).toInt()

private fun u64(data: ByteArray, at: Int, limit: Int): Long {
    requireBytes(data, at, 8, limit)
    var value = 0L
    repeat(8) { value = (value shl 8) or (data[at + it].toLong() and 0xffL) }
    return value
}

private fun requireBytes(data: ByteArray, at: Int, count: Int, limit: Int) {
    require(at >= 0 && count >= 0 && limit <= data.size && at <= limit && count <= limit - at) {
        "MP4 字段不完整"
    }
}

private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("MP4 样本偏移溢出")
}
