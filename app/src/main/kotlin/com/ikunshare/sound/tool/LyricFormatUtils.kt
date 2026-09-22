package com.ikunshare.sound.tool

import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.model.Lyric
import kotlin.math.abs

object LyricFormatUtils {
    private val META_LINE = Regex("""^\[[a-zA-Z]+:.*]$""")
    private val FIRST_TIME = Regex("""^\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?]""")
    private val ANY_TIME = Regex("""[<\[](\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?[>\]]""")

    private data class TimedLine(val timestamp: Long, val raw: String)
    private data class Parsed(val meta: List<String>, val lines: List<TimedLine>)

    fun formatLyricsForExport(lyric: Lyric, settings: AppSettings): String {
        val useCharLyrics = settings.downloadCharLyrics && lyric.char.isNotBlank()
        val useCharRoma = settings.downloadCharRomanization && lyric.chroma.isNotBlank()

        val mainSource = if (useCharLyrics) toEnhancedLrc(lyric.char)
        else if (lyric.lrc.isNotBlank()) lyric.lrc
        else return ""

        val transSource = if (settings.downloadTranslation) lyric.trans else ""
        val romaSource = if (useCharRoma) toEnhancedLrc(lyric.chroma)
        else if (settings.downloadRomanization) lyric.roma
        else ""

        val main = parse(mainSource)
        val trans = parse(transSource)
        val roma = parse(romaSource)

        // 输出格式：增强 LRC（逐字）用 2 位精度，否则用主词原始精度
        val twoDigit = useCharLyrics || detectTwoDigit(main.lines)

        val out = mutableListOf<String>()
        // 元数据：主词 → 翻译 → 音译，按出现顺序去重
        (main.meta + trans.meta + roma.meta).distinct().forEach { out += it }

        val sortedMain = main.lines.sortedBy { it.timestamp }
        for ((i, entry) in sortedMain.withIndex()) {
            out += entry.raw
            findText(trans.lines, entry.timestamp)?.let {
                out += formatTimestamp(entry.timestamp, twoDigit) + it
            }
            findText(roma.lines, entry.timestamp)?.let {
                out += formatTimestamp(entry.timestamp, twoDigit) + it
            }

            if (settings.downloadLrcInterludes && i < sortedMain.size - 1) {
                val curEnd = lineEnd(entry.raw) ?: entry.timestamp
                val next = sortedMain[i + 1].timestamp
                if (next - curEnd > 5000) {
                    out += formatTimestamp(curEnd, twoDigit)
                }
            }
        }

        return out.joinToString("\n")
    }

    private fun toEnhancedLrc(lrc: String): String =
        convertLastBracketToAngle(convertTo10msPrecision(lrc))

    private fun convertTo10msPrecision(lrc: String): String {
        val regex = Regex("""(\d{2}:\d{2})\.(\d{3})""")
        return regex.replace(lrc) { match ->
            val prefix = match.groupValues[1]
            val ms3 = match.groupValues[2].toInt()
            val ms2 = (ms3 + 5) / 10
            val clamped = ms2.coerceAtMost(99)
            "$prefix.${clamped.toString().padStart(2, '0')}"
        }
    }

    private fun convertLastBracketToAngle(lrc: String): String {
        val lineRegex = Regex("""\[(\d{2}:\d{2}\.\d{2,3})]$""")
        return lrc.lines().joinToString("\n") { line ->
            lineRegex.replace(line) { "<${it.groupValues[1]}>" }
        }
    }

    private fun parse(lrc: String): Parsed {
        if (lrc.isBlank()) return Parsed(emptyList(), emptyList())
        val meta = mutableListOf<String>()
        val timed = mutableListOf<TimedLine>()
        for (raw in lrc.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (META_LINE.matches(line)) {
                meta += line
                continue
            }
            val ts = parseLineTime(line) ?: continue
            timed += TimedLine(ts, line)
        }
        return Parsed(meta, timed)
    }

    private fun parseLineTime(line: String): Long? {
        val m = FIRST_TIME.find(line) ?: return null
        if (m.range.first != 0) return null
        return parseTime(m.groupValues[1], m.groupValues[2], m.groupValues[3])
    }

    private fun parseTime(minStr: String, secStr: String, msStr: String): Long {
        val min = minStr.toLongOrNull() ?: 0L
        val sec = secStr.toLongOrNull() ?: 0L
        val ms = when (msStr.length) {
            2 -> (msStr.toLongOrNull() ?: 0L) * 10
            3 -> msStr.toLongOrNull() ?: 0L
            0 -> 0L
            else -> msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        }
        return min * 60000 + sec * 1000 + ms
    }

    private fun lineEnd(line: String): Long? =
        ANY_TIME.findAll(line).map {
            parseTime(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }.toList().maxOrNull()

    private fun stripLeadingTags(line: String): String {
        var s = line
        while (true) {
            val m = FIRST_TIME.find(s) ?: break
            if (m.range.first != 0) break
            s = s.substring(m.range.last + 1)
        }
        return s.trim()
    }

    private fun findText(lines: List<TimedLine>, ts: Long): String? {
        if (lines.isEmpty()) return null
        val tol = 100L
        val match = lines.firstOrNull { it.timestamp == ts }
            ?: lines.firstOrNull { abs(it.timestamp - ts) <= tol }
            ?: return null
        val text = stripLeadingTags(match.raw)
        return text.takeIf { it.isNotBlank() }
    }

    private fun detectTwoDigit(lines: List<TimedLine>): Boolean {
        val first = lines.firstOrNull()?.raw ?: return false
        val m = FIRST_TIME.find(first) ?: return false
        return m.groupValues[3].length == 2
    }

    private fun formatTimestamp(ms: Long, twoDigit: Boolean): String {
        val min = ms / 60000
        val sec = (ms % 60000) / 1000
        return if (twoDigit) {
            val frac = ((ms % 1000) + 5) / 10
            "[%02d:%02d.%02d]".format(min, sec, frac.coerceAtMost(99))
        } else {
            val frac = ms % 1000
            "[%02d:%02d.%03d]".format(min, sec, frac)
        }
    }
}
