package com.ikunshare.music.common

import java.util.Calendar

/** Mirrors the desktop year label for normalized provider timestamps, including pre-1970 albums. */
internal fun albumReleaseYear(timestamp: Long?): String {
    if (timestamp == null || timestamp == 0L) return ""
    val year = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
    return if (year in 1001..2199) year.toString() else ""
}
