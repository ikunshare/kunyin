package com.ikunshare.sound.utils.tag.flac

import com.ikunshare.sound.utils.tag.MusicMeta
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

class FlacProcessor(private val newMeta: NewMetadata?) {

    companion object {
        private const val MDB_TYPE_VORBIS_COMMENT = 4
        private const val MDB_TYPE_PICTURE = 6

        fun readMeta(inSource: InputStream): MusicMeta {
            val din = DataInputStream(BufferedInputStream(inSource))
            val marker = ByteArray(4)
            din.readFully(marker)
            if (!marker.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII))) {
                throw IOException("Not a valid FLAC file")
            }

            val meta = MusicMeta()
            var isLastBlock = false

            while (!isLastBlock) {
                val headerByte = din.readUnsignedByte()
                val lengthH = din.readUnsignedByte()
                val lengthM = din.readUnsignedByte()
                val lengthL = din.readUnsignedByte()

                isLastBlock = (headerByte and 0x80) != 0
                val type = headerByte and 0x7F
                val length = (lengthH shl 16) or (lengthM shl 8) or lengthL

                when (type) {
                    MDB_TYPE_VORBIS_COMMENT -> {
                        val blockData = ByteArray(length)
                        din.readFully(blockData)
                        val vc = MetaDataBlockVorbisComment.parse(blockData)
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
                                }
                            }
                        }
                    }

                    MDB_TYPE_PICTURE -> {
                        val blockData = ByteArray(length)
                        din.readFully(blockData)
                        val pic = MetaDataBlockPicture.parse(blockData)
                        if (pic.pictureType == MetaDataBlockPicture.TYPE_FRONT_COVER) {
                            meta.pictureData = pic.pictureData
                            meta.pictureMimeType = pic.mimeType
                        }
                    }

                    else -> {
                        var skipped = 0
                        while (skipped < length) {
                            val count = din.skipBytes(length - skipped)
                            if (count == 0) {
                                if (din.read() < 0) throw EOFException("Unexpected EOF during skip")
                                skipped++
                            } else {
                                skipped += count
                            }
                        }
                    }
                }
            }
            return meta
        }
    }

    private var tasks = 0

    init {
        if (newMeta != null) {
            if (newMeta.vorbis != null) tasks++
            if (newMeta.picture != null) tasks++
        }
    }

    fun process(inSource: InputStream, outDest: OutputStream) {
        val din = DataInputStream(BufferedInputStream(inSource))
        val dout = DataOutputStream(BufferedOutputStream(outDest))

        val marker = ByteArray(4)
        din.readFully(marker)
        if (!marker.contentEquals("fLaC".toByteArray(StandardCharsets.US_ASCII))) {
            throw IOException("Not a valid FLAC file")
        }
        dout.write(marker)

        var isLastBlock = false

        while (!isLastBlock) {
            val headerByte = din.readUnsignedByte()
            val lengthH = din.readUnsignedByte()
            val lengthM = din.readUnsignedByte()
            val lengthL = din.readUnsignedByte()

            isLastBlock = (headerByte and 0x80) != 0
            val type = headerByte and 0x7F
            val length = (lengthH shl 16) or (lengthM shl 8) or lengthL

            when (type) {
                MDB_TYPE_VORBIS_COMMENT if newMeta?.vorbis != null -> {
                    skipBytes(din, length)
                    val isFinal = isLastBlock && tasks == 1
                    writeBlock(dout, MDB_TYPE_VORBIS_COMMENT, newMeta.vorbis!!, isFinal)
                    newMeta.vorbis = null
                    tasks--
                }

                MDB_TYPE_PICTURE if newMeta?.picture != null -> {
                    skipBytes(din, length)
                    val isFinal = isLastBlock && tasks == 1
                    writeBlock(dout, MDB_TYPE_PICTURE, newMeta.picture!!, isFinal)
                    newMeta.picture = null
                    tasks--
                }

                else -> {
                    var writeAsLast = isLastBlock
                    if (isLastBlock && tasks > 0) writeAsLast = false

                    var newHeaderFirst = type and 0x7F
                    if (writeAsLast) newHeaderFirst = newHeaderFirst or 0x80

                    dout.write(newHeaderFirst)
                    dout.write(lengthH)
                    dout.write(lengthM)
                    dout.write(lengthL)
                    copyStream(din, dout, length.toLong())
                }
            }

            if (isLastBlock && tasks > 0) {
                if (newMeta?.vorbis != null) {
                    val isFinal = tasks == 1
                    writeBlock(dout, MDB_TYPE_VORBIS_COMMENT, newMeta.vorbis!!, isFinal)
                    newMeta.vorbis = null
                    tasks--
                }
                if (newMeta?.picture != null) {
                    val isFinal = tasks == 1
                    writeBlock(dout, MDB_TYPE_PICTURE, newMeta.picture!!, isFinal)
                    newMeta.picture = null
                    tasks--
                }
            }
        }

        copyStream(din, dout, -1)
        dout.flush()
    }

    private fun writeBlock(
        out: DataOutputStream,
        type: Int,
        blockObj: MetaDataBlock,
        isLast: Boolean
    ) {
        val payload = blockObj.buildPayload()
        val length = payload.size
        var headerFirst = type and 0x7F
        if (isLast) headerFirst = headerFirst or 0x80
        out.write(headerFirst)
        out.write((length ushr 16) and 0xFF)
        out.write((length ushr 8) and 0xFF)
        out.write(length and 0xFF)
        out.write(payload)
    }

    private fun skipBytes(din: DataInputStream, n: Int) {
        var skipped = 0
        while (skipped < n) {
            val count = din.skipBytes(n - skipped)
            if (count == 0) {
                if (din.read() < 0) throw EOFException("Unexpected EOF during skip")
                skipped++
            } else {
                skipped += count
            }
        }
    }

    private fun copyStream(din: DataInputStream, dout: DataOutputStream, length: Long) {
        val buffer = ByteArray(8192)
        if (length == -1L) {
            var read: Int
            while (din.read(buffer).also { read = it } != -1) {
                dout.write(buffer, 0, read)
            }
            return
        }
        var remaining = length
        while (remaining > 0) {
            val toRead = minOf(remaining, buffer.size.toLong()).toInt()
            val read = din.read(buffer, 0, toRead)
            if (read == -1) throw EOFException("Unexpected EOF during copy")
            dout.write(buffer, 0, read)
            remaining -= read
        }
    }

    class NewMetadata(
        var vorbis: MetaDataBlockVorbisComment?,
        var picture: MetaDataBlockPicture?
    )
}
