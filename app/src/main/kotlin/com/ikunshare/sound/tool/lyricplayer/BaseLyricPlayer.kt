package com.ikunshare.sound.tool.lyricplayer

import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.ParsedLyric
import kotlin.math.abs

data class LyricPlayerOffsetConfig(
    val global: Long = 0L,
    val useMeta: Boolean = true,
    val resetTempOnLyricChange: Boolean = true
)

data class BaseLyricPlayerConfig(
    val bridgeActive: Boolean = true,
    val mergeWindow: Long = 300L,
    val mergeLimit: Int = 3,
    val offset: LyricPlayerOffsetConfig = LyricPlayerOffsetConfig()
)

data class LyricPlayerMatch(
    val lines: List<LyricLine>,
    val indexes: List<Int>
) {
    val firstActiveIndex: Int
        get() = indexes.firstOrNull() ?: -1

    companion object {
        val EMPTY = LyricPlayerMatch(emptyList(), emptyList())
    }
}

private class LyricLineMerger {
    private var lines: List<LyricLine> = emptyList()
    private var mergedEnd: LongArray = LongArray(0)

    fun build(lines: List<LyricLine>, mergeWindow: Long, mergeLimit: Int) {
        this.lines = lines
        val count = lines.size
        val merged = LongArray(count)
        if (count == 0) {
            mergedEnd = merged
            return
        }

        val threshold = mergeWindow.coerceAtLeast(0L)
        val limit = if (mergeLimit > 0) mergeLimit else Int.MAX_VALUE

        var nextRaw = rawEndTime(count - 1)
        merged[count - 1] = nextRaw
        var batchSize = 1

        for (index in count - 2 downTo 0) {
            val raw = rawEndTime(index)
            if (threshold > 0L && batchSize < limit && abs(nextRaw - raw) < threshold) {
                merged[index] = maxOf(raw, merged[index + 1])
                batchSize++
            } else {
                merged[index] = raw
                batchSize = 1
            }
            nextRaw = raw
        }

        mergedEnd = merged
    }

    fun getMergedTime(index: Int): Long {
        return mergedEnd.getOrNull(index) ?: rawEndTime(index)
    }

    private fun rawEndTime(index: Int): Long {
        if (index !in lines.indices) return 0L
        if (index == lines.lastIndex) return Long.MAX_VALUE
        val nextStart = lines[index + 1].timestamp
        return maxOf(lines[index].getEndTime(nextStart), nextStart)
    }
}

private class LyricOffset {
    private var temp: Long = 0L
    private var meta: Long = 0L

    fun setTemp(value: Long) {
        temp = value
    }

    fun resetTemp() {
        temp = 0L
    }

    fun refreshFromMeta(@Suppress("UNUSED_PARAMETER") info: ParsedLyric, useMeta: Boolean) {
        meta = if (useMeta) 0L else 0L
    }

    fun resolve(global: Long): Long = global + meta + temp
}

class BaseLyricPlayer(
    var config: BaseLyricPlayerConfig = BaseLyricPlayerConfig()
) {
    private var info: ParsedLyric = ParsedLyric.EMPTY
    private var activeLines: List<LyricLine> = emptyList()
    private var activeIndexes: List<Int> = emptyList()
    private val merger = LyricLineMerger()
    private val offset = LyricOffset()

    fun updateConfig(config: BaseLyricPlayerConfig) {
        val old = this.config
        this.config = config
        if (old.offset.useMeta != config.offset.useMeta) {
            offset.refreshFromMeta(info, config.offset.useMeta)
        }
        if (old.mergeWindow != config.mergeWindow || old.mergeLimit != config.mergeLimit) {
            rebuildMergedLineEnd()
        }
    }

    fun updateLyric(info: ParsedLyric) {
        this.info = info
        rebuildMergedLineEnd()
        offset.refreshFromMeta(info, config.offset.useMeta)
        if (config.offset.resetTempOnLyricChange) {
            offset.resetTemp()
        }
        activeLines = emptyList()
        activeIndexes = emptyList()
    }

    fun updateTempOffset(value: Long) {
        offset.setTemp(value)
    }

    fun matchLinesWithTime(time: Long): LyricPlayerMatch {
        if (!info.isSynced || info.lines.isEmpty()) return LyricPlayerMatch.EMPTY
        val effective = time + currentOffset
        val lines = mutableListOf<LyricLine>()
        val indexes = mutableListOf<Int>()

        for (index in info.lines.indices) {
            val line = info.lines[index]
            if (line.timestamp > effective) break
            if (merger.getMergedTime(index) > effective) {
                lines.add(line)
                indexes.add(index)
            }
        }

        return bridgeActive(lines, indexes)
    }

    fun syncTime(time: Long): LyricPlayerMatch {
        val match = matchLinesWithTime(time)
        activeLines = match.lines
        activeIndexes = match.indexes
        return match
    }

    fun convertContentTime(contentTime: Long): Long = contentTime - currentOffset

    val currentLines: List<LyricLine>
        get() = bridgeActive(activeLines, activeIndexes).lines

    val currentIndex: List<Int>
        get() = bridgeActive(activeLines, activeIndexes).indexes

    val currentActive: Int
        get() = currentIndex.firstOrNull() ?: -1

    val currentInfo: ParsedLyric
        get() = info

    val currentOffset: Long
        get() = offset.resolve(config.offset.global)

    private fun rebuildMergedLineEnd() {
        merger.build(info.lines, config.mergeWindow, config.mergeLimit)
    }

    private fun bridgeActive(lines: List<LyricLine>, indexes: List<Int>): LyricPlayerMatch {
        if (!config.bridgeActive || indexes.size < 2) {
            return LyricPlayerMatch(lines, indexes)
        }

        val min = indexes.first()
        val max = indexes.last()
        if (max - min + 1 == indexes.size) {
            return LyricPlayerMatch(lines, indexes)
        }

        val existing = indexes.zip(lines).toMap()
        val bridgedLines = mutableListOf<LyricLine>()
        val bridgedIndexes = mutableListOf<Int>()
        for (index in min..max) {
            val line = existing[index] ?: info.lines.getOrNull(index) ?: continue
            bridgedLines.add(line)
            bridgedIndexes.add(index)
        }
        return LyricPlayerMatch(bridgedLines, bridgedIndexes)
    }
}

