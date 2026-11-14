package com.ikunshare.music.platform.am

data class AppleTrackMetadata(
    val timescale: Long,
    val movieTimescale: Long,
    val sampleRate: Int,
    /** ALAC 帧长；AAC 没有这个字段，为 0。 */
    val frameLength: Long,
    /** 样本描述里的编码格式：ALAC 流式播放自身，AAC 走整曲解密直出。 */
    val codec: String = "alac",
    /** `alac` 样本描述下的配置盒（含盒头），交给原生解码器初始化；AAC 为 null。 */
    val alacCookie: ByteArray? = null
) {
    val isAlac: Boolean get() = codec == "alac"
}

data class AppleInitSegment(
    val ftyp: ByteArray,
    val moov: ByteArray,
    val trex: TrexTable,
    val ivSize: Int,
    val metadata: AppleTrackMetadata
)

fun parseAppleInitSegment(data: ByteArray): AppleInitSegment {
    val ftyp = readMp4Box(data, 0)
    require(ftyp.type == "ftyp") { "HLS 初始化段开头不是 ftyp" }
    val moov = readMp4Box(data, ftyp.end.toInt())
    require(moov.type == "moov") { "HLS 初始化段里没有 moov" }
    val transformed = transformAppleInit(data, moov)
    val metadata = readTrackMetadata(transformed)
    require(metadata.timescale > 0L) { "moov 缺少 mdhd timescale" }
    return AppleInitSegment(
        ftyp = data.copyOfRange(ftyp.start, ftyp.end.toInt()),
        moov = transformed,
        trex = readTrexTable(data, moov),
        ivSize = readAppleIvSize(data, moov),
        metadata = metadata
    )
}

fun transformAppleInit(data: ByteArray, parent: Mp4Box): ByteArray {
    if (parent.type == "pssh") return ByteArray(0)
    if (parent.type == "sbgp" || parent.type == "sgpd") {
        if (readType(data, parent, parent.body + 4) in setOf("seam", "seig")) return ByteArray(0)
    }
    if (parent.type == "enca") {
        requireBoxBytes(parent, parent.body, 28)
        var format = "mp4a"
        val body = mutableListOf<ByteArray>()
        for (child in mp4Children(data, parent, parent.body + 28)) {
            if (child.type == "sinf") {
                findMp4Boxes(data, child, "frma").firstOrNull()?.let {
                    format = readType(data, it, it.body)
                }
            } else {
                body += transformAppleInit(data, child)
            }
        }
        return makeMp4Box(
            format,
            data.copyOfRange(parent.body, parent.body + 28),
            *body.toTypedArray()
        )
    }
    if (parent.type == "stsd") {
        requireBoxBytes(parent, parent.body, 8)
        val entries = mp4Children(data, parent, parent.body + 8).toList()
        require(entries.isNotEmpty()) { "stsd 没有样本描述" }
        val header = ByteArray(8)
        data.copyInto(header, 0, parent.body, parent.body + 4)
        putU32(header, 4, entries.size)
        return makeMp4Box("stsd", header, *entries.map { transformAppleInit(data, it) }.toTypedArray())
    }
    if (isMp4Container(parent.type)) {
        return makeMp4Box(
            parent.type,
            *mp4Children(data, parent).map { transformAppleInit(data, it) }.toList().toTypedArray()
        )
    }
    return data.copyOfRange(parent.start, parent.end.toInt())
}

internal fun readAppleIvSize(data: ByteArray, moov: Mp4Box): Int {
    val tenc = findMp4Boxes(data, moov, "tenc").firstOrNull() ?: return 0
    requireBoxBytes(tenc, tenc.body, 8)
    val version = data[tenc.body].toInt() and 0xff
    if (version > 0 && (data[tenc.body + 5].toInt() and 15) != 0) {
        throw IllegalArgumentException("暂不支持跳块加密的 Apple Music 码流")
    }
    return data[tenc.body + 7].toInt() and 0xff
}

/**
 * 读取初始化段里的轨道元数据。
 *
 * Apple 的两档音源编码不同：无损档是 ALAC，`320k` 档是 AAC。两者都能流式解密，
 * 但只有 ALAC 需要按帧长补样本时长，所以这里把编码格式一并返回，由调用方分流。
 */
fun readTrackMetadata(moov: ByteArray): AppleTrackMetadata {
    val root = readMp4Box(moov, 0)
    require(root.type == "moov") { "轨道元数据不在 moov 中" }
    val stsd = findMp4Boxes(moov, root, "stsd").firstOrNull()
        ?: throw IllegalArgumentException("moov 里没有 stsd")
    requireBoxBytes(stsd, stsd.body, 8)
    val entries = mp4Children(moov, stsd, stsd.body + 8).toList()
    require(entries.isNotEmpty()) { "stsd 没有样本描述" }
    val entry = entries.first()
    requireBoxBytes(entry, entry.body, 28)
    val sampleRate = readU32(moov, entry, entry.start + 32).ushr(16).toInt()
    var frameLength = 0L
    var alacCookie: ByteArray? = null
    // 真实流的配置可能直接挂在样本描述下，也可能再包一层 'wave'；两种布局都要找。
    // 盒里是否带 4 字节 version/flags 各家编码器不一致，所以不在 Kotlin 侧假设偏移：
    // 把整段配置盒交给原生层，由它按配置字段自身的合理性定位起点。
    val config = findAlacConfig(moov, entry)
    if (config != null) {
        val body = moov.copyOfRange(config.body, config.end.toInt())
        alacCookie = body
        frameLength = readAlacFrameLength(body)
    }
    return AppleTrackMetadata(
        timescale = readTimescale(moov, root, "mdhd"),
        movieTimescale = readTimescale(moov, root, "mvhd"),
        sampleRate = sampleRate,
        frameLength = frameLength,
        codec = entry.type,
        alacCookie = alacCookie
    )
}

/**
 * 在配置盒里定位 ALACSpecificConfig 并读出帧长；找不到合理配置时返回 0。
 *
 * 各编码器给出的盒体不同：裸 24 字节配置、带 4 字节 version/flags、带 8 字节盒头、
 * 带 12 字节 'alac' 盒头。用字段自身范围筛出真正的起点，和原生层用同一套判据。
 */
private fun readAlacFrameLength(body: ByteArray): Long {
    for (offset in intArrayOf(0, 4, 8, 12)) {
        if (offset + 24 > body.size) continue
        val frameLength = readU32(body, offset)
        val bitDepth = body[offset + 5].toInt() and 0xff
        val channels = body[offset + 9].toInt() and 0xff
        val sampleRate = readU32(body, offset + 20)
        if (frameLength in 1L..16384L && channels in 1..8 &&
            bitDepth in 16..32 && sampleRate > 0L
        ) {
            return frameLength
        }
    }
    return 0L
}

/**
 * 确保 ALAC magic cookie 传给 ALACDecoder::Init 时 frameLength 和 sampleRate 是真实值。
 *
 * ALACDecoder::Init 解析规则（完全照抄 C++ 侧）：
 * 1. 若 [at+4..at+7] == "frma"，跳过 12 字节
 * 2. 若 [at+4..at+7] == "alac"，跳过 12 字节
 * 3. 此后即为裸 24 字节 ALACSpecificConfig，从 at 开始读：
 *    at+0  : frameLength (u32 BE)
 *    at+5  : bitDepth
 *    at+9  : numChannels
 *    at+20 : sampleRate (u32 BE)
 *
 * 特殊布局（此次发现）：某些 cookie 长 28 字节，格式是
 *   [4 字节全零填充] [24 字节 ALACSpecificConfig]
 * C++ 不跳任何盒头（[4..7] != "frma"/"alac"），直接从 at=0 读
 * frameLength=0（全零填充），随后强制为 16384，导致噪音。
 * 解决：把真实值写到 at=0（C++ 实际读取的位置）。
 *
 * @param realFrameLength 真实每包帧数（来自分片索引，经 timescale→sampleRate 换算）
 * @param realSampleRate  真实采样率（来自 stsd 样本描述）
 */
internal fun repairAlacCookie(
    cookie: ByteArray,
    realFrameLength: Int,
    realSampleRate: Int
): ByteArray {
    require(realFrameLength in 1..16384) { "ALAC 帧长无效: $realFrameLength" }
    require(realSampleRate > 0) { "ALAC 采样率无效: $realSampleRate" }

    // 和 ALACDecoder::Init 完全相同地跳过可选盒头。
    var at = 0
    if (at + 8 <= cookie.size &&
        cookie[at + 4] == 'f'.code.toByte() && cookie[at + 5] == 'r'.code.toByte() &&
        cookie[at + 6] == 'm'.code.toByte() && cookie[at + 7] == 'a'.code.toByte()
    ) { at += 12 }
    if (at + 8 <= cookie.size &&
        cookie[at + 4] == 'a'.code.toByte() && cookie[at + 5] == 'l'.code.toByte() &&
        cookie[at + 6] == 'a'.code.toByte() && cookie[at + 7] == 'c'.code.toByte()
    ) { at += 12 }

    val fixed = cookie.copyOf()
    // 写到 at：这是 C++ ALACDecoder::Init 实际读取 frameLength 和 sampleRate 的位置。
    putU32Be(fixed, at, realFrameLength.toLong())
    putU32Be(fixed, at + 20, realSampleRate.toLong())
    return fixed
}

private fun putU32Be(data: ByteArray, at: Int, value: Long) {
    data[at]     = (value ushr 24).toByte()
    data[at + 1] = (value ushr 16).toByte()
    data[at + 2] = (value ushr 8).toByte()
    data[at + 3] = value.toByte()
}

private fun readU32(data: ByteArray, at: Int): Long {
    var value = 0L
    repeat(4) { value = (value shl 8) or (data[at + it].toLong() and 0xffL) }
    return value
}

/** 在样本描述（含其内的 'wave' 盒）里找 `alac` 配置盒。 */
private fun findAlacConfig(data: ByteArray, entry: Mp4Box): Mp4Box? {
    for (child in mp4Children(data, entry, entry.body + 28)) {
        if (child.type == "alac") return child
        if (child.type == "wave") {
            for (nested in mp4Children(data, child)) {
                if (nested.type == "alac") return nested
            }
        }
    }
    return null
}

private fun readTimescale(data: ByteArray, moov: Mp4Box, type: String): Long {
    val box = if (type == "mvhd") {
        mp4Children(data, moov).firstOrNull { it.type == type }
    } else {
        findMp4Boxes(data, moov, type).firstOrNull()
    } ?: return 0L
    requireBoxBytes(box, box.body, 1)
    val version = data[box.body].toInt() and 0xff
    return readU32(data, box, box.body + if (version == 1) 20 else 12)
}

private fun readType(data: ByteArray, box: Mp4Box, at: Int): String {
    requireBoxBytes(box, at, 4)
    return String(data, at, 4, Charsets.ISO_8859_1)
}

private fun readU32(data: ByteArray, box: Mp4Box, at: Int): Long {
    requireBoxBytes(box, at, 4)
    var result = 0L
    repeat(4) { result = (result shl 8) or (data[at + it].toLong() and 0xffL) }
    return result
}

private fun requireBoxBytes(box: Mp4Box, at: Int, count: Int) {
    require(at >= box.start && at >= 0 && count >= 0 && at <= box.end && count <= box.end - at) {
        "${box.type} 字段不完整"
    }
}

private fun putU32(data: ByteArray, at: Int, value: Int) {
    data[at] = (value ushr 24).toByte()
    data[at + 1] = (value ushr 16).toByte()
    data[at + 2] = (value ushr 8).toByte()
    data[at + 3] = value.toByte()
}
