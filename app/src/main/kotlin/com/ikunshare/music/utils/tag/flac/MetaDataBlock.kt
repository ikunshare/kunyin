package com.ikunshare.music.utils.tag.flac

interface MetaDataBlock {
    fun buildPayload(): ByteArray
}
