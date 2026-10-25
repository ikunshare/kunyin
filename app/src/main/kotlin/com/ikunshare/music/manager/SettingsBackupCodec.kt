package com.ikunshare.music.manager

import com.google.gson.*
import com.ikunshare.music.common.AppSettings

internal data class DecodedBackupSettings(val settings: AppSettings, val recognizedFields: Int)

/** Version 1 keeps Android's flat fields and adds desktop's shared nested fields. */
internal object SettingsBackupCodec {
    private val gson = Gson()
    // These paths and document grants belong to this installation, not another device.
    private val localFields = setOf("downloadPath", "downloadTreeUri", "bgImagePath")
    private val aliases = linkedMapOf(
        "showListOperationButtons" to "list.showOperationButtons",
        "albumListMode" to "list.albumListMode", "albumSortAsc" to "list.albumSortAsc",
        "showTranslation" to "lyrics.showTranslation", "showRomanization" to "lyrics.showRomanization",
        "pushLyricsToMediaSession" to "lyrics.bluetoothLyric",
        "floatingLyricsEnabled" to "lyrics.desktopEnabled", "floatingLyricsLocked" to "lyrics.desktopLocked",
        "floatingLyricsHideWhenNotPlaying" to "lyrics.desktopPauseHide",
        "floatingLyricsAlignment" to "lyrics.desktopAlign", "floatingLyricsFontSize" to "lyrics.desktopFontSize",
        "floatingLyricsBgOpacity" to "lyrics.desktopBgOpacity",
        "hideAiQualities" to "quality.blockAi", "defaultQualityId" to "player.preferredQuality",
        "autoPlayOnRestart" to "player.autoPlay",
        "maxConcurrentDownloads" to "download.maxConcurrent", "downloadNamingStyle" to "download.namingStyle",
        "downloadTrackNumber" to "download.trackNumberPrefix", "downloadDiscSubDir" to "download.discSubDir",
        "downloadAlbumCover" to "download.saveAlbumCover", "downloadSampleRateTag" to "download.appendQualityTag",
        "downloadOverwriteExisting" to "download.overwriteExisting", "downloadWriteLyricMeta" to "download.embedLyric",
        "downloadLrcFile" to "download.saveLrcFile", "language" to "appearance.lang",
    )
    private val naming = mapOf("artist_title" to "artist-title", "title_artist" to "title-artist", "title_only" to "title-only")

    fun encode(settings: AppSettings): JsonObject {
        val root = gson.toJsonTree(settings).asJsonObject
        localFields.forEach(root::remove)
        aliases.forEach { (field, path) ->
            val value = root[field].deepCopy()
            val mapped = when (field) {
                "downloadNamingStyle" -> naming[value.asString]?.let(::JsonPrimitive)
                "language" -> value.asString.takeIf { it in setOf("zh-CN", "zh-TW") }?.lowercase()?.let(::JsonPrimitive)
                else -> value
            }
            if (mapped != null) put(root, path, mapped)
        }
        // Desktop has no unlimited cache representation. Do not turn Android's unlimited into disabled.
        if (settings.maxCacheSizeMb > 0) put(root, "player.audioCacheBytes",
            JsonPrimitive(if (settings.maxCacheSizeMb == 1) 0L else settings.maxCacheSizeMb.toLong() * 1024 * 1024))
        return root
    }

    fun decode(source: JsonObject, current: AppSettings): DecodedBackupSettings {
        val merged = gson.toJsonTree(current).asJsonObject
        val supplied = JsonObject()
        val desktop = source["version"]?.let { it.isJsonPrimitive && it.asJsonPrimitive.isNumber } == true
        fun addFlatFields() {
            for ((field, _) in merged.entrySet()) {
                if (field in localFields || !source.has(field)) continue
                if (desktop && aliases[field]?.let { get(source, it) != null } == true) continue
                supplied.add(field, source[field])
            }
        }
        // Desktop retains unknown keys when importing Android settings, then exports its own version.
        // Its updated nested values must win over the older Android flat copies on the return trip.
        if (desktop) addFlatFields()
        aliases.forEach { (field, path) ->
            if (!desktop && source.has(field)) return@forEach
            val value = get(source, path) ?: return@forEach
            val mapped = when (field) {
                "downloadNamingStyle" -> {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid setting: $path" }
                    naming.entries.firstOrNull { it.value == value.asString }?.key?.let(::JsonPrimitive)
                }
                "language" -> {
                    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid setting: $path" }
                    when (value.asString.lowercase()) { "zh-cn" -> JsonPrimitive("zh-CN"); "zh-tw" -> JsonPrimitive("zh-TW"); else -> null }
                }
                else -> value
            }
            if (mapped != null) supplied.add(field, mapped)
        }
        (if (desktop || !source.has("maxCacheSizeMb")) get(source, "player.audioCacheBytes") else null)?.let { value ->
            require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "Invalid audioCacheBytes" }
            val bytes = value.asBigDecimal.toBigIntegerExact().longValueExact()
            require(bytes >= 0) { "Invalid audioCacheBytes" }
            val mb = if (bytes == 0L) 1L else ((bytes - 1) / (1024 * 1024) + 1).coerceAtLeast(2)
            require(mb <= Int.MAX_VALUE)
            supplied.addProperty("maxCacheSizeMb", mb.toInt())
        }
        if (!desktop) addFlatFields()
        var count = 0
        for ((field, value) in supplied.entrySet()) {
            if (value.isJsonNull) continue // Older optional fields can be null.
            val expected = merged[field].asJsonPrimitive
            require(value.isJsonPrimitive) { "Invalid setting: $field" }
            val actual = value.asJsonPrimitive
            require(when {
                expected.isBoolean -> actual.isBoolean
                expected.isString -> actual.isString
                else -> actual.isNumber
            }) { "Invalid setting: $field" }
            if (expected.isNumber) {
                val number = actual.asBigDecimal
                if (expected.asNumber is Float || expected.asNumber is Double) require(number.toFloat().isFinite()) { "Invalid setting: $field" }
                else number.toBigIntegerExact().intValueExact()
            }
            merged.add(field, actual.deepCopy()); count++
        }
        val restored = gson.fromJson(merged, AppSettings::class.java)
        return DecodedBackupSettings(if (supplied["floatingLyricsMaxLines"]?.isJsonNull == false)
            restored.copy(floatingLyricsMaxLines = restored.floatingLyricsMaxLines.coerceIn(1, 6)) else restored, count)
    }

    private fun get(root: JsonObject, path: String): JsonElement? {
        var value: JsonElement = root
        for (key in path.split('.')) {
            if (value.isJsonNull) return null
            require(value.isJsonObject) { "Invalid setting section: $path" }
            value = value.asJsonObject[key] ?: return null
        }
        return value.takeUnless { it.isJsonNull }
    }
    private fun put(root: JsonObject, path: String, value: JsonElement) {
        val parts = path.split('.'); var target = root
        for (key in parts.dropLast(1)) target = target.getAsJsonObject(key) ?: JsonObject().also { target.add(key, it) }
        target.add(parts.last(), value)
    }
}
