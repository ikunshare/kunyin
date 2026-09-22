package com.ikunshare.sound.utils.tag

import android.util.Log
import java.io.File

/**
 * 从音频文件头读取采样率/位深，生成形如 `[16Bit-44.1kHz]` 的质量标记。
 *
 * 采样率/位深无法从各平台 API 或 [com.ikunshare.sound.model.Quality] 提前拿到，
 * 只能在文件下载（并解密）完成后从文件头读取。目前仅支持 FLAC 的 STREAMINFO 块，
 * 其它容器（mp3/ogg）没有位深概念，返回 null。
 */
object AudioSampleInfo {
    private const val TAG = "AudioSampleInfo"

    data class StreamInfo(val sampleRate: Int, val bitsPerSample: Int)

    /**
     * 读取 FLAC STREAMINFO（必为首个 metadata block）。非 FLAC 或解析失败返回 null。
     *
     * 文件布局：`fLaC`(4B) + 块头(1B 类型 + 3B 长度) + STREAMINFO payload。
     * payload 第 10~13 字节含：20 位采样率 + 3 位声道 + 5 位位深。
     */
    fun readFlac(file: File): StreamInfo? {
        return try {
            file.inputStream().use { input ->
                val head = ByteArray(22)
                var off = 0
                while (off < head.size) {
                    val n = input.read(head, off, head.size - off)
                    if (n < 0) break
                    off += n
                }
                if (off < head.size) return null
                // "fLaC" marker
                if (head[0] != 0x66.toByte() || head[1] != 0x4C.toByte() ||
                    head[2] != 0x61.toByte() || head[3] != 0x43.toByte()
                ) return null
                // head[4] 低 7 位为块类型，0 = STREAMINFO（FLAC 规范要求首块即 STREAMINFO）
                if ((head[4].toInt() and 0x7F) != 0) return null
                // STREAMINFO payload 从 head[8] 起，采样率位于 payload 第 10~12 字节
                val b10 = head[18].toInt() and 0xFF
                val b11 = head[19].toInt() and 0xFF
                val b12 = head[20].toInt() and 0xFF
                val b13 = head[21].toInt() and 0xFF
                val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 ushr 4)
                val bitsPerSample = (((b12 and 0x01) shl 4) or (b13 ushr 4)) + 1
                if (sampleRate <= 0) return null
                StreamInfo(sampleRate, bitsPerSample)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readFlac failed for ${file.name}: ${e.message}")
            null
        }
    }

    /** 生成形如 `[16Bit-44.1kHz]` 的标记；无法识别返回 null。 */
    fun qualityTag(file: File): String? {
        val info = readFlac(file) ?: return null
        return "[${info.bitsPerSample}Bit-${formatKhz(info.sampleRate)}kHz]"
    }

    /** 44100 -> "44.1"，48000 -> "48"，96000 -> "96"，88200 -> "88.2"。 */
    private fun formatKhz(sampleRate: Int): String {
        val khz = sampleRate / 1000.0
        if (khz == khz.toLong().toDouble()) return khz.toLong().toString()
        val oneDecimal = Math.round(khz * 10.0) / 10.0
        return if (oneDecimal == oneDecimal.toLong().toDouble()) oneDecimal.toLong().toString()
        else oneDecimal.toString()
    }
}
