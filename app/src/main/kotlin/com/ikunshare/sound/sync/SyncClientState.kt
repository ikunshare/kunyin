package com.ikunshare.sound.sync

import android.content.Context
import android.content.SharedPreferences
import com.ikunshare.sound.manager.AuthManager

data class SyncSession(val clientId: String, val aesKey: String, val serverName: String)

/** 持久化同步会话信息及用户填写的服务器地址 / CDK / 设备名。 */
class SyncClientState(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("lx_sync", Context.MODE_PRIVATE)

    /** 激活卡密偏好：未单独配置同步 CDK 时，默认沿用激活卡密。 */
    private val authPrefs: SharedPreferences =
        context.getSharedPreferences(AuthManager.PREFS_NAME, Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty()
            .ifBlank { DEFAULT_SERVER_URL }
        set(v) = prefs.edit().putString(KEY_SERVER_URL, v).apply()

    var cdk: String
        get() {
            val stored = prefs.getString(KEY_CDK, null).orEmpty()
            // 未单独配置同步 CDK（含早期版本误存的占位符）时，默认使用激活卡密
            return if (stored.isBlank() || stored == LEGACY_DEFAULT_CDK) {
                authPrefs.getString(AuthManager.KEY_AUTHST, null).orEmpty()
            } else stored
        }
        set(v) = prefs.edit().putString(KEY_CDK, v).apply()

    var deviceName: String
        get() = prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(v) = prefs.edit().putString(KEY_DEVICE_NAME, v).apply()

    /** 同步模式：merge_local_remote / merge_remote_local / overwrite_local_remote / overwrite_remote_local */
    var syncMode: String
        get() = prefs.getString(KEY_MODE, "merge_local_remote") ?: "merge_local_remote"
        set(v) = prefs.edit().putString(KEY_MODE, v).apply()

    var autoConnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO, v).apply()

    fun load(): SyncSession? {
        val cid = prefs.getString(KEY_CLIENT_ID, null) ?: return null
        val key = prefs.getString(KEY_SESSION_KEY, null) ?: return null
        val name = prefs.getString(KEY_SERVER_NAME, "") ?: ""
        return SyncSession(cid, key, name)
    }

    fun save(session: SyncSession) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, session.clientId)
            .putString(KEY_SESSION_KEY, session.aesKey)
            .putString(KEY_SERVER_NAME, session.serverName)
            .apply()
    }

    fun clearSession() {
        prefs.edit()
            .remove(KEY_CLIENT_ID)
            .remove(KEY_SESSION_KEY)
            .remove(KEY_SERVER_NAME)
            .apply()
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://c.wwwweb.top/sync"

        /** 早期版本曾把同步 CDK 默认值误写为固定汉字，读取时视为未配置。 */
        private const val LEGACY_DEFAULT_CDK = "激活卡密"

        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_CDK = "cdk"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_SESSION_KEY = "session_key"
        private const val KEY_SERVER_NAME = "server_name"
        private const val KEY_MODE = "sync_mode"
        private const val KEY_AUTO = "auto_connect"
    }
}
