package com.ikunshare.sound.utils.crypto

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class MflacCrypto(ekey: ByteArray) : AutoCloseable {

    companion object {
        init {
            System.loadLibrary("MflacCrypto")
        }

        fun decryptFile(filePath: String, base64Ekey: String) {
            val ekeyBytes = base64Ekey.toByteArray(Charsets.US_ASCII)
            val src = File(filePath)
            val tmp = File("$filePath.tmp")

            MflacCrypto(ekeyBytes).use { crypto ->
                BufferedInputStream(FileInputStream(src)).use { input ->
                    BufferedOutputStream(FileOutputStream(tmp)).use { output ->
                        val buffer = ByteArray(8192)
                        var offset = 0L
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            val chunk = if (bytesRead == buffer.size) buffer
                            else buffer.copyOfRange(0, bytesRead)
                            val decrypted = crypto.decryptChunk(chunk, offset)
                            output.write(decrypted, 0, bytesRead)
                            offset += bytesRead
                        }
                        output.flush()
                    }
                }
            }

            if (!src.delete() || !tmp.renameTo(src)) {
                tmp.delete()
                throw IOException("Failed to replace file after decryption")
            }
        }
    }

    private var nativeHandle: Long = nativeInit(ekey)

    fun decryptChunk(src: ByteArray, offset: Long): ByteArray {
        check(nativeHandle != 0L) { "Crypto object has been closed or not initialized" }
        return nativeDecryptChunk(nativeHandle, src, offset)
    }

    override fun close() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
            nativeHandle = 0
        }
    }

    protected fun finalize() {
        if (nativeHandle != 0L) {
            nativeRelease(nativeHandle)
        }
    }

    private external fun nativeInit(ekey: ByteArray): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeDecryptChunk(handle: Long, src: ByteArray, offset: Long): ByteArray
}
