package com.ikunshare.sound.platform.qq.device

import android.util.Base64
import com.ikunshare.sound.platform.qq.device.QQDeviceCrypto.generateMValue
import com.ikunshare.sound.platform.qq.device.QQDeviceCrypto.oicqTeaEncrypt
import kotlin.random.Random

/**
 * QQ 设备相关的底层加密原语，全部对齐后端实现。
 *
 * - [generateMValue]：OICQ TEA（QQ 私有 16 轮 TEA + CBC 变体），用 AndroidID 作密钥加密固定明文。
 * - [oicqTeaEncrypt]：QQ 客户端经典的带随机填充的 TEA 流加密。
 */
object QQDeviceCrypto {

    private const val TEA_DELTA = 0x9E3779B9L
    private const val MASK32 = 0xFFFFFFFFL

    /** 生成 MValue：OICQ-TEA 加密固定明文后 Base64。 */
    fun generateMValue(androidId: String): String {
        val plaintext = """{"did":null,"mcc":null,"mnc":null}""".toByteArray(Charsets.UTF_8)
        val encrypted = oicqTeaEncrypt(plaintext, androidId.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** 单块 16 轮 TEA 加密，8 字节进 8 字节出。 */
    private fun teaEncryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        var v0 = readUInt32(block, 0)
        var v1 = readUInt32(block, 4)
        val k0 = readUInt32(key, 0)
        val k1 = readUInt32(key, 4)
        val k2 = readUInt32(key, 8)
        val k3 = readUInt32(key, 12)

        var sum = 0L
        repeat(16) {
            sum = (sum + TEA_DELTA) and MASK32
            v0 = (v0 + (((v1 shl 4) + k0) xor (v1 + sum) xor ((v1 ushr 5) + k1))) and MASK32
            v1 = (v1 + (((v0 shl 4) + k2) xor (v0 + sum) xor ((v0 ushr 5) + k3))) and MASK32
        }

        val out = ByteArray(8)
        writeUInt32(out, 0, v0)
        writeUInt32(out, 4, v1)
        return out
    }

    /**
     * QQ 经典 TEA 流加密：头字节记录填充长度，随机填充对齐到 8 字节块，
     * 再以 CBC 方式逐块异或后 TEA 加密。
     */
    private fun oicqTeaEncrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val plainLen = plaintext.size
        var fill = (plainLen + 10) % 8
        if (fill != 0) fill = 8 - fill

        val padded = ArrayList<Byte>(1 + fill + 2 + plainLen + 7)
        val header = ((Random.nextInt(256) and 0xF8) or fill).toByte()
        padded.add(header)
        repeat(fill) { padded.add(Random.nextInt(256).toByte()) }
        padded.add(Random.nextInt(256).toByte())
        padded.add(Random.nextInt(256).toByte())
        plaintext.forEach { padded.add(it) }
        repeat(7) { padded.add(0) }

        val teaKey = ByteArray(16)
        System.arraycopy(key, 0, teaKey, 0, minOf(16, key.size))

        val data = padded.toByteArray()
        val numBlocks = data.size / 8
        val output = ByteArray(data.size)
        var tPrev = ByteArray(8)
        var cPrev = ByteArray(8)

        for (i in 0 until numBlocks) {
            val block = data.copyOfRange(i * 8, i * 8 + 8)
            val tI = xorBytes(block, cPrev)
            val encrypted = teaEncryptBlock(tI, teaKey)
            val cI = xorBytes(encrypted, tPrev)
            System.arraycopy(cI, 0, output, i * 8, 8)
            tPrev = tI
            cPrev = cI
        }
        return output
    }

    private fun xorBytes(a: ByteArray, b: ByteArray): ByteArray =
        ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun readUInt32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xff) shl 24) or
                ((b[off + 1].toLong() and 0xff) shl 16) or
                ((b[off + 2].toLong() and 0xff) shl 8) or
                (b[off + 3].toLong() and 0xff)

    private fun writeUInt32(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 24) and 0xff).toByte()
        b[off + 1] = ((v ushr 16) and 0xff).toByte()
        b[off + 2] = ((v ushr 8) and 0xff).toByte()
        b[off + 3] = (v and 0xff).toByte()
    }
}
