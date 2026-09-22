package com.ikunshare.sound.ui.screens.download

import androidx.annotation.StringRes
import com.ikunshare.sound.R

enum class DownloadStatus {
    DOWNLOADING, WAITING, COMPLETED, FAILED, PAUSED
}

data class DownloadTask(
    val songId: Long,
    val source: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val cover: String,
    val qualityId: String = "",
    val qualityName: String = "",
    val status: DownloadStatus,
    val progress: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val totalBytes: Long = 0,
    val downloadedBytes: Long = 0,
    val filePath: String = "",
    val errorMessage: String = "",
    val subDir: String = "",
    val trackNumber: Int = 0
) {
    /** 跨平台唯一标识: "${source}_${songId}_${qualityId}" */
    val taskKey: String get() = "${source}_${songId}_${qualityId}"
}

sealed class DownloadCategory(@StringRes val titleRes: Int, val key: String) {
    data object All : DownloadCategory(R.string.cat_all, "all")
    data object Completed : DownloadCategory(R.string.cat_completed, "completed")
    data object Downloading : DownloadCategory(R.string.cat_downloading, "downloading")
    data object Waiting : DownloadCategory(R.string.cat_waiting, "waiting")
    data object Failed : DownloadCategory(R.string.cat_failed, "failed")
    data object Paused : DownloadCategory(R.string.cat_paused, "paused")
}

enum class DownloadNamingStyle(@StringRes val labelRes: Int, val key: String) {
    ARTIST_TITLE(R.string.naming_artist_title, "artist_title"),
    TITLE_ARTIST(R.string.naming_title_artist, "title_artist"),
    TITLE_ONLY(R.string.naming_title_only, "title_only");

    companion object {
        fun fromKey(key: String): DownloadNamingStyle =
            entries.firstOrNull { it.key == key } ?: ARTIST_TITLE
    }
}
