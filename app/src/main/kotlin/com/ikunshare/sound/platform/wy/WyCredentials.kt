package com.ikunshare.sound.platform.wy

import com.google.gson.annotations.SerializedName
import com.ikunshare.sound.platform.base.ProviderCredentials

data class WyCredentials(
    @SerializedName("cookie") val cookie: String
) : ProviderCredentials()
