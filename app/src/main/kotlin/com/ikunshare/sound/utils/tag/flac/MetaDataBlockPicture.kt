package com.ikunshare.sound.utils.tag.flac

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

class MetaDataBlockPicture(
    val pictureType: Int,
    val mimeType: String = "image/jpeg",
    val description: String = "",
    val width: Int,
    val height: Int,
    val bitsPerPixel: Int,
    val colors: Int,
    val pictureData: ByteArray
) : MetaDataBlock {

    companion object {
        const val TYPE_FRONT_COVER = 3

        fun parse(data: ByteArray): MetaDataBlockPicture {
            var offset = 0
            val pictureType = readIntBE(data, offset); offset += 4
            val mimeLen = readIntBE(data, offset); offset += 4
            val mimeType = String(data, offset, mimeLen, StandardCharsets.UTF_8); offset += mimeLen
            val descLen = readIntBE(data, offset); offset += 4
            val description =
                String(data, offset, descLen, StandardCharsets.UTF_8); offset += descLen
            val width = readIntBE(data, offset); offset += 4
            val height = readIntBE(data, offset); offset += 4
            val bitsPerPixel = readIntBE(data, offset); offset += 4
            val colors = readIntBE(data, offset); offset += 4
            val dataLen = readIntBE(data, offset); offset += 4
            val picData = data.copyOfRange(offset, offset + dataLen)
            return MetaDataBlockPicture(
                pictureType,
                mimeType,
                description,
                width,
                height,
                bitsPerPixel,
                colors,
                picData
            )
        }

        private fun readIntBE(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                    ((data[offset + 1].toInt() and 0xFF) shl 16) or
                    ((data[offset + 2].toInt() and 0xFF) shl 8) or
                    (data[offset + 3].toInt() and 0xFF)
    }

    override fun buildPayload(): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(pictureType)
        val mimeBytes = mimeType.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(mimeBytes.size)
        dos.write(mimeBytes)
        val descBytes = description.toByteArray(StandardCharsets.UTF_8)
        dos.writeInt(descBytes.size)
        dos.write(descBytes)
        dos.writeInt(width)
        dos.writeInt(height)
        dos.writeInt(bitsPerPixel)
        dos.writeInt(colors)
        dos.writeInt(pictureData.size)
        dos.write(pictureData)
        return baos.toByteArray()
    }
}
