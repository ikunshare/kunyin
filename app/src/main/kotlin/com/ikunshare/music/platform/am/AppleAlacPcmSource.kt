package com.ikunshare.music.platform.am

import android.media.MediaDataSource
import java.io.IOException
import kotlin.math.roundToLong

/**
 * 把解密后的 ALAC 包实时解成 PCM，并以 WAV 流交给 ExoPlayer。
 *
 * Android 一直没带 ALAC 解码器：`audio/alac` 会直接 NAME_NOT_FOUND（模拟器与真机均验证过），
 * 所以直接喂 ExoPlayer 是"时间线走完但没有任何声音"。这里用内置的 ffmpeg ALAC
 * 解码器（LGPL-2.1，见 cpp/ffmpeg/LICENSE）自己解，PCM 外面包一层 44 字节 WAV 头，
 * 交给 ExoPlayer 既有的 WavExtractor，seek 也就落在 PCM 字节偏移上。
 */
class AppleAlacPcmSource(
    private val decoder: AlacNativeTemplate,
    private val sampleOffsets: List<Long>,
    private val sampleSizes: List<Long>,
    sampleDurations: List<Long>,
    timescale: Long,
    private val sampleRate: Int,
    /** 按虚拟偏移读取一个已解密的 ALAC 包。 */
    private val readPacket: (offset: Long, size: Int) -> ByteArray,
    /** 关闭时释放底层读取器（连同密钥模板）。 */
    private val onClose: () -> Unit = {}
) : MediaDataSource() {

    private val channels = decoder.channels
    private val bytesPerSample = decoder.bytesPerSample

    /** 每个包解出来的帧数：时长按时基换算成帧。 */
    private val packetFrames: LongArray = LongArray(sampleDurations.size) { index ->
        val duration = sampleDurations[index]
        if (timescale == sampleRate.toLong() || timescale <= 0L) duration
        else (duration.toDouble() * sampleRate / timescale).roundToLong().coerceAtLeast(0L)
    }

    /** 每个包在 PCM 区的起始字节（累计）。 */
    private val packetPcmStart: LongArray = LongArray(sampleDurations.size + 1).also { starts ->
        for (index in sampleDurations.indices) {
            starts[index + 1] = starts[index] + packetFrames[index] * channels * bytesPerSample
        }
    }

    private val pcmBytes = packetPcmStart.last()
    private val totalBytes = WAV_HEADER_SIZE + pcmBytes
    private val header: ByteArray = buildWavHeader(pcmBytes)

    private var cachedPacket = -1
    private var cachedPcm: ByteArray? = null

    @Volatile
    private var closed = false

    override fun getSize(): Long = totalBytes

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed) return -1
        if (size == 0) return 0
        if (position < 0L || position >= totalBytes) return -1
        val available = minOf(size.toLong(), totalBytes - position).toInt()
        var written = 0
        if (position < WAV_HEADER_SIZE) {
            val headerCount = minOf(available, (WAV_HEADER_SIZE - position).toInt())
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + headerCount)
            written += headerCount
        }
        var pcmPosition = position + written - WAV_HEADER_SIZE
        while (written < available) {
            val packet = packetAt(pcmPosition) ?: break
            val pcm = decodedPacket(packet)
            val within = pcmPosition - packetPcmStart[packet]
            if (within >= pcm.size) break
            val count = minOf((pcm.size - within).toInt(), available - written)
            pcm.copyInto(buffer, offset + written, within.toInt(), within.toInt() + count)
            written += count
            pcmPosition += count
        }
        return if (written == 0) -1 else written
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        cachedPcm = null
        runCatching { decoder.close() }
        onClose()
    }

    /** 二分找出 PCM 偏移落在哪个包。 */
    private fun packetAt(pcmPosition: Long): Int? {
        if (pcmPosition < 0L || pcmPosition >= pcmBytes) return null
        var low = 0
        var high = packetFrames.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (packetPcmStart[middle] <= pcmPosition) low = middle else high = middle - 1
        }
        return low
    }

    private fun decodedPacket(index: Int): ByteArray {
        cachedPcm?.let { if (cachedPacket == index) return it }
        val offset = sampleOffsets[index]
        val size = sampleSizes[index]
        if (size <= 0L || size > Int.MAX_VALUE) throw IOException("ALAC 包大小无效")
        val cipher = readPacket(offset, size.toInt())
        if (cipher.isEmpty()) throw IOException("ALAC 包读取为空")
        val plain = decoder.decode(cipher, packetFrames[index].coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        val pcm = plain
        val expected = (packetPcmStart[index + 1] - packetPcmStart[index]).toInt()
        val normalized = if (pcm.size == expected) pcm else ByteArray(expected).also { pcm.copyInto(it) }
        cachedPacket = index
        cachedPcm = normalized
        return normalized
    }

    private fun buildWavHeader(dataBytes: Long): ByteArray {
        val bitsPerSample = bytesPerSample * 8
        val blockAlign = channels * bytesPerSample
        val byteRate = sampleRate * blockAlign
        val header = ByteArray(WAV_HEADER_SIZE)
        fun ascii(at: Int, text: String) {
            text.forEachIndexed { index, character -> header[at + index] = character.code.toByte() }
        }
        fun int32(at: Int, value: Long) {
            header[at] = value.toByte()
            header[at + 1] = (value ushr 8).toByte()
            header[at + 2] = (value ushr 16).toByte()
            header[at + 3] = (value ushr 24).toByte()
        }
        fun int16(at: Int, value: Int) {
            header[at] = value.toByte()
            header[at + 1] = (value ushr 8).toByte()
        }
        ascii(0, "RIFF")
        int32(4, 36L + dataBytes)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16L)
        int16(20, 1) // PCM
        int16(22, channels)
        int32(24, sampleRate.toLong())
        int32(28, byteRate.toLong())
        int16(32, blockAlign)
        int16(34, bitsPerSample)
        ascii(36, "data")
        int32(40, dataBytes)
        return header
    }

    companion object {
        private const val WAV_HEADER_SIZE = 44
    }
}
