package com.ikunshare.sound.database

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.PlayMode

data class PlaybackState(
    val playlist: List<MusicItem>,
    val currentIndex: Int,
    val positionMs: Long,
    val playMode: PlayMode,
    val stopAfterQueueDrains: Boolean = false
)

class PlaybackStateStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("playback_state", Context.MODE_PRIVATE)

    fun save(playlist: List<MusicItem>, currentIndex: Int, positionMs: Long, playMode: PlayMode) {
        save(playlist, currentIndex, positionMs, playMode, false)
    }

    fun save(
        playlist: List<MusicItem>,
        currentIndex: Int,
        positionMs: Long,
        playMode: PlayMode,
        stopAfterQueueDrains: Boolean
    ) {
        val jsonArray = JsonArray().apply {
            playlist.forEach { add(it.toJson()) }
        }
        prefs.edit()
            .putString(KEY_PLAYLIST_JSON, jsonArray.toString())
            .putInt(KEY_CURRENT_INDEX, currentIndex)
            .putLong(KEY_POSITION_MS, positionMs)
            .putString(KEY_PLAY_MODE, playMode.name)
            .putBoolean(KEY_STOP_AFTER_QUEUE_DRAINS, stopAfterQueueDrains)
            .apply()
    }

    fun restore(): PlaybackState? {
        val playlistJson = prefs.getString(KEY_PLAYLIST_JSON, null) ?: return null
        return try {
            val array = JsonParser.parseString(playlistJson).asJsonArray
            val playlist = array.map { MusicItem.fromJson(it.asJsonObject) }
            if (playlist.isEmpty()) return null
            val currentIndex = prefs.getInt(KEY_CURRENT_INDEX, 0).coerceIn(0, playlist.size - 1)
            val positionMs = prefs.getLong(KEY_POSITION_MS, 0L)
            val playModeName = prefs.getString(KEY_PLAY_MODE, null)
            val playMode = playModeName?.let {
                try {
                    PlayMode.valueOf(it)
                } catch (_: Exception) {
                    PlayMode.SEQUENTIAL
                }
            } ?: PlayMode.SEQUENTIAL
            val stopAfterQueueDrains = prefs.getBoolean(KEY_STOP_AFTER_QUEUE_DRAINS, false)
            PlaybackState(playlist, currentIndex, positionMs, playMode, stopAfterQueueDrains)
        } catch (_: Exception) {
            null
        }
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_PLAYLIST_JSON)
            .remove(KEY_CURRENT_INDEX)
            .remove(KEY_POSITION_MS)
            .remove(KEY_PLAY_MODE)
            .remove(KEY_STOP_AFTER_QUEUE_DRAINS)
            .apply()
    }

    companion object {
        private const val KEY_PLAYLIST_JSON = "pb_playlist_json"
        private const val KEY_CURRENT_INDEX = "pb_current_index"
        private const val KEY_POSITION_MS = "pb_position_ms"
        private const val KEY_PLAY_MODE = "pb_play_mode"
        private const val KEY_STOP_AFTER_QUEUE_DRAINS = "pb_stop_after_queue_drains"
    }
}
