package com.ikunshare.music.platform.am

import kotlinx.coroutines.ensureActive
import java.io.IOException
import kotlin.coroutines.coroutineContext

fun decryptAppleSamples(
    data: ByteArray,
    info: AppleFragmentInfo,
    template: AppleSampleDecryptor
): ByteArray {
    var total = 0L
    for (sample in info.samples) {
        require(sample.size > 0L) { "MP4 样本大小无效" }
        total = checkedSampleAdd(total, sample.size)
    }
    require(total <= Int.MAX_VALUE) { "MP4 样本区过大" }
    val output = ByteArray(total.toInt())
    var at = 0L
    for (index in info.samples.indices) {
        val sample = info.samples[index]
        require(sample.offset >= 0L && sample.size <= data.size.toLong() &&
            sample.offset <= data.size.toLong() - sample.size) { "MP4 样本越界" }
        val end = sample.offset + sample.size
        val cipher = data.copyOfRange(sample.offset.toInt(), end.toInt())
        val patterns = info.subsamples.getOrElse(index) { emptyList() }
        if (patterns.isEmpty()) {
            decryptExact(template, cipher).copyInto(output, at.toInt())
        } else {
            var cursor = 0L
            for (pattern in patterns) {
                require(pattern.clearBytes >= 0 && pattern.protectedBytes >= 0L) { "子样本范围越界" }
                val clearEnd = cursor + pattern.clearBytes
                val protectedEnd = clearEnd + pattern.protectedBytes
                require(clearEnd >= cursor && protectedEnd >= clearEnd && protectedEnd <= cipher.size) {
                    "子样本范围越界"
                }
                if (clearEnd > cursor) {
                    cipher.copyInto(output, (at + cursor).toInt(), cursor.toInt(), clearEnd.toInt())
                }
                decryptExact(
                    template,
                    cipher.copyOfRange(clearEnd.toInt(), protectedEnd.toInt())
                ).copyInto(output, (at + clearEnd).toInt())
                cursor = protectedEnd
            }
            if (cursor < cipher.size) {
                cipher.copyInto(output, (at + cursor).toInt(), cursor.toInt(), cipher.size)
            }
        }
        at += sample.size
    }
    return output
}

class AppleTrackAssembler(
    private val fetch: suspend (String, Long, Long) -> ByteArray
) {
    constructor(client: AppleRangeClient = AppleRangeClient()) : this(client::fetch)

    suspend fun assemble(
        fileUrl: String,
        header: AppleTrackHeader,
        segments: List<AppleSegment>,
        trex: TrexTable,
        ivSize: Int,
        templates: Map<String, AppleSampleDecryptor>
    ): ByteArray {
        require(header.totalSize >= header.bytes.size && header.totalSize <= Int.MAX_VALUE) {
            "Apple Music 内存曲目过大或头长度无效"
        }
        require(segments.size == header.fragments.size) { "HLS 分片数与索引不一致" }
        val output = ByteArray(header.totalSize.toInt())
        header.bytes.copyInto(output)
        for (position in header.fragments.indices) {
            coroutineContext.ensureActive()
            val slot = header.fragments[position]
            require(slot.virtualStart >= header.bytes.size && slot.virtualStart <= output.size &&
                slot.bytes >= 0L && slot.bytes <= output.size.toLong() - slot.virtualStart) {
                "Apple Music 分片虚拟范围无效"
            }
            val segment = segments[position]
            val template = templates[segment.keyUri] ?: throw IOException("缺少 Temari 密钥模板")
            val data = fetch(fileUrl, slot.remoteStart, slot.remoteEnd)
            coroutineContext.ensureActive()
            val moof = readMp4Box(data, 0)
            require(moof.type == "moof") { "Apple Music 分片起点不是 moof" }
            val info = parseAppleFragment(data, moof, trex, ivSize, slot.remoteStart)
            val plain = decryptAppleSamples(data, info, template)
            require(plain.size.toLong() == slot.bytes) { "Apple Music 分片解密后长度不符" }
            plain.copyInto(output, slot.virtualStart.toInt())
        }
        return output
    }
}

private fun decryptExact(template: AppleSampleDecryptor, cipher: ByteArray): ByteArray {
    val plain = template.decrypt(cipher)
    require(plain.size == cipher.size) { "Temari 样本解密长度不符" }
    return plain
}

private fun checkedSampleAdd(first: Long, second: Long): Long = try {
    Math.addExact(first, second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("MP4 样本大小溢出")
}
