package com.ikunshare.sound.database

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.UserInfo
import com.ikunshare.sound.platform.base.PlayListInfoResult

class PlatformPlaylistStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("platform_playlists", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveUserInfo(source: String, userInfo: UserInfo) {
        prefs.edit().putString("user_$source", gson.toJson(userInfo)).apply()
    }

    fun getUserInfo(source: String): UserInfo? {
        val json = prefs.getString("user_$source", null) ?: return null
        return try {
            gson.fromJson(json, UserInfo::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun savePlaylists(source: String, playlists: List<PlayListInfoResult>) {
        prefs.edit().putString("playlists_$source", gson.toJson(playlists)).apply()
    }

    fun getPlaylists(source: String): List<PlayListInfoResult> {
        val json = prefs.getString("playlists_$source", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<PlayListInfoResult>>() {}.type
            gson.fromJson(json, type)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveSongs(source: String, playlistId: String, songs: List<MusicItem>) {
        val arr = JsonArray()
        songs.forEach { arr.add(it.toJson()) }
        prefs.edit().putString("songs_${source}_$playlistId", arr.toString()).apply()
    }

    fun savePlaylistInfo(source: String, playlistId: String, info: PlayListInfoResult) {
        prefs.edit().putString("info_${source}_$playlistId", gson.toJson(info)).apply()
    }

    fun getPlaylistInfo(source: String, playlistId: String): PlayListInfoResult? {
        val json = prefs.getString("info_${source}_$playlistId", null) ?: return null
        return try {
            gson.fromJson(json, PlayListInfoResult::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun getSongs(source: String, playlistId: String): List<MusicItem>? {
        val json = prefs.getString("songs_${source}_$playlistId", null) ?: return null
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            arr.map { MusicItem.fromJson(it.asJsonObject) }
        } catch (_: Exception) {
            null
        }
    }

    fun clearSongs(source: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("songs_${source}_") }.forEach {
            editor.remove(it)
        }
        editor.apply()
    }

    fun clear(source: String) {
        val editor = prefs.edit()
        editor.remove("user_$source")
        editor.remove("playlists_$source")
        prefs.all.keys.filter { it.startsWith("songs_${source}_") || it.startsWith("info_${source}_") }
            .forEach {
                editor.remove(it)
            }
        editor.apply()
    }
}
