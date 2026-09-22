package com.ikunshare.sound.sync

import android.util.Base64
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** 与 Go 服务端 `sync/crypto.go` 对齐的加密/压缩工具。 */
object SyncCrypto {

    /** 与 Go 端 deriveAESKey 完全一致：md5(password) 取 hex 前 16 字节 → base64。 */
    fun deriveAESKey(password: String): String {
        val md5 = MessageDigest.getInstance("MD5").digest(password.toByteArray(Charsets.UTF_8))
        val hex = md5.joinToString("") { "%02x".format(it) }
        return Base64.encodeToString(
            hex.substring(0, 16).toByteArray(Charsets.US_ASCII),
            Base64.NO_WRAP
        )
    }

    /** 16 字节随机 → base64，作为新设备会话密钥。 */
    fun generateAESKey(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return Base64.encodeToString(b, Base64.NO_WRAP)
    }

    /** AES-128 ECB + PKCS7（与 Go 端 block-by-block 加密等价）。 */
    fun aesEncrypt(plain: String, keyB64: String): String {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        return Base64.encodeToString(
            cipher.doFinal(plain.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
    }

    fun aesDecrypt(cipherB64: String, keyB64: String): String {
        val key = Base64.decode(keyB64, Base64.DEFAULT)
        val data = Base64.decode(cipherB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    /** RSA-OAEP-SHA1 解密，对应 Go 端用客户端公钥加密、客户端私钥解密的流程。 */
    fun rsaDecrypt(cipherB64: String, privateKey: java.security.PrivateKey): ByteArray {
        val data = Base64.decode(cipherB64, Base64.DEFAULT)
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding").apply {
            val spec = OAEPParameterSpec(
                "SHA-1", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT
            )
            init(Cipher.DECRYPT_MODE, privateKey, spec)
        }
        return cipher.doFinal(data)
    }

    /** PEM(SPKI base64) → PublicKey；用于将服务端可能发回的公钥解析（备用）。 */
    fun parsePublicKey(pem: String): java.security.PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val spec = X509EncodedKeySpec(Base64.decode(body, Base64.DEFAULT))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /** > 1024 字节时 gzip+base64，加 `cg_` 前缀。与 Go 端逻辑一致（按字节而非字符计长度）。 */
    fun compressMsg(msg: String): String {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        if (bytes.size <= SyncProtocol.COMPRESS_THRESH) return msg
        val out = java.io.ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return SyncProtocol.COMPRESS_PREFIX +
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressMsg(msg: String): String {
        if (!msg.startsWith(SyncProtocol.COMPRESS_PREFIX)) return msg
        val payload = msg.substring(SyncProtocol.COMPRESS_PREFIX.length)
        val data = Base64.decode(payload, Base64.DEFAULT)
        return GZIPInputStream(java.io.ByteArrayInputStream(data)).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }

    /** 直接对二进制 gzip 数据解压（用于服务端发送的原始 gzip 二进制帧）。 */
    fun decompressGzipBytes(data: ByteArray): String {
        return GZIPInputStream(java.io.ByteArrayInputStream(data)).use {
            String(it.readBytes(), Charsets.UTF_8)
        }
    }

    /** 检测是否为 gzip 数据（magic bytes 0x1f 0x8b）。 */
    fun isGzipBytes(data: ByteArray): Boolean =
        data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()
}
