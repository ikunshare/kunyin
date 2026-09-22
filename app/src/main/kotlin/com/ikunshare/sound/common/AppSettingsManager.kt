package com.ikunshare.sound.common

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 自定义主题条目 */
data class CustomThemeEntry(
    val id: String,
    val name: String,
    val colorsJson: String,
    val bgImagePath: String = ""
)

data class AppSettings(
    val showTranslation: Boolean = false,
    val showRomanization: Boolean = false,
    val showPhonetic: Boolean = false,
    val hideAiQualities: Boolean = false,
    val defaultQualityId: String = "",
    val wifiQualityId: String = "hires",
    val cellularQualityId: String = "320k",
    val maxCacheSizeMb: Int = 500,
    val themeMode: String = "system", // "dark" | "light" | "system" | "custom_<uuid>"
    val systemLightTheme: String = "light", // 跟随系统浅色时: "light"|"custom_<id>"
    val systemDarkTheme: String = "dark",   // 跟随系统深色时: "dark"|"custom_<id>"
    val downloadPath: String = "",
    val downloadTreeUri: String = "",
    val maxConcurrentDownloads: Int = 3,
    val downloadNamingStyle: String = "artist_title",
    val downloadTrackNumber: Boolean = false,
    // 整专下载文件夹名加年份前缀：「年份 艺人 - 专辑名」（多艺人用 & 隔开）
    val downloadAlbumFolderWithYear: Boolean = false,
    // 整专下载时在专辑文件夹内单独保存一份封面文件（命名 cover.jpg/cover.png）
    val downloadAlbumCover: Boolean = false,
    // 文件名追加采样率/位深标记：「... [16Bit-44.1kHz]」，仅无损（FLAC）生效
    val downloadSampleRateTag: Boolean = false,
    val downloadWriteLyricMeta: Boolean = false,
    val downloadLrcFile: Boolean = false,
    val downloadCharLyrics: Boolean = false,
    val downloadTranslation: Boolean = false,
    val downloadRomanization: Boolean = false,
    val downloadCharRomanization: Boolean = false,
    val downloadLrcInterludes: Boolean = false,
    val downloadOverwriteExisting: Boolean = false,
    val autoPlayOnRestart: Boolean = false,
    // 背景图（内置主题共用）
    val bgImagePath: String = "",
    val bgImageOnPlayer: Boolean = false,
    val bgOverlayOpacity: Float = 0.7f,
    // 封面模糊背景
    val coverBlurBg: Boolean = true,
    // 液态玻璃模式
    val floatingBottomBar: Boolean = false,
    val liquidGlassMode: Boolean = false,
    // 底栏不透明度
    val bottomBarOpacity: Float = 1.0f,
    // 桌面歌词设置
    val floatingLyricsEnabled: Boolean = false,
    val floatingLyricsKaraoke: Boolean = true,
    val floatingLyricsTranslation: Boolean = false,
    val floatingLyricsRomanization: Boolean = false,
    val floatingLyricsLocked: Boolean = false,
    val floatingLyricsAlignment: String = "center",  // "left"|"center"|"right"
    val floatingLyricsWidthPercent: Float = 0.9f,     // 0.3~1.0
    val floatingLyricsPlayedColor: Int = 0xFF4CAF50.toInt(),   // 已播放颜色（绿色）
    val floatingLyricsUnplayedColor: Int = 0xFFFFFFFF.toInt(),  // 未播放颜色（白色）
    val floatingLyricsSubColor: Int = 0xDCFFFFFF.toInt(),       // 翻译/音译颜色
    val floatingLyricsBgOpacity: Float = 0.39f,                 // 窗口背景透明度 0.0~1.0
    val floatingLyricsMaxLines: Int = 2,                        // 自动换行最大行数
    val floatingLyricsHideWhenNotPlaying: Boolean = false,        // 未播放时隐藏
    // 歌词推送设置
    val pushLyricsToMediaSession: Boolean = false,
    val pushMeizuStatusBarLyrics: Boolean = false,
    // Lyricon 设置
    val lyriconEnabled: Boolean = false,
    val lyriconSubLine: String = "none",       // "none"|"translation"|"romanization"
    // 歌词推送副行（MediaSession/状态栏）
    val pushLyricsSubLine: String = "none",    // "none"|"translation"|"romanization"
    // 语言设置
    val language: String = "system",  // "system" | "zh-CN" | "zh-TW"
    // 播放设置
    val audioFocusBehavior: String = "pause",  // "none"|"duck"|"pause"
    val audioDuckVolume: Int = 30,              // 压低音量到多少% (10~80)
    val pauseOnPhoneCall: Boolean = true,
    // 蓝牙/有线耳机断开后是否继续播放（默认 false 表示跟随系统行为：暂停）
    val keepPlayingOnHeadsetDisconnect: Boolean = false,
    // ── 字体自定义（全局）──
    val globalFontScale: Float = 1.0f,          // 全局字号缩放 (0.8~1.4)
    val globalFontWeightAdjust: Int = 0,        // 全局字重偏移 (-100 细 / 0 标准 / +100 粗)
    // 歌词字号/字重（播放详情页 + 独立歌词页）
    val lyricsMainFontSize: Int = 24,           // 主歌词字号 (sp)
    val lyricsTransFontSize: Int = 16,          // 翻译/音译字号 (sp)
    val lyricsFontWeight: String = "bold",      // 歌词字重: normal|medium|semibold|bold|black
    val floatingLyricsFontSize: Int = 18,       // 桌面歌词主文字字号 (sp)
    val floatingLyricsSubFontSize: Int = 14,    // 桌面歌词翻译/音译字号 (sp)

    // ── 调试 ──
    val debugHttpLog: Boolean = false,
    val debugLogcat: Boolean = false,
    val debugLyriconLocalCentral: Boolean = false,
    // ── 上次使用的平台 ──
    val lastSearchProvider: String = "",
    val lastImportProvider: String = "",
    // 试听列表：点击搜索结果时是否插入到最前
    val trialListAddToHead: Boolean = false,
    // 点歌后是否自动进入播放页
    val autoOpenPlayerOnSongClick: Boolean = true,
    // 歌手页专辑列表模式（true=单行列表，false=网格）
    val albumListMode: Boolean = false
)

private fun AppSettings.withExclusiveLyricSubLine(previous: AppSettings? = null): AppSettings {
    val normalizedLyrics = if (showTranslation && showRomanization) {
        if (previous != null && showTranslation != previous.showTranslation && showTranslation) {
            copy(showRomanization = false)
        } else {
            copy(showTranslation = false)
        }
    } else {
        this
    }

    return if (normalizedLyrics.floatingLyricsTranslation && normalizedLyrics.floatingLyricsRomanization) {
        if (
            previous != null &&
            normalizedLyrics.floatingLyricsTranslation != previous.floatingLyricsTranslation &&
            normalizedLyrics.floatingLyricsTranslation
        ) {
            normalizedLyrics.copy(floatingLyricsRomanization = false)
        } else {
            normalizedLyrics.copy(floatingLyricsTranslation = false)
        }
    } else {
        normalizedLyrics
    }
}

class AppSettingsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // ── 自定义主题列表 ──
    private val _customThemes = MutableStateFlow(loadCustomThemes())
    val customThemes: StateFlow<List<CustomThemeEntry>> = _customThemes.asStateFlow()

    init {
        migrateOldCustomTheme()
        prefs.edit()
            .remove("download_lrc_angle_bracket_end")
            .remove("download_lrc_10ms_precision")
            .apply()
    }

    private fun load(): AppSettings = AppSettings(
        showTranslation = prefs.getBoolean("show_translation", false),
        showRomanization = prefs.getBoolean("show_romanization", false),
        showPhonetic = prefs.getBoolean("show_phonetic", false),
        hideAiQualities = prefs.getBoolean("hide_ai_qualities", false),
        defaultQualityId = prefs.getString("default_quality_id", "") ?: "",
        wifiQualityId = prefs.getString("wifi_quality_id", "hires") ?: "hires",
        cellularQualityId = prefs.getString("cellular_quality_id", "320k") ?: "320k",
        maxCacheSizeMb = prefs.getInt("max_cache_size_mb", 500),
        themeMode = prefs.getString("theme_mode", "system") ?: "system",
        systemLightTheme = prefs.getString("system_light_theme", "light") ?: "light",
        systemDarkTheme = prefs.getString("system_dark_theme", "dark") ?: "dark",
        downloadPath = prefs.getString("download_path", "") ?: "",
        downloadTreeUri = prefs.getString("download_tree_uri", "") ?: "",
        maxConcurrentDownloads = prefs.getInt("max_concurrent_downloads", 3),
        downloadNamingStyle = prefs.getString("download_naming_style", "artist_title")
            ?: "artist_title",
        downloadTrackNumber = prefs.getBoolean("download_track_number", false),
        downloadAlbumFolderWithYear = prefs.getBoolean("download_album_folder_with_year", false),
        downloadAlbumCover = prefs.getBoolean("download_album_cover", false),
        downloadSampleRateTag = prefs.getBoolean("download_sample_rate_tag", false),
        downloadWriteLyricMeta = prefs.getBoolean("download_write_lyric_meta", false),
        downloadLrcFile = prefs.getBoolean("download_lrc_file", false),
        downloadCharLyrics = prefs.getBoolean("download_char_lyrics", false),
        downloadTranslation = prefs.getBoolean("download_translation", false),
        downloadRomanization = prefs.getBoolean("download_romanization", false),
        downloadCharRomanization = prefs.getBoolean("download_char_romanization", false),
        downloadLrcInterludes = prefs.getBoolean("download_lrc_interludes", false),
        downloadOverwriteExisting = prefs.getBoolean("download_overwrite_existing", false),
        autoPlayOnRestart = prefs.getBoolean("auto_play_on_restart", false),
        bgImagePath = prefs.getString("bg_image_path", "") ?: "",
        bgImageOnPlayer = prefs.getBoolean("bg_image_on_player", false),
        bgOverlayOpacity = prefs.getFloat("bg_overlay_opacity", 0.7f),
        coverBlurBg = prefs.getBoolean("cover_blur_bg", true),
        floatingBottomBar = prefs.getBoolean(
            "floating_bottom_bar",
            prefs.getBoolean("enable_liquid_glass", false)
        ),
        liquidGlassMode = prefs.getBoolean("enable_liquid_glass", false),
        bottomBarOpacity = prefs.getFloat("bottom_bar_opacity", 1.0f),
        floatingLyricsEnabled = prefs.getBoolean("floating_lyrics_enabled", false),
        floatingLyricsKaraoke = prefs.getBoolean("floating_lyrics_karaoke", true),
        floatingLyricsTranslation = prefs.getBoolean("floating_lyrics_translation", false),
        floatingLyricsRomanization = prefs.getBoolean("floating_lyrics_romanization", false),
        floatingLyricsLocked = prefs.getBoolean("floating_lyrics_locked", false),
        floatingLyricsAlignment = prefs.getString("floating_lyrics_alignment", "center")
            ?: "center",
        floatingLyricsWidthPercent = prefs.getFloat("floating_lyrics_width_percent", 0.9f),
        floatingLyricsPlayedColor = prefs.getInt(
            "floating_lyrics_played_color",
            0xFF4CAF50.toInt()
        ),
        floatingLyricsUnplayedColor = prefs.getInt(
            "floating_lyrics_unplayed_color",
            0xFFFFFFFF.toInt()
        ),
        floatingLyricsSubColor = prefs.getInt("floating_lyrics_sub_color", 0xDCFFFFFF.toInt()),
        floatingLyricsBgOpacity = prefs.getFloat("floating_lyrics_bg_opacity", 0.39f),
        floatingLyricsMaxLines = prefs.getInt("floating_lyrics_max_lines", 2).coerceIn(1, 6),
        floatingLyricsHideWhenNotPlaying = prefs.getBoolean(
            "floating_lyrics_hide_when_not_playing",
            false
        ),
        pushLyricsToMediaSession = prefs.getBoolean("push_lyrics_to_media_session", false),
        pushMeizuStatusBarLyrics = prefs.getBoolean("push_meizu_status_bar_lyrics", false),
        lyriconEnabled = prefs.getBoolean("lyricon_enabled", false),
        lyriconSubLine = prefs.getString("lyricon_sub_line", "none") ?: "none",
        pushLyricsSubLine = prefs.getString("push_lyrics_sub_line", "none") ?: "none",
        language = prefs.getString("language", "system") ?: "system",
        audioFocusBehavior = prefs.getString("audio_focus_behavior", "pause") ?: "pause",
        audioDuckVolume = prefs.getInt("audio_duck_volume", 30),
        pauseOnPhoneCall = prefs.getBoolean("pause_on_phone_call", true),
        keepPlayingOnHeadsetDisconnect = prefs.getBoolean(
            "keep_playing_on_headset_disconnect",
            false
        ),
        globalFontScale = prefs.getFloat("global_font_scale", 1.0f),
        globalFontWeightAdjust = prefs.getInt("global_font_weight_adjust", 0),
        lyricsMainFontSize = prefs.getInt("lyrics_main_font_size", 24),
        lyricsTransFontSize = prefs.getInt("lyrics_trans_font_size", 16),
        lyricsFontWeight = prefs.getString("lyrics_font_weight", "bold") ?: "bold",
        floatingLyricsFontSize = prefs.getInt("floating_lyrics_font_size", 18),
        floatingLyricsSubFontSize = prefs.getInt("floating_lyrics_sub_font_size", 14),
        debugHttpLog = prefs.getBoolean("debug_http_log", false),
        debugLogcat = prefs.getBoolean("debug_logcat", false),
        debugLyriconLocalCentral = prefs.getBoolean("debug_lyricon_local_central", false),
        lastSearchProvider = prefs.getString("last_search_provider", "") ?: "",
        lastImportProvider = prefs.getString("last_import_provider", "") ?: "",
        trialListAddToHead = prefs.getBoolean("trial_list_add_to_head", false),
        autoOpenPlayerOnSongClick = prefs.getBoolean("auto_open_player_on_song_click", true),
        albumListMode = prefs.getBoolean("album_list_mode", false)
    ).withExclusiveLyricSubLine()

    fun update(transform: AppSettings.() -> AppSettings) {
        val previous = _settings.value
        val new = previous.transform().withExclusiveLyricSubLine(previous)
        _settings.value = new
        prefs.edit()
            .putBoolean("show_translation", new.showTranslation)
            .putBoolean("show_romanization", new.showRomanization)
            .putBoolean("show_phonetic", new.showPhonetic)
            .putBoolean("hide_ai_qualities", new.hideAiQualities)
            .putString("default_quality_id", new.defaultQualityId)
            .putString("wifi_quality_id", new.wifiQualityId)
            .putString("cellular_quality_id", new.cellularQualityId)
            .putInt("max_cache_size_mb", new.maxCacheSizeMb)
            .putString("theme_mode", new.themeMode)
            .putString("system_light_theme", new.systemLightTheme)
            .putString("system_dark_theme", new.systemDarkTheme)
            .putString("download_path", new.downloadPath)
            .putString("download_tree_uri", new.downloadTreeUri)
            .putInt("max_concurrent_downloads", new.maxConcurrentDownloads)
            .putString("download_naming_style", new.downloadNamingStyle)
            .putBoolean("download_track_number", new.downloadTrackNumber)
            .putBoolean("download_album_folder_with_year", new.downloadAlbumFolderWithYear)
            .putBoolean("download_album_cover", new.downloadAlbumCover)
            .putBoolean("download_sample_rate_tag", new.downloadSampleRateTag)
            .putBoolean("download_write_lyric_meta", new.downloadWriteLyricMeta)
            .putBoolean("download_lrc_file", new.downloadLrcFile)
            .putBoolean("download_char_lyrics", new.downloadCharLyrics)
            .putBoolean("download_translation", new.downloadTranslation)
            .putBoolean("download_romanization", new.downloadRomanization)
            .putBoolean("download_char_romanization", new.downloadCharRomanization)
            .putBoolean("download_lrc_interludes", new.downloadLrcInterludes)
            .putBoolean("download_overwrite_existing", new.downloadOverwriteExisting)
            .putBoolean("auto_play_on_restart", new.autoPlayOnRestart)
            .putString("bg_image_path", new.bgImagePath)
            .putBoolean("bg_image_on_player", new.bgImageOnPlayer)
            .putFloat("bg_overlay_opacity", new.bgOverlayOpacity)
            .putBoolean("cover_blur_bg", new.coverBlurBg)
            .putBoolean("floating_bottom_bar", new.floatingBottomBar)
            .putBoolean("enable_liquid_glass", new.liquidGlassMode)
            .putFloat("bottom_bar_opacity", new.bottomBarOpacity)
            .putBoolean("floating_lyrics_enabled", new.floatingLyricsEnabled)
            .putBoolean("floating_lyrics_karaoke", new.floatingLyricsKaraoke)
            .putBoolean("floating_lyrics_translation", new.floatingLyricsTranslation)
            .putBoolean("floating_lyrics_romanization", new.floatingLyricsRomanization)
            .putBoolean("floating_lyrics_locked", new.floatingLyricsLocked)
            .putString("floating_lyrics_alignment", new.floatingLyricsAlignment)
            .putFloat("floating_lyrics_width_percent", new.floatingLyricsWidthPercent)
            .putInt("floating_lyrics_played_color", new.floatingLyricsPlayedColor)
            .putInt("floating_lyrics_unplayed_color", new.floatingLyricsUnplayedColor)
            .putInt("floating_lyrics_sub_color", new.floatingLyricsSubColor)
            .putFloat("floating_lyrics_bg_opacity", new.floatingLyricsBgOpacity)
            .putInt("floating_lyrics_max_lines", new.floatingLyricsMaxLines.coerceIn(1, 6))
            .putBoolean(
                "floating_lyrics_hide_when_not_playing",
                new.floatingLyricsHideWhenNotPlaying
            )
            .putBoolean("push_lyrics_to_media_session", new.pushLyricsToMediaSession)
            .putBoolean("push_meizu_status_bar_lyrics", new.pushMeizuStatusBarLyrics)
            .putBoolean("lyricon_enabled", new.lyriconEnabled)
            .putString("lyricon_sub_line", new.lyriconSubLine)
            .putString("push_lyrics_sub_line", new.pushLyricsSubLine)
            .putString("language", new.language)
            .putString("audio_focus_behavior", new.audioFocusBehavior)
            .putInt("audio_duck_volume", new.audioDuckVolume)
            .putBoolean("pause_on_phone_call", new.pauseOnPhoneCall)
            .putBoolean("keep_playing_on_headset_disconnect", new.keepPlayingOnHeadsetDisconnect)
            .putFloat("global_font_scale", new.globalFontScale)
            .putInt("global_font_weight_adjust", new.globalFontWeightAdjust)
            .putInt("lyrics_main_font_size", new.lyricsMainFontSize)
            .putInt("lyrics_trans_font_size", new.lyricsTransFontSize)
            .putString("lyrics_font_weight", new.lyricsFontWeight)
            .putInt("floating_lyrics_font_size", new.floatingLyricsFontSize)
            .putInt("floating_lyrics_sub_font_size", new.floatingLyricsSubFontSize)
            .putBoolean("debug_http_log", new.debugHttpLog)
            .putBoolean("debug_logcat", new.debugLogcat)
            .putBoolean("debug_lyricon_local_central", new.debugLyriconLocalCentral)
            .putString("last_search_provider", new.lastSearchProvider)
            .putString("last_import_provider", new.lastImportProvider)
            .putBoolean("trial_list_add_to_head", new.trialListAddToHead)
            .putBoolean("auto_open_player_on_song_click", new.autoOpenPlayerOnSongClick)
            .putBoolean("album_list_mode", new.albumListMode)
            .apply()
    }

    // ── 自定义主题 CRUD ──

    private fun loadCustomThemes(): List<CustomThemeEntry> {
        val json = prefs.getString("custom_themes", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { i ->
                val obj = arr.getJSONObject(i)
                CustomThemeEntry(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    colorsJson = obj.getString("colorsJson"),
                    bgImagePath = obj.optString("bgImagePath", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCustomThemes(themes: List<CustomThemeEntry>) {
        val arr = JSONArray()
        themes.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("colorsJson", t.colorsJson)
                put("bgImagePath", t.bgImagePath)
            })
        }
        prefs.edit().putString("custom_themes", arr.toString()).apply()
        _customThemes.value = themes
    }

    fun addCustomTheme(name: String, colorsJson: String = ""): String {
        val id = UUID.randomUUID().toString().take(8)
        val list = _customThemes.value.toMutableList()
        list.add(CustomThemeEntry(id, name, colorsJson))
        saveCustomThemes(list)
        return id
    }

    fun updateCustomTheme(id: String, transform: CustomThemeEntry.() -> CustomThemeEntry) {
        val list = _customThemes.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) {
            list[idx] = list[idx].transform()
            saveCustomThemes(list)
        }
    }

    fun deleteCustomTheme(id: String) {
        val list = _customThemes.value.toMutableList()
        list.removeAll { it.id == id }
        saveCustomThemes(list)
        // 如果当前使用的是被删除的主题，回退到系统
        val current = _settings.value.themeMode
        if (current == "custom_$id") {
            update { copy(themeMode = "system") }
        }
        // 清理 systemLightTheme / systemDarkTheme 引用
        val s = _settings.value
        if (s.systemLightTheme == "custom_$id" || s.systemDarkTheme == "custom_$id") {
            update {
                copy(
                    systemLightTheme = if (systemLightTheme == "custom_$id") "light" else systemLightTheme,
                    systemDarkTheme = if (systemDarkTheme == "custom_$id") "dark" else systemDarkTheme
                )
            }
        }
    }

    fun getCustomTheme(id: String): CustomThemeEntry? =
        _customThemes.value.firstOrNull { it.id == id }

    /** 迁移旧版单个 customThemeJson 到新的多主题系统 */
    private fun migrateOldCustomTheme() {
        val oldJson = prefs.getString("custom_theme_json", null)
        if (oldJson.isNullOrBlank()) return

        val id = addCustomTheme("自定义主题", oldJson)
        // 如果旧模式为 "custom"，切换到新 ID
        if (_settings.value.themeMode == "custom") {
            update { copy(themeMode = "custom_$id") }
        }
        // 清除旧字段
        prefs.edit().remove("custom_theme_json").apply()
    }

    // ── 搜索历史 ──

    companion object {
        private const val KEY_SEARCH_HISTORY = "search_history"
        private const val MAX_HISTORY_SIZE = 10
    }

    fun getSearchHistory(): List<String> {
        val json = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            List(arr.length()) { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSearchHistory(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val list = getSearchHistory().toMutableList()
        list.remove(trimmed)
        list.add(0, trimmed)
        if (list.size > MAX_HISTORY_SIZE) list.subList(MAX_HISTORY_SIZE, list.size).clear()
        saveHistory(list)
    }

    fun removeSearchHistory(query: String) {
        val list = getSearchHistory().toMutableList()
        if (list.remove(query)) saveHistory(list)
    }

    fun clearSearchHistory() {
        prefs.edit().remove(KEY_SEARCH_HISTORY).apply()
    }

    private fun saveHistory(list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        prefs.edit().putString(KEY_SEARCH_HISTORY, arr.toString()).apply()
    }
}
