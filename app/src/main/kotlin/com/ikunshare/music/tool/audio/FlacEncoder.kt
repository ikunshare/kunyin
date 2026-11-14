package com.ikunshare.music.tool.audio

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 极简 FLAC 无损编码器：固定分块 + 固定预测器（0..4 阶）+ Rice 编码（单分区）。
 *
 * 目标是「任何播放器都能解」与严格无损，不追求极限压缩率。输出是标准 FLAC 流，
 * STREAMINFO 里写入真实的帧长上下界、总样本数与 PCM MD5，播放器可据此校验。
 *
 * 输入为交错排列的小端 PCM，位深 16/24/32 字节对齐后的字节数（ALAC 的 20 位按 24 位容器输出）。
 * 当 Rice 编码在一个块上反而不如直接存原始样本时，该块自动回退为 verbatim 子帧。
 */
class FlacEncoder(
    output: File,
    private val sampleRate: Int,
    private val channels: Int,
    private val bitsPerSample: Int,
    private val totalSamples: Long
) : AutoCloseable {

    private val out = RandomAccessFile(output, "rw").also { it.setLength(0L) }
    private val bytesPerSample = (bitsPerSample + 7) / 8
    private val md5 = MessageDigest.getInstance("MD5")

    private val pending = IntArray(BLOCK_SIZE * channels)
    private var pendingSamples = 0
    private var writtenSamples = 0L
    private var flushedSamples = 0L  // 已写入 FLAC 流的样本数（帧头里的采样偏移）

    private var minBlockSize = Int.MAX_VALUE
    private var maxBlockSize = 0
    private var minFrameSize = Int.MAX_VALUE
    private var maxFrameSize = 0

    @Volatile
    private var closed = false

    init {
        require(sampleRate in 1..0xfffff) { "FLAC 采样率无效: $sampleRate" }
        require(channels in 1..8) { "FLAC 声道数无效: $channels" }
        require(bitsPerSample in 4..32) { "FLAC 位深无效: $bitsPerSample" }
        require(totalSamples >= 0L) { "FLAC 总样本数无效" }
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        // 唯一元数据块：STREAMINFO（0x80 = 最后一块，类型 0），长度 34。
        out.write(byteArrayOf(0x80.toByte(), 0, 0, STREAMINFO_SIZE.toByte()))
        out.write(ByteArray(STREAMINFO_SIZE))
    }

    /** 追加交错小端 PCM；[samples] 为每声道样本数。 */
    fun write(pcm: ByteArray, samples: Int) {
        check(!closed) { "FLAC 编码器已关闭" }
        require(samples >= 0) { "FLAC 样本数不能为负" }
        val needed = samples.toLong() * channels * bytesPerSample
        require(needed <= pcm.size) { "PCM 数据不足：需要 $needed 字节，实际 ${pcm.size}" }
        md5.update(pcm, 0, needed.toInt())
        writtenSamples += samples
        var at = 0
        var remaining = samples
        while (remaining > 0) {
            val take = minOf(remaining, BLOCK_SIZE - pendingSamples)
            var slot = pendingSamples * channels
            repeat(take * channels) {
                pending[slot++] = readSample(pcm, at)
                at += bytesPerSample
            }
            pendingSamples += take
            remaining -= take
            if (pendingSamples == BLOCK_SIZE) flushBlock(pendingSamples)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            if (pendingSamples > 0) flushBlock(pendingSamples)
            pendingSamples = 0
            check(totalSamples == 0L || writtenSamples == totalSamples) {
                "FLAC 样本数不一致：声明 $totalSamples，实际 $writtenSamples"
            }
            writeStreamInfo()
        } finally {
            out.close()
        }
    }

    private fun readSample(data: ByteArray, at: Int): Int = when (bytesPerSample) {
        1 -> data[at].toInt()
        2 -> ((data[at].toInt() and 0xff) or (data[at + 1].toInt() shl 8)).toShort().toInt()
        3 -> {
            val value = (data[at].toInt() and 0xff) or
                ((data[at + 1].toInt() and 0xff) shl 8) or
                ((data[at + 2].toInt() and 0xff) shl 16)
            (value shl 8) shr 8
        }
        else -> (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)
    }

    private fun flushBlock(samples: Int) {
        val frame = BitWriter()
        // 帧头：variable-blocksize 流（标志位 = 1），帧头里写采样偏移，
        // 这样 seek 时播放器能直接定位，不依赖 STREAMINFO 里的分块一致性。
        frame.writeBits(0x3ffe, 14)           // sync
        frame.writeBits(0, 1)                 // reserved
        frame.writeBits(1, 1)                 // 1 = variable-blocksize（帧头带采样偏移）
        frame.writeBits(BLOCK_SIZE_CODE, 4)   // 块长写在帧头尾部
        frame.writeBits(0, 4)                 // 采样率取自 STREAMINFO
        frame.writeBits(channels - 1, 4)      // 独立声道
        frame.writeBits(0, 3)                 // 位深取自 STREAMINFO
        frame.writeBits(0, 1)                 // reserved
        frame.writeUtf8(flushedSamples)       // 变长流写采样偏移
        frame.writeBits(samples - 1, 16)
        val header = frame.toByteArray()
        frame.writeBits(crc8(header), 8)

        val channelData = IntArray(samples)
        for (channel in 0 until channels) {
            var index = channel
            for (i in 0 until samples) {
                channelData[i] = pending[index]
                index += channels
            }
            writeSubframe(frame, channelData, samples)
        }
        frame.align()
        val body = frame.toByteArray()
        frame.writeBits(crc16(body), 16)
        val bytes = frame.toByteArray()
        out.write(bytes)

        minBlockSize = minOf(minBlockSize, samples)
        maxBlockSize = maxOf(maxBlockSize, samples)
        minFrameSize = minOf(minFrameSize, bytes.size)
        maxFrameSize = maxOf(maxFrameSize, bytes.size)
        flushedSamples += samples
        pendingSamples = 0
    }

    private fun writeSubframe(frame: BitWriter, data: IntArray, samples: Int) {
        var constant = true
        for (i in 1 until samples) {
            if (data[i] != data[0]) {
                constant = false
                break
            }
        }
        if (constant) {
            frame.writeBits(0, 1)
            frame.writeBits(SUBFRAME_CONSTANT, 6)
            frame.writeBits(0, 1)
            frame.writeSigned(data[0], bitsPerSample)
            return
        }

        val maxOrder = minOf(4, samples - 1)
        var order = 0
        var bestCost = Long.MAX_VALUE
        for (candidate in 0..maxOrder) {
            var cost = 0L
            for (i in candidate until samples) {
                cost += kotlin.math.abs(fixedResidual(data, i, candidate).toLong())
            }
            if (cost < bestCost) {
                bestCost = cost
                order = candidate
            }
        }
        val residuals = IntArray(samples - order) { fixedResidual(data, it + order, order) }
        val riceParameter = bestRiceParameter(residuals)
        val fixedBits = 8L + order.toLong() * bitsPerSample + 2L + 4L + 4L +
            residuals.sumOf { riceBits(it, riceParameter) }
        val verbatimBits = 8L + samples.toLong() * bitsPerSample

        if (fixedBits >= verbatimBits) {
            frame.writeBits(0, 1)
            frame.writeBits(SUBFRAME_VERBATIM, 6)
            frame.writeBits(0, 1)
            for (i in 0 until samples) frame.writeSigned(data[i], bitsPerSample)
            return
        }

        frame.writeBits(0, 1)
        frame.writeBits(SUBFRAME_FIXED or order, 6)
        frame.writeBits(0, 1)
        for (i in 0 until order) frame.writeSigned(data[i], bitsPerSample)

        frame.writeBits(0, 2) // Rice 编码，4 位参数
        frame.writeBits(0, 4) // 单分区
        frame.writeBits(riceParameter, 4)
        for (value in residuals) {
            val zigzag = zigzag(value)
            val quotient = zigzag ushr riceParameter
            var zeros = quotient
            while (zeros > 0L) {
                val chunk = minOf(zeros, 32L).toInt()
                frame.writeBits(0, chunk)
                zeros -= chunk
            }
            frame.writeBits(1, 1)
            if (riceParameter > 0) frame.writeBits(zigzag and ((1L shl riceParameter) - 1L), riceParameter)
        }
    }

    private fun fixedResidual(data: IntArray, index: Int, order: Int): Int = when (order) {
        0 -> data[index]
        1 -> data[index] - data[index - 1]
        2 -> data[index] - 2 * data[index - 1] + data[index - 2]
        3 -> data[index] - 3 * data[index - 1] + 3 * data[index - 2] - data[index - 3]
        else -> data[index] - 4 * data[index - 1] + 6 * data[index - 2] - 4 * data[index - 3] + data[index - 4]
    }

    private fun bestRiceParameter(residuals: IntArray): Int {
        if (residuals.isEmpty()) return 0
        var best = 0
        var bestBits = Long.MAX_VALUE
        for (k in 0..14) {
            var bits = 0L
            for (value in residuals) {
                bits += riceBits(value, k)
                if (bits >= bestBits) break
            }
            if (bits < bestBits) {
                bestBits = bits
                best = k
            }
        }
        return best
    }

    private fun riceBits(value: Int, k: Int): Long = (zigzag(value) ushr k) + 1L + k

    private fun zigzag(value: Int): Long =
        ((value.toLong() shl 1) xor (value.toLong() shr 31)) and 0xffff_ffffL

    private fun writeStreamInfo() {
        val body = BitWriter(64)
        body.writeBits(if (minBlockSize == Int.MAX_VALUE) BLOCK_SIZE else minBlockSize, 16)
        body.writeBits(if (maxBlockSize == 0) BLOCK_SIZE else maxBlockSize, 16)
        body.writeBits(if (minFrameSize == Int.MAX_VALUE) 0 else minFrameSize, 24)
        body.writeBits(maxFrameSize, 24)
        body.writeBits(sampleRate, 20)
        body.writeBits(channels - 1, 3)
        body.writeBits(bitsPerSample - 1, 5)
        body.writeBits(if (totalSamples > 0L) totalSamples else writtenSamples, 36)
        for (byte in md5.digest()) body.writeBits(byte.toInt() and 0xff, 8)
        val bytes = body.toByteArray()
        require(bytes.size == STREAMINFO_SIZE) { "STREAMINFO 长度错误：${bytes.size}" }
        out.seek(8L)
        out.write(bytes)
        out.seek(out.length())
    }

    private companion object {
        const val BLOCK_SIZE = 4096
        const val BLOCK_SIZE_CODE = 0b0111
        const val STREAMINFO_SIZE = 34
        const val SUBFRAME_CONSTANT = 0b000000
        const val SUBFRAME_VERBATIM = 0b000001
        const val SUBFRAME_FIXED = 0b001000

        fun crc8(data: ByteArray): Int {
            var crc = 0
            for (byte in data) {
                crc = crc xor (byte.toInt() and 0xff)
                repeat(8) {
                    crc = if (crc and 0x80 != 0) ((crc shl 1) xor 0x07) and 0xff
                    else (crc shl 1) and 0xff
                }
            }
            return crc
        }

        fun crc16(data: ByteArray): Int {
            var crc = 0
            for (byte in data) {
                crc = crc xor ((byte.toInt() and 0xff) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) ((crc shl 1) xor 0x8005) and 0xffff
                    else (crc shl 1) and 0xffff
                }
            }
            return crc
        }
    }
}

/** 按位写入，MSB 在前。 */
internal class BitWriter(initial: Int = 4096) {
    private var buffer = ByteArray(initial)
    private var size = 0
    private var current = 0
    private var used = 0

    fun writeBits(value: Long, count: Int) {
        require(count in 0..64) { "位数无效：$count" }
        var remaining = count
        while (remaining > 0) {
            val take = minOf(remaining, 8 - used)
            val shift = remaining - take
            val bits = ((value ushr shift) and ((1L shl take) - 1L)).toInt()
            current = (current shl take) or bits
            used += take
            remaining -= take
            if (used == 8) {
                ensureCapacity(size + 1)
                buffer[size++] = current.toByte()
                current = 0
                used = 0
            }
        }
    }

    fun writeBits(value: Int, count: Int) = writeBits(value.toLong() and 0xffff_ffffL, count)

    fun writeSigned(value: Int, bits: Int) {
        val masked = if (bits >= 32) value.toLong() and 0xffff_ffffL
        else value.toLong() and ((1L shl bits) - 1L)
        writeBits(masked, bits)
    }

    fun writeUtf8(value: Long) {
        require(value >= 0L)
        when {
            value < 0x80L -> writeBits(value, 8)
            value < 0x800L -> {
                writeBits(0xc0L or (value ushr 6), 8)
                writeBits(0x80L or (value and 0x3f), 8)
            }
            value < 0x10000L -> {
                writeBits(0xe0L or (value ushr 12), 8)
                writeBits(0x80L or ((value ushr 6) and 0x3f), 8)
                writeBits(0x80L or (value and 0x3f), 8)
            }
            value < 0x200000L -> {
                writeBits(0xf0L or (value ushr 18), 8)
                writeBits(0x80L or ((value ushr 12) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 6) and 0x3f), 8)
                writeBits(0x80L or (value and 0x3f), 8)
            }
            value < 0x4000000L -> {
                writeBits(0xf8L or (value ushr 24), 8)
                writeBits(0x80L or ((value ushr 18) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 12) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 6) and 0x3f), 8)
                writeBits(0x80L or (value and 0x3f), 8)
            }
            else -> {
                writeBits(0xfcL or (value ushr 30), 8)
                writeBits(0x80L or ((value ushr 24) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 18) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 12) and 0x3f), 8)
                writeBits(0x80L or ((value ushr 6) and 0x3f), 8)
                writeBits(0x80L or (value and 0x3f), 8)
            }
        }
    }

    fun align() {
        if (used > 0) writeBits(0, 8 - used)
    }

    fun toByteArray(): ByteArray {
        require(used == 0) { "位写入器未对齐" }
        return buffer.copyOf(size)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= buffer.size) return
        var capacity = buffer.size
        while (capacity < required) capacity *= 2
        buffer = buffer.copyOf(capacity)
    }
}
