package com.ikunshare.music.database

import android.icu.text.Collator
import android.icu.text.RuleBasedCollator
import androidx.annotation.StringRes
import com.ikunshare.music.R
import com.ikunshare.music.model.MusicItem
import java.util.Locale
import kotlin.random.Random

enum class PlaylistSortField(@StringRes val label: Int) {
    TITLE(R.string.playlist_sort_title), ARTIST(R.string.playlist_sort_artist),
    ALBUM(R.string.playlist_sort_album), DURATION(R.string.playlist_sort_duration),
    SOURCE(R.string.playlist_sort_source)
}

enum class PlaylistSortOrder(@StringRes val label: Int) {
    ASC(R.string.playlist_sort_ascending), DESC(R.string.playlist_sort_descending),
    RANDOM(R.string.playlist_sort_random)
}

internal fun sortedPlaylistSongs(
    songs: List<MusicItem>, field: PlaylistSortField, order: PlaylistSortOrder,
    random: Random = Random.Default
): List<MusicItem> {
    if (order == PlaylistSortOrder.RANDOM) return songs.shuffled(random)
    // Match the desktop zh-Hans-CN collator's numeric ordering and base sensitivity.
    val collator = (Collator.getInstance(Locale.SIMPLIFIED_CHINESE) as RuleBasedCollator).apply {
        strength = Collator.PRIMARY
        setNumericCollation(true)
    }
    fun text(song: MusicItem): String = when (field) {
        PlaylistSortField.TITLE -> song.title
        PlaylistSortField.ARTIST -> song.artist
        PlaylistSortField.ALBUM -> song.album
        PlaylistSortField.SOURCE -> song.getTypeDiscriminator()
        PlaylistSortField.DURATION -> ""
    }
    val comparison = Comparator<MusicItem> { a, b ->
        if (field == PlaylistSortField.DURATION) a.duration.compareTo(b.duration)
        else collator.compare(text(a), text(b))
    }
    return songs.sortedWith(if (order == PlaylistSortOrder.DESC) comparison.reversed() else comparison)
}
