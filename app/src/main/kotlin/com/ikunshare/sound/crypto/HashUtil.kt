package com.ikunshare.sound.crypto

import java.security.MessageDigest

/**
 * 摘要算法接口
 */
interface Hasher {
    fun update(data: ByteArray): Hasher
    fun update(data: String): Hasher = update(data.toByteArray(Charsets.UTF_8))
    fun digest(): ByteArray
    fun digestHex(): String = EncodeUtil.byteArrayToHex(digest())
    fun reset()
}

/**
 * Java 原生 MessageDigest 包装
 */
class JavaHasher(algorithm: String) : Hasher {
    private val digest = MessageDigest.getInstance(algorithm)

    override fun update(data: ByteArray): Hasher {
        digest.update(data)
        return this
    }

    override fun digest(): ByteArray = digest.digest()
    override fun reset() = digest.reset()
}

object HashUtil {
    // --- 摘要算法获取器 ---
    fun md5() = JavaHasher("MD5")

    // --- 摘要快捷方法 (直接返回 Hex) ---
    fun md5(input: String) = md5().update(input).digestHex()
}
