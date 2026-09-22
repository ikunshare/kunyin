package com.ikunshare.sound.platform.qq.device

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.ikunshare.sound.platform.qq.QQCredentials
import com.ikunshare.sound.platform.qq.device.QQDeviceManager.ensureQimeiReady
import com.ikunshare.sound.platform.qq.device.QQDeviceManager.init

/**
 * QQ 虚拟设备的统一入口：负责生成、持久化（install 级别，单设备）、补齐 QIMEI，
 * 并基于设备 + 凭据构造各类 musicu/musics 请求所需的 `comm` 对象。
 *
 * 用法：在 Application.onCreate 调用 [init]；联网相关补齐通过 [ensureQimeiReady] 在 IO 线程触发。
 */
object QQDeviceManager {

    private const val TAG = "QQDevice"
    private const val PREFS_NAME = "qq_device"
    private const val KEY_DEVICE = "device_json"
    private const val QIMEI_APP_VERSION = "14.9.0.8"

    private val gson = Gson()
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var device: QQDevice? = null

    @Volatile
    private var qimeiFetched = false

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 返回当前设备，首次访问时生成并持久化。线程安全。 */
    fun getDevice(): QQDevice {
        device?.let { return it }
        synchronized(this) {
            device?.let { return it }
            val loaded = load() ?: QQDevice.create().also { save(it) }
            device = loaded
            return loaded
        }
    }

    /**
     * 确保 QIMEI 就绪。无网络/失败时设备仍可用（comm 里 QIMEI 字段为空）。
     * 需在 IO 线程调用，整个生命周期只真正联网一次。
     */
    fun ensureQimeiReady() {
        if (qimeiFetched) return
        val dev = getDevice()
        if (dev.qimei != null && (dev.qimei?.q36?.isNotBlank() == true)) {
            qimeiFetched = true
            return
        }
        synchronized(this) {
            if (qimeiFetched) return
            val result = QQQimeiFetcher.fetch(dev, QIMEI_APP_VERSION, System.currentTimeMillis())
            dev.qimei = result
            save(dev)
            qimeiFetched = true
            Log.d(TAG, "qimei ready: q36=${result.q36.take(8)}…")
        }
    }

    /**
     * 构造请求 `comm`，对齐后端 BuildComm。未登录时省略 uin/authst/tmeLoginType。
     * QIMEI 未就绪时对应字段为空字符串，不阻塞请求。
     */
    fun buildComm(creds: QQCredentials?): MutableMap<String, Any> {
        val dev = getDevice()
        val q16 = dev.qimei?.q16 ?: ""
        val q36 = dev.qimei?.q36 ?: ""

        val comm = mutableMapOf<String, Any>(
            "tyt_exp_env" to "0",
            "v" to "20040008",
            "ct" to "11",
            "cv" to "20040008",
            "chid" to "2005000982",
            "QIMEI" to q16,
            "QIMEI36" to q36,
            "tmeAppID" to "qqmusic",
            "OpenUDID" to dev.openUDID2,
            "udid" to dev.openUDID2,
            "os_ver" to dev.version.release,
            "aid" to dev.androidId,
            "phonetype" to dev.model,
            "devicelevel" to dev.version.sdk,
            "newdevicelevel" to dev.version.sdk,
            "nettype" to "1030",
            "rom" to dev.fingerprint,
            "OpenUDID2" to dev.openUDID2,
            "MValue" to dev.mValue
        )

        if (creds != null && creds.uin.isNotBlank() && creds.authst.isNotBlank()) {
            comm["uin"] = creds.uin
            comm["authst"] = creds.authst
            comm["tmeLoginType"] = creds.loginType
        }
        return comm
    }

    private fun load(): QQDevice? {
        val json = prefs.getString(KEY_DEVICE, null) ?: return null
        return try {
            gson.fromJson(json, QQDevice::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "load device failed, regenerating", e)
            null
        }
    }

    private fun save(dev: QQDevice) {
        prefs.edit().putString(KEY_DEVICE, gson.toJson(dev)).apply()
    }
}
