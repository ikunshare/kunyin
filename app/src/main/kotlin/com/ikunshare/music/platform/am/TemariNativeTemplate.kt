package com.ikunshare.music.platform.am

import com.google.gson.JsonObject

class TemariTemplate(json: String) : AppleSampleDecryptor {
    constructor(data: JsonObject) : this(data.toString())

    private var handle = nativeCreate(json.toByteArray(Charsets.UTF_8)).also {
        require(it != 0L) { "Temari 密钥模板无效" }
    }

    @Synchronized
    override fun decrypt(sample: ByteArray): ByteArray {
        check(handle != 0L) { "Temari 模板已关闭" }
        return nativeDecrypt(handle, sample)
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(json: ByteArray): Long
    private external fun nativeDecrypt(handle: Long, sample: ByteArray): ByteArray
    private external fun nativeDestroy(handle: Long)

    companion object {
        init {
            System.loadLibrary("temari")
            System.loadLibrary("TemariJni")
        }
    }
}
