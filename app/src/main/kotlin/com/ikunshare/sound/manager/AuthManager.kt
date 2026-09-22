package com.ikunshare.sound.manager

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.ikunshare.sound.utils.HTTPUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * 卡密激活管理器
 *
 * 使用自有 API `c.wwwweb.top/app/checkAuth` 校验 authst 卡密。
 * 卡密保存在 `auth` SharedPreferences 中，App 启动时自动校验一次。
 */
class AuthManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        run {
            val stored = prefs.getString(KEY_AUTHST, null).orEmpty()
            // 有本地卡密时先置为“校验中”，避免启动激活门面首帧误判为未激活而闪烁
            AuthState(authst = stored, isChecking = stored.isNotEmpty())
        }
    )
    val state: StateFlow<AuthState> = _state.asStateFlow()

    val isAuthValid: Boolean
        get() = _state.value.isValid

    val currentAuthst: String
        get() = _state.value.authst

    /** App 启动时调用：若本地有 authst，则向服务端校验一次；结果写入 state。 */
    fun checkOnStartup() {
        val authst = _state.value.authst
        if (authst.isEmpty()) {
            _state.value = _state.value.copy(isChecking = false)
            return
        }
        val (valid, _) = callCheckAuthApi(authst)
        _state.value = _state.value.copy(isValid = valid, isChecking = false)
    }

    /** 用户输入卡密提交：校验通过后写入 prefs 并更新 state。返回 (valid, message)。 */
    fun validateAndSave(authst: String): Pair<Boolean, String> {
        _state.value = _state.value.copy(isChecking = true, errorMessage = "")
        val (valid, message) = callCheckAuthApi(authst)
        if (valid) {
            prefs.edit { putString(KEY_AUTHST, authst) }
        }
        _state.value = _state.value.copy(
            authst = if (valid) authst else _state.value.authst,
            isValid = valid,
            isChecking = false,
            errorMessage = if (!valid) message else ""
        )
        return valid to message
    }

    fun clearError() {
        _state.value = _state.value.copy(errorMessage = "")
    }

    fun logout() {
        prefs.edit { remove(KEY_AUTHST) }
        _state.value = AuthState()
    }

    private fun callCheckAuthApi(authst: String): Pair<Boolean, String> {
        return try {
            val body = JSONObject().put("authst", authst).toString()
            val response = HTTPUtils.post(
                AUTH_API_URL,
                headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = body
            )
            if (!response.isSuccessful || response.body.isNullOrEmpty()) {
                return false to "网络错误，请重试"
            }
            val json = JSONObject(response.body)
            val valid = json.optBoolean("valid", false)
            val message = json.optString("message", "验证失败")
            valid to message
        } catch (_: Exception) {
            false to "网络错误，请重试"
        }
    }

    companion object {
        const val PREFS_NAME = "auth"
        const val KEY_AUTHST = "authst"
        const val AUTH_API_URL = "https://c.wwwweb.top/app/checkAuth"
    }
}

data class AuthState(
    val authst: String = "",
    val isValid: Boolean = false,
    val isChecking: Boolean = false,
    val errorMessage: String = ""
)
