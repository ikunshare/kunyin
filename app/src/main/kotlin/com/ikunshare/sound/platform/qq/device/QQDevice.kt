package com.ikunshare.sound.platform.qq.device

import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import kotlin.random.Random

/** QQ 音乐设备的系统版本信息。 */
data class QQOSVersion(
    @SerializedName("incremental") val incremental: String = "5891938",
    @SerializedName("release") val release: String = "10",
    @SerializedName("codename") val codename: String = "REL",
    @SerializedName("sdk") val sdk: Int = 29
)

/** QIMEI 结果（q16 / q36）。 */
data class QimeiResult(
    @SerializedName("q16") val q16: String = "",
    @SerializedName("q36") val q36: String = ""
)

/**
 * QQ 音乐虚拟设备指纹。结构与后端 device.go 对齐，整体序列化为 JSON 持久化到本地，
 * 每个安装实例只生成一份（install 级别），供所有 QQ 账号共用。
 *
 * - [openUDID2]：模拟 QQ SDK 的 OpenUDID 算法，由设备各字段拼接 + Java hashCode 推导。
 * - [mValue]：OICQ TEA 加密产物，见 [QQDeviceCrypto.generateMValue]。
 * - [qimei]：联网获取，首次为空，由 [QQDeviceManager] 异步补齐并回写缓存。
 */
data class QQDevice(
    @SerializedName("display") val display: String,
    @SerializedName("product") val product: String,
    @SerializedName("device") val deviceName: String,
    @SerializedName("board") val board: String,
    @SerializedName("model") val model: String,
    @SerializedName("fingerprint") val fingerprint: String,
    @SerializedName("boot_id") val bootId: String,
    @SerializedName("proc_version") val procVersion: String,
    @SerializedName("imei") val imei: String,
    @SerializedName("brand") val brand: String,
    @SerializedName("bootloader") val bootloader: String,
    @SerializedName("base_band") val baseBand: String,
    @SerializedName("version") val version: QQOSVersion,
    @SerializedName("sim_info") val simInfo: String,
    @SerializedName("os_type") val osType: String,
    @SerializedName("mac_address") val macAddress: String,
    @SerializedName("wifi_bssid") val wifiBssid: String,
    @SerializedName("wifi_ssid") val wifiSsid: String,
    @SerializedName("imsi_md5") val imsiMd5: List<Int>,
    @SerializedName("android_id") val androidId: String,
    @SerializedName("apn") val apn: String,
    @SerializedName("vendor_name") val vendorName: String,
    @SerializedName("vendor_os_name") val vendorOs: String,
    @SerializedName("qimei") var qimei: QimeiResult? = null,
    @SerializedName("open_udid2") var openUDID2: String = "",
    @SerializedName("m_value") var mValue: String = ""
) {
    companion object {
        private const val ID_CHARS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

        /** 生成一台全新的随机设备，对齐后端 NewDevice()。 */
        fun create(): QQDevice {
            val androidIdBytes = ByteArray(8) { Random.nextInt(256).toByte() }
            val androidId = androidIdBytes.toHex()

            val imsiMd5 = MessageDigest.getInstance("MD5")
                .digest(ByteArray(16) { Random.nextInt(256).toByte() })
                .map { it.toInt() and 0xff }

            val dev = QQDevice(
                display = "QMAPI.${Random.nextInt(100000, 1000000)}.001",
                product = "iarim",
                deviceName = "sagit",
                board = "eomam",
                model = "MI 6",
                fingerprint = "xiaomi/iarim/sagit:10/eomam.200122.001/" +
                        "${Random.nextInt(1000000, 10000000)}:user/release-keys",
                bootId = randomUUID(),
                procVersion = "Linux 5.4.0-54-generic-${randomString(8)} " +
                        "(android-build@google.com)",
                imei = randomIMEI(),
                brand = "Xiaomi",
                bootloader = "U-boot",
                baseBand = "",
                version = QQOSVersion(),
                simInfo = "T-Mobile",
                osType = "android",
                macAddress = "00:50:56:C0:00:08",
                wifiBssid = "00:50:56:C0:00:08",
                wifiSsid = "<unknown ssid>",
                imsiMd5 = imsiMd5,
                androidId = androidId,
                apn = "wifi",
                vendorName = "MIUI",
                vendorOs = "qmapi"
            )
            dev.openUDID2 = generateOpenUDID2(dev)
            dev.mValue = QQDeviceCrypto.generateMValue(dev.androidId)
            return dev
        }

        /** 模拟 QQ SDK 的 OpenUDID 算法：AndroidID.hashCode 拼接设备信息 hashCode + 时间戳。 */
        fun generateOpenUDID2(dev: QQDevice): String {
            val deviceInfo = listOf(
                dev.display, dev.product, dev.deviceName, dev.board, dev.model,
                dev.fingerprint, dev.bootId, dev.procVersion, dev.imei, dev.brand,
                dev.bootloader, dev.baseBand, dev.version.incremental, dev.version.release,
                dev.version.codename, dev.version.sdk.toString(), dev.simInfo, dev.osType,
                dev.macAddress, dev.wifiBssid, dev.wifiSsid, dev.androidId, dev.apn,
                dev.vendorName, dev.vendorOs
            ).joinToString("")

            // 与后端一致：int32 hashCode 符号扩展为 int64 后取各字段
            val mostSigBits = javaHashCode(dev.androidId).toLong()
            val leastSigBits =
                javaHashCode(deviceInfo).toLong() or System.currentTimeMillis()

            return "%08x%04x%04x%04x%012x".format(
                mostSigBits and 0xffffffffL,
                (mostSigBits ushr 32) and 0xffff,
                (mostSigBits ushr 48) and 0xffff,
                (leastSigBits ushr 48) and 0xffff,
                leastSigBits and 0xffffffffffffL
            )
        }

        private fun javaHashCode(s: String): Int {
            var h = 0
            for (c in s) h = 31 * h + c.code
            return h
        }

        private fun randomString(n: Int): String =
            (1..n).map { ID_CHARS[Random.nextInt(ID_CHARS.length)] }.joinToString("")

        private fun randomUUID(): String = java.util.UUID.randomUUID().toString()

        /** 生成符合 Luhn 校验的 15 位 IMEI，对齐后端 randomIMEI()。 */
        private fun randomIMEI(): String {
            val digits = IntArray(15)
            var sum = 0
            for (i in 0 until 14) {
                var num = Random.nextInt(10)
                if ((i + 2) % 2 == 0) {
                    num *= 2
                    if (num >= 10) num = (num % 10) + 1
                }
                sum += num
                digits[i] = num
            }
            digits[14] = (sum * 9) % 10
            return digits.joinToString("")
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
