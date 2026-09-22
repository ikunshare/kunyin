package com.ikunshare.sound.platform.kg

import com.google.gson.annotations.SerializedName
import com.ikunshare.sound.platform.base.ProviderCredentials
import java.util.UUID

data class KgCredentials(
    @SerializedName("userid") val userid: String,
    @SerializedName("token") val token: String,
    @SerializedName("mid") val mid: String? = null,
    @SerializedName("dfid") val dfid: String? = null,
    @SerializedName("deviceId") val deviceId: String = generateDeviceId()
) : ProviderCredentials() {
    companion object {
        fun generateDeviceId(): String =
            UUID.randomUUID().toString().replace("-", "")
    }
}
