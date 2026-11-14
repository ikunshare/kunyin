package com.ikunshare.music.platform.am

import java.math.BigInteger

data class AppleTrackFragment(
    val remoteStart: Long,
    val remoteEnd: Long,
    val virtualStart: Long,
    val bytes: Long
)

data class AppleTrackHeader(
    val bytes: ByteArray,
    val totalSize: Long,
    val sampleSizes: List<Long>,
    val sampleDurations: List<Long>,
    val sampleOffsets: List<Long>,
    val trackDuration: Long,
    val movieDuration: Long,
    val fragments: List<AppleTrackFragment>
)

private data class StblParts(
    val stsd: ByteArray,
    val mdia: List<ByteArray>,
    val minf: List<ByteArray>
)

private data class MoovParts(
    val sampleSizes: List<Long>,
    val sampleDurations: List<Long>,
    val offsets: List<Long>,
    val trackDuration: Long,
    val movieDuration: Long
)

fun buildAppleTrackHeader(init: AppleInitSegment, index: AppleTrackIndex): AppleTrackHeader {
    val sampleSizes = index.sampleSizes
    require(sampleSizes.isNotEmpty()) { "HLS 分片没有样本" }
    require(sampleSizes.size == index.sampleDurations.size) { "样本大小与时长数量不一致" }
    require(sampleSizes.all { it > 0L && it <= 0xffff_ffffL }) { "样本大小无效" }
    val sampleBytes = sampleSizes.fold(0L, ::checkedAdd)
    require(sampleBytes == index.sampleBytes) { "曲目样本字节数不一致" }
    var fragmentBytes = 0L
    for (fragment in index.fragments) {
        require(fragment.sampleStart == fragmentBytes) { "分片样本偏移不连续" }
        fragmentBytes = checkedAdd(fragmentBytes, fragment.bytes)
    }
    require(fragmentBytes == sampleBytes) { "分片样本字节数不一致" }

    val sampleDurations = index.sampleDurations.toMutableList()
    if (sampleDurations.none { it > 0L }) {
        // ALAC 每帧样本数固定，可以用帧长补齐；AAC 没有这个字段，说明分片真的没给时长。
        require(init.metadata.frameLength > 0L) {
            "分片没有样本时长，${init.metadata.codec} 也没有可用的帧长"
        }
        sampleDurations.fill(init.metadata.frameLength)
    }
    require(sampleDurations.all { it > 0L && it <= 0xffff_ffffL }) { "样本时长无效" }
    require(init.metadata.timescale > 0L) { "moov 缺少 mdhd timescale" }
    val trackDuration = sampleDurations.fold(0L, ::checkedAdd)
    val movieDuration = scaleDuration(
        trackDuration,
        init.metadata.movieTimescale,
        init.metadata.timescale
    )

    val root = readMp4Box(init.moov, 0)
    val parts = collectStbl(init.moov, root)
    val build = { offsets: List<Long> ->
        buildFlatMoov(init.moov, root, parts, MoovParts(
            sampleSizes,
            sampleDurations,
            offsets,
            trackDuration,
            movieDuration
        ))
    }
    val probe = build(List(sampleSizes.size) { 0L })
    val base = init.ftyp.size.toLong() + probe.size.toLong()
    require(base <= Int.MAX_VALUE) { "Apple Music 文件头过大" }
    val totalSize = checkedAdd(base, sampleBytes)
    require(totalSize <= 0xffff_ffffL) { "Apple Music 曲目过大，超出 32 位偏移" }
    val offsets = mutableListOf<Long>()
    var cursor = base
    for (size in sampleSizes) {
        offsets += cursor
        cursor = checkedAdd(cursor, size)
    }
    val builtMoov = build(offsets)
    require(builtMoov.size == probe.size) { "合成 moov 长度不稳定" }
    val fragments = index.fragments.map {
        AppleTrackFragment(it.remoteStart, it.remoteEnd, checkedAdd(base, it.sampleStart), it.bytes)
    }
    return AppleTrackHeader(
        bytes = init.ftyp + builtMoov,
        totalSize = totalSize,
        sampleSizes = sampleSizes,
        sampleDurations = sampleDurations,
        sampleOffsets = offsets,
        trackDuration = trackDuration,
        movieDuration = movieDuration,
        fragments = fragments
    )
}

private fun collectStbl(moov: ByteArray, root: Mp4Box): StblParts {
    val trak = child(moov, root, "trak")
    val mdia = child(moov, trak, "mdia")
    val minf = child(moov, mdia, "minf")
    val stbl = child(moov, minf, "stbl")
    val stsd = child(moov, stbl, "stsd")
    val parts = { parent: Mp4Box, skip: Set<String> ->
        mp4Children(moov, parent)
            .filter { it.type !in skip }
            .map { moov.copyOfRange(it.start, it.end.toInt()) }
            .toList()
    }
    return StblParts(
        stsd = moov.copyOfRange(stsd.start, stsd.end.toInt()),
        mdia = parts(mdia, setOf("mdhd", "minf")),
        minf = parts(minf, setOf("stbl"))
    )
}

private fun buildFlatMoov(
    moov: ByteArray,
    root: Mp4Box,
    parts: StblParts,
    data: MoovParts
): ByteArray {
    require(data.sampleSizes.size == data.sampleDurations.size &&
        data.sampleSizes.size == data.offsets.size) { "样本表数量不一致" }
    val mvhd = child(moov, root, "mvhd")
    val trak = child(moov, root, "trak")
    val tkhd = child(moov, trak, "tkhd")
    val mdia = child(moov, trak, "mdia")
    val mdhd = child(moov, mdia, "mdhd")

    val runs = mutableListOf<Pair<Long, Long>>()
    for (duration in data.sampleDurations) {
        val last = runs.lastOrNull()
        if (last?.second == duration) runs[runs.lastIndex] = last.copy(first = last.first + 1)
        else runs += 1L to duration
    }
    val stts = fullMp4Box(
        "stts",
        u32(runs.size),
        *runs.flatMap { listOf(u32(it.first), u32(it.second)) }.toTypedArray()
    )
    val stsz = fullMp4Box(
        "stsz",
        u32(0),
        u32(data.sampleSizes.size),
        *data.sampleSizes.map(::u32).toTypedArray()
    )
    val stsc = fullMp4Box("stsc", u32(1), u32(1), u32(1), u32(1))
    val stco = fullMp4Box("stco", u32(data.offsets.size), *data.offsets.map(::u32).toTypedArray())
    val stbl = makeMp4Box("stbl", parts.stsd, stts, stsz, stsc, stco)
    val minf = makeMp4Box("minf", *parts.minf.toTypedArray(), stbl)
    val mdiaBox = makeMp4Box(
        "mdia",
        withDuration(moov, mdhd, data.trackDuration),
        *parts.mdia.toTypedArray(),
        minf
    )
    val trakBox = makeMp4Box("trak", withDuration(moov, tkhd, data.movieDuration), mdiaBox)
    return makeMp4Box("moov", withDuration(moov, mvhd, data.movieDuration), trakBox)
}

private fun withDuration(data: ByteArray, target: Mp4Box, duration: Long): ByteArray {
    require(duration >= 0L) { "MP4 时长无效" }
    requireBoxBytes(data, target, target.body, 1)
    val output = data.copyOfRange(target.start, target.end.toInt())
    val version = data[target.body].toInt() and 0xff
    val offset = when (target.type) {
        "mvhd", "mdhd" -> target.body + if (version == 1) 24 else 16
        "tkhd" -> target.body + if (version == 1) 28 else 20
        else -> throw IllegalArgumentException("不支持改写 ${target.type} 的时长")
    }
    if (version == 1) {
        requireBoxBytes(data, target, offset, 8)
        putU64(output, offset - target.start, duration)
    } else {
        requireBoxBytes(data, target, offset, 4)
        putU32(output, offset - target.start, duration)
    }
    return output
}

private fun scaleDuration(duration: Long, movieTimescale: Long, timescale: Long): Long {
    if (movieTimescale <= 0L) return duration
    val numerator = BigInteger.valueOf(duration).multiply(BigInteger.valueOf(movieTimescale))
    val divisor = BigInteger.valueOf(timescale)
    val quotient = numerator.divide(divisor)
    val remainder = numerator.remainder(divisor)
    val rounded = if (remainder.shiftLeft(1) >= divisor) quotient.add(BigInteger.ONE) else quotient
    return try {
        rounded.longValueExact()
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Apple Music 曲目时长溢出")
    }
}

private fun child(data: ByteArray, parent: Mp4Box, type: String): Mp4Box =
    mp4Children(data, parent).firstOrNull { it.type == type }
        ?: throw IllegalArgumentException("${parent.type} 里没有 $type")

private fun fullMp4Box(type: String, vararg parts: ByteArray): ByteArray =
    makeMp4Box(type, ByteArray(4), *parts)

private fun u32(value: Int): ByteArray = u32(value.toLong())

private fun u32(value: Long): ByteArray {
    require(value >= 0L && value <= 0xffff_ffffL) { "MP4 32 位字段越界" }
    return byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )
}

private fun putU32(data: ByteArray, at: Int, value: Long) {
    u32(value).copyInto(data, at)
}

private fun putU64(data: ByteArray, at: Int, value: Long) {
    repeat(8) { data[at + it] = (value ushr (56 - it * 8)).toByte() }
}

private fun requireBoxBytes(data: ByteArray, box: Mp4Box, at: Int, count: Int) {
    require(at >= box.start && at >= 0 && count >= 0 && at <= box.end &&
        count <= box.end - at && box.end <= data.size) { "${box.type} 字段不完整" }
}

private fun checkedAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("Apple Music 曲目索引溢出")
}
