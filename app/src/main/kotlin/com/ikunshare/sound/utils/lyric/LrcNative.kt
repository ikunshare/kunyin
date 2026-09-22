package com.ikunshare.sound.utils.lyric

object LrcNative {
    init {
        System.loadLibrary("Lrc")
    }

    @JvmStatic
    external fun decryptKrc(data: ByteArray): String?

    @JvmStatic
    external fun decryptKuwo(data: ByteArray): ByteArray?

    @JvmStatic
    external fun buildKuwoParams(musicId: String): String

    @JvmStatic
    external fun decryptQrc(hexData: String): String?
}
