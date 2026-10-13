package com.ikunshare.music.platform.wy

import com.google.gson.annotations.SerializedName
import com.ikunshare.music.platform.base.ProviderCredentials

data class WyCredentials(
    @SerializedName("cookie") val cookie: String
) : ProviderCredentials()
