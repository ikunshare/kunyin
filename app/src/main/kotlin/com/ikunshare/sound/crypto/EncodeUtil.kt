package com.ikunshare.sound.crypto

object EncodeUtil {
    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    /**
     * 字节数组转 Hex 字符串
     */
    fun byteArrayToHex(bytes: ByteArray): String {
        val result = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val i = b.toInt()
            result.append(HEX_CHARS[i shr 4 and 0x0f])
            result.append(HEX_CHARS[i and 0x0f])
        }
        return result.toString()
    }
}
