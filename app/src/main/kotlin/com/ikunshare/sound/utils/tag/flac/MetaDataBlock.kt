package com.ikunshare.sound.utils.tag.flac

interface MetaDataBlock {
    fun buildPayload(): ByteArray
}
