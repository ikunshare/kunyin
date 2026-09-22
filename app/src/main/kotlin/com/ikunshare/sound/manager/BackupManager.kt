package com.ikunshare.sound.manager

import android.content.Context
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.model.MusicItem
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== 数据类 =====

data class BackupData(
    val version: Int,
    val type: String,
    val createdAt: Long,
    val settings: JsonObject?,
    val customThemes: List<ThemeBackupEntry>?,
    val searchHistory: List<String>?,
    val favorites: List<SongBackupEntry>?,
    val playlists: List<PlaylistBackupEntry>?,
    val trialSongs: List<PlaylistSongEntry>? = null
) {
    val isFullBackup get() = type == "full"

    fun summary(): String {
        val parts = mutableListOf<String>()
        if (settings != null) parts.add("设置")
        customThemes?.let { if (it.isNotEmpty()) parts.add("${it.size} 个主题") }
        searchHistory?.let { if (it.isNotEmpty()) parts.add("${it.size} 条搜索历史") }
        favorites?.let { if (it.isNotEmpty()) parts.add("${it.size} 首收藏") }
        playlists?.let { if (it.isNotEmpty()) parts.add("${it.size} 个歌单") }
        trialSongs?.let { if (it.isNotEmpty()) parts.add("${it.size} 首试听列表") }
        return parts.joinToString("、")
    }

    fun formattedDate(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt))
    }
}

data class ThemeBackupEntry(
    val id: String,
    val name: String,
    val colorsJson: String,
    val bgImageBase64: String
)

data class SongBackupEntry(
    val songJson: String,
    val addedAt: Long
)

data class PlaylistBackupEntry(
    val name: String,
    val createdAt: Long,
    val songs: List<PlaylistSongEntry>
)

data class PlaylistSongEntry(
    val songJson: String,
    val addedAt: Long,
    val position: Int
)

data class RestoreOptions(
    val restoreSettings: Boolean = true,
    val restoreThemes: Boolean = true,
    val restoreHistory: Boolean = true,
    val restorePlaylists: Boolean = true
)

data class RestoreResult(
    val themesAdded: Int = 0,
    val themesSkipped: Int = 0,
    val favoritesAdded: Int = 0,
    val favoritesSkipped: Int = 0,
    val playlistsCreated: Int = 0,
    val songsAdded: Int = 0,
    val songsSkipped: Int = 0,
    val settingsRestored: Boolean = false
)

// ===== BackupManager =====

class BackupManager(
    private val context: Context,
    private val appSettingsManager: AppSettingsManager,
    private val localMusicStore: LocalMusicStore
) {
    companion object {
        private const val BACKUP_VERSION = 1
        private const val MAX_BG_IMAGE_SIZE = 2L * 1024 * 1024 // 2MB
    }

    // ── 导出 ──

    fun exportFull(outputStream: OutputStream) {
        val root = JsonObject()
        root.addProperty("version", BACKUP_VERSION)
        root.addProperty("type", "full")
        root.addProperty("created_at", System.currentTimeMillis())

        // 设置
        root.add("settings", serializeSettings())

        // 自定义主题
        root.add("custom_themes", serializeThemes())

        // 搜索历史
        val historyArr = JsonArray()
        appSettingsManager.getSearchHistory().forEach { historyArr.add(it) }
        root.add("search_history", historyArr)

        // 收藏
        root.add("favorites", serializeFavorites())

        // 歌单
        root.add("playlists", serializePlaylists(null))

        // 试听列表（系统歌单，恢复时写回系统试听列表）
        root.add("trial", serializeTrial())

        outputStream.bufferedWriter().use { it.write(root.toString()) }
    }

    fun exportPlaylists(
        outputStream: OutputStream,
        includeFavorites: Boolean,
        includeTrial: Boolean,
        playlistIds: List<Long>
    ) {
        val root = JsonObject()
        root.addProperty("version", BACKUP_VERSION)
        root.addProperty("type", "playlists_only")
        root.addProperty("created_at", System.currentTimeMillis())

        if (includeFavorites) {
            root.add("favorites", serializeFavorites())
        }

        if (includeTrial) {
            root.add("trial", serializeTrial())
        }

        root.add("playlists", serializePlaylists(playlistIds))

        outputStream.bufferedWriter().use { it.write(root.toString()) }
    }

    private fun serializeSettings(): JsonObject {
        val s = appSettingsManager.settings.value
        return JsonObject().apply {
            addProperty("showTranslation", s.showTranslation)
            addProperty("showRomanization", s.showRomanization)
            addProperty("hideAiQualities", s.hideAiQualities)
            addProperty("defaultQualityId", s.defaultQualityId)
            addProperty("maxCacheSizeMb", s.maxCacheSizeMb)
            addProperty("themeMode", s.themeMode)
            addProperty("systemLightTheme", s.systemLightTheme)
            addProperty("systemDarkTheme", s.systemDarkTheme)
            addProperty("maxConcurrentDownloads", s.maxConcurrentDownloads)
            addProperty("downloadNamingStyle", s.downloadNamingStyle)
            addProperty("downloadWriteLyricMeta", s.downloadWriteLyricMeta)
            addProperty("downloadLrcFile", s.downloadLrcFile)
            addProperty("downloadCharLyrics", s.downloadCharLyrics)
            addProperty("downloadTranslation", s.downloadTranslation)
            addProperty("downloadRomanization", s.downloadRomanization)
            addProperty("downloadCharRomanization", s.downloadCharRomanization)
            addProperty("downloadOverwriteExisting", s.downloadOverwriteExisting)
            addProperty("autoPlayOnRestart", s.autoPlayOnRestart)
            addProperty("bgImageOnPlayer", s.bgImageOnPlayer)
            addProperty("bgOverlayOpacity", s.bgOverlayOpacity)
            addProperty("coverBlurBg", s.coverBlurBg)
            addProperty("floatingBottomBar", s.floatingBottomBar)
            addProperty("liquidGlassMode", s.liquidGlassMode)
            addProperty("bottomBarOpacity", s.bottomBarOpacity)
            addProperty("floatingLyricsEnabled", s.floatingLyricsEnabled)
            addProperty("floatingLyricsKaraoke", s.floatingLyricsKaraoke)
            addProperty("floatingLyricsTranslation", s.floatingLyricsTranslation)
            addProperty("floatingLyricsRomanization", s.floatingLyricsRomanization)
            addProperty("floatingLyricsLocked", s.floatingLyricsLocked)
            addProperty("floatingLyricsAlignment", s.floatingLyricsAlignment)
            addProperty("floatingLyricsWidthPercent", s.floatingLyricsWidthPercent)
            addProperty("floatingLyricsPlayedColor", s.floatingLyricsPlayedColor)
            addProperty("floatingLyricsUnplayedColor", s.floatingLyricsUnplayedColor)
            addProperty("floatingLyricsSubColor", s.floatingLyricsSubColor)
            addProperty("floatingLyricsBgOpacity", s.floatingLyricsBgOpacity)
            addProperty("floatingLyricsMaxLines", s.floatingLyricsMaxLines)
            addProperty("floatingLyricsHideWhenNotPlaying", s.floatingLyricsHideWhenNotPlaying)
            addProperty("pushLyricsToMediaSession", s.pushLyricsToMediaSession)
            addProperty("pushMeizuStatusBarLyrics", s.pushMeizuStatusBarLyrics)
            addProperty("language", s.language)
            addProperty("audioFocusBehavior", s.audioFocusBehavior)
            addProperty("audioDuckVolume", s.audioDuckVolume)
            addProperty("pauseOnPhoneCall", s.pauseOnPhoneCall)
        }
    }

    private fun serializeThemes(): JsonArray {
        val arr = JsonArray()
        appSettingsManager.customThemes.value.forEach { theme ->
            val obj = JsonObject().apply {
                addProperty("id", theme.id)
                addProperty("name", theme.name)
                addProperty("colorsJson", theme.colorsJson)
                // 背景图 base64
                var bgBase64 = ""
                if (theme.bgImagePath.isNotBlank()) {
                    val file = File(theme.bgImagePath)
                    if (file.isFile && file.length() in 1..MAX_BG_IMAGE_SIZE) {
                        bgBase64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                    }
                }
                addProperty("bgImageBase64", bgBase64)
            }
            arr.add(obj)
        }
        return arr
    }

    private fun serializeFavorites(): JsonArray {
        val arr = JsonArray()
        localMusicStore.queryAllFavoritesRaw().forEach { (json, addedAt) ->
            arr.add(JsonObject().apply {
                addProperty("song_json", json)
                addProperty("added_at", addedAt)
            })
        }
        return arr
    }

    private fun serializePlaylists(ids: List<Long>?): JsonArray {
        val arr = JsonArray()
        val playlists = localMusicStore.playlistsFlow.value
        // 系统歌单（试听列表 / 我的收藏）不写入 playlists 段：
        // 我的收藏走 favorites 段，试听列表是临时容器不需要持久化。
        val filtered = if (ids != null) {
            playlists.filter { it.id in ids && !it.isSystem }
        } else {
            playlists.filter { !it.isSystem }
        }
        filtered.forEach { pl ->
            val songsArr = JsonArray()
            localMusicStore.queryPlaylistSongsRaw(pl.id).forEach { (json, addedAt, pos) ->
                songsArr.add(JsonObject().apply {
                    addProperty("song_json", json)
                    addProperty("added_at", addedAt)
                    addProperty("position", pos)
                })
            }
            arr.add(JsonObject().apply {
                addProperty("name", pl.name)
                addProperty("created_at", pl.createdAt)
                add("songs", songsArr)
            })
        }
        return arr
    }

    /** 序列化系统试听列表的歌曲（恢复时写回系统试听列表，不作为普通歌单）。 */
    private fun serializeTrial(): JsonArray {
        val arr = JsonArray()
        val trialId = localMusicStore.getTrialPlaylistId()
        if (trialId <= 0) return arr
        localMusicStore.queryPlaylistSongsRaw(trialId).forEach { (json, addedAt, pos) ->
            arr.add(JsonObject().apply {
                addProperty("song_json", json)
                addProperty("added_at", addedAt)
                addProperty("position", pos)
            })
        }
        return arr
    }

    // ── 解析 ──

    fun parseBackup(inputStream: InputStream): BackupData {
        val json = inputStream.bufferedReader().use { it.readText() }
        val root = JsonParser.parseString(json).asJsonObject

        val version = root.get("version")?.asInt ?: 0
        if (version < 1) throw IllegalArgumentException("unsupported_version")

        val type = root.get("type")?.asString ?: "full"
        val createdAt = root.get("created_at")?.asLong ?: 0L

        val settings = root.getAsJsonObject("settings")

        val themes = root.getAsJsonArray("custom_themes")?.map { el ->
            val obj = el.asJsonObject
            ThemeBackupEntry(
                id = obj.get("id")?.asString ?: "",
                name = obj.get("name")?.asString ?: "",
                colorsJson = obj.get("colorsJson")?.asString ?: "",
                bgImageBase64 = obj.get("bgImageBase64")?.asString ?: ""
            )
        }

        val history = root.getAsJsonArray("search_history")?.map { it.asString }

        val favorites = root.getAsJsonArray("favorites")?.map { el ->
            val obj = el.asJsonObject
            SongBackupEntry(
                songJson = obj.get("song_json").asString,
                addedAt = obj.get("added_at")?.asLong ?: System.currentTimeMillis()
            )
        }

        val playlists = root.getAsJsonArray("playlists")?.map { el ->
            val obj = el.asJsonObject
            PlaylistBackupEntry(
                name = obj.get("name").asString,
                createdAt = obj.get("created_at")?.asLong ?: System.currentTimeMillis(),
                songs = obj.getAsJsonArray("songs")?.map { sEl ->
                    val sObj = sEl.asJsonObject
                    PlaylistSongEntry(
                        songJson = sObj.get("song_json").asString,
                        addedAt = sObj.get("added_at")?.asLong ?: System.currentTimeMillis(),
                        position = sObj.get("position")?.asInt ?: 0
                    )
                } ?: emptyList()
            )
        }

        val trialSongs = root.getAsJsonArray("trial")?.map { sEl ->
            val sObj = sEl.asJsonObject
            PlaylistSongEntry(
                songJson = sObj.get("song_json").asString,
                addedAt = sObj.get("added_at")?.asLong ?: System.currentTimeMillis(),
                position = sObj.get("position")?.asInt ?: 0
            )
        }

        return BackupData(
            version, type, createdAt, settings, themes, history,
            favorites, playlists, trialSongs
        )
    }

    // ── 恢复 ──

    fun restore(data: BackupData, options: RestoreOptions): RestoreResult {
        var themesAdded = 0
        var themesSkipped = 0
        var favoritesAdded = 0
        var favoritesSkipped = 0
        var playlistsCreated = 0
        var songsAdded = 0
        var songsSkipped = 0
        var settingsRestored = false

        // 恢复设置
        if (options.restoreSettings && data.settings != null) {
            restoreSettings(data.settings)
            settingsRestored = true
        }

        // 恢复主题
        if (options.restoreThemes && data.customThemes != null) {
            val existingNames = appSettingsManager.customThemes.value.map { it.name }.toSet()
            data.customThemes.forEach { theme ->
                if (theme.name in existingNames) {
                    themesSkipped++
                } else {
                    val newId = appSettingsManager.addCustomTheme(theme.name, theme.colorsJson)
                    // 恢复背景图
                    if (theme.bgImageBase64.isNotBlank()) {
                        try {
                            val bytes = Base64.decode(theme.bgImageBase64, Base64.NO_WRAP)
                            val dest = File(
                                context.filesDir,
                                "custom_bg_${System.currentTimeMillis()}.jpg"
                            )
                            dest.writeBytes(bytes)
                            appSettingsManager.updateCustomTheme(newId) {
                                copy(bgImagePath = dest.absolutePath)
                            }
                        } catch (_: Exception) { /* skip bg image */
                        }
                    }
                    themesAdded++
                }
            }
        }

        // 恢复搜索历史
        if (options.restoreHistory && data.searchHistory != null) {
            data.searchHistory.reversed().forEach { query ->
                appSettingsManager.addSearchHistory(query)
            }
        }

        // 恢复收藏和歌单
        if (options.restorePlaylists) {
            // 收藏
            data.favorites?.forEach { entry ->
                try {
                    val item = MusicItem.fromJsonString(entry.songJson)
                    if (localMusicStore.isFavorite(item)) {
                        favoritesSkipped++
                    } else {
                        localMusicStore.addFavoriteDirect(entry.songJson, entry.addedAt)
                        favoritesAdded++
                    }
                } catch (_: Exception) {
                    songsSkipped++
                }
            }

            // 歌单
            data.playlists?.forEach { pl ->
                val newPlaylistId = localMusicStore.createPlaylistDirect(pl.name, pl.createdAt)
                if (newPlaylistId > 0) {
                    playlistsCreated++
                    pl.songs.forEach { song ->
                        try {
                            localMusicStore.addToPlaylistDirect(
                                newPlaylistId, song.songJson, song.addedAt, song.position
                            )
                            songsAdded++
                        } catch (_: Exception) {
                            songsSkipped++
                        }
                    }
                }
            }

            // 试听列表：写回系统试听列表（CONFLICT_IGNORE 天然去重，不新建歌单）
            data.trialSongs?.let { songs ->
                if (songs.isNotEmpty()) {
                    val trialId = localMusicStore.getTrialPlaylistId()
                    if (trialId > 0) {
                        songs.forEach { song ->
                            try {
                                localMusicStore.addToPlaylistDirect(
                                    trialId, song.songJson, song.addedAt, song.position
                                )
                                songsAdded++
                            } catch (_: Exception) {
                                songsSkipped++
                            }
                        }
                    }
                }
            }

            localMusicStore.refreshAfterRestore()
        }

        return RestoreResult(
            themesAdded = themesAdded,
            themesSkipped = themesSkipped,
            favoritesAdded = favoritesAdded,
            favoritesSkipped = favoritesSkipped,
            playlistsCreated = playlistsCreated,
            songsAdded = songsAdded,
            songsSkipped = songsSkipped,
            settingsRestored = settingsRestored
        )
    }

    private fun restoreSettings(json: JsonObject) {
        appSettingsManager.update {
            copy(
                showTranslation = json.get("showTranslation")?.asBoolean ?: showTranslation,
                showRomanization = json.get("showRomanization")?.asBoolean ?: showRomanization,
                hideAiQualities = json.get("hideAiQualities")?.asBoolean ?: hideAiQualities,
                defaultQualityId = json.get("defaultQualityId")?.asString ?: defaultQualityId,
                maxCacheSizeMb = json.get("maxCacheSizeMb")?.asInt ?: maxCacheSizeMb,
                themeMode = json.get("themeMode")?.asString ?: themeMode,
                systemLightTheme = json.get("systemLightTheme")?.asString ?: systemLightTheme,
                systemDarkTheme = json.get("systemDarkTheme")?.asString ?: systemDarkTheme,
                maxConcurrentDownloads = json.get("maxConcurrentDownloads")?.asInt
                    ?: maxConcurrentDownloads,
                downloadNamingStyle = json.get("downloadNamingStyle")?.asString
                    ?: downloadNamingStyle,
                downloadWriteLyricMeta = json.get("downloadWriteLyricMeta")?.asBoolean
                    ?: downloadWriteLyricMeta,
                downloadLrcFile = json.get("downloadLrcFile")?.asBoolean ?: downloadLrcFile,
                downloadCharLyrics = json.get("downloadCharLyrics")?.asBoolean
                    ?: downloadCharLyrics,
                downloadTranslation = json.get("downloadTranslation")?.asBoolean
                    ?: downloadTranslation,
                downloadRomanization = json.get("downloadRomanization")?.asBoolean
                    ?: downloadRomanization,
                downloadCharRomanization = json.get("downloadCharRomanization")?.asBoolean
                    ?: downloadCharRomanization,
                downloadOverwriteExisting = json.get("downloadOverwriteExisting")?.asBoolean
                    ?: downloadOverwriteExisting,
                autoPlayOnRestart = json.get("autoPlayOnRestart")?.asBoolean ?: autoPlayOnRestart,
                bgImageOnPlayer = json.get("bgImageOnPlayer")?.asBoolean ?: bgImageOnPlayer,
                bgOverlayOpacity = json.get("bgOverlayOpacity")?.asFloat ?: bgOverlayOpacity,
                coverBlurBg = json.get("coverBlurBg")?.asBoolean ?: coverBlurBg,
                floatingBottomBar = json.get("floatingBottomBar")?.asBoolean
                    ?: (json.get("liquidGlassMode")?.asBoolean ?: floatingBottomBar),
                liquidGlassMode = json.get("liquidGlassMode")?.asBoolean ?: liquidGlassMode,
                bottomBarOpacity = json.get("bottomBarOpacity")?.asFloat ?: bottomBarOpacity,
                floatingLyricsEnabled = json.get("floatingLyricsEnabled")?.asBoolean
                    ?: floatingLyricsEnabled,
                floatingLyricsKaraoke = json.get("floatingLyricsKaraoke")?.asBoolean
                    ?: floatingLyricsKaraoke,
                floatingLyricsTranslation = json.get("floatingLyricsTranslation")?.asBoolean
                    ?: floatingLyricsTranslation,
                floatingLyricsRomanization = json.get("floatingLyricsRomanization")?.asBoolean
                    ?: floatingLyricsRomanization,
                floatingLyricsLocked = json.get("floatingLyricsLocked")?.asBoolean
                    ?: floatingLyricsLocked,
                floatingLyricsAlignment = json.get("floatingLyricsAlignment")?.asString
                    ?: floatingLyricsAlignment,
                floatingLyricsWidthPercent = json.get("floatingLyricsWidthPercent")?.asFloat
                    ?: floatingLyricsWidthPercent,
                floatingLyricsPlayedColor = json.get("floatingLyricsPlayedColor")?.asInt
                    ?: floatingLyricsPlayedColor,
                floatingLyricsUnplayedColor = json.get("floatingLyricsUnplayedColor")?.asInt
                    ?: floatingLyricsUnplayedColor,
                floatingLyricsSubColor = json.get("floatingLyricsSubColor")?.asInt
                    ?: floatingLyricsSubColor,
                floatingLyricsBgOpacity = json.get("floatingLyricsBgOpacity")?.asFloat
                    ?: floatingLyricsBgOpacity,
                floatingLyricsMaxLines = (json.get("floatingLyricsMaxLines")?.asInt
                    ?: floatingLyricsMaxLines).coerceIn(1, 6),
                floatingLyricsHideWhenNotPlaying = json.get("floatingLyricsHideWhenNotPlaying")?.asBoolean
                    ?: floatingLyricsHideWhenNotPlaying,
                pushLyricsToMediaSession = json.get("pushLyricsToMediaSession")?.asBoolean
                    ?: pushLyricsToMediaSession,
                pushMeizuStatusBarLyrics = json.get("pushMeizuStatusBarLyrics")?.asBoolean
                    ?: pushMeizuStatusBarLyrics,
                language = json.get("language")?.asString ?: language,
                audioFocusBehavior = json.get("audioFocusBehavior")?.asString ?: audioFocusBehavior,
                audioDuckVolume = json.get("audioDuckVolume")?.asInt ?: audioDuckVolume,
                pauseOnPhoneCall = json.get("pauseOnPhoneCall")?.asBoolean ?: pauseOnPhoneCall
            )
        }
    }
}
