package com.ikunshare.sound.utils.tag.util

import java.io.File
import java.io.FileInputStream
import java.io.IOException

class ImageParser {
    var width: Int = 0
    var height: Int = 0
    var mimeType: String = "image/jpeg"
    lateinit var data: ByteArray

    companion object {
        @Throws(IOException::class)
        fun parse(filePath: String): ImageParser {
            val file = File(filePath)
            val fileData = ByteArray(file.length().toInt())
            FileInputStream(file).use { it.read(fileData) }
            return parse(fileData)
        }

        fun parse(data: ByteArray): ImageParser {
            val info = ImageParser()
            info.data = data

            when {
                isJpeg(data) -> {
                    info.mimeType = "image/jpeg"
                    parseJpegSize(data, info)
                }

                isPng(data) -> {
                    info.mimeType = "image/png"
                    parsePngSize(data, info)
                }

                else -> {
                    info.mimeType = "image/jpeg"
                    info.width = 500
                    info.height = 500
                }
            }
            return info
        }

        private fun isJpeg(d: ByteArray): Boolean =
            d.size > 2 && (d[0].toInt() and 0xFF) == 0xFF && (d[1].toInt() and 0xFF) == 0xD8

        private fun isPng(d: ByteArray): Boolean =
            d.size > 8 && (d[0].toInt() and 0xFF) == 0x89 && (d[1].toInt() and 0xFF) == 0x50

        private fun parsePngSize(d: ByteArray, info: ImageParser) {
            info.width = readInt(d, 16)
            info.height = readInt(d, 20)
        }

        private fun parseJpegSize(d: ByteArray, info: ImageParser) {
            var i = 2
            while (i < d.size - 1) {
                val marker = d[i].toInt() and 0xFF
                if (marker == 0xFF) {
                    val type = d[i + 1].toInt() and 0xFF
                    val len = ((d[i + 2].toInt() and 0xFF) shl 8) or (d[i + 3].toInt() and 0xFF)
                    if (type == 0xC0 || type == 0xC2) {
                        info.height =
                            ((d[i + 5].toInt() and 0xFF) shl 8) or (d[i + 6].toInt() and 0xFF)
                        info.width =
                            ((d[i + 7].toInt() and 0xFF) shl 8) or (d[i + 8].toInt() and 0xFF)
                        return
                    }
                    i += 2 + len
                } else {
                    i++
                }
            }
        }

        private fun readInt(b: ByteArray, offset: Int): Int =
            ((b[offset].toInt() and 0xFF) shl 24) or
                    ((b[offset + 1].toInt() and 0xFF) shl 16) or
                    ((b[offset + 2].toInt() and 0xFF) shl 8) or
                    (b[offset + 3].toInt() and 0xFF)
    }
}