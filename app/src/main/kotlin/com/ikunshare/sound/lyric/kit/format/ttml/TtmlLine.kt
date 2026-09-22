package com.ikunshare.sound.lyric.kit.format.ttml

import com.ikunshare.sound.lyric.kit.model.ContentCarrier
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineAgent
import com.ikunshare.sound.lyric.kit.model.LineBackground
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.util.TextUtil
import com.ikunshare.sound.lyric.kit.util.XmlElement
import com.ikunshare.sound.lyric.kit.util.XmlText

class ProcessLinesResult(
    val lines: MutableList<Line>,
    val lineMap: Map<String, LineNormal>,
    val backgroundMap: Map<String, LineBackground>,
)

private class LineBase(val time: Time, val agent: LineAgent?)

/** 对应 format-ttml 的 parser/itunes/line.ts。 */
object TtmlLine {

    private fun calcEndSpaceCount(content: String): Int {
        var count = 0
        var i = content.length - 1
        while (i >= 0 && content[i] == ' ') { count++; i-- }
        return count
    }

    private fun calcStartSpaceCount(content: String): Int {
        var count = 0
        var i = 0
        while (i < content.length && content[i] == ' ') { count++; i++ }
        return count
    }

    private fun parseLineAgent(el: XmlElement): LineAgent? {
        val raw = TtmlUtil.getAttributeByName(el, "agent", true) ?: return null
        return Runtime.makeLineAgent(raw)
    }

    private fun resolveLineTime(el: XmlElement, background: Boolean): Time? {
        val beginRaw = TtmlUtil.getAttributeByName(el, "begin", true)
        val endRaw = TtmlUtil.getAttributeByName(el, "end", true)
        val begin = if (beginRaw != null) TextUtil.parseTime(beginRaw) else null
        val end = if (endRaw != null) TextUtil.parseTime(endRaw) else null
        if ((begin == null || end == null) && !background) return null
        return Time(begin ?: 0, end ?: 0)
    }

    private fun resolveWordSpace(content: String, isFirst: Boolean): Word? {
        if (isFirst || content.trim().isNotEmpty()) return null
        val count = if (content.isNotEmpty()) content.length else 1
        return Runtime.makeWordSpace(count)
    }

    private fun appendWordSpan(words: MutableList<Word>, element: XmlElement) {
        val text = TtmlUtil.getTextContent(element)
        val trimed = text.trim()
        if (trimed.isEmpty()) return
        val time = TtmlUtil.parseSpanTime(element) ?: return
        if (text.startsWith(" ") && (words.isEmpty() || words.last() !is com.ikunshare.sound.lyric.kit.model.WordSpace)) {
            words.add(Runtime.makeWordSpace(calcStartSpaceCount(text)))
        }
        words.add(Runtime.makeWordNormal(content = trimed, time = time))
        if (text.endsWith(" ")) words.add(Runtime.makeWordSpace(calcEndSpaceCount(text)))
    }

    fun parseSpanWords(
        element: XmlElement,
        onRole: ((span: XmlElement, role: String) -> Unit)? = null,
        options: ParseSpanOptions? = null,
    ): MutableList<Word> {
        val words = ArrayList<Word>()
        for (i in element.children.indices) {
            val item = element.children[i]
            if (item is XmlText) {
                val space = resolveWordSpace(item.content, i == 0)
                if (space != null) words.add(space)
                continue
            }
            if (item !is XmlElement || item.local != "span") continue
            val role = TtmlUtil.getAttributeByName(item, "role", true)
            if (role != null) {
                onRole?.invoke(item, role)
                continue
            }
            if (options?.onSpan?.invoke(item, words) == true) continue
            appendWordSpan(words, item)
        }
        return words
    }

    private fun applyLineRole(line: ContentCarrier, span: XmlElement, role: String, background: Boolean, options: ParseSpanOptions?) {
        if (role == "x-bg") {
            if (background || line !is LineNormal) return
            val bg = parseBackgroundLine(span, options) ?: return
            line.backgrounds.add(bg)
            options?.onBackground?.invoke(span, bg)
            return
        }
        val text = TtmlUtil.getTextContent(span).trim()
        if (text.isEmpty()) return
        when (role) {
            "x-translation" -> TtmlUtil.appendLineTranslate(line, text, TtmlUtil.getAttributeByName(span, "lang", true))
            "x-roman" -> TtmlUtil.appendLineRoman(line, text, TtmlUtil.getAttributeByName(span, "lang", true))
        }
    }

    private fun fillBodyWords(body: ContentCarrier, element: XmlElement, background: Boolean, options: ParseSpanOptions?) {
        val content = TtmlUtil.ensureContent(body)
        if (!TtmlUtil.hasChildElementByLocalName(element, "span")) {
            content.words = TtmlUtil.parseTextToWords(TtmlUtil.getTextContent(element).trim())
        } else {
            content.words = parseSpanWords(
                element,
                onRole = { span, role -> applyLineRole(body, span, role, background, options) },
                options = options,
            )
        }
    }

    private fun resolveLineBase(el: XmlElement, background: Boolean): LineBase? {
        val time = resolveLineTime(el, background) ?: return null
        if (!TtmlUtil.hasChildElementByLocalName(el, "span") && TtmlUtil.getTextContent(el).trim().isEmpty()) return null
        return LineBase(time, parseLineAgent(el))
    }

    private fun parseBackgroundLine(el: XmlElement, options: ParseSpanOptions?): LineBackground? {
        val base = resolveLineBase(el, true) ?: return null
        val body = Runtime.makeLineBackground(time = Time(base.time.start, base.time.end))
        if (base.agent != null) TtmlUtil.ensureContent(body).agent = base.agent
        fillBodyWords(body, el, true, options)
        return body
    }

    private fun parseNormalLine(el: XmlElement, options: ParseSpanOptions?): LineNormal? {
        val base = resolveLineBase(el, false) ?: return null
        val line = Runtime.makeLineNormal(content = null, time = Time(base.time.start, base.time.end))
        if (base.agent != null) TtmlUtil.ensureContent(line).agent = base.agent
        fillBodyWords(line, el, false, options)
        return line
    }

    fun parseLines(body: XmlElement?, options: ParseSpanOptions?): ProcessLinesResult {
        val lines = ArrayList<Line>()
        val lineMap = LinkedHashMap<String, LineNormal>()
        val backgroundMap = LinkedHashMap<String, LineBackground>()
        if (body == null) return ProcessLinesResult(lines, lineMap, backgroundMap)

        val lineOptions = ParseSpanOptions(
            onSpan = options?.onSpan,
            onBackground = { span, bg ->
                options?.onBackground?.invoke(span, bg)
                val key = TtmlUtil.getAttributeByName(span, "key", true)
                if (key != null) backgroundMap[key] = bg
            },
        )

        for (element in TtmlUtil.findElementsByLocalName(body, "p")) {
            val line = parseNormalLine(element, lineOptions) ?: continue
            lines.add(line)
            val key = TtmlUtil.getAttributeByName(element, "key", true)
            if (key != null) lineMap[key] = line
        }
        return ProcessLinesResult(lines, lineMap, backgroundMap)
    }
}
