package com.ikunshare.music.manager

internal fun normalizePlayerVolume(value: Float): Float =
    value.takeIf { it.isFinite() }?.coerceIn(0.0f, 1.0f) ?: 1.0f

internal fun effectivePlayerVolume(volume: Float, muted: Boolean): Float =
    if (muted) 0.0f else normalizePlayerVolume(volume)
