package com.ikunshare.music.utils.crypto

import com.ikunshare.music.utils.EncodeUtil
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

object KgCrypto {
    private val signingKey = "9046ad4ecae74a70aa750c1bb2307ae6".toByteArray(Charsets.US_ASCII)
    private val identityKey = MessageDigest.getInstance("MD5").digest(signingKey)

    fun getKgIdentity(body: String): String =
        Base64.encode(hmacSha256(identityKey, body.toModifiedUtf8()))

    fun getSignature(body: String): String {
        val digest = MessageDigest.getInstance("MD5")
        digest.update(body.toModifiedUtf8())
        return EncodeUtil.byteArrayToHex(digest.digest(signingKey))
    }

    fun getTrialSignature(body: String): String =
        EncodeUtil.byteArrayToHex(hmacSha256(signingKey, body.toModifiedUtf8()))

    private fun hmacSha256(key: ByteArray, body: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(body)
        }

    // Match the former JNI GetStringUTFChars encoding: NUL uses two bytes,
    // and each UTF-16 surrogate is encoded separately using three bytes.
    private fun String.toModifiedUtf8(): ByteArray {
        val byteCount = sumOf { char ->
            when (char.code) {
                in 1..0x7f -> 1
                in 0..0x7ff -> 2
                else -> 3
            }
        }
        val bytes = ByteArray(byteCount)
        var offset = 0
        for (char in this) {
            val value = char.code
            when (value) {
                in 1..0x7f -> bytes[offset++] = value.toByte()
                in 0..0x7ff -> {
                    bytes[offset++] = (0xc0 or (value ushr 6)).toByte()
                    bytes[offset++] = (0x80 or (value and 0x3f)).toByte()
                }
                else -> {
                    bytes[offset++] = (0xe0 or (value ushr 12)).toByte()
                    bytes[offset++] = (0x80 or ((value ushr 6) and 0x3f)).toByte()
                    bytes[offset++] = (0x80 or (value and 0x3f)).toByte()
                }
            }
        }
        return bytes
    }
}
