package com.ikunshare.music.platform.am

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream

class AppleDownloadResult(
    val file: File,
    val bytes: Long,
    /** 非空表示该曲目是 ALAC 编码，需要在 DownloadManager 侧转成 FLAC。 */
    val alacMeta: AlacDownloadMeta? = null
)

/** 转换 FLAC 所需的 ALAC 元信息。 */
class AlacDownloadMeta(
    val header: AppleTrackHeader,
    val cookie: ByteArray,
    /** 来自 init 段 stsd 的采样率，和播放路径一致；原生解码器对该字段不可靠。 */
    val sampleRate: Int,
    /** 来自 init 段 mdhd 的 timescale；用于把 sampleDurations 换算成实际帧数。 */
    val timescale: Long
)

class AppleTrackDownloader(
    private val preparer: AppleTrackPreparer
) {
    constructor() : this(AppleTrackPreparer())

    suspend fun download(
        spec: AppleAudioSpec,
        outputFile: File,
        onProgress: suspend (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): AppleDownloadResult {
        val source = preparer.prepareSource(spec)
        try {
            when (source) {
                is CachedAppleTrack -> {
                    source.file.copyTo(outputFile, overwrite = true)
                    onProgress(source.size, source.size)
                    return AppleDownloadResult(outputFile, outputFile.length())
                }
                is PreparedAppleTrack -> {
                    val reader = source.newReader()
                    try {
                        val total = reader.size
                        var position = 0L
                        onProgress(0L, total)
                        FileOutputStream(outputFile).use { output ->
                            while (position < total) {
                                currentCoroutineContext().ensureActive()
                                val data = reader.read(position)
                                if (data.isEmpty()) break
                                output.write(data)
                                position += data.size
                                onProgress(position, total)
                            }
                        }
                        check(position == total) { "Apple Music 下载内容不完整" }
                    } finally {
                        reader.close()
                    }
                    val meta = source.init.metadata
                    val cookie = meta.alacCookie
                    val alacMeta = if (meta.isAlac && cookie != null) {
                        AlacDownloadMeta(
                            header = source.header,
                            cookie = cookie,
                            sampleRate = meta.sampleRate,
                            timescale = meta.timescale
                        )
                    } else null
                    return AppleDownloadResult(outputFile, outputFile.length(), alacMeta)
                }
            }
        } catch (error: Throwable) {
            outputFile.delete()
            if (source is PreparedAppleTrack) source.close()
            throw error
        }
    }
}
