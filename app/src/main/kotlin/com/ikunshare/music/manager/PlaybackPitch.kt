package com.ikunshare.music.manager

internal fun playbackPitchFor(speed: Float, preserve: Boolean): Float = if (preserve) 1.0f else speed

internal fun normalizePlaybackRate(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(0.25f, 3.0f) ?: 1.0f
