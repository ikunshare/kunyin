package com.ikunshare.sound.lyric.kit.format.ttml

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.AgentItem
import com.ikunshare.sound.lyric.kit.model.InfoType
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.Meta
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Timing
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordAnnotationContent
import com.ikunshare.sound.lyric.kit.util.XmlElement
import com.ikunshare.sound.lyric.kit.util.XmlParser

private class TtmlDocument(
    val lines: MutableList<Line>,
    val meta: Meta,
    val agents: MutableList<AgentItem>,
    val timing: Timing,
    val groups: Map<String, List<XmlElement>>,
)

private val HEAD_BLOCKS = listOf("songwriter", "agent", "translation", "transliteration", "meta")

private fun parseDocument(root: XmlElement, options: ParseSpanOptions?): TtmlDocument {
    val body = TtmlUtil.findElementsByLocalName(root, "body", true).firstOrNull()
    val metadata = TtmlUtil.findElementsByLocalName(root, "metadata", true).firstOrNull()

    val parsed = TtmlLine.parseLines(body, options)
    val groups = if (metadata != null) TtmlUtil.collectElementsByLocalName(metadata, HEAD_BLOCKS) else emptyMap()

    TtmlAttach.attachHeadAnnotations(groups, parsed.lineMap, parsed.backgroundMap)

    val meta = Runtime.makeMeta()
    TtmlUtil.applyItunesMetas(meta, groups["songwriter"] ?: emptyList())

    val timing = if (TtmlUtil.hasWordTiming(parsed.lines.getOrNull(0))) Timing.WORD else Timing.LINE
    return TtmlDocument(
        lines = parsed.lines,
        meta = meta,
        agents = TtmlUtil.parseAgents(groups["agent"] ?: emptyList()),
        timing = timing,
        groups = groups,
    )
}

// region ruby（AMLL 专用）

private fun parseRubyItems(container: XmlElement): MutableList<WordAnnotationContent> {
    val items = ArrayList<WordAnnotationContent>()
    for (span in TtmlUtil.getChildElementsByLocalName(container, "span")) {
        if (TtmlUtil.getAttributeByName(span, "ruby", true) != "text") continue
        val content = TtmlUtil.getTextContent(span).trim()
        if (content.isEmpty()) continue
        val time = TtmlUtil.parseSpanTime(span) ?: continue
        items.add(Runtime.makeWordAnnotationContent(content = content, time = time))
    }
    return items
}

private fun interceptRubySpan(span: XmlElement, words: MutableList<Word>): Boolean {
    if (TtmlUtil.getAttributeByName(span, "ruby", true) != "container") return false
    var base = ""
    var items: MutableList<WordAnnotationContent> = ArrayList()
    for (child in TtmlUtil.getChildElementsByLocalName(span, "span")) {
        when (TtmlUtil.getAttributeByName(child, "ruby", true)) {
            "base" -> base = TtmlUtil.getTextContent(child).trim()
            "textContainer" -> items = parseRubyItems(child)
        }
    }
    if (base.isEmpty() || items.isEmpty()) return true
    val start = items.first().time?.start ?: 0
    val end = items.last().time?.end ?: 0
    val phraseStart = TtmlUtil.getAttributeByName(span, "rubyPhraseStart", true) == "true"
    val ruby = Runtime.makeWordAnnotationRuby(words = items, time = Time(start, end), phraseStart = phraseStart)
    words.add(
        Runtime.makeWordNormal(
            content = base,
            time = Time(start, end),
            annotation = Runtime.makeWordAnnotation(ruby = ruby),
        )
    )
    return true
}

// endregion

// region AMLL meta

private fun applyAmllMeta(meta: Meta, element: XmlElement) {
    val key = TtmlUtil.getAttributeByName(element, "key", true) ?: return
    val value = TtmlUtil.getAttributeByName(element, "value", true) ?: return
    val text = value.trim()
    when (key) {
        "musicName" -> meta.titles.add(Runtime.makeMetaText(text))
        "artists" -> meta.artists.add(Runtime.makeMetaText(text))
        "album" -> meta.albums.add(Runtime.makeMetaText(text))
        "isrc" -> meta.isrcs.add(text)
        "ttmlAuthorGithubLogin" -> meta.authors.add(Runtime.makeMetaText(text))
        else -> meta.unknowns.add(Runtime.makeMetaUnknown(key = key, value = text))
    }
}

private fun applyAmllMetas(meta: Meta, elements: List<XmlElement>) {
    for (el in elements) applyAmllMeta(meta, el)
}

// endregion

/** iTunes 方言 TTML 解析插件。 */
class TtmlItunesParser : ParserPlugin() {
    override val id: String get() = "TTML-ITUNES-PARSER"
    override val stage: PluginStage get() = PluginStage.Process
    override val format: String get() = "ttml-itunes"

    private val checkRegexp = Regex("xmlns:itunes|iTunesMetadata|itunes:timing=", RegexOption.IGNORE_CASE)
    private val amllHint = Regex("xmlns:amll|amll:meta", RegexOption.IGNORE_CASE)

    override fun check(ctx: ParserContext): Boolean {
        val input = ctx.params.content as? String ?: return false
        return checkRegexp.containsMatchIn(input) && !amllHint.containsMatchIn(input)
    }

    override fun exec(ctx: ParserContext) {
        val input = ctx.params.content as? String ?: return
        val root = XmlParser().parse(input) ?: return
        val doc = parseDocument(root, null)
        ctx.result.type = InfoType.VALID
        ctx.result.timing = doc.timing
        ctx.result.lines = doc.lines
        ctx.result.meta = doc.meta
        ctx.result.agents = doc.agents
    }
}

/** AMLL（Apple-Music-Like-Lyrics）方言 TTML 解析插件。 */
class TtmlAmllParser : ParserPlugin() {
    override val id: String get() = "TTML-AMLL-PARSER"
    override val stage: PluginStage get() = PluginStage.Process
    override val format: String get() = "ttml-amll"

    private val checkRegexp = Regex("xmlns:amll=[\"'][^\"']+[\"']|amll:meta", RegexOption.IGNORE_CASE)

    override fun check(ctx: ParserContext): Boolean {
        val input = ctx.params.content as? String ?: return false
        return checkRegexp.containsMatchIn(input)
    }

    override fun exec(ctx: ParserContext) {
        val input = ctx.params.content as? String ?: return
        val root = XmlParser().parse(input) ?: return
        val doc = parseDocument(root, ParseSpanOptions(onSpan = ::interceptRubySpan))
        applyAmllMetas(doc.meta, doc.groups["meta"] ?: emptyList())
        ctx.result.type = InfoType.VALID
        ctx.result.timing = doc.timing
        ctx.result.lines = doc.lines
        ctx.result.meta = doc.meta
        ctx.result.agents = doc.agents
    }
}
