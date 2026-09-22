package com.ikunshare.sound.platform.qq.device

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.ikunshare.sound.platform.qq.device.QQQimeiFetcher.defaultQimei
import com.ikunshare.sound.utils.HTTPUtils
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * 获取 QIMEI（q16 / q36），对齐后端 qimei.go 的 GetQimei。
 *
 * 流程：随机 16 字节 AES 密钥 → RSA(PKCS1v15) 公钥加密得到 key；
 * 用该密钥 AES/CBC 加密设备 payload 得到 params；MD5 签名后 POST 到腾讯 trpc 代理。
 * 失败时返回 [defaultQimei] 兜底，保证上报链路不中断。
 */
internal object QQQimeiFetcher {

    private const val TAG = "QQQimei"
    private const val SECRET = "ZdJqM15EeO2zWc08"
    private const val PROXY_URL = "https://api.tencentmusic.com/tme/trpc/proxy"
    private const val DEFAULT_Q36 = "6c9d3cd110abca9b16311cee10001e717614"
    private const val PUB_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDEIxgwoutfwoJxcGQeedgP7FG9" +
                "qaIuS0qzfR8gWkrkTZKM2iWHn2ajQpBRZjMSoSf6+KJGvar2ORhBfpDXyVtZCKp" +
                "qLQ+FLkpncClKVIrBwv6PHyUvuCb0rIarmgDnzkfQAqVufEtR64iazGDKatvJ9y" +
                "6B9NMbHddGSAUmRTCrHQIDAQAB"

    private val gson = Gson()

    /**
     * 联网获取 QIMEI。[nowMillis] 由调用方传入以便复用同一时间戳。
     * 任何异常都降级为 [defaultQimei]。
     */
    fun fetch(device: QQDevice, version: String, nowMillis: Long): QimeiResult {
        return try {
            val tsSec = nowMillis / 1000
            val payload = QQQimeiPayload.build(device, version, nowMillis)

            val cryptKey = randomCryptKey()
            val key = Base64.encodeToString(rsaEncrypt(cryptKey), Base64.NO_WRAP)
            val params = Base64.encodeToString(
                aesCbcEncrypt(cryptKey, gson.toJson(payload).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )

            val nonce = randomCryptKey().let { String(it) }
            val extra = """{"appKey":"${QQQimeiPayload.APP_KEY}"}"""
            val sign = md5Hex(key + params + (tsSec * 1000).toString() + nonce + SECRET + extra)
            val headerSign = md5Hex("qimei_qq_androidpzAuCmaFAaFaHrdakPjLIEqKrGnSOOvH$tsSec")

            val reqBody = mapOf(
                "app" to 0,
                "os" to 1,
                "qimeiParams" to mapOf(
                    "key" to key,
                    "params" to params,
                    "time" to tsSec.toString(),
                    "nonce" to nonce,
                    "sign" to sign,
                    "extra" to extra
                )
            )

            val headers = mapOf(
                "Host" to "api.tencentmusic.com",
                "method" to "GetQimei",
                "service" to "trpc.tme_datasvr.qimeiproxy.QimeiProxy",
                "appid" to "qimei_qq_android",
                "sign" to headerSign,
                "User-Agent" to "QQMusic",
                "timestamp" to tsSec.toString()
            )

            val response = HTTPUtils.post(PROXY_URL, headers, reqBody)
            parseResult(response.body) ?: defaultQimei(device)
        } catch (e: Exception) {
            Log.w(TAG, "fetch qimei failed", e)
            defaultQimei(device)
        }
    }

    private fun parseResult(body: String?): QimeiResult? {
        if (body.isNullOrBlank()) return null
        return try {
            val outer = gson.fromJson(body, JsonObject::class.java) ?: return null
            val dataStr = outer.get("data")?.takeIf { !it.isJsonNull }?.asString ?: return null
            val inner = gson.fromJson(dataStr, JsonObject::class.java) ?: return null
            val innerData = inner.getAsJsonObject("data") ?: return null
            val q16 = innerData.get("q16")?.asString ?: ""
            val q36 = innerData.get("q36")?.asString ?: ""
            if (q36.isBlank()) null else QimeiResult(q16, q36)
        } catch (e: Exception) {
            null
        }
    }

    private fun defaultQimei(device: QQDevice): QimeiResult {
        device.qimei?.let { if (it.q36.isNotBlank()) return QimeiResult("", it.q36) }
        return QimeiResult("", DEFAULT_Q36)
    }

    private fun randomCryptKey(): ByteArray {
        val chars = "adbcdef1234567890"
        return ByteArray(16) { chars[Random.nextInt(chars.length)].code.toByte() }
    }

    private fun rsaEncrypt(data: ByteArray): ByteArray {
        val keyBytes = Base64.decode(PUB_KEY_B64, Base64.NO_WRAP)
        val pubKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return cipher.doFinal(data)
    }

    /** AES/CBC，密钥同时复用作 IV，对齐后端 cipher.NewCBCEncrypter(block, key)。 */
    private fun aesCbcEncrypt(key: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(key)
        )
        return cipher.doFinal(data)
    }

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
