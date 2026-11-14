package com.ikunshare.music.ui.screens.player

import com.ikunshare.music.model.PlayMode

internal fun shouldAutoSkipOnError(enabled: Boolean, songCount: Int, playMode: PlayMode): Boolean =
    enabled && songCount > 1 && playMode != PlayMode.SINGLE_LOOP && playMode != PlayMode.SINGLE_PLAY

internal fun nextEligibleSongIndex(
    songCount: Int,
    currentIndex: Int,
    failedKeys: Set<String>,
    keyAt: (Int) -> String,
    direction: Int = 1,
    wrap: Boolean
): Int? {
    if (songCount == 0 || direction == 0) return null
    val start = currentIndex.takeIf { it in 0 until songCount } ?: if (direction > 0) -1 else songCount
    for (offset in 1..songCount) {
        val raw = start + direction * offset
        val index = if (wrap) (raw % songCount + songCount) % songCount else raw
        if (index !in 0 until songCount) return null
        if (keyAt(index) !in failedKeys) return index
    }
    return null
}
