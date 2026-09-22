package com.ikunshare.sound.utils.tag.flac

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class MetaDataBlockVorbisComment(
    val vendor: String,
    val comments: List<String>
) : MetaDataBlock {

    override fun buildPayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val vendorBytes = vendor.toByteArray(StandardCharsets.UTF_8)
        writeIntLE(baos, vendorBytes.size)
        baos.write(vendorBytes)
        writeIntLE(baos, comments.size)
        comments.forEach { comment ->
            val commentBytes = comment.toByteArray(StandardCharsets.UTF_8)
            writeIntLE(baos, commentBytes.size)
            baos.write(commentBytes)
        }
        return baos.toByteArray()
    }

    companion object {
        fun parse(data: ByteArray): MetaDataBlockVorbisComment {
            var offset = 0
            val vendorLength = readIntLE(data, offset); offset += 4
            val vendor =
                String(data, offset, vendorLength, StandardCharsets.UTF_8); offset += vendorLength
            val commentCount = readIntLE(data, offset); offset += 4
            val comments = mutableListOf<String>()
            repeat(commentCount) {
                val len = readIntLE(data, offset); offset += 4
                comments.add(String(data, offset, len, StandardCharsets.UTF_8)); offset += len
            }
            return MetaDataBlockVorbisComment(vendor, comments)
        }

        private fun readIntLE(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun writeIntLE(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }
}
