package com.ikunshare.music.platform.am

/**
 * 基于 FFmpeg libavcodec（LGPL-2.1，见 cpp/ffmpeg/LICENSE）的 ALAC 解码器 Kotlin 封装。
 *
 * Android 一直没带 ALAC 解码器：`MediaCodec.createDecoderByType("audio/alac")` 会抛
 * NAME_NOT_FOUND。桌面版靠 FFmpeg 转 WAV 绕过，这里用同一套 ffmpeg alac 解码器
 * 把 ALAC 包解成 PCM，再包成 WAV 交给 ExoPlayer。
 *
 * @param cookie stsd 里 `alac` 样本描述下的 `alac` 子盒（含 4 字节 version/flags 与
 *   24 字节 ALACSpecificConfig）；原生侧会跳过盒头并重组成 ffmpeg 需要的 36 字节原子头。
 */
class AlacNativeTemplate(cookie: ByteArray) : AutoCloseable {

    private var handle: Long = nativeCreate(cookie).also {
        require(it != 0L) { "ALAC 解码器初始化失败" }
    }

    val sampleRate: Int get() = nativeSampleRate(handle)
    val channels: Int get() = nativeChannels(handle)
    val bitDepth: Int get() = nativeBitDepth(handle)

    /** 每个样本占用的字节数；20 位按 24 位输出（左对齐），与参考实现一致。 */
    val bytesPerSample: Int get() = when {
        bitDepth <= 16 -> 2
        bitDepth <= 24 -> 3
        else -> 4
    }

    /**
     * 解码一个 ALAC 包，返回交错排列的 PCM 字节。
     *
     * @param framesPerPacket 该包预期的帧数；传 0 表示按 cookie 里的默认帧长。
     */
    @Synchronized
    fun decode(packet: ByteArray, framesPerPacket: Int = 0): ByteArray {
        check(handle != 0L) { "ALAC 解码器已关闭" }
        return nativeDecode(handle, packet, framesPerPacket)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(cookie: ByteArray): Long
    private external fun nativeDecode(handle: Long, packet: ByteArray, framesPerPacket: Int): ByteArray
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeChannels(handle: Long): Int
    private external fun nativeBitDepth(handle: Long): Int
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("AlacJni")
        }
    }
}
