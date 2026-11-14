package com.ikunshare.music.tool.audio

import com.ikunshare.music.platform.am.AlacDownloadMeta
import com.ikunshare.music.platform.am.AlacNativeTemplate
import com.ikunshare.music.platform.am.AppleTrackHeader
import com.ikunshare.music.platform.am.repairAlacCookie
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.roundToLong

/**
 * 把下载好的 ALAC m4a（扁平格式：moov + 解密样本）转成 FLAC 落盘。
 *
 * 文件布局假设：[header.bytes][sample0][sample1]...（即 [AppleTrackDownloader] 生成的格式）。
 *
 * sampleDurations 的单位是 mdhd timescale，不是采样帧数。
 * 当 timescale ≠ sampleRate（例如 timescale=90000, sampleRate=44100）时，
 * 必须做换算，和播放路径 [AppleAlacPcmSource] 保持一致。
 */
object AlacToFlacConverter {

    suspend fun convert(
        source: File,
        dest: File,
        header: AppleTrackHeader,
        cookie: ByteArray,
        /** 来自 init 段 stsd 的采样率；和播放路径保持一致。 */
        sampleRate: Int,
        /** 来自 init 段 mdhd 的 timescale；用于把 sampleDurations 换算成帧数。 */
        timescale: Long,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> }
    ) {
        // 把每包 duration（timescale 单位）换算成实际帧数，与 AppleAlacPcmSource 逻辑一致。
        val packetFrames: LongArray = LongArray(header.sampleDurations.size) { index ->
            val duration = header.sampleDurations[index]
            if (timescale == sampleRate.toLong() || timescale <= 0L) duration
            else (duration.toDouble() * sampleRate / timescale).roundToLong().coerceAtLeast(0L)
        }

        // cookie 里的 frameLength/sampleRate 可能是垃圾值，用真实值覆盖后再初始化解码器。
        val realFrameLength = packetFrames.firstOrNull()
            ?.coerceIn(1L, 16384L)?.toInt() ?: 4096
        val fixedCookie = repairAlacCookie(cookie, realFrameLength, sampleRate)

        val decoder = AlacNativeTemplate(fixedCookie)
        try {
            val channels = decoder.channels
            val bitsPerSample = decoder.bitDepth
            val bytesPerSample = decoder.bytesPerSample

            // 把每包的 duration（timescale 单位）换算成实际帧数，与 AppleAlacPcmSource 逻辑一致。
            val packetFrames: LongArray = LongArray(header.sampleDurations.size) { index ->
                val duration = header.sampleDurations[index]
                if (timescale == sampleRate.toLong() || timescale <= 0L) duration
                else (duration.toDouble() * sampleRate / timescale).roundToLong().coerceAtLeast(0L)
            }
            val totalSamples = packetFrames.sum()

            FlacEncoder(dest, sampleRate, channels, bitsPerSample, totalSamples).use { enc ->
                val sourceBytes = source.readBytes()
                var done = 0L
                for ((index, offset) in header.sampleOffsets.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val size = header.sampleSizes[index]
                    require(size > 0 && size <= Int.MAX_VALUE) { "ALAC 样本大小无效: $size" }
                    val alacPacket = sourceBytes.copyOfRange(offset.toInt(), (offset + size).toInt())
                    val frames = packetFrames[index].coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                    require(frames > 0) { "ALAC 包帧数为 0（index=$index，duration=${header.sampleDurations[index]}）" }
                    val pcm = decoder.decode(alacPacket, frames)
                    enc.write(pcm, pcm.size / (channels * bytesPerSample))
                    done += frames
                    onProgress(done, totalSamples)
                }
            }
        } finally {
            decoder.close()
        }
    }

    /** 便捷重载，直接接受 [AlacDownloadMeta]。 */
    suspend fun convert(
        source: File,
        dest: File,
        meta: AlacDownloadMeta,
        onProgress: suspend (done: Long, total: Long) -> Unit = { _, _ -> }
    ) = convert(source, dest, meta.header, meta.cookie, meta.sampleRate, meta.timescale, onProgress)
}

