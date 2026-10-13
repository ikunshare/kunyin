package com.ikunshare.music.ui.screens.download

import androidx.annotation.StringRes
import com.ikunshare.music.R
import com.ikunshare.music.model.MusicItem

/** Keep ordinary provider task IDs compatible; Kugou aliases share their hash identity. */
internal fun downloadIdentity(song: MusicItem): String =
    if (song.getTypeDiscriminator() == "kg") song.uniqueKey else song.id.toString()

internal fun downloadTaskKey(source: String, identity: String, quality: String) = "${source}_${identity}_${quality}"

internal fun downloadRestorePriority(task: DownloadTask): Int = when (task.status) {
    DownloadStatus.COMPLETED -> 4
    DownloadStatus.PAUSED -> 3
    DownloadStatus.WAITING, DownloadStatus.DOWNLOADING -> 2
    DownloadStatus.FAILED -> 1
}

enum class DownloadStatus {
    DOWNLOADING, WAITING, COMPLETED, FAILED, PAUSED
}

data class DownloadDisc(val no: Int, val name: String, val total: Int, val trackNumber: Int)

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
    val trackNumber: Int = 0,
    val songIdentity: String = "",
    val disc: DownloadDisc? = null
) {
    val taskKey: String get() = downloadTaskKey(source, songIdentity.ifEmpty { songId.toString() }, qualityId)
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
