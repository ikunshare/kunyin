package com.ikunshare.sound.platform.kg

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.utils.HTTPUtils
import com.ikunshare.sound.utils.crypto.KgCrypto
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

data class KgQRCode(
    val url: String,
    val ticket: String
)

object KgThirdsso {

    private val gson = Gson()

    @Volatile
    private var cachedIp: String? = null

    private fun getNonce(): String {
        val random = Math.random().toString()
        val bytes = random.toByteArray(Charsets.UTF_8)
        val ns = UUID.nameUUIDFromBytes("6ba7b810-9dad-11d1-80b4-00c04fd430c8".toByteArray())
        val nsBytes = ByteBuffer.allocate(16).apply {
            putLong(ns.mostSignificantBits)
            putLong(ns.leastSignificantBits)
        }.array()

        val md = MessageDigest.getInstance("SHA-1")
        md.update(nsBytes)
        md.update(bytes)
        val hash = md.digest()
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun getClientIp(credentials: KgCredentials): String {
        cachedIp?.let { return it }
        val resp = rawRequest("user/ip", credentials, "127.0.0.1") ?: return ""
        val data = resp.getAsJsonObject("data") ?: return ""
        val ip = data.get("ip")?.asString ?: return ""
        if (ip.isNotEmpty()) cachedIp = ip
        return ip
    }

    private fun rawRequest(
        path: String,
        credentials: KgCredentials,
        clientIp: String,
        params: Map<String, Any?> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap()
    ): JsonObject? {
        val baseBody = mutableMapOf<String, Any?>(
            "package" to "com.kugou.android.auto",
            "device_id" to credentials.deviceId,
            "pid" to "203051",
            "apk_ver" to "10200",
            "sha1" to "35D2D23C98CB8B9CFEFF20E5909E34206D906172",
            "device_info" to mapOf(
                "resolution_height" to 1600,
                "resolution_width" to 900,
                "api_level" to 32,
                "device_level" to 2,
                "memory_size" to 5.8095703
            ),
            "sp" to "KG",
            "client_ver" to "155-e4b4136-20250923120802",
            "client_ip" to clientIp,
            "nonce" to getNonce(),
            "timestamp" to (System.currentTimeMillis() / 1000)
        )

        params.forEach { (k, v) -> baseBody[k] = v }

        val reqBody = gson.toJson(baseBody)

        val headers = mutableMapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "User-Agent" to "Android12-androidCar-155-e4b4136-203051-0-UltimateSdk-wifi",
            "kgidentity" to KgCrypto.getKgIdentity(reqBody),
            "signature" to KgCrypto.getSignature(reqBody),
            "signtrial" to KgCrypto.getTrialSignature(reqBody)
        )
        headers.putAll(extraHeaders)

        val resp = HTTPUtils.post(
            "https://thirdsso.kugou.com/v2/$path",
            headers,
            reqBody
        )
        if (!resp.isSuccessful) return null
        return try {
            JsonParser.parseString(resp.body ?: return null).asJsonObject
        } catch (_: Exception) {
            null
        }
    }

    fun request(
        path: String,
        credentials: KgCredentials,
        params: Map<String, Any?> = emptyMap(),
        extraHeaders: Map<String, String> = emptyMap()
    ): JsonObject? {
        val ip = getClientIp(credentials)
        return rawRequest(path, credentials, ip, params, extraHeaders)
    }

    fun registerDevice(credentials: KgCredentials): Boolean {
        val authResp = request(
            "sdk/auth", credentials,
            mapOf("userid" to "anonymous", "token" to "password")
        ) ?: return false

        val data = authResp.getAsJsonObject("data") ?: return false
        val status = data.get("status")?.asInt ?: -1
        val errorCode = authResp.get("error_code")?.asInt ?: -1
        if (status != 0 || errorCode != 0) return false

        val activationResp = request(
            "device/activation", credentials,
            mapOf("userid" to "anonymous", "token" to "password")
        ) ?: return false

        val data2 = activationResp.getAsJsonObject("data") ?: return false
        val errorCode2 = activationResp.get("error_code")?.asInt ?: -1
        if (errorCode2 != 0) return false
        return data2.has("activation_date") && !data2.get("activation_date").isJsonNull
    }

    // ===== QR Login =====

    fun getQRCode(credentials: KgCredentials): KgQRCode? {
        val resp = request(
            "user/qrcode/get", credentials,
            mapOf("userid" to "anonymous", "token" to "password"),
            mapOf("url_code" to "1001")
        ) ?: return null
        if (resp.get("error_code")?.asInt != 0) return null
        val data = resp.getAsJsonObject("data") ?: return null
        val url = data.get("qrcode")?.asString ?: return null
        val ticket = data.get("ticket")?.asString ?: return null
        return KgQRCode(url, ticket)
    }

    /**
     * 轮询二维码状态。
     * @return 登录成功返回新的 KgCredentials（含 userid/token），未登录返回 null
     */
    fun pollQRAuth(credentials: KgCredentials, ticket: String): KgCredentials? {
        val resp = request(
            "user/qrcode/auth", credentials,
            mapOf(
                "userid" to "anonymous",
                "token" to "password",
                "ticket" to ticket
            ),
            mapOf("url_code" to "1002")
        ) ?: return null

        val errorCode = resp.get("error_code")?.asInt ?: return null
        if (errorCode != 0) return null

        val data = resp.getAsJsonObject("data") ?: return null
        val userid = data.get("userid")?.asString ?: return null
        val token = data.get("token")?.asString ?: return null
        return credentials.copy(
            userid = userid,
            token = token
        )
    }

    // ===== User Info =====

    fun refreshToken(credentials: KgCredentials): KgCredentials? {
        val resp = request(
            "user/refresh/tokenv2", credentials,
            mapOf("userid" to credentials.userid, "token" to credentials.token)
        ) ?: return null
        if (resp.get("error_code")?.asInt != 0) return null
        val data = resp.getAsJsonObject("data") ?: return null
        val refresh = data.get("refresh")?.asBoolean ?: false
        return if (refresh) {
            val newToken = data.get("token")?.asString ?: credentials.token
            val newUserid = data.get("userid")?.asString ?: credentials.userid
            credentials.copy(userid = newUserid, token = newToken)
        } else {
            credentials
        }
    }

    fun getUserInfo(credentials: KgCredentials): UserInfo? {
        val resp = request(
            "vip/client/ssov2/userinfo", credentials,
            mapOf(
                "userid" to credentials.userid,
                "token" to credentials.token,
                "extend" to 1
            ),
            mapOf("url_code" to "1005")
        ) ?: return null
        if (resp.get("error_code")?.asInt != 0) return null
        val data = resp.getAsJsonObject("data") ?: return null

        val name = data.get("nick_name")?.asString ?: ""
        val avatar = data.get("img")?.asString ?: ""
        val vipInfo = buildMap {
            val isSvip = data.get("is_su_vip")?.asInt == 1
            put("vip", if (isSvip) "超级会员" else "普通")
            data.get("su_vip_end_time")?.asString?.let { put("vip_expire", it) }
        }
        return UserInfo(source = "kg", name = name, avatar = avatar, extra = vipInfo)
    }
}
