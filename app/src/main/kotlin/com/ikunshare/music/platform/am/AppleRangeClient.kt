package com.ikunshare.music.platform.am

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 进程内共享的 HTTP 客户端；Apple 分片请求量大，连接池与线程池只建一次。 */
internal val sharedAppleHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}

class AppleRangeClient(
    private val client: OkHttpClient = sharedAppleHttpClient
) {
    suspend fun fetch(url: String, start: Long, end: Long): ByteArray {
        require(start >= 0L && end >= start) { "无效的 Apple Range: $start-$end" }
        val length = end - start + 1L
        require(length <= Int.MAX_VALUE) { "Apple Range 过大: $length" }
        val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
        val call = client.newCall(request)
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = runCatching { response.use { readRange(call, it, start, length) } }
                    if (continuation.isActive) {
                        result.onSuccess { continuation.resume(it) }
                            .onFailure { continuation.resumeWithException(it) }
                    }
                }
            })
        }
    }

    private fun readRange(call: Call, response: Response, start: Long, length: Long): ByteArray {
        if (!response.isSuccessful) throw IOException("Apple Music 分片请求失败 (${response.code})")
        val skip = if (response.code == 206) 0L else start
        val exact = response.code == 206 &&
            response.header("Content-Length")?.toLongOrNull() == length
        val target = ByteArray(length.toInt())
        var complete = false
        var overflow = false
        response.body.byteStream().use { input ->
            // 206 正常路径直接读进目标数组，省掉 ByteArrayOutputStream + copyOfRange 的整块拷贝。
            if (skip > 0L) {
                // 服务器忽略 Range 返回整段时，先丢弃前缀。
                val discard = ByteArray(16 * 1024)
                var skipped = 0L
                while (skipped < skip) {
                    val read = input.read(discard, 0, minOf(discard.size.toLong(), skip - skipped).toInt())
                    if (read < 0) {
                        complete = true
                        break
                    }
                    skipped += read
                }
            }
            var total = 0
            while (!complete && total < target.size) {
                val read = input.read(target, total, target.size - total)
                if (read < 0) {
                    complete = true
                    break
                }
                if (read == 0) continue
                total += read
            }
            if (!complete && !exact) overflow = input.read() >= 0
        }
        if (overflow || (!complete && !exact)) call.cancel()
        if (complete) throw IOException("Apple Music 分片响应不完整")
        return target
    }
}
