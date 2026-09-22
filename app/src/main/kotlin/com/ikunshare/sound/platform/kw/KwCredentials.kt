package com.ikunshare.sound.platform.kw

import com.google.gson.annotations.SerializedName
import com.ikunshare.sound.platform.base.ProviderCredentials

data class KwCredentials(
    @SerializedName("source") val source: String,
    @SerializedName("uid") val uid: String
) : ProviderCredentials()
