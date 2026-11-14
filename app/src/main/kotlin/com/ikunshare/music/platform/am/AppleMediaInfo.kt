package com.ikunshare.music.platform.am

import com.google.gson.JsonObject

internal data class AppleMediaMetadata(
    val kind: String? = null,
    val adamId: String? = null,
    val keyEndpoint: String? = null,
    val codec: String? = null,
    val audioGroup: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
    val bitDepth: Int? = null
)

internal fun parseAppleMediaMetadata(json: JsonObject): AppleMediaMetadata = AppleMediaMetadata(
    kind = json.mediaString("kind"),
    adamId = json.mediaString("adamId"),
    keyEndpoint = json.mediaString("keyEndpoint"),
    codec = json.mediaString("codec"),
    audioGroup = json.mediaString("audioGroup"),
    bitrate = json.mediaInt("bitrate"),
    sampleRate = json.mediaInt("sampleRate"),
    bitDepth = json.mediaInt("bitDepth")
)

private fun JsonObject.mediaString(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }

private fun JsonObject.mediaInt(key: String): Int? =
    get(key)?.takeIf { it.isJsonPrimitive }?.asString?.toIntOrNull()
