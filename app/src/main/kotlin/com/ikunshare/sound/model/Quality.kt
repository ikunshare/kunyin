package com.ikunshare.sound.model

import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

data class Quality(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("filesize") val filesize: Long,
    @SerializedName("bitrate") val bitrate: Int? = null,
    @SerializedName("md5") val md5: String? = null,
    @SerializedName("mediaInfo") val mediaInfo: String? = null,
    @SerializedName("displaySize") val displaySize: String? = null
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("filesize", filesize)
        bitrate?.let { addProperty("bitrate", it) }
        md5?.let { addProperty("md5", it) }
        mediaInfo?.let { addProperty("mediaInfo", it) }
        displaySize?.let { addProperty("displaySize", it) }
    }

    companion object {
        fun fromJson(json: JsonObject): Quality = Quality(
            id = json.get("id").asString,
            name = json.get("name").asString,
            filesize = json.get("filesize").asLong,
            bitrate = json.get("bitrate")?.takeIf { !it.isJsonNull }?.asInt,
            md5 = json.get("md5")?.takeIf { !it.isJsonNull }?.asString,
            mediaInfo = json.get("mediaInfo")?.takeIf { !it.isJsonNull }?.asString,
            displaySize = json.get("displaySize")?.takeIf { !it.isJsonNull }?.asString
        )

        fun mapToJson(qualities: Map<String, Quality>): JsonObject = JsonObject().apply {
            qualities.forEach { (key, quality) -> add(key, quality.toJson()) }
        }

        fun mapFromJson(json: JsonObject): Map<String, Quality> =
            json.entrySet().associate { (key, value) -> key to fromJson(value.asJsonObject) }
    }
}