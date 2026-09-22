package com.ikunshare.sound.utils.tag.mp3

import com.ikunshare.sound.utils.tag.MusicMeta
import com.ikunshare.sound.utils.tag.util.ImageParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

object Mp3Processor {

    fun read(filePath: String): MusicMeta? {
        return try {
            readTag(filePath)
        } catch (_: Exception) {
            null
        }
    }

    private fun readTag(filePath: String): MusicMeta? {
        val file = File(filePath)
        FileInputStream(file).use { fis ->
            BufferedInputStream(fis).use { bis ->
                val header = ByteArray(10)
                val read = bis.read(header)
                if (read < 10 ||
                    header[0] != 'I'.code.toByte() ||
                    header[1] != 'D'.code.toByte() ||
                    header[2] != '3'.code.toByte()
                ) {
                    return null
                }

                val tagSize = ((header[6].toInt() and 0x7F) shl 21) or
                        ((header[7].toInt() and 0x7F) shl 14) or
                        ((header[8].toInt() and 0x7F) shl 7) or
                        (header[9].toInt() and 0x7F)

                val tagData = ByteArray(tagSize)
                var totalRead = 0
                while (totalRead < tagSize) {
                    val n = bis.read(tagData, totalRead, tagSize - totalRead)
                    if (n <= 0) break
                    totalRead += n
                }

                return parseFrames(tagData, totalRead)
            }
        }
    }

    private fun parseFrames(data: ByteArray, length: Int): MusicMeta {
        val meta = MusicMeta()
        var offset = 0

        while (offset + 10 <= length) {
            val frameId = String(data, offset, 4, StandardCharsets.ISO_8859_1)
            if (frameId[0] == '\u0000') break

            val frameSize = ((data[offset + 4].toInt() and 0xFF) shl 24) or
                    ((data[offset + 5].toInt() and 0xFF) shl 16) or
                    ((data[offset + 6].toInt() and 0xFF) shl 8) or
                    (data[offset + 7].toInt() and 0xFF)

            offset += 10

            if (frameSize <= 0 || offset + frameSize > length) break

            val frameData = data.copyOfRange(offset, offset + frameSize)
            offset += frameSize

            when (frameId) {
                "TIT2" -> meta.title = decodeTextFrame(frameData)
                "TPE1" -> meta.artist = decodeTextFrame(frameData)
                "TALB" -> meta.album = decodeTextFrame(frameData)
                "TRCK" -> meta.trackNumber =
                    decodeTextFrame(frameData)?.split("/")?.firstOrNull()?.trim()?.toIntOrNull()

                "USLT" -> meta.lyrics = decodeUsltFrame(frameData)
                "APIC" -> decodeApicFrame(frameData, meta)
            }
        }
        return meta
    }

    private fun decodeTextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val encoding = data[0].toInt() and 0xFF
        val textData = data.copyOfRange(1, data.size)
        return decodeString(textData, encoding)
    }

    private fun decodeUsltFrame(data: ByteArray): String? {
        if (data.size < 5) return null
        val encoding = data[0].toInt() and 0xFF
        // skip language (3 bytes)
        var offset = 4
        // skip content descriptor (null-terminated)
        offset = skipNullTerminated(data, offset, encoding)
        if (offset >= data.size) return null
        return decodeString(data.copyOfRange(offset, data.size), encoding)
    }

    private fun decodeApicFrame(data: ByteArray, meta: MusicMeta) {
        if (data.size < 4) return
        val encoding = data[0].toInt() and 0xFF
        var offset = 1
        // read null-terminated MIME type (always ISO-8859-1)
        val mimeEnd = data.indexOf(0.toByte(), offset)
        if (mimeEnd < 0) return
        val mimeType = String(data, offset, mimeEnd - offset, StandardCharsets.ISO_8859_1)
        offset = mimeEnd + 1
        if (offset >= data.size) return
        val pictureType = data[offset].toInt() and 0xFF
        offset++
        // skip description (null-terminated, encoding-dependent)
        offset = skipNullTerminated(data, offset, encoding)
        if (offset >= data.size) return
        if (pictureType == 3 || meta.pictureData == null) {
            meta.pictureData = data.copyOfRange(offset, data.size)
            meta.pictureMimeType = mimeType
        }
    }

    private fun decodeString(data: ByteArray, encoding: Int): String {
        return when (encoding) {
            0 -> String(data, StandardCharsets.ISO_8859_1)
            1 -> decodeUtf16WithBom(data)
            2 -> String(data, StandardCharsets.UTF_16BE)
            3 -> String(data, StandardCharsets.UTF_8)
            else -> String(data, StandardCharsets.ISO_8859_1)
        }.trimEnd('\u0000')
    }

    private fun decodeUtf16WithBom(data: ByteArray): String {
        if (data.size < 2) return ""
        val bom0 = data[0].toInt() and 0xFF
        val bom1 = data[1].toInt() and 0xFF
        return when {
            bom0 == 0xFF && bom1 == 0xFE -> String(
                data,
                2,
                data.size - 2,
                StandardCharsets.UTF_16LE
            )

            bom0 == 0xFE && bom1 == 0xFF -> String(
                data,
                2,
                data.size - 2,
                StandardCharsets.UTF_16BE
            )

            else -> String(data, StandardCharsets.UTF_16LE)
        }
    }

    private fun skipNullTerminated(data: ByteArray, start: Int, encoding: Int): Int {
        return if (encoding == 1 || encoding == 2) {
            // UTF-16: null terminator is two zero bytes
            var i = start
            while (i + 1 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            data.size
        } else {
            // ISO-8859-1 or UTF-8: single zero byte
            var i = start
            while (i < data.size) {
                if (data[i] == 0.toByte()) return i + 1
                i++
            }
            data.size
        }
    }

    private fun ByteArray.indexOf(byte: Byte, fromIndex: Int): Int {
        for (i in fromIndex until size) {
            if (this[i] == byte) return i
        }
        return -1
    }

    fun write(filePath: String, meta: MusicMeta) {
        try {
            writeTag(filePath, meta)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeTag(filePath: String, meta: MusicMeta) {
        val file = File(filePath)
        val tempFile = File("$filePath.tmp")

        try {
            FileOutputStream(tempFile).use { fos ->
                DataOutputStream(BufferedOutputStream(fos)).use { dos ->
                    dos.write("ID3".toByteArray())
                    dos.write(3)
                    dos.write(0)
                    dos.write(0)

                    val frameBuffer = ByteArrayOutputStream()
                    val frameDos = DataOutputStream(frameBuffer)

                    meta.title?.let { writeTextFrame(frameDos, "TIT2", it) }
                    meta.artist?.let { writeTextFrame(frameDos, "TPE1", it) }
                    meta.album?.let { writeTextFrame(frameDos, "TALB", it) }
                    meta.trackNumber?.let { writeTextFrame(frameDos, "TRCK", it.toString()) }
                    meta.lyrics?.let { writeUsltFrame(frameDos, it) }
                    if (meta.picture != null) {
                        writeApicFrame(frameDos, meta.picture!!)
                    } else if (meta.pictureData != null) {
                        writeApicFrameFromData(
                            frameDos,
                            meta.pictureData!!,
                            meta.pictureMimeType ?: "image/jpeg"
                        )
                    }

                    val frames = frameBuffer.toByteArray()
                    dos.write(toSynchSafe(frames.size))
                    dos.write(frames)

                    FileInputStream(file).use { fis ->
                        BufferedInputStream(fis).use { bis ->
                            bis.mark(10)
                            val header = ByteArray(10)
                            val read = bis.read(header)

                            var skipSize = 0
                            if (read == 10 && header[0] == 'I'.code.toByte() && header[1] == 'D'.code.toByte() && header[2] == '3'.code.toByte()) {
                                skipSize = ((header[6].toInt() and 0x7F) shl 21) or
                                        ((header[7].toInt() and 0x7F) shl 14) or
                                        ((header[8].toInt() and 0x7F) shl 7) or
                                        (header[9].toInt() and 0x7F)
                                skipSize += 10
                            } else {
                                bis.reset()
                            }

                            var skipped = 0L
                            while (skipped < skipSize) {
                                val s = bis.skip((skipSize - skipped))
                                if (s <= 0) break
                                skipped += s
                            }

                            val buf = ByteArray(8192)
                            var n: Int
                            while (bis.read(buf).also { n = it } != -1) {
                                dos.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
        } catch (e: IOException) {
            tempFile.delete()
            throw e
        }

        if (file.delete()) {
            tempFile.renameTo(file)
        } else {
            copyFile(tempFile, file)
            tempFile.delete()
        }
    }

    private fun writeTextFrame(dos: DataOutputStream, id: String, text: String) {
        val textBytes = text.toByteArray(StandardCharsets.UTF_16LE)
        val size = 1 + 2 + textBytes.size
        writeFrameHeader(dos, id, size)
        dos.write(1)
        dos.write(0xFF)
        dos.write(0xFE)
        dos.write(textBytes)
    }

    private fun writeUsltFrame(dos: DataOutputStream, lyrics: String) {
        val textBytes = lyrics.toByteArray(StandardCharsets.UTF_16LE)
        val size = 1 + 3 + 2 + 2 + 2 + textBytes.size
        writeFrameHeader(dos, "USLT", size)
        dos.write(1)
        dos.write("zho".toByteArray())
        dos.write(0xFF); dos.write(0xFE)
        dos.write(0x00); dos.write(0x00)
        dos.write(0xFF); dos.write(0xFE)
        dos.write(textBytes)
    }

    private fun writeApicFrame(dos: DataOutputStream, apicPath: String) {
        val img: ImageParser
        try {
            img = ImageParser.parse(apicPath)
        } catch (_: IOException) {
            return
        }
        val mimeBytes = img.mimeType.toByteArray(StandardCharsets.ISO_8859_1)
        val size = 1 + mimeBytes.size + 1 + 1 + 1 + img.data.size
        writeFrameHeader(dos, "APIC", size)
        dos.write(0)
        dos.write(mimeBytes)
        dos.write(0)
        dos.write(3)
        dos.write(0)
        dos.write(img.data)
    }

    private fun writeApicFrameFromData(dos: DataOutputStream, data: ByteArray, mimeType: String) {
        val mimeBytes = mimeType.toByteArray(StandardCharsets.ISO_8859_1)
        val size = 1 + mimeBytes.size + 1 + 1 + 1 + data.size
        writeFrameHeader(dos, "APIC", size)
        dos.write(0)
        dos.write(mimeBytes)
        dos.write(0)
        dos.write(3)
        dos.write(0)
        dos.write(data)
    }

    private fun writeFrameHeader(dos: DataOutputStream, id: String, size: Int) {
        dos.write(id.toByteArray())
        dos.writeInt(size)
        dos.writeShort(0)
    }

    private fun toSynchSafe(value: Int): ByteArray = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte()
    )

    private fun copyFile(src: File, dst: File) {
        FileInputStream(src).use { input ->
            FileOutputStream(dst).use { output ->
                val buf = ByteArray(8192)
                var len: Int
                while (input.read(buf).also { len = it } > 0) {
                    output.write(buf, 0, len)
                }
            }
        }
    }
}
