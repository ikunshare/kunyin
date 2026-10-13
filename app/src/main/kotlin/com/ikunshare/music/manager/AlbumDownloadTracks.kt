package com.ikunshare.music.manager

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.ui.screens.album.albumContent
import com.ikunshare.music.ui.screens.album.albumSections
import com.ikunshare.music.ui.screens.download.DownloadDisc
import com.ikunshare.music.ui.screens.download.DownloadTask

internal data class AlbumDownloadTrack(val song: MusicItem, val number: Int, val disc: DownloadDisc?)

internal fun albumDownloadTracks(songs: List<MusicItem>, discs: List<AlbumDisc>?): List<AlbumDownloadTrack> {
    val content = albumContent(songs, discs)
    var number = 0
    return albumSections(content.songs, content.discs).flatMap { section ->
        section.songs.mapIndexed { index, song ->
            AlbumDownloadTrack(song, ++number, section.disc?.let {
                DownloadDisc(it.no, it.name.takeUnless { name -> name == "CD${it.no}" }.orEmpty(),
                    content.discs!!.size, index + 1)
            })
        }
    }
}

internal fun downloadDiscDirectory(task: DownloadTask, enabled: Boolean): String {
    val disc = task.disc?.takeIf { enabled } ?: return task.subDir
    val label = "CD${disc.no}" + disc.name.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
    val safe = label.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    return listOf(task.subDir, safe).filter { it.isNotBlank() }.joinToString("/")
}
