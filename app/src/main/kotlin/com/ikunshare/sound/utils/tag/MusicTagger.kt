package com.ikunshare.sound.utils.tag

import com.ikunshare.sound.utils.tag.flac.FlacHandler
import com.ikunshare.sound.utils.tag.mp3.Mp3Processor
import com.ikunshare.sound.utils.tag.ogg.OggProcessor

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
}