package com.ikunshare.music.ui.screens.player

import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.QueueSource

internal enum class ListenReportKind { START, END }

internal data class ListenReportEvent(
    val kind: ListenReportKind,
    val song: MusicItem,
    val qualityId: String,
    val sourceId: String?,
    val playedSec: Long = 0L
)

internal class ListenReportTracker(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private var tracked: MusicItem? = null
    private var trackedQuality = ""
    private var trackedSourceId: String? = null
    private var accumulatedMs = 0L
    private var playStartedAt: Long? = null

    fun beforeLoad(song: MusicItem, now: Long = clock()): List<ListenReportEvent> {
        if (tracked?.uniqueKey == song.uniqueKey) {
            settle(now)
            playStartedAt = null
            return emptyList()
        }
        return listOfNotNull(flush(now))
    }

    fun loaded(
        song: MusicItem,
        qualityId: String,
        sourceId: String?,
        playing: Boolean,
        now: Long = clock()
    ): List<ListenReportEvent> {
        val same = tracked?.uniqueKey == song.uniqueKey
        val events = mutableListOf<ListenReportEvent>()
        if (same) {
            settle(now)
        } else {
            flush(now)?.let(events::add)
            tracked = song
            accumulatedMs = 0L
            events += ListenReportEvent(ListenReportKind.START, song, qualityId, sourceId)
        }
        trackedQuality = qualityId
        trackedSourceId = sourceId
        playStartedAt = if (playing) now else null
        return events
    }

    fun playingChanged(playing: Boolean, now: Long = clock()) {
        if (playing) {
            if (tracked != null && playStartedAt == null) playStartedAt = now
        } else {
            settle(now)
        }
    }

    fun flush(now: Long = clock()): ListenReportEvent? {
        settle(now)
        val seconds = accumulatedMs / 1000L
        accumulatedMs = 0L
        val song = tracked ?: return null
        if (seconds <= 0L) return null
        return ListenReportEvent(
            ListenReportKind.END,
            song,
            trackedQuality,
            trackedSourceId,
            seconds
        )
    }

    fun clear(now: Long = clock()): ListenReportEvent? {
        val event = flush(now)
        tracked = null
        trackedQuality = ""
        trackedSourceId = null
        return event
    }

    private fun settle(now: Long) {
        playStartedAt?.let { started ->
            accumulatedMs += (now - started).coerceAtLeast(0L)
            playStartedAt = null
        }
    }
}

internal fun wyListenSourceId(source: QueueSource?): String? {
    if (source == null || source.id.isBlank()) return null
    return when (source.kind) {
        "platform" -> {
            if (source.id.startsWith("wy:")) {
                source.id.removePrefix("wy:").takeUnless { it.startsWith("chart:") }
            } else {
                source.id.takeUnless { ':' in it }
            }
        }
        "album", "artist" -> source.id.removePrefix("wy:").takeIf { source.id.startsWith("wy:") }
        else -> null
    }?.takeIf { it.isNotBlank() }
}
