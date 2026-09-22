package com.ikunshare.sound.platform.qq

import android.util.Base64
import android.util.Log
import com.ikunshare.sound.platform.qq.utils.QQMusicUtils
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference


object MobileQRLogin {

    private const val TAG = "QRLogin"

    /**
     * 向 musicu.fcg 发送无签名请求，返回指定 reqKey 下的 data
     */
    private fun apiCall(
        module: String,
        method: String,
        param: JSONObject,
        commonOverrides: Map<String, String>? = null
    ): JSONObject {
        val reqKey = "$module.$method"
        val comm = JSONObject().apply {
            put("ct", "11")
            put("cv", "13020508")
            put("v", "13020508")
            put("tmeAppID", "qqmusic")
            put("format", "json")
            put("inCharset", "utf-8")
            put("outCharset", "utf-8")
            commonOverrides?.forEach { (k, v) -> put(k, v) }
        }
        val body = JSONObject().apply {
            put("comm", comm)
            put(reqKey, JSONObject().apply {
                put("module", module)
                put("method", method)
                put("param", param)
            })
        }
        val response = QQMusicUtils.unsignedRequest(body.toString())
        if (!response.isSuccessful) throw Exception("HTTP ${response.status}")
        val respJson = JSONObject(response.body!!)
        val reqData = respJson.optJSONObject(reqKey) ?: throw Exception("响应缺少 $reqKey")
        val code = reqData.optInt("code", 0)
        if (code != 0) throw Exception("API错误: $code")
        return reqData.optJSONObject("data") ?: reqData
    }

    // ===== QR 码登录 =====

    fun createQRCode(): Pair<ByteArray, String> {
        val resp = apiCall(
            "music.login.LoginServer", "CreateQRCode",
            JSONObject().apply {
                put("tmeAppID", "qqmusic")
                put("ct", 11)
                put("cv", 13020508)
            }
        )
        val qrcodeID = resp.getString("qrcodeID")
        val qrRaw = resp.getString("qrcode")
        val b64 = if (qrRaw.contains(",")) qrRaw.substringAfter(",") else qrRaw
        val imageBytes = Base64.decode(b64, Base64.DEFAULT)
        return Pair(imageBytes, qrcodeID)
    }

    fun connectAndListen(
        qrcodeId: String,
        onStatus: (QRPollResult) -> Unit,
        onWebSocketChanged: (WebSocket) -> Unit = {}
    ): WebSocket {
        val clientId = "${System.currentTimeMillis()}${(1000..9999).random()}"
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val done = AtomicBoolean(false)
        val activeWs = AtomicReference<WebSocket>()

        fun buildRequest(path: String = ""): Request = Request.Builder()
            .url("wss://mu.y.qq.com:443/ws/handshake$path")
            .addHeader("Origin", "https://y.qq.com")
            .addHeader("Referer", "https://y.qq.com/")
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
            )
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val connectPacket = MqttProtocol.buildConnectPacket(
                    clientId = clientId,
                    authMethod = "pass",
                    userProperties = listOf(
                        "tmeAppID" to "qqmusic",
                        "business" to "management",
                        "hashTag" to qrcodeId,
                        "clientTag" to "management.user",
                        "userID" to qrcodeId
                    )
                )
                webSocket.send(connectPacket.toByteString())
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (done.get()) return
                val msg = MqttProtocol.parsePacket(bytes.toByteArray()) ?: return

                when {
                    msg.serverReference != null -> {
                        val newWs = client.newWebSocket(
                            buildRequest("/${msg.serverReference}"), this
                        )
                        activeWs.set(newWs)
                        onWebSocketChanged(newWs)
                        webSocket.close(1000, "redirect")
                    }

                    msg.type == 0x20.toByte() -> {
                        val subPacket = MqttProtocol.buildSubscribePacket(
                            packetId = 1,
                            topic = "management.qrcode_login/$qrcodeId",
                            userProperties = listOf(
                                "authorization" to "tmelogin",
                                "pubsub" to "unicast"
                            )
                        )
                        webSocket.send(subPacket.toByteString())
                    }

                    msg.type == (0x90).toByte() -> {
                        onStatus(QRPollResult(QRStatus.WAITING, "等待扫码"))
                    }

                    msg.payload != null -> {
                        handleMqttEvent(msg, qrcodeId) { result ->
                            if (result.status == QRStatus.CONFIRMED ||
                                result.status == QRStatus.REFUSED ||
                                result.status == QRStatus.TIMEOUT
                            ) {
                                done.set(true)
                            }
                            onStatus(result)
                        }
                    }
                }
            }

            override fun onFailure(
                webSocket: WebSocket, t: Throwable, response: Response?
            ) {
                if (!done.get() && activeWs.get() === webSocket) {
                    onStatus(QRPollResult(QRStatus.ERROR, "连接失败: ${t.message ?: ""}"))
                }
            }
        }

        val ws = client.newWebSocket(buildRequest(), listener)
        activeWs.set(ws)
        return ws
    }

    private fun handleMqttEvent(
        msg: MqttProtocol.MqttMessage,
        qrcodeId: String,
        onStatus: (QRPollResult) -> Unit
    ) {
        val eventType = msg.userProperties["type"] ?: ""
        when (eventType) {
            "scanned" -> onStatus(QRPollResult(QRStatus.SCANNED, "已扫码，请确认"))
            "cookies" -> {
                try {
                    val json = JSONObject(String(msg.payload!!))
                    val cookies = json.optJSONObject("cookies")
                    val uin = cookies?.optJSONObject("qqmusic_uin")
                        ?.optString("value", "")
                        ?: cookies?.optString("qqmusic_uin", "") ?: ""
                    val key = cookies?.optJSONObject("qqmusic_key")
                        ?.optString("value", "")
                        ?: cookies?.optString("qqmusic_key", "") ?: ""
                    if (uin.isNotEmpty() && key.isNotEmpty()) {
                        val credentials = mobileLogin(uin.toLongOrNull() ?: 0L, qrcodeId, key)
                        onStatus(QRPollResult(QRStatus.CONFIRMED, "登录成功", credentials))
                    } else {
                        onStatus(QRPollResult(QRStatus.ERROR, "登录数据不完整"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "登录失败", e)
                    onStatus(QRPollResult(QRStatus.ERROR, "登录失败: ${e.message}"))
                }
            }

            "canceled" -> onStatus(QRPollResult(QRStatus.REFUSED, "已取消登录"))
            "timeout" -> onStatus(QRPollResult(QRStatus.TIMEOUT, "二维码已过期"))
            "loginFailed" -> onStatus(QRPollResult(QRStatus.ERROR, "登录失败"))
        }
    }

    // ===== Login API =====

    private fun mobileLogin(musicid: Long, qrCodeID: String, token: String): QQCredentials {
        val resp = apiCall(
            "music.login.LoginServer", "Login",
            JSONObject().apply {
                put("musicid", musicid)
                put("qrCodeID", qrCodeID)
                put("token", token)
            },
            commonOverrides = mapOf("tmeLoginType" to "6")
        )
        Log.d(TAG, "mobileLogin 响应: $resp")
        return parseCredentialResponse(resp)
    }

    /**
     * 刷新 QQ 音乐凭据
     * @return 刷新后的新凭据，失败返回 null
     */
    fun refreshCredential(creds: QQCredentials): QQCredentials? {
        return try {
            val param = when (creds.loginType) {
                1 -> JSONObject().apply { // 微信登录
                    put("code", "")
                    put("openid", creds.openid)
                    put("refresh_token", creds.refreshToken)
                    put("str_musicid", creds.uin)
                    put("musickey", creds.authst)
                    put("unionid", "")
                    put("refresh_key", creds.refreshKey)
                    put("expired_in", creds.expiredAt)
                    put("loginMode", 1)
                }

                else -> JSONObject().apply { // QQ登录
                    put("openid", creds.openid)
                    put("access_token", creds.accessToken)
                    put("refresh_token", creds.refreshToken)
                    put("musickey", creds.authst)
                    put("musicid", creds.uin.toLongOrNull() ?: 0L)
                    put("refresh_key", creds.refreshKey)
                    put("expired_in", creds.expiredAt)
                    put("loginMode", 2)
                }
            }
            val resp = apiCall(
                "music.login.LoginServer", "Login", param,
                commonOverrides = mapOf("tmeLoginType" to creds.loginType.toString())
            )
            Log.d(TAG, "refreshCredential 响应: $resp")
            parseCredentialResponse(resp)
        } catch (e: Exception) {
            Log.e(TAG, "刷新凭据失败", e)
            null
        }
    }

    private fun parseCredentialResponse(data: JSONObject): QQCredentials {
        return QQCredentials(
            authst = data.optString("musickey", ""),
            uin = data.optString("str_musicid", data.optString("musicid", "")),
            refreshToken = data.optString("refresh_token", ""),
            refreshKey = data.optString("refresh_key", ""),
            accessToken = data.optString("access_token", ""),
            openid = data.optString("openid", ""),
            expiredAt = data.optLong("expired_at", 0L)
        )
    }
}
