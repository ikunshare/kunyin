package com.ikunshare.music.manager.discovery

import android.util.Base64
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Matches the desktop wbd AES-128-ECB payload and uppercase MD5 request signature. */
internal object KuwoWbdCodec {
    private const val APP_ID = "y67sprxhhpws"
    private val key = byteArrayOf(112, 87, 39, 61, 199.toByte(), 250.toByte(), 41, 191.toByte(),
        57, 68, 45, 114, 221.toByte(), 94, 140.toByte(), 228.toByte())
    private fun cipher(mode: Int) = Cipher.getInstance("AES/ECB/PKCS5Padding").apply {
        init(mode, SecretKeySpec(key, "AES"))
    }

    fun parameters(data: Any, time: Long = System.currentTimeMillis()): String {
        val encoded = Base64.encodeToString(cipher(Cipher.ENCRYPT_MODE).doFinal(GsonBuilder().disableHtmlEscaping().create().toJson(data).toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val sign = MessageDigest.getInstance("MD5").digest("$APP_ID$encoded$time".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02X".format(it.toInt() and 255) }
        return "data=${URLEncoder.encode(encoded, "UTF-8")}&time=$time&appId=$APP_ID&sign=$sign"
    }

    fun decode(text: String): JsonObject {
        // decodeURIComponent keeps '+' characters in plain base64; URLDecoder needs them escaped.
        val encoded = URLDecoder.decode(text.trim().replace("+", "%2B"), "UTF-8")
        val plain = cipher(Cipher.DECRYPT_MODE).doFinal(Base64.decode(encoded, Base64.DEFAULT))
        return JsonParser.parseString(String(plain, Charsets.UTF_8)).asJsonObject
    }
}
