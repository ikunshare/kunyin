package com.ikunshare.sound.platform.qq

import com.google.gson.annotations.SerializedName
import com.ikunshare.sound.platform.base.ProviderCredentials

data class QQCredentials(
    @SerializedName("authst") val authst: String,
    @SerializedName("uin") val uin: String,
    @SerializedName("refreshToken") val refreshToken: String = "",
    @SerializedName("refreshKey") val refreshKey: String = "",
    @SerializedName("accessToken") val accessToken: String = "",
    @SerializedName("openid") val openid: String = "",
    @SerializedName("expiredAt") val expiredAt: Long = 0
) : ProviderCredentials() {
    val loginType: Int
        get() = when {
            authst.startsWith("Q_H_L") -> 2
            authst.startsWith("W_X_") -> 1
            else -> 0
        }
}
