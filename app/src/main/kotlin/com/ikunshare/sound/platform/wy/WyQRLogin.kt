package com.ikunshare.sound.platform.wy

import android.util.Log
import com.google.gson.JsonObject
import com.ikunshare.sound.platform.wy.utils.NeteaseCrypto

object WyQRLogin {

    private const val TAG = "WyQRLogin"

    enum class QRStatus { LOADING, WAITING, SCANNED, SUCCESS, EXPIRED, ERROR }

    data class PollResult(
        val status: QRStatus,
        val cookie: String? = null
    )

    fun getUnikey(): String? {
        val data = mapOf(
            "type" to 3
        )
        val response = NeteaseCrypto.eapiPost("/api/login/qrcode/unikey", data)
        if (!response.isSuccessful) {
            Log.w(TAG, "getUnikey failed: ${response.status}")
            return null
        }
        return try {
            val json = response.json(JsonObject::class.java) ?: return null
            if (json.get("code")?.asInt != 200) return null
            json.get("unikey")?.asString
        } catch (e: Exception) {
            Log.e(TAG, "getUnikey parse failed", e)
            null
        }
    }

    fun buildQRUrl(unikey: String): String =
        "https://music.163.com/login?codekey=$unikey"

    fun pollStatus(unikey: String): PollResult {
        val data = mapOf(
            "type" to 3,
            "key" to unikey
        )
        val response = NeteaseCrypto.eapiPost("/api/login/qrcode/client/login", data)
        if (!response.isSuccessful) {
            Log.w(TAG, "pollStatus failed: ${response.status}")
            return PollResult(QRStatus.ERROR)
        }
        return try {
            val json = response.json(JsonObject::class.java)
                ?: return PollResult(QRStatus.ERROR)
            when (json.get("code")?.asInt) {
                801 -> PollResult(QRStatus.WAITING)
                802 -> PollResult(QRStatus.SCANNED)
                803 -> {
                    val setCookie = response.headers["set-cookie"] ?: ""
                    val musicU = NeteaseCrypto.extractMusicU(setCookie)
                    if (musicU != null) {
                        PollResult(QRStatus.SUCCESS, "MUSIC_U=$musicU")
                    } else {
                        Log.w(TAG, "803 but no MUSIC_U in Set-Cookie")
                        PollResult(QRStatus.ERROR)
                    }
                }

                800 -> PollResult(QRStatus.EXPIRED)
                else -> {
                    Log.w(TAG, "pollStatus unknown code: ${json.get("code")}")
                    PollResult(QRStatus.ERROR)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pollStatus parse failed", e)
            PollResult(QRStatus.ERROR)
        }
    }
}
