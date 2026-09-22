package com.ikunshare.sound.platform.wy.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.ikunshare.sound.platform.wy.utils.NeteaseCrypto.eapiPost
import com.ikunshare.sound.platform.wy.utils.NeteaseCrypto.extractMusicU
import com.ikunshare.sound.platform.wy.utils.NeteaseCrypto.weapiPost
import com.ikunshare.sound.utils.HTTPResponse
import com.ikunshare.sound.utils.HTTPUtils
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云加密 + 请求封装。
 *
 * - [eapiPost]：EAPI 协议，AES/ECB 加密，发送到 `interface3.music.163.com/eapi{path}`。
 * - [weapiPost]：WEAPI 协议，AES/CBC 双层加密 + RSA 包裹随机密钥，发送到 `music.163.com/weapi{path}`。
 * - [extractMusicU]：从 cookie 字符串中提取 `MUSIC_U` 值。
 */
object NeteaseCrypto {

    private val gson = Gson()
    private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)
    private val WEAPI_PRESET_KEY = "0CoJUm6Qyw8W8jud".toByteArray(Charsets.UTF_8)
    private val WEAPI_IV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private val RSA_PUB_KEY = BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725" +
                "152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312" +
                "ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424" +
                "d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
        16
    )
    private val RSA_PUB_EXP = BigInteger("010001", 16)

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("wy_crypto", Context.MODE_PRIVATE)
    }

    private val deviceId: String by lazy {
        val key = "device_id"
        prefs.getString(key, null) ?: run {
            val hexChars = "0123456789ABCDEF"
            val id = buildString(52) { repeat(52) { append(hexChars.random()) } }
            prefs.edit().putString(key, id).apply()
            id
        }
    }

    private const val APP_VER = "3.1.17.204416"
    private const val VERSION_CODE = "140"
    private const val OS_VER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val RESOLUTION = "1920x1080"
    private const val CHANNEL = "netease"

    private val DEFAULT_HEADERS = mapOf(
        "User-Agent" to "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)",
        "Accept" to "application/json, text/plain, */*"
    )

    private fun buildRequestId(): String {
        val ts = System.currentTimeMillis() / 1000
        val rand = (0..9999).random().toString().padStart(4, '0')
        return "${ts}_$rand"
    }

    private fun buildEapiHeader(musicU: String? = null): Map<String, String> {
        val header = linkedMapOf(
            "osver" to OS_VER,
            "deviceId" to deviceId,
            "os" to "pc",
            "appver" to APP_VER,
            "versioncode" to VERSION_CODE,
            "mobilename" to "",
            "buildver" to (System.currentTimeMillis() / 1000).toString(),
            "resolution" to RESOLUTION,
            "__csrf" to "",
            "channel" to CHANNEL,
            "requestId" to buildRequestId()
        )
        if (!musicU.isNullOrBlank()) header["MUSIC_U"] = musicU
        return header
    }

    private fun buildCookieString(header: Map<String, String>): String =
        header.entries.joinToString("; ") { "${it.key}=${it.value}" }

    /** EAPI 加密并 POST。`data` 可为 Map 或已序列化的 JSON 字符串。`musicU` 为 MUSIC_U cookie 值。 */
    fun eapiPost(path: String, data: Any, musicU: String? = null): HTTPResponse {
        val header = buildEapiHeader(musicU)

        val dataMap: MutableMap<String, Any?> = when (data) {
            is Map<*, *> -> data.entries.associate { it.key.toString() to it.value }.toMutableMap()
            is String -> {
                @Suppress("UNCHECKED_CAST")
                (gson.fromJson(data, Map::class.java) as Map<String, Any?>).toMutableMap()
            }

            else -> gson.fromJson(gson.toJson(data), Map::class.java).let {
                @Suppress("UNCHECKED_CAST")
                (it as Map<String, Any?>).toMutableMap()
            }
        }
        dataMap["header"] = header

        val params = gson.toJson(dataMap)
        val encrypted = eapiEncrypt(path, params)
        val url = "https://interface.music.163.com/eapi" + path.removePrefix("/api")
        val headers = DEFAULT_HEADERS.toMutableMap().apply {
            put("Content-Type", "application/x-www-form-urlencoded")
            put("Cookie", buildCookieString(header))
        }
        val body = "params=$encrypted"
        return HTTPUtils.post(url, headers, body)
    }

    /** WEAPI 加密并 POST。`data` 可为 Map 或已序列化的 JSON 字符串。`musicU` 为 MUSIC_U cookie 值。 */
    fun weapiPost(path: String, data: Any, musicU: String? = null): HTTPResponse {
        val params = toJsonString(data)
        val (encParams, encSecKey) = weapiEncrypt(params)
        val url = "https://music.163.com/weapi" + path.removePrefix("/api")
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
            "Referer" to "https://music.163.com/",
            "Content-Type" to "application/x-www-form-urlencoded"
        )
        if (!musicU.isNullOrBlank()) {
            headers["Cookie"] = "os=pc; MUSIC_U=$musicU"
        } else {
            headers["Cookie"] = "os=pc"
        }
        val body = "params=${urlEncode(encParams)}&encSecKey=${urlEncode(encSecKey)}"
        return HTTPUtils.post(url, headers, body)
    }

    /** 从 cookie 字符串中抽取 MUSIC_U 值，找不到返回 null。 */
    fun extractMusicU(cookie: String): String? {
        val match = Regex("MUSIC_U=([^;\\s]+)").find(cookie) ?: return null
        return match.groupValues[1].takeIf { it.isNotEmpty() }
    }

    // ----- internals -----

    private fun toJsonString(data: Any): String = when (data) {
        is String -> data
        else -> gson.toJson(data)
    }

    private fun eapiEncrypt(path: String, text: String): String {
        val message = "nobody${path}use${text}md5forencrypt"
        val digest = md5Hex(message)
        val payload = "$path-36cd479b6b5-$text-36cd479b6b5-$digest"
        val keySpec = SecretKeySpec(EAPI_KEY, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(payload.toByteArray(Charsets.UTF_8))
        return bytesToHex(encrypted).uppercase()
    }

    private fun weapiEncrypt(text: String): Pair<String, String> {
        val secKey = randomSecKey()
        val step1 = aesCbcBase64(text, WEAPI_PRESET_KEY)
        val step2 = aesCbcBase64(step1, secKey.toByteArray(Charsets.UTF_8))
        val encSecKey = rsaEncryptHex(secKey.reversed())
        return step2 to encSecKey
    }

    private fun aesCbcBase64(text: String, key: ByteArray): String {
        val keySpec = SecretKeySpec(key, "AES")
        val ivSpec = IvParameterSpec(WEAPI_IV)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    private fun rsaEncryptHex(text: String): String {
        // 网易云的 RSA：明文左侧用 0 字节补到 128 字节，无 padding。
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val padded = ByteArray(128)
        System.arraycopy(textBytes, 0, padded, 128 - textBytes.size, textBytes.size)
        val message = BigInteger(1, padded)
        val cipher = message.modPow(RSA_PUB_EXP, RSA_PUB_KEY)
        val hex = cipher.toString(16)
        return hex.padStart(256, '0')
    }

    private fun randomSecKey(): String {
        val charset = "abcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        val sb = StringBuilder(16)
        repeat(16) { sb.append(charset[random.nextInt(charset.length)]) }
        return sb.toString()
    }

    private fun md5Hex(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(bytes)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX_CHARS[v ushr 4])
            sb.append(HEX_CHARS[v and 0x0f])
        }
        return sb.toString()
    }

    private fun urlEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    private val HEX_CHARS = "0123456789abcdef".toCharArray()
}
