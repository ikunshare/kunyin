package com.ikunshare.music.ui.screens.artist

import com.ikunshare.music.platform.base.AlbumInfoResult

/** Providers normalize release dates to milliseconds. Unknown dates stay last in both directions. */
internal fun sortArtistAlbums(albums: List<AlbumInfoResult>, ascending: Boolean): List<AlbumInfoResult> =
    albums.sortedWith { a, b ->
        // Negative timestamps can represent real releases before 1970.
        val first = a.publishTime?.takeIf { it != 0L }
        val second = b.publishTime?.takeIf { it != 0L }
        when {
            first == null && second == null -> 0
            first == null -> 1
            second == null -> -1
            ascending -> first.compareTo(second)
            else -> second.compareTo(first)
        }
    }
