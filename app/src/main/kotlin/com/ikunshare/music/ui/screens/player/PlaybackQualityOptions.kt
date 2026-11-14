package com.ikunshare.music.ui.screens.player

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.Quality

/** Desktop playback can attempt QQ's basic stream when optional cloud track details are unavailable. */
internal fun playbackQualityOptions(song: MusicItem): List<Quality> {
    val source = song.getTypeDiscriminator()
    if (source == "qq" && song.qualities.isEmpty()) {
        return listOf(Quality("128k", "普通音质 128K", 0))
    }
    return song.qualities.values.filterNot { it.id == "atmos_plus" && source != "wy" }
}
