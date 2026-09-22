package com.ikunshare.sound.lyric.kit.format.lrc

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.InfoType
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Meta
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Timing
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.model.WordSpace
import com.ikunshare.sound.lyric.kit.util.NumberAlign
import com.ikunshare.sound.lyric.kit.util.TextUtil

/** LRC 解析入参。 */
data class LrcParserInput(
    val original: String,
    val translate: String? = null,
    val roman: String? = null,
)

/** matchLyric 的中间结果项。 */
private data class MatchItem(val raw: String, val tag: String, val content: String)
private data class MatchInfo(val meta: List<MatchItem>, val line: List<MatchItem>)

/**
 * LRC 解析插件，对应 @music-lyric-kit/plugin-format-lrc 的 Parser。
 */
class LrcParser : ParserPlugin() {
    override val id: String get() = "LRC-PARSER"
    override val stage: PluginStage get() = PluginStage.Process
    override val format: String get() = "lrc"

    private val checkRegexp = Regex("\\[(?:\\d+:)?(?:\\d+:)?\\d+\\.\\d+\\]")

    private fun processInput(content: Any?): LrcParserInput? = when (content) {
        is String -> LrcParserInput(content)
        is LrcParserInput -> content
        else -> null
    }

    override fun check(ctx: ParserContext): Boolean {
        val input = processInput(ctx.params.content) ?: return false
        if (input.original.isEmpty()) return false
        return checkRegexp.containsMatchIn(input.original)
    }

    override fun exec(ctx: ParserContext) {
        val input = processInput(ctx.params.content) ?: return
        val match = matchLyric(input.original)
        if (match.line.isEmpty()) return
        val lines = processLines(match.line, false)
        if (lines.isEmpty()) {
            ctx.result.type = InfoType.VALID
            return
        }
        processExtended(lines, input.translate ?: "", input.roman ?: "")
        ctx.result.lines = lines.toMutableList()
        ctx.result.type = InfoType.VALID
        ctx.result.timing = if (checkIsSyllable(match.line)) Timing.WORD else Timing.LINE
        ctx.result.meta = processMetas(match.meta)
    }

    private fun processExtended(lines: List<Line>, inputTranslate: String, inputRoman: String) {
        val lineMap = LinkedHashMap<Int, LineNormal>()
        for (line in lines) {
            if (line !is LineNormal) continue
            val time = line.time ?: continue
            lineMap[time.start] = line
        }

        val translateMap = LinkedHashMap<Int, MutableList<String>>()
        for (item in processLines(matchLyric(inputTranslate).line, true)) {
            if (item !is LineNormal) continue
            val time = item.time ?: continue
            translateMap.getOrPut(time.start) { mutableListOf() }.add(Runtime.getLineText(item))
        }

        val romanMap = LinkedHashMap<Int, MutableList<String>>()
        for (item in processLines(matchLyric(inputRoman).line, true)) {
            if (item !is LineNormal) continue
            val time = item.time ?: continue
            romanMap.getOrPut(time.start) { mutableListOf() }.add(Runtime.getLineText(item))
        }

        if (translateMap.isEmpty() && romanMap.isEmpty()) return

        val targets = LinkedHashSet<Int>().apply {
            addAll(translateMap.keys)
            addAll(romanMap.keys)
        }.toList()

        val aligned = NumberAlign.alignNumberArray(lineMap.keys.toList(), targets)
        for (result in aligned) {
            val line = lineMap[result.base] ?: continue
            val translates = ArrayList<String>()
            val romans = ArrayList<String>()
            for (target in result.targets) {
                translateMap[target.value]?.let { translates.addAll(it) }
                romanMap[target.value]?.let { romans.addAll(it) }
            }
            if (translates.isEmpty() && romans.isEmpty()) continue
            val content = line.content ?: Runtime.makeLineContent().also { line.content = it }
            val annotation = content.annotation ?: Runtime.makeLineAnnotation().also { content.annotation = it }
            for (t in translates) annotation.translates.add(Runtime.makeLineAnnotationTranslate(content = t))
            for (r in romans) annotation.romans.add(Runtime.makeLineAnnotationRoman(content = r))
        }
    }

    // region match

    private val lineRegexp = Regex(
        "(\\[(?:[a-zA-Z]+\\s*:\\s*[^\\]]+|(?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?)\\])([\\s\\S]*?)(?=(?:\\[(?:[a-zA-Z]+\\s*:\\s*[^\\]]+|(?:\\d+:)?\\d+:\\d+(?:\\.\\d+)?)\\])|$)"
    )
    private val metaRegex = Regex("^\\[[a-zA-Z]+:[^\\]]+\\]$")
    private val lineRegex = Regex("^\\[(\\d+:)?\\d+:\\d+(\\.\\d+)?\\].+$")

    private fun checkIsValidMeta(content: String): Boolean = metaRegex.matches(TextUtil.removeTextSpaceAll(content))
    private fun checkIsValidLine(content: String): Boolean = lineRegex.matches(TextUtil.removeTextSpaceAll(content))

    private fun matchLine(content: String): List<MatchItem> {
        val result = ArrayList<MatchItem>()
        for (m in lineRegexp.findAll(content)) {
            val raw = m.value
            val tag = m.groupValues.getOrElse(1) { "" }.trim()
            val text = m.groupValues.getOrElse(2) { "" }.trim()
            if (tag.isEmpty()) continue
            result.add(MatchItem(raw, tag, text))
        }
        return result
    }

    private fun matchLyric(content: String?): MatchInfo {
        if (content == null || !TextUtil.checkTextIsValid(content)) return MatchInfo(emptyList(), emptyList())
        val parsed = ArrayList<MatchItem>()
        for (line in content.split("\n")) {
            if (line.isBlank()) continue
            parsed.addAll(matchLine(line))
        }
        val meta = ArrayList<MatchItem>()
        val lineItems = ArrayList<MatchItem>()
        for (item in parsed) {
            if (checkIsValidMeta(item.raw)) meta.add(item)
            else if (checkIsValidLine(item.raw)) lineItems.add(item)
        }
        return MatchInfo(meta, lineItems)
    }

    // endregion

    // region time

    private val tagContentRegexp = Regex("^[<\\[]([^>\\]]+)[>\\]]$")

    private fun parseTagTime(tag: String): Int? {
        val trimmed = tag.trim()
        val m = tagContentRegexp.matchEntire(trimmed) ?: return null
        val inner = m.groupValues[1].trim()
        if (inner.isEmpty()) return null
        return TextUtil.parseTime(inner)
    }

    // endregion

    // region line / word

    private val timeContentRegexp = Regex("(<[^>]+>)([^<]*)")
    private val timeTag2 = Regex("<([0-9]+),([0-9]+)>")
    private val syllableCheckRegexp = Regex("<[^>]+>")
    private val coreCharRegexp = Regex("[\\p{L}\\p{N}]")
    private val numberConnectors = setOf(':', '.', ',', '/', '-')

    private fun hasCoreChar(t: String): Boolean = coreCharRegexp.containsMatchIn(t)
    private fun isCoreChar(c: Char): Boolean = coreCharRegexp.matches(c.toString())
    private fun isDigitChar(c: Char?): Boolean = c != null && c in '0'..'9'

    private fun getTrailingPunct(text: String): String {
        var i = text.length
        while (i > 0) {
            val c = text[i - 1]
            if (c == ' ' || isCoreChar(c)) break
            i--
        }
        return text.substring(i)
    }

    private fun getLeadingPunct(text: String): String {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == ' ' || isCoreChar(c)) break
            i++
        }
        return text.substring(0, i)
    }

    private fun checkIsSyllable(content: List<MatchItem>): Boolean =
        content.any { syllableCheckRegexp.containsMatchIn(it.content) }

    private fun processLines(content: List<MatchItem>, forceNormal: Boolean): List<Line> {
        if (content.isEmpty()) return emptyList()
        return if (!forceNormal && checkIsSyllable(content)) processSyllable(content) else processNormal(content)
    }

    private fun processTextToWords(text: String): MutableList<Word> {
        val normalized = TextUtil.removeTextSpaceToOne(text).trim()
        val words = ArrayList<Word>()
        for (token in splitCapture(normalized, Regex("\\s+"))) {
            if (token.trim().isEmpty()) words.add(Runtime.makeWordSpace(1))
            else words.add(Runtime.makeWordNormal(content = token.trim()))
        }
        return words
    }

    /** 模拟 JS `str.split(/(\s+)/)`：保留被捕获的分隔符与两侧片段（含空串）。 */
    private fun splitCapture(s: String, re: Regex): List<String> {
        val result = ArrayList<String>()
        var last = 0
        for (m in re.findAll(s)) {
            result.add(s.substring(last, m.range.first))
            result.add(m.value)
            last = m.range.last + 1
        }
        result.add(s.substring(last))
        return result
    }

    private fun processNormal(lines: List<MatchItem>): List<Line> {
        val result = ArrayList<Line>()
        for (line in lines) {
            val time = parseTagTime(line.tag) ?: 0
            val text = line.content.trim()
            val content = LineContent(words = processTextToWords(text))
            result.add(Runtime.makeLineNormal(content = content, time = Time(start = time)))
        }
        for (i in 0 until result.size - 1) {
            val current = result[i].time ?: continue
            val next = result[i + 1].time ?: continue
            current.end = next.start
        }
        return result
    }

    private fun processSyllable(lines: List<MatchItem>): List<Line> {
        val result = ArrayList<Line>()
        for (line in lines) {
            val parsed = processSyllableLine(line) ?: continue
            result.add(parsed)
        }
        return result
    }

    private fun processSyllableLine(line: MatchItem): Line? {
        val lineTime = parseTagTime(line.tag) ?: return null
        val words = ArrayList<Word>()
        for (m in timeContentRegexp.findAll(line.content)) {
            val timeTag = m.groupValues[1]
            var time = parseTagTime(timeTag)
            var duration = 0
            if (time == null) {
                val m2 = timeTag2.find(timeTag)
                if (m2 != null) {
                    time = lineTime + (m2.groupValues[1].toIntOrNull() ?: 0)
                    duration = m2.groupValues[2].toIntOrNull() ?: 0
                }
            }
            if (time == null) continue
            val text = m.groupValues[2]
            if (text.isEmpty()) continue
            val trimed = text.trim()
            if (trimed.isEmpty() || text.startsWith(" ")) {
                if (words.size < 1) continue
                else if (words.last() !is WordSpace) words.add(Runtime.makeWordSpace(1))
            }
            if (trimed.isEmpty()) continue
            words.add(
                Runtime.makeWordNormal(
                    content = TextUtil.removeTextSpaceToOne(text),
                    time = Time(start = time, end = time + duration),
                )
            )
            if (text.endsWith(" ")) words.add(Runtime.makeWordSpace(1))
        }
        val normalized = normalizeBoundaryPunct(words)
        var first: WordNormal? = null
        var last: WordNormal? = null
        for (w in normalized) {
            if (w is WordNormal) {
                if (first == null) first = w
                last = w
            }
        }
        val start = first?.time?.start ?: lineTime
        val end = last?.time?.end ?: start
        val content = LineContent(words = normalized)
        return Runtime.makeLineNormal(content = content, time = Time(start, end))
    }

    private fun normalizeBoundaryPunct(words: List<Word>): MutableList<Word> {
        val result = ArrayList<Word>()
        var index = 0
        while (index < words.size) {
            val word = words[index]
            val prev = result.lastOrNull()
            if (word !is WordNormal || prev !is WordNormal) {
                result.add(word); index++; continue
            }
            val prevValue = prev
            val wordValue = word

            val isNumberConnector = wordValue.content.isNotEmpty() && wordValue.content.all { numberConnectors.contains(it) }
            if (isNumberConnector) {
                val next = words.getOrNull(index + 1)
                if (next is WordNormal &&
                    isDigitChar(prevValue.content.lastOrNull()) &&
                    isDigitChar(next.content.firstOrNull())
                ) {
                    prevValue.content = prevValue.content + wordValue.content + next.content
                    val pt = prevValue.time
                    val nt = next.time
                    if (pt != null && nt != null) pt.end = nt.end
                    index += 2
                    continue
                } else {
                    result.add(word); index++; continue
                }
            }

            if (!(hasCoreChar(prevValue.content) && hasCoreChar(wordValue.content))) {
                result.add(word); index++; continue
            }

            val trailing = getTrailingPunct(prevValue.content)
            val leading = getLeadingPunct(wordValue.content)
            if (trailing.isEmpty() && leading.isEmpty()) {
                result.add(word); index++; continue
            }

            val leftCore = prevValue.content.substring(0, prevValue.content.length - trailing.length)
            val rightCore = wordValue.content.substring(leading.length)
            val leftFlank = leftCore.lastOrNull()
            val rightFlank = rightCore.firstOrNull()
            val punct = trailing + leading

            val isNumberJoin = isDigitChar(leftFlank) && isDigitChar(rightFlank) &&
                punct.isNotEmpty() && punct.all { numberConnectors.contains(it) }
            if (isNumberJoin) {
                prevValue.content = prevValue.content + wordValue.content
                val pt = prevValue.time
                val wt = wordValue.time
                if (pt != null && wt != null) pt.end = wt.end
                index++
                continue
            }

            prevValue.content = leftCore
            val punctStart = if (trailing.isNotEmpty()) prevValue.time?.start else wordValue.time?.start
            val punctEnd = if (leading.isNotEmpty()) wordValue.time?.end else prevValue.time?.end
            val punctTime = if (punctStart != null && punctEnd != null) Time(punctStart, punctEnd) else null
            result.add(Runtime.makeWordNormal(content = punct, time = punctTime))
            wordValue.content = rightCore
            result.add(wordValue)
            index++
        }
        return result
    }

    // endregion

    // region meta

    private val lyricMetaRegexp = Regex("^\\s*\\[\\s*([A-Za-z0-9_-]+)\\s*:\\s*([^\\]]*)\\s*\\]\\s*$")

    private fun processMetas(metas: List<MatchItem>): Meta {
        val result = Runtime.makeMeta()
        for (meta in metas) {
            if (meta.tag.isEmpty()) continue
            val m = lyricMetaRegexp.matchEntire(meta.tag) ?: continue
            val rawKey = m.groupValues[1].trim()
            val key = rawKey.lowercase()
            val value = m.groupValues[2].trim()
            if (key.isEmpty() || value.isEmpty()) continue
            applyMeta(result, key, rawKey, value)
        }
        return result
    }

    private fun applyMeta(meta: Meta, key: String, rawKey: String, content: String) {
        when (key) {
            "offset" -> meta.offset = content.toIntOrNull() ?: 0
            "length", "duration" -> meta.duration = TextUtil.parseTime(content) ?: 0
            "ti", "title" -> meta.titles.add(Runtime.makeMetaText(content))
            "ar", "artist" -> meta.artists.add(Runtime.makeMetaText(content))
            "al", "album" -> meta.albums.add(Runtime.makeMetaText(content))
            "by" -> meta.authors.add(Runtime.makeMetaText(content))
            "isrc" -> meta.isrcs.add(content)
            else -> meta.unknowns.add(Runtime.makeMetaUnknown(key = rawKey, value = content))
        }
    }

    // endregion
}
