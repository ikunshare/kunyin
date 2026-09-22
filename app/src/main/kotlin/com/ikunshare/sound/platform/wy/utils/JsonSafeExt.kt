package com.ikunshare.sound.platform.wy.utils

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** Gson JsonNull 安全取值（get() 和 getAsJsonObject/Array 都可能返回 JsonNull） */
internal fun JsonElement?.safeString(): String? =
    if (this == null || this.isJsonNull) null else this.asString

internal fun JsonElement?.safeLong(default: Long = 0): Long =
    if (this == null || this.isJsonNull) default else this.asLong

internal fun JsonObject.safeGetObject(key: String): JsonObject? {
    val elem = get(key)
    return if (elem != null && elem.isJsonObject) elem.asJsonObject else null
}

internal fun JsonObject.safeGetArray(key: String): JsonArray? {
    val elem = get(key)
    return if (elem != null && elem.isJsonArray) elem.asJsonArray else null
}
