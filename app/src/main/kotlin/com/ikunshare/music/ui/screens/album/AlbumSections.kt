package com.ikunshare.music.ui.screens.album

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.platform.base.AlbumDisc

internal data class AlbumSection(val disc: AlbumDisc?, val songs: List<MusicItem>)
internal data class AlbumContent(val songs: List<MusicItem>, val discs: List<AlbumDisc>?)

internal fun albumSections(songs: List<MusicItem>, discs: List<AlbumDisc>?): List<AlbumSection> {
    if (discs.isNullOrEmpty() || discs.size < 2 || discs.any { it.count <= 0 } || discs.sumOf { it.count.toLong() } != songs.size.toLong()) {
        return listOf(AlbumSection(null, songs))
    }
    var offset = 0
    return discs.map { disc ->
        AlbumSection(disc, songs.subList(offset, offset + disc.count)).also { offset += disc.count }
    }
}

/** Keep stable track identities without losing disc boundaries when a provider repeats a track. */
internal fun albumContent(songs: List<MusicItem>, discs: List<AlbumDisc>?): AlbumContent {
    val seen = mutableSetOf<String>()
    val sections = albumSections(songs, discs).mapNotNull { section ->
        val tracks = section.songs.filter { seen.add(it.uniqueKey) }
        if (tracks.isEmpty()) null else AlbumSection(section.disc?.copy(count = tracks.size), tracks)
    }
    val validDiscs = sections.mapNotNull { it.disc }.takeIf { it.size > 1 && it.size == sections.size }
    return AlbumContent(sections.flatMap { it.songs }, validDiscs)
}
