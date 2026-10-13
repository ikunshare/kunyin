package com.ikunshare.music.utils.tag

import com.ikunshare.music.utils.tag.flac.FlacHandler
import com.ikunshare.music.utils.tag.mp3.Mp3Processor
import com.ikunshare.music.utils.tag.ogg.OggProcessor

object MusicTagger {
    fun read(filePath: String): MusicMeta? {
        val lowerPath = filePath.lowercase()
        return when {
            lowerPath.endsWith(".mp3") -> Mp3Processor.read(filePath)
            lowerPath.endsWith(".flac") -> FlacHandler.read(filePath)
            lowerPath.endsWith(".ogg") -> OggProcessor.read(filePath)
            else -> null
        }
    }

    fun write(filePath: String, meta: MusicMeta) {
        val lowerPath = filePath.lowercase()
        when {
            lowerPath.endsWith(".mp3") -> Mp3Processor.write(filePath, meta)
            lowerPath.endsWith(".flac") -> FlacHandler.write(filePath, meta)
            lowerPath.endsWith(".ogg") -> OggProcessor.write(filePath, meta)
        }
    }

    fun edit(filePath: String, block: MusicMeta.() -> Unit) {
        val meta = read(filePath) ?: MusicMeta()
        meta.block()
        write(filePath, meta)
    }

    /** Downloaded container metadata takes priority over search-result metadata. */
    fun fillMissing(filePath: String, wanted: MusicMeta) {
        val existing = read(filePath) ?: MusicMeta()
        fillMissingMusicMeta(existing, wanted)
        write(filePath, existing)
    }
}

internal fun fillMissingMusicMeta(existing: MusicMeta, wanted: MusicMeta) {
    if (existing.title.isNullOrBlank() && !wanted.title.isNullOrBlank()) existing.title = wanted.title
    if (existing.artist.isNullOrBlank() && !wanted.artist.isNullOrBlank()) existing.artist = wanted.artist
    if (existing.album.isNullOrBlank() && !wanted.album.isNullOrBlank()) existing.album = wanted.album
    if (existing.trackNumber == null) existing.trackNumber = wanted.trackNumber
    if (existing.discNumber == null) existing.discNumber = wanted.discNumber
    if (existing.discTotal == null) existing.discTotal = wanted.discTotal
    if (existing.discName.isNullOrBlank() && !wanted.discName.isNullOrBlank()) existing.discName = wanted.discName
    if (existing.lyrics.isNullOrBlank() && !wanted.lyrics.isNullOrBlank()) existing.lyrics = wanted.lyrics
    if (existing.picture.isNullOrBlank() && !wanted.picture.isNullOrBlank()) existing.picture = wanted.picture
    if (existing.pictureData?.isNotEmpty() != true && wanted.pictureData?.isNotEmpty() == true) existing.pictureData = wanted.pictureData
    if (existing.pictureMimeType.isNullOrBlank() && !wanted.pictureMimeType.isNullOrBlank()) existing.pictureMimeType = wanted.pictureMimeType
}
