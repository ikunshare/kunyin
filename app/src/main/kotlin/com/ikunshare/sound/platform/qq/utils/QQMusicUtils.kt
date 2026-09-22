package com.ikunshare.sound.platform.qq.utils

import com.ikunshare.sound.platform.qq.utils.QQMusicUtils.unsignedRequest
import com.ikunshare.sound.platform.qq.utils.QQMusicUtils.zzcRequest
import com.ikunshare.sound.utils.HTTPResponse
import com.ikunshare.sound.utils.HTTPUtils

/**
 * QQ 音乐 musicu.fcg 调用封装。
 *
 * - [zzcRequest]：发送已经构造好的请求体（map），由调用方组织 comm/req 结构。
 * - [unsignedRequest]：直接发送 JSON 字符串，无任何签名。
 */
object QQMusicUtils {

    private const val MUSICU_URL = "https://u6.y.qq.com/cgi-bin/musicu.fcg"

    private val DEFAULT_HEADERS = mapOf(
        "User-Agent" to "QQMusic/2104583050",
        "Content-Type" to "application/json",
        "Referer" to "https://y.qq.com/"
    )

    fun zzcRequest(reqData: Map<String, Any?>): HTTPResponse {
        return HTTPUtils.post(MUSICU_URL, DEFAULT_HEADERS, reqData)
    }

    /** 直接发送 body 至 musicu.fcg，body 可为 Map（自动 gson 序列化）或 JSON 字符串。 */
    fun unsignedRequest(body: Any): HTTPResponse {
        return HTTPUtils.post(MUSICU_URL, DEFAULT_HEADERS, body)
    }
}
