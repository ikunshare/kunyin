package com.ikunshare.sound.lyric.kit.format.ttml

import com.ikunshare.sound.lyric.kit.model.ContentCarrier
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordAnnotationContent
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.model.WordSpace
import com.ikunshare.sound.lyric.kit.util.XmlElement

/** 对应 format-ttml 的 parser/itunes/attach.ts。 */
object TtmlAttach {

    fun attachHeadAnnotations(
        groups: Map<String, List<XmlElement>>,
        lineMap: Map<String, LineNormal>,
        backgroundMap: Map<String, com.ikunshare.sound.lyric.kit.model.LineBackground>,
    ) {
        if (lineMap.isEmpty()) return
        attachTranslate(groups["translation"] ?: emptyList(), lineMap, backgroundMap)
        attachRoman(groups["transliteration"] ?: emptyList(), lineMap, backgroundMap)
    }

    private fun eachAnnotationText(
        blocks: List<XmlElement>,
        lineMap: Map<String, LineNormal>,
        handle: (text: XmlElement, line: LineNormal, language: String?, type: String?) -> Unit,
    ) {
        for (block in blocks) {
            val language = TtmlUtil.getAttributeByName(block, "lang", true)
            val type = TtmlUtil.getAttributeByName(block, "type", false)
            for (text in TtmlUtil.findElementsByLocalName(block, "text")) {
                val key = TtmlUtil.getAttributeByName(text, "for", false) ?: continue
                val line = lineMap[key] ?: continue
                handle(text, line, language, type)
            }
        }
    }

    private fun readReplacementWords(text: XmlElement): MutableList<Word> =
        if (TtmlUtil.hasChildElementByLocalName(text, "span")) TtmlLine.parseSpanWords(text)
        else TtmlUtil.parseTextToWords(TtmlUtil.getTextContent(text).trim())

    private data class BackgroundText(val content: String, val key: String?)
    private data class SplitResult(val main: String, val backgrounds: List<BackgroundText>)

    private fun splitBackgroundText(text: XmlElement): SplitResult {
        val backgrounds = ArrayList<BackgroundText>()
        val main = StringBuilder()
        for (child in text.children) {
            if (child is XmlElement && child.local == "span" &&
                TtmlUtil.getAttributeByName(child, "role", true) == "x-bg"
            ) {
                backgrounds.add(
                    BackgroundText(
                        content = TtmlUtil.getTextContent(child),
                        key = TtmlUtil.getAttributeByName(child, "for", false),
                    )
                )
            } else {
                main.append(TtmlUtil.getTextContent(child))
            }
        }
        return SplitResult(main.toString(), backgrounds)
    }

    private fun attachBackgroundTexts(
        line: LineNormal,
        backgrounds: List<BackgroundText>,
        backgroundMap: Map<String, com.ikunshare.sound.lyric.kit.model.LineBackground>,
        attach: (target: ContentCarrier, content: String) -> Unit,
    ) {
        val list = line.backgrounds
        if (list.isEmpty()) return
        for (i in backgrounds.indices) {
            val content = backgrounds[i].content.trim()
            if (content.isEmpty()) continue
            val key = backgrounds[i].key
            val target: ContentCarrier? = (if (key != null) backgroundMap[key] else null) ?: list.getOrNull(i)
            if (target != null) attach(target, content)
        }
    }

    private fun attachTranslate(
        blocks: List<XmlElement>,
        lineMap: Map<String, LineNormal>,
        backgroundMap: Map<String, com.ikunshare.sound.lyric.kit.model.LineBackground>,
    ) {
        eachAnnotationText(blocks, lineMap) { text, line, language, type ->
            if (type == "replacement") {
                val words = readReplacementWords(text)
                if (words.isNotEmpty()) TtmlUtil.ensureContent(line).words = words
                return@eachAnnotationText
            }
            val split = splitBackgroundText(text)
            TtmlUtil.appendLineTranslate(line, split.main, language, true)
            attachBackgroundTexts(line, split.backgrounds, backgroundMap) { bg, content ->
                TtmlUtil.appendLineTranslate(bg, content, language, true)
            }
        }
    }

    private fun attachRoman(
        blocks: List<XmlElement>,
        lineMap: Map<String, LineNormal>,
        backgroundMap: Map<String, com.ikunshare.sound.lyric.kit.model.LineBackground>,
    ) {
        eachAnnotationText(blocks, lineMap) { text, line, language, _ ->
            if (hasTimedSpans(text) && attachWordRomans(text, line, language)) return@eachAnnotationText
            val split = splitBackgroundText(text)
            TtmlUtil.appendLineRoman(line, split.main, language)
            attachBackgroundTexts(line, split.backgrounds, backgroundMap) { bg, content ->
                TtmlUtil.appendLineRoman(bg, content, language)
            }
        }
    }

    private fun hasTimedSpans(text: XmlElement): Boolean =
        text.children.any {
            it is XmlElement && it.local == "span" &&
                TtmlUtil.getAttributeByName(it, "begin", true) != null &&
                TtmlUtil.getAttributeByName(it, "end", true) != null
        }

    private class RomanEntry(
        var content: String,
        val start: Int,
        val end: Int,
        val target: WordNormal,
        val boundary: Boolean,
    )

    private fun attachWordRomans(text: XmlElement, line: LineNormal, language: String?): Boolean {
        val words = line.content?.words ?: emptyList()
        val indexMap = HashMap<WordNormal, Int>()
        val startMap = HashMap<Int, WordNormal>()
        for (i in words.indices) {
            val w = words[i]
            if (w is WordNormal) {
                indexMap[w] = i
                w.time?.let { startMap[it.start] = w }
            }
        }

        val roman = TtmlLine.parseSpanWords(text)
        val entries = ArrayList<RomanEntry>()
        for (i in roman.indices) {
            val r = roman[i]
            if (r !is WordNormal) continue
            val t = r.time ?: continue
            val target = findBodyWordByTime(words, startMap, t.start, t.end) ?: continue
            val next = roman.getOrNull(i + 1)
            entries.add(RomanEntry(content = r.content, start = t.start, end = t.end, target = target, boundary = next is WordSpace))
        }
        if (entries.isEmpty()) return false

        val groups = LinkedHashMap<WordNormal, MutableList<WordAnnotationContent>>()
        // 记录首/末 token 用于边缘 trim
        var headContent: WordAnnotationContent? = null
        var headIndex = Int.MAX_VALUE
        var tailContent: WordAnnotationContent? = null
        var tailIndex = Int.MIN_VALUE

        for (i in entries.indices) {
            val entry = entries[i]
            var content = entry.content
            val nextEntry = entries.getOrNull(i + 1)
            if (entry.boundary && nextEntry != null) {
                val from = indexMap[entry.target]
                val to = indexMap[nextEntry.target]
                val bodySpaced = from != null && to != null && hasWordSpaceBetween(words, from, to)
                if (!bodySpaced) content += " "
            }
            val token = Runtime.makeWordAnnotationContent(content = content, time = Time(entry.start, entry.end))
            groups.getOrPut(entry.target) { mutableListOf() }.add(token)

            val idx = indexMap[entry.target] ?: 0
            if (idx < headIndex) { headIndex = idx; headContent = token }
            if (idx >= tailIndex) { tailIndex = idx; tailContent = token }
        }

        headContent?.let { it.content = it.content.trimStart() }
        tailContent?.let { it.content = it.content.trimEnd() }

        val lang = TtmlUtil.normalizeLanguage(language)
        for ((word, tokens) in groups) {
            val item = Runtime.makeWordAnnotationRoman(
                words = tokens,
                time = Time(tokens.first().time?.start ?: 0, tokens.last().time?.end ?: 0),
            )
            if (lang != null) item.language = lang
            val annotation = word.annotation ?: Runtime.makeWordAnnotation().also { word.annotation = it }
            annotation.romans.add(item)
        }
        return true
    }

    private fun findBodyWordByTime(words: List<Word>, startMap: Map<Int, WordNormal>, start: Int, end: Int): WordNormal? {
        startMap[start]?.let { return it }
        var best: WordNormal? = null
        var bestOverlap = 0
        for (w in words) {
            if (w !is WordNormal) continue
            val t = w.time ?: continue
            val overlap = minOf(end, t.end) - maxOf(start, t.start)
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = w
            }
        }
        return best
    }

    private fun hasWordSpaceBetween(words: List<Word>, a: Int, b: Int): Boolean {
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        for (i in (lo + 1) until hi) {
            if (words[i] is WordSpace) return true
        }
        return false
    }
}
