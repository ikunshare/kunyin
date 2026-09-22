package com.ikunshare.sound.platform.qq.device

import com.google.gson.Gson
import kotlin.random.Random

/** 构造 QIMEI 请求的设备 payload，对齐后端 qimei.go 的 randomPayloadByDevice / randomBeaconID。 */
internal object QQQimeiPayload {

    const val APP_KEY = "0AND0HD6FE4HY80F"
    private val gson = Gson()

    fun build(device: QQDevice, version: String, nowMillis: Long): Map<String, Any> {
        val reserved = mapOf(
            "harmony" to "0",
            "clone" to "0",
            "containe" to "",
            "oz" to "UhYmelwouA+V2nPWbOvLTgN2/m8jwGB+yUB5v9tysQg=",
            "oo" to "Xecjt+9S1+f8Pz2VLSxgpw==",
            "kelong" to "0",
            "uptimes" to formatUptime(nowMillis),
            "multiUser" to "0",
            "bod" to device.brand,
            "dv" to device.deviceName,
            "firstLevel" to "",
            "manufact" to device.brand,
            "name" to device.model,
            "host" to "se.infra",
            "kernel" to device.procVersion
        )

        return mapOf(
            "androidId" to device.androidId,
            "platformId" to 1,
            "appKey" to APP_KEY,
            "appVersion" to version,
            "beaconIdSrc" to randomBeaconId(nowMillis),
            "brand" to device.brand,
            "channelId" to "10003505",
            "cid" to "",
            "imei" to device.imei,
            "imsi" to "",
            "mac" to "",
            "model" to device.model,
            "networkType" to "unknown",
            "oaid" to "",
            "osVersion" to "Android ${device.version.release},level ${device.version.sdk}",
            "qimei" to "",
            "qimei36" to "",
            "sdkVersion" to "1.2.13.6",
            "targetSdkVersion" to "33",
            "audit" to "",
            "userId" to "{}",
            "packageId" to "com.tencent.qqmusic",
            "deviceType" to "Phone",
            "sdkName" to "",
            "reserved" to gson.toJson(reserved)
        )
    }

    private fun formatUptime(nowMillis: Long): String {
        val offsetSec = Random.nextInt(14401)
        val date = java.util.Date(nowMillis - offsetSec * 1000L)
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(date)
    }

    private fun randomBeaconId(nowMillis: Long): String {
        val sb = StringBuilder()
        val timeMonth = java.text.SimpleDateFormat("yyyy-MM-", java.util.Locale.US)
            .format(java.util.Date(nowMillis)) + "01"
        val rand1 = Random.nextInt(100000, 1000000)
        val rand2 = Random.nextInt(100000000, 1000000000)
        val special = setOf(1, 2, 13, 14, 17, 18, 21, 22, 25, 26, 29, 30, 33, 34, 37, 38)

        for (i in 1..40) {
            when {
                special.contains(i) -> sb.append("k$i:$timeMonth$rand1.$rand2")
                i == 3 -> sb.append("k3:0000000000000000")
                i == 4 -> {
                    val chars = "123456789abcdef"
                    val s = (1..16).map { chars[Random.nextInt(chars.length)] }.joinToString("")
                    sb.append("k4:$s")
                }

                else -> sb.append("k$i:${Random.nextInt(10000)}")
            }
            sb.append(';')
        }
        return sb.toString()
    }
}
