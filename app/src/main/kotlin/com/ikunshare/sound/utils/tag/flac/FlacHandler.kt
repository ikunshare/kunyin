package com.ikunshare.sound.utils.tag.flac

import com.ikunshare.sound.utils.tag.MusicMeta
import com.ikunshare.sound.utils.tag.util.ImageParser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object FlacHandler {

    fun read(filePath: String): MusicMeta? {
        return try {
            FileInputStream(File(filePath)).use { fis ->
                FlacProcessor.readMeta(fis)
            }
        } catch (_: IOException) {
            null
        }
    }

    fun write(filePath: String, meta: MusicMeta) {
        processFile(filePath, meta)
    }

    private fun processFile(filePath: String, meta: MusicMeta) {
        val comments = mutableListOf<String>()
        meta.title?.let { comments.add("TITLE=$it") }
        meta.artist?.let { comments.add("ARTIST=$it") }
        meta.album?.let { comments.add("ALBUM=$it") }
        meta.trackNumber?.let { comments.add("TRACKNUMBER=$it") }
        meta.lyrics?.let { comments.add("LYRICS=$it") }

        val vorbis = MetaDataBlockVorbisComment("reference libFLAC 1.2.1 20070917", comments)

        var picture: MetaDataBlockPicture? = null
        if (meta.picture != null) {
            try {
                val img = ImageParser.parse(meta.picture!!)
                picture = MetaDataBlockPicture(
                    MetaDataBlockPicture.TYPE_FRONT_COVER,
                    img.mimeType,
                    "",
                    img.width,
                    img.height,
                    if (img.mimeType == "image/png") 32 else 24,
                    0,
                    img.data
                )
            } catch (_: IOException) {
            }
        } else if (meta.pictureData != null) {
            val img = ImageParser.parse(meta.pictureData!!)
            picture = MetaDataBlockPicture(
                MetaDataBlockPicture.TYPE_FRONT_COVER,
                img.mimeType,
                "",
                img.width,
                img.height,
                if (img.mimeType == "image/png") 32 else 24,
                0,
                img.data
            )
        }

        val newMeta = FlacProcessor.NewMetadata(vorbis, picture)
        val inputFile = File(filePath)
        val tempFile = File("$filePath.lxmtemp")

        try {
            FileInputStream(inputFile).use { fis ->
                FileOutputStream(tempFile).use { fos ->
                    val processor = FlacProcessor(newMeta)
                    processor.process(fis, fos)
                }
            }
            if (inputFile.delete()) {
                tempFile.renameTo(inputFile)
            }
        } catch (_: IOException) {
            tempFile.delete()
        }
    }
}
