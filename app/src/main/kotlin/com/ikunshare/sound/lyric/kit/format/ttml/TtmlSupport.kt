package com.ikunshare.sound.lyric.kit.format.ttml

import com.ikunshare.sound.lyric.kit.model.AgentType
import com.ikunshare.sound.lyric.kit.model.ContentCarrier
import com.ikunshare.sound.lyric.kit.model.AgentItem
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineAnnotation
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.Meta
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.TextUtil
import com.ikunshare.sound.lyric.kit.util.XmlCdata
import com.ikunshare.sound.lyric.kit.util.XmlElement
import com.ikunshare.sound.lyric.kit.util.XmlNode
import com.ikunshare.sound.lyric.kit.util.XmlText

/** span 时间范围。 */
class ParseSpanOptions(
    val onSpan: ((span: XmlElement, words: MutableList<Word>) -> Boolean)? = null,
    val onBackground: ((span: XmlElement, background: com.ikunshare.sound.lyric.kit.model.LineBackground) -> Unit)? = null,
)

/** 对应 format-ttml 的 utils/xml.ts 与 utils/index.ts。 */
object TtmlUtil {

    fun getTextContent(node: XmlNode): String = when (node) {
        is XmlElement -> {
            val sb = StringBuilder()
            for (c in node.children) sb.append(getTextContent(c))
            sb.toString()
        }
        is XmlText -> node.content
        is XmlCdata -> node.content
        else -> ""
    }

    /** 迭代 DFS（文档顺序），返回目标 localName → 元素列表。 */
    fun collectElementsByLocalName(root: XmlElement, names: List<String>): Map<String, List<XmlElement>> {
        val want = names.toHashSet()
        val result = HashMap<String, MutableList<XmlElement>>()
        val stack = ArrayDeque<XmlElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            if (el.local in want) result.getOrPut(el.local) { mutableListOf() }.add(el)
            for (i in el.children.indices.reversed()) {
                val c = el.children[i]
                if (c is XmlElement) stack.addLast(c)
            }
        }
        return result
    }

    fun findElementsByLocalName(root: XmlElement, local: String, first: Boolean = false): List<XmlElement> {
        val result = ArrayList<XmlElement>()
        val stack = ArrayDeque<XmlElement>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val el = stack.removeLast()
            if (el.local == local) {
                result.add(el)
                if (first) return result
            }
            for (i in el.children.indices.reversed()) {
                val c = el.children[i]
                if (c is XmlElement) stack.addLast(c)
            }
        }
        return result
    }

    fun getChildElementsByLocalName(el: XmlElement, name: String): List<XmlElement> =
        el.children.filterIsInstance<XmlElement>().filter { it.local == name }

    fun hasChildElementByLocalName(el: XmlElement, name: String): Boolean =
        el.children.any { it is XmlElement && it.local == name }

    fun getAttributeByName(el: XmlElement, name: String, local: Boolean = false): String? {
        val attr = if (local) el.attributes.firstOrNull { it.local == name } else el.attributes.firstOrNull { it.name == name }
        return attr?.value
    }

    fun parseSpanTime(el: XmlElement): Time? {
        val beginRaw = getAttributeByName(el, "begin", true)
        val endRaw = getAttributeByName(el, "end", true)
        if (beginRaw == null || endRaw == null) return null
        val begin = TextUtil.parseTime(beginRaw)
        val end = TextUtil.parseTime(endRaw)
        if (begin == null || end == null) return null
        return Time(begin, end)
    }

    fun ensureContent(carrier: ContentCarrier): LineContent =
        carrier.content ?: Runtime.makeLineContent().also { carrier.content = it }

    fun ensureAnnotation(carrier: ContentCarrier): LineAnnotation {
        val content = ensureContent(carrier)
        return content.annotation ?: Runtime.makeLineAnnotation().also { content.annotation = it }
    }

    /** 首行是否含正时间的 normal 词，用于判断 word/line timing。 */
    fun hasWordTiming(line: Line?): Boolean {
        if (line == null || !Runtime.isLineNormal(line)) return false
        val words = Runtime.getLineWords(line)
        return words.any { it is WordNormal && (it.time?.start ?: 0) > 0 }
    }

    /** 纯文本行分词，与 LRC 的 processTextToWords 一致。 */
    fun parseTextToWords(text: String): MutableList<Word> {
        val normalized = TextUtil.removeTextSpaceToOne(text).trim()
        val words = ArrayList<Word>()
        var last = 0
        val re = Regex("\\s+")
        val tokens = ArrayList<String>()
        for (m in re.findAll(normalized)) {
            tokens.add(normalized.substring(last, m.range.first))
            tokens.add(m.value)
            last = m.range.last + 1
        }
        tokens.add(normalized.substring(last))
        for (token in tokens) {
            if (token.trim().isEmpty()) words.add(Runtime.makeWordSpace(1))
            else words.add(Runtime.makeWordNormal(content = token.trim()))
        }
        return words
    }

    // region 语言归一化 & 注解追加

    fun normalizeLanguage(lang: String?): String? {
        val l = lang?.lowercase()?.trim()
        if (l.isNullOrEmpty()) return null
        if (l.startsWith("zh")) {
            if (l.contains("hant")) return "zh-hant"
            if (l.contains("hans")) return "zh-hans"
        }
        return l
    }

    fun appendLineTranslate(line: ContentCarrier, content: String, language: String?, fromItunes: Boolean = false) {
        val text = content.trim()
        if (text.isEmpty()) return
        val lang = normalizeLanguage(language)
        val item = Runtime.makeLineAnnotationTranslate(content = text, language = lang)
        val annotation = ensureAnnotation(line)
        val list = annotation.translates
        val index = list.indexOfFirst { normalizeLanguage(it.language) == lang }
        if (index < 0) list.add(item)
        else if (fromItunes) list[index] = item
    }

    fun appendLineRoman(line: ContentCarrier, content: String, language: String?) {
        val text = content.trim()
        if (text.isEmpty()) return
        val lang = normalizeLanguage(language)
        ensureAnnotation(line).romans.add(Runtime.makeLineAnnotationRoman(content = text, language = lang))
    }

    // endregion

    // region meta / agent

    fun applyItunesMetas(meta: Meta, songwriters: List<XmlElement>) {
        val names = ArrayList<String>()
        for (el in songwriters) {
            val text = getTextContent(el).trim()
            if (text.isNotEmpty()) names.add(text)
        }
        if (names.isEmpty()) return
        meta.credits.add(
            Runtime.makeMetaCredit(
                role = "songWriter",
                names = names.map { Runtime.makeMetaText(it) }.toMutableList(),
            )
        )
    }

    private fun resolveAgentType(type: String?): AgentType = when (type) {
        "person" -> AgentType.PERSON
        "group" -> AgentType.GROUP
        "other" -> AgentType.OTHER
        else -> AgentType.UNKNOWN
    }

    private fun parseAgentNames(el: XmlElement): MutableList<String> {
        val names = ArrayList<String>()
        for (child in getChildElementsByLocalName(el, "name")) {
            val text = getTextContent(child).trim()
            if (text.isNotEmpty()) names.add(text)
        }
        return names
    }

    private fun parseAgent(el: XmlElement): AgentItem? {
        val type = getAttributeByName(el, "type", true)
        val id = getAttributeByName(el, "id", true)
        if (type == null || id == null) return null
        return Runtime.makeAgentItem(id = id, type = resolveAgentType(type), names = parseAgentNames(el))
    }

    fun parseAgents(agents: List<XmlElement>): MutableList<AgentItem> {
        val result = ArrayList<AgentItem>()
        for (el in agents) parseAgent(el)?.let { result.add(it) }
        return result
    }

    // endregion
}
