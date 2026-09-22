package com.ikunshare.sound.utils.tag.ogg

import com.ikunshare.sound.utils.tag.MusicMeta
import com.ikunshare.sound.utils.tag.flac.MetaDataBlockPicture
import com.ikunshare.sound.utils.tag.flac.MetaDataBlockVorbisComment
import com.ikunshare.sound.utils.tag.util.ImageParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object OggProcessor {

    private val CRC_TABLE = IntArray(256).also { table ->
        for (i in 0..255) {
            var r = i shl 24
            for (j in 0..7) {
                r = if ((r and 0x80000000.toInt()) != 0) (r shl 1) xor 0x04C11DB7 else r shl 1
            }
            table[i] = r
        }
    }

    fun read(filePath: String): MusicMeta? {
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(File(filePath)))).use { din ->
                readMeta(din)
            }
        } catch (_: Exception) {
            null
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun readMeta(din: DataInputStream): MusicMeta {
        val page0 = readPage(din)
        val isOpus = page0.body.size >= 8 &&
                String(page0.body, 0, 8, StandardCharsets.US_ASCII) == "OpusHead"

        // Read comment pages until we have the full comment packet
        val commentData = ByteArrayOutputStream()
        var packetComplete = false

        readLoop@ while (!packetComplete) {
            val page = readPage(din)
            var bodyOffset = 0
            for (i in page.segmentTable.indices) {
                val segSize = page.segmentTable[i].toInt() and 0xFF
                bodyOffset += segSize
                if (segSize < 255) {
                    // End of packet - write only up to this segment
                    commentData.write(page.body, 0, bodyOffset)
                    packetComplete = true
                    break@readLoop
                }
            }
            // All segments are 255 - packet continues on next page
            commentData.write(page.body)
        }

        val packetBytes = commentData.toByteArray()
        val vcData: ByteArray
        if (isOpus) {
            // "OpusTags" (8 bytes) prefix
            if (packetBytes.size < 8) return MusicMeta()
            vcData = packetBytes.copyOfRange(8, packetBytes.size)
        } else {
            // 0x03 + "vorbis" (7 bytes) prefix
            if (packetBytes.size < 7) return MusicMeta()
            vcData = packetBytes.copyOfRange(7, packetBytes.size)
        }

        val vc = MetaDataBlockVorbisComment.parse(vcData)
        val meta = MusicMeta()

        for (comment in vc.comments) {
            val eq = comment.indexOf('=')
            if (eq > 0) {
                val key = comment.substring(0, eq).uppercase()
                val value = comment.substring(eq + 1)
                when (key) {
                    "TITLE" -> meta.title = value
                    "ARTIST" -> meta.artist = value
                    "ALBUM" -> meta.album = value
                    "TRACKNUMBER" -> meta.trackNumber = value.trim().toIntOrNull()
                    "LYRICS" -> meta.lyrics = value
                    "METADATA_BLOCK_PICTURE" -> {
                        try {
                            val picPayload = Base64.decode(value)
                            val pic = MetaDataBlockPicture.parse(picPayload)
                            if (pic.pictureType == MetaDataBlockPicture.TYPE_FRONT_COVER) {
                                meta.pictureData = pic.pictureData
                                meta.pictureMimeType = pic.mimeType
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }
        return meta
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun write(filePath: String, meta: MusicMeta) {
        val inputFile = File(filePath)
        val tempFile = File("$filePath.oggtmp")
        try {
            DataInputStream(BufferedInputStream(FileInputStream(inputFile))).use { din ->
                DataOutputStream(BufferedOutputStream(FileOutputStream(tempFile))).use { dout ->
                    process(din, dout, meta)
                }
            }
            if (inputFile.delete()) {
                tempFile.renameTo(inputFile)
            }
        } catch (_: IOException) {
            tempFile.delete()
        }
    }

    private class OggPage(
        val headerType: Int,
        val granulePosition: Long,
        val serialNumber: Int,
        val pageSequenceNumber: Int,
        val segmentTable: ByteArray,
        val body: ByteArray
    )

    private fun readPage(din: DataInputStream): OggPage {
        val capture = ByteArray(4)
        din.readFully(capture)
        if (String(capture, StandardCharsets.US_ASCII) != "OggS") {
            throw IOException("Invalid OGG capture pattern")
        }
        din.readUnsignedByte()
        val headerType = din.readUnsignedByte()
        val granulePosition = readLongLE(din)
        val serialNumber = readIntLE(din)
        val pageSequenceNumber = readIntLE(din)
        readIntLE(din)
        val numSegments = din.readUnsignedByte()
        val segmentTable = ByteArray(numSegments)
        din.readFully(segmentTable)
        var bodySize = 0
        for (s in segmentTable) bodySize += s.toInt() and 0xFF
        val body = ByteArray(bodySize)
        din.readFully(body)
        return OggPage(
            headerType,
            granulePosition,
            serialNumber,
            pageSequenceNumber,
            segmentTable,
            body
        )
    }

    private fun writePage(dout: DataOutputStream, page: OggPage) {
        val headerSize = 27 + page.segmentTable.size
        val raw = ByteArray(headerSize + page.body.size)
        raw[0] = 'O'.code.toByte()
        raw[1] = 'g'.code.toByte()
        raw[2] = 'g'.code.toByte()
        raw[3] = 'S'.code.toByte()
        raw[4] = 0
        raw[5] = page.headerType.toByte()
        putLongLE(raw, 6, page.granulePosition)
        putIntLE(raw, 14, page.serialNumber)
        putIntLE(raw, 18, page.pageSequenceNumber)
        raw[26] = page.segmentTable.size.toByte()
        System.arraycopy(page.segmentTable, 0, raw, 27, page.segmentTable.size)
        System.arraycopy(page.body, 0, raw, headerSize, page.body.size)
        putIntLE(raw, 22, oggCrc(raw))
        dout.write(raw)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun process(din: DataInputStream, dout: DataOutputStream, meta: MusicMeta) {
        val page0 = readPage(din)
        val isOpus = page0.body.size >= 8 &&
                String(page0.body, 0, 8, StandardCharsets.US_ASCII) == "OpusHead"
        writePage(dout, page0)

        var trailingBody: ByteArray? = null
        var trailingSegments: ByteArray? = null
        var pagesConsumed = 0

        readLoop@ while (true) {
            val page = readPage(din)
            pagesConsumed++
            var bodyOffset = 0
            for (i in page.segmentTable.indices) {
                val segSize = page.segmentTable[i].toInt() and 0xFF
                bodyOffset += segSize
                if (segSize < 255) {
                    if (i + 1 < page.segmentTable.size) {
                        trailingSegments =
                            page.segmentTable.copyOfRange(i + 1, page.segmentTable.size)
                        trailingBody = page.body.copyOfRange(bodyOffset, page.body.size)
                    }
                    break@readLoop
                }
            }
        }

        val newPacket = buildCommentPacket(meta, isOpus)

        val commentSegments = buildSegmentTable(newPacket.size)
        val allSegments: List<Byte>
        val allBody: ByteArray
        if (trailingSegments != null && trailingBody != null) {
            allSegments = commentSegments + trailingSegments.toList()
            allBody = ByteArray(newPacket.size + trailingBody.size)
            System.arraycopy(newPacket, 0, allBody, 0, newPacket.size)
            System.arraycopy(trailingBody, 0, allBody, newPacket.size, trailingBody.size)
        } else {
            allSegments = commentSegments
            allBody = newPacket
        }

        var segOffset = 0
        var bodyOffset = 0
        var seq = 1
        while (segOffset < allSegments.size) {
            val count = minOf(255, allSegments.size - segOffset)
            val pageSegs = ByteArray(count) { allSegments[segOffset + it] }
            var pageBodySize = 0
            for (i in 0 until count) pageBodySize += pageSegs[i].toInt() and 0xFF
            val pageBody = allBody.copyOfRange(bodyOffset, bodyOffset + pageBodySize)
            val headerType = if (segOffset > 0) 0x01 else 0x00
            writePage(dout, OggPage(headerType, 0L, page0.serialNumber, seq, pageSegs, pageBody))
            segOffset += count
            bodyOffset += pageBodySize
            seq++
        }

        val seqAdjust = seq - (1 + pagesConsumed)
        while (true) {
            val page = try {
                readPage(din)
            } catch (_: Exception) {
                break
            }
            writePage(
                dout, OggPage(
                    page.headerType,
                    page.granulePosition,
                    page.serialNumber,
                    page.pageSequenceNumber + seqAdjust,
                    page.segmentTable,
                    page.body
                )
            )
        }
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun buildCommentPacket(meta: MusicMeta, isOpus: Boolean): ByteArray {
        val comments = mutableListOf<String>()
        meta.title?.let { comments.add("TITLE=$it") }
        meta.artist?.let { comments.add("ARTIST=$it") }
        meta.album?.let { comments.add("ALBUM=$it") }
        meta.trackNumber?.let { comments.add("TRACKNUMBER=$it") }
        meta.lyrics?.let { comments.add("LYRICS=$it") }

        if (meta.picture != null) {
            try {
                val img = ImageParser.parse(meta.picture!!)
                val picture = MetaDataBlockPicture(
                    MetaDataBlockPicture.TYPE_FRONT_COVER,
                    img.mimeType, "", img.width, img.height,
                    if (img.mimeType == "image/png") 32 else 24,
                    0, img.data
                )
                val b64 = Base64.encode(picture.buildPayload())
                comments.add("METADATA_BLOCK_PICTURE=$b64")
            } catch (_: IOException) {
            }
        } else if (meta.pictureData != null) {
            val img = ImageParser.parse(meta.pictureData!!)
            val picture = MetaDataBlockPicture(
                MetaDataBlockPicture.TYPE_FRONT_COVER,
                img.mimeType, "", img.width, img.height,
                if (img.mimeType == "image/png") 32 else 24,
                0, img.data
            )
            val b64 = Base64.encode(picture.buildPayload())
            comments.add("METADATA_BLOCK_PICTURE=$b64")
        }

        val vendor = if (isOpus) "libopus" else "Xiph.Org libVorbis I 20150105"
        val vcPayload = MetaDataBlockVorbisComment(vendor, comments).buildPayload()

        val baos = ByteArrayOutputStream()
        if (isOpus) {
            baos.write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
        } else {
            baos.write(0x03)
            baos.write("vorbis".toByteArray(StandardCharsets.US_ASCII))
        }
        baos.write(vcPayload)
        if (!isOpus) {
            baos.write(0x01)
        }
        return baos.toByteArray()
    }

    private fun buildSegmentTable(packetSize: Int): List<Byte> {
        val segments = mutableListOf<Byte>()
        var remaining = packetSize
        while (remaining >= 255) {
            segments.add(255.toByte())
            remaining -= 255
        }
        segments.add(remaining.toByte())
        return segments
    }

    private fun readIntLE(din: DataInputStream): Int {
        val b = ByteArray(4)
        din.readFully(b)
        return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readLongLE(din: DataInputStream): Long {
        val b = ByteArray(8)
        din.readFully(b)
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[i].toLong() and 0xFF)
        return v
    }

    private fun putIntLE(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putLongLE(buf: ByteArray, off: Int, v: Long) {
        for (i in 0..7) buf[off + i] = ((v ushr (i * 8)) and 0xFF).toByte()
    }

    private fun oggCrc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = (crc shl 8) xor CRC_TABLE[((crc ushr 24) and 0xFF) xor (b.toInt() and 0xFF)]
        }
        return crc
    }
}
