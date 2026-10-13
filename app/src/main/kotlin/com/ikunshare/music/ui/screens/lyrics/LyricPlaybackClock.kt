package com.ikunshare.music.ui.screens.lyrics

/** Interpolate between player samples without rewinding the highlight on a late sample. */
internal class LyricPlaybackClock(initialPosition: Long, sampledAtNanos: Long) {
    private var samplePosition = initialPosition
    private var sampleTime = sampledAtNanos
    private var renderedPosition = initialPosition

    fun accept(position: Long, nowNanos: Long, running: Boolean): Long {
        // Compare with the previous player sample, not the extrapolated animation frame.
        // An actual backward seek must still take effect immediately, even a small one.
        val backwardsSeek = position < samplePosition
        samplePosition = position
        sampleTime = nowNanos
        renderedPosition = if (!running || backwardsSeek) position else maxOf(renderedPosition, position)
        return renderedPosition
    }

    fun frame(nowNanos: Long, playbackRate: Float): Long {
        val elapsedMs = ((nowNanos - sampleTime) / 1_000_000L).coerceIn(0L, 1_000L)
        val predicted = samplePosition + (elapsedMs * playbackRate).toLong()
        renderedPosition = maxOf(renderedPosition, predicted)
        return renderedPosition
    }
}
