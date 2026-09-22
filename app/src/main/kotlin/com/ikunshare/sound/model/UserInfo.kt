package com.ikunshare.sound.model

import com.google.gson.annotations.SerializedName

data class UserInfo(
    @SerializedName("source") val source: String,
    @SerializedName("name") val name: String,
    @SerializedName("avatar") val avatar: String,
    @SerializedName("extra") val extra: Map<String, String> = emptyMap()
)
