package com.ikunshare.music.platform.wy.utils

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.math.min

private val RSA_MODULUS = BigInteger("fd90bd466ff9bc8a3fec2fbcf263b90d5c564879fa5d7aab89b31c1d5cb4139d", 16)
private val RSA_EXPONENT = BigInteger.valueOf(65_537)
private val RANDOM = SecureRandom()
private const val NCBL_HEADER_SIZE = 70
private const val NCBL_META_TYPE = 0x4343
private const val DEFAULT_FRAME_SIZE = 0x8000
private const val ZSTD_RAW_BLOCK_SIZE = 0x20000

internal fun buildNcblRecord(time: Long, action: String, dataJson: String): String =
    "$time\u0001$action\u0001$dataJson"

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { RANDOM.nextBytes(it) }

private fun readIntLe(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xff) or
        ((data[offset + 1].toInt() and 0xff) shl 8) or
        ((data[offset + 2].toInt() and 0xff) shl 16) or
        ((data[offset + 3].toInt() and 0xff) shl 24)

private fun readShortLe(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

private fun writeIntLe(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value ushr 8) and 0xff)
    out.write((value ushr 16) and 0xff)
    out.write((value ushr 24) and 0xff)
}

private fun writeShortLe(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xff)
    out.write((value ushr 8) and 0xff)
}

private fun quarterRound(state: IntArray, a: Int, b: Int, c: Int, d: Int) {
    state[a] += state[b]
    state[d] = (state[d] xor state[a]).rotateLeft(16)
    state[c] += state[d]
    state[b] = (state[b] xor state[c]).rotateLeft(12)
    state[a] += state[b]
    state[d] = (state[d] xor state[a]).rotateLeft(8)
    state[c] += state[d]
    state[b] = (state[b] xor state[c]).rotateLeft(7)
}

private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
    val state = intArrayOf(
        0x61707865, 0x3320646e, 0x79622d32, 0x6b206574,
        0, 0, 0, 0, 0, 0, 0, 0,
        counter,
        readIntLe(nonce, 0), readIntLe(nonce, 4), readIntLe(nonce, 8)
    )
    for (i in 0 until 8) state[4 + i] = readIntLe(key, i * 4)
    val work = state.copyOf()
    repeat(10) {
        quarterRound(work, 0, 4, 8, 12)
        quarterRound(work, 1, 5, 9, 13)
        quarterRound(work, 2, 6, 10, 14)
        quarterRound(work, 3, 7, 11, 15)
        quarterRound(work, 0, 5, 10, 15)
        quarterRound(work, 1, 6, 11, 12)
        quarterRound(work, 2, 7, 8, 13)
        quarterRound(work, 3, 4, 9, 14)
    }
    return ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        for (i in 0 until 16) putInt(work[i] + state[i])
    }.array()
}

internal fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
    require(key.size == 32 && nonce.size == 12)
    val result = ByteArray(data.size)
    var offset = 0
    while (offset < data.size) {
        val stream = chachaBlock(key, counter + offset / 64, nonce)
        val end = min(offset + 64, data.size)
        for (i in offset until end) {
            result[i] = (data[i].toInt() xor stream[i - offset].toInt()).toByte()
        }
        offset = end
    }
    return result
}

internal fun rsaWrap(keyA: ByteArray): ByteArray {
    require(keyA.size == 32)
    val encoded = BigInteger(1, keyA).modPow(RSA_EXPONENT, RSA_MODULUS).toByteArray()
    val result = ByteArray(32)
    val count = min(encoded.size, result.size)
    System.arraycopy(encoded, encoded.size - count, result, result.size - count, count)
    return result
}

internal fun zstdRawFrame(data: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(data.size + 14)
    out.writeBytes(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte()))
    when {
        data.size <= 0xff -> {
            out.write(0x20)
            out.write(data.size)
        }
        data.size <= 0xffff -> {
            out.write(0x60)
            writeShortLe(out, data.size)
        }
        else -> {
            out.write(0xa0)
            writeIntLe(out, data.size)
        }
    }
    var offset = 0
    do {
        val size = min(ZSTD_RAW_BLOCK_SIZE, data.size - offset)
        val last = offset + size == data.size
        val header = (size shl 3) or if (last) 1 else 0
        out.write(header and 0xff)
        out.write((header ushr 8) and 0xff)
        out.write((header ushr 16) and 0xff)
        if (size > 0) out.write(data, offset, size)
        offset += size
    } while (!last)
    return out.toByteArray()
}

internal fun encryptNcbl(
    meta: String,
    body: String,
    keyA: ByteArray? = null,
    uuid: ByteArray? = null,
    baseSeq: Int? = null,
    maxFrame: Int = DEFAULT_FRAME_SIZE
): ByteArray {
    require(maxFrame in 1..0xffff)
    val recordKey = (keyA?.copyOf() ?: randomBytes(32)).also {
        require(it.size == 32)
        if ((it[0].toInt() and 0xff) >= 0xa3) it[0] = 0xa2.toByte()
    }
    val wrappedKey = rsaWrap(recordKey)
    val id = (uuid?.copyOf() ?: randomBytes(16)).also {
        require(it.size == 16)
        if (uuid == null) {
            it[6] = ((it[6].toInt() and 0x0f) or 0x40).toByte()
            it[8] = ((it[8].toInt() and 0x3f) or 0x80).toByte()
        }
    }
    val nonce = id.copyOfRange(0, 12)
    val counter = readIntLe(id, 12) ushr 2
    val firstSeq = baseSeq ?: readShortLe(randomBytes(2), 0)

    val metaCipher = chacha20(wrappedKey, counter, nonce, meta.toByteArray(Charsets.UTF_8))
    val metaBlock = ByteArrayOutputStream(4 + metaCipher.size).apply {
        writeShortLe(this, NCBL_META_TYPE)
        writeShortLe(this, metaCipher.size)
        writeBytes(metaCipher)
    }.toByteArray()
    val headerLength = NCBL_HEADER_SIZE + metaBlock.size

    val compressed = zstdRawFrame(body.toByteArray(Charsets.UTF_8))
    val trailing = ByteArrayOutputStream()
    var offset = 0
    var sequence = firstSeq
    while (offset < compressed.size) {
        val end = min(offset + maxFrame, compressed.size)
        val cipher = chacha20(recordKey, counter, nonce, compressed.copyOfRange(offset, end))
        writeShortLe(trailing, cipher.size)
        writeIntLe(trailing, sequence)
        trailing.writeBytes(cipher)
        sequence++
        offset = end
    }
    val trailingBytes = trailing.toByteArray()
    val header = ByteBuffer.allocate(NCBL_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("NCBL".toByteArray(Charsets.US_ASCII))
        putInt(3)
        putShort(headerLength.toShort())
        put(id)
        put(wrappedKey)
        putInt(firstSeq)
        putInt(sequence - 1)
        putInt(trailingBytes.size)
    }.array()
    return ByteArrayOutputStream(headerLength + trailingBytes.size).apply {
        writeBytes(header)
        writeBytes(metaBlock)
        writeBytes(trailingBytes)
    }.toByteArray()
}
