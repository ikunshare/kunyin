package com.ikunshare.sound.lyric.kit.transform

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.ConfigManager

/** transform-space 支持的空格插入类型。 */
object SpaceTypes {
    const val ALL = "ALL"
    const val CJK_ENGLISH = "CJK_ENGLISH"
    const val CJK_NUMBER = "CJK_NUMBER"
    const val PUNCTUATION = "PUNCTUATION"
    const val QUOTE = "QUOTE"
    const val BRACKET_OUTSIDE = "BRACKET_OUTSIDE"
    const val MATH_OPERATOR = "MATH_OPERATOR"
    const val HYPHEN = "HYPHEN"
    const val SLASH = "SLASH"
    const val COMPRESS_SPACES = "COMPRESS_SPACES"
    const val TRIM_INSIDE_SYMBOLS = "TRIM_INSIDE_SYMBOLS"

    val ALL_VALUES = listOf(
        ALL, CJK_ENGLISH, CJK_NUMBER, PUNCTUATION, QUOTE, BRACKET_OUTSIDE,
        MATH_OPERATOR, HYPHEN, SLASH, COMPRESS_SPACES, TRIM_INSIDE_SYMBOLS,
    )
}

data class SpaceInsertConfig(
    val original: Boolean = true,
    val extended: Boolean = true,
    val types: List<String> = listOf(SpaceTypes.ALL),
)

/** transform-space：在 CJK/英文/数字/标点等边界插入空格。 */
class SpaceInsert : ParserPlugin() {
    override val id: String get() = "TRANSFORM-SPACE-INSERT"
    override val stage: PluginStage get() = PluginStage.Transform
    val config = ConfigManager(SpaceInsertConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val enableOriginal = config.current.original
        val enableExtended = config.current.extended
        if (!enableOriginal && !enableExtended) return
        val target = SpaceCore.processTypes(config.current.types)

        fun handleLine(content: LineContent?) {
            if (enableOriginal) SpaceCore.insertSpaceToLine(content, target)
            if (enableExtended) SpaceCore.insertSpaceToExtended(content, target)
        }

        for (line in lines) {
            if (line !is LineNormal) continue
            handleLine(line.content)
            for (background in line.backgrounds) handleLine(background.content)
        }
    }
}

/** 加空格核心引擎，对应 transform-space 的 core.ts。 */
private object SpaceCore {
    private const val CJK = "぀-ヿ㐀-䶿一-鿿豈-﫿"
    private val WORD_CHAR = "A-Za-z0-9$CJK"
    private val ALL_RANGE = "A-Za-z0-9!@#\$%^&+\\-=/|<>$CJK"

    private val CJK_WITH_EN_RULE = Regex("([$CJK])([A-Za-z])")
    private val EN_WITH_CJK_RULE = Regex("([A-Za-z])([$CJK])")
    private val CJK_WITH_NUM_RULE = Regex("([$CJK])([0-9])")
    private val NUM_WITH_CJK_RULE = Regex("([0-9])([$CJK])")

    private val TRIM_INSIDE_SYMBOLS_RULE =
        Regex("([<\\[\\{\\(\"“‘])\\s*([^<>\\[\\]\\{\\}\\(\\)\"“‘”’]*?)\\s*([>\\]\\}\\)\"”’])")

    private val QUOTE_BEFORE_RULE = Regex("([$WORD_CHAR])([\"`“‘])")
    private val QUOTE_AFTER_RULE = Regex("([\"`”’])([$WORD_CHAR])")

    private val PUNCTUATION_RULE = Regex("([$ALL_RANGE])([!;,\\?:])(?=[$ALL_RANGE])")
    private val MATH_OPERATOR_RULE = Regex("([$ALL_RANGE])([+=&])(?=[$ALL_RANGE])")

    private val BRACKET_OUTSIDE_BEFORE_RULE = Regex("([$WORD_CHAR])([\\[({<])")
    private val BRACKET_OUTSIDE_AFTER_RULE = Regex("([\\])}>])([$WORD_CHAR])")

    private val HYPHEN_RULE = Regex("(?<=[$WORD_CHAR])-(?=[$WORD_CHAR])")
    private val SLASH_RULE = Regex("([$WORD_CHAR])(/)(?=[$WORD_CHAR])")
    private val HYPHEN_EDGE_RULE = Regex("(\\s|^)(-)(?=[$WORD_CHAR])|([$WORD_CHAR])(-)(?=\\s|$)")

    private val MULTI_SPACE = Regex("[ ]{2,}")

    private fun isDigit(c: Char?): Boolean = c != null && c in '0'..'9'

    private fun applyCjkEnglish(text: String): String =
        EN_WITH_CJK_RULE.replace(CJK_WITH_EN_RULE.replace(text, "$1 $2"), "$1 $2")

    private fun applyCjkNumber(text: String): String =
        NUM_WITH_CJK_RULE.replace(CJK_WITH_NUM_RULE.replace(text, "$1 $2"), "$1 $2")

    private fun applyPunctuation(text: String): String = PUNCTUATION_RULE.replace(text) { m ->
        val before = m.groupValues[1]
        val punct = m.groupValues[2]
        val afterIdx = m.range.last + 1
        val afterChar = if (afterIdx < text.length) text[afterIdx] else null
        if ((punct == "," || punct == ":") && isDigit(before.firstOrNull()) && isDigit(afterChar)) m.value
        else "$before$punct "
    }

    private fun applyQuote(text: String): String =
        QUOTE_AFTER_RULE.replace(QUOTE_BEFORE_RULE.replace(text, "$1 $2"), "$1 $2")

    private fun applyBracketOutside(text: String): String =
        BRACKET_OUTSIDE_AFTER_RULE.replace(BRACKET_OUTSIDE_BEFORE_RULE.replace(text, "$1 $2"), "$1 $2")

    private fun applyMathOperator(text: String): String = MATH_OPERATOR_RULE.replace(text, "$1 $2 ")

    private fun applySlash(text: String): String = SLASH_RULE.replace(text) { m ->
        val before = m.groupValues[1]
        val slash = m.groupValues[2]
        val afterIdx = m.range.last + 1
        val afterChar = if (afterIdx < text.length) text[afterIdx] else null
        if (isDigit(before.firstOrNull()) && isDigit(afterChar)) m.value
        else "$before $slash "
    }

    private fun applyHyphen(text: String): String {
        val stage1 = HYPHEN_RULE.replace(text) { m ->
            val i = m.range.first
            val prev = if (i > 0) text[i - 1] else null
            val next = if (i + 1 < text.length) text[i + 1] else null
            if (isDigit(prev) && isDigit(next)) m.value else " - "
        }
        return HYPHEN_EDGE_RULE.replace(stage1) { m ->
            if (m.groups[1] != null) {
                m.groupValues[1] + m.groupValues[2] + " "
            } else {
                m.groupValues[3] + " " + m.groupValues[4]
            }
        }
    }

    private fun applyTrimInsideSymbols(text: String): String = TRIM_INSIDE_SYMBOLS_RULE.replace(text) { m ->
        m.groupValues[1].trim() + m.groupValues[2].trim() + m.groupValues[3].trim()
    }

    private fun applyCompressSpaces(text: String): String = MULTI_SPACE.replace(text, " ")

    fun processTypes(types: List<String>?): Set<String> {
        val target = types ?: listOf(SpaceTypes.ALL)
        return if (target.contains(SpaceTypes.ALL)) SpaceTypes.ALL_VALUES.toSet() else target.toSet()
    }

    fun insertSpace(text: String, target: Set<String>): String {
        if (text.trim().isEmpty()) return text
        var result = text
        if (target.contains(SpaceTypes.CJK_ENGLISH)) result = applyCjkEnglish(result)
        if (target.contains(SpaceTypes.CJK_NUMBER)) result = applyCjkNumber(result)
        if (target.contains(SpaceTypes.PUNCTUATION)) result = applyPunctuation(result)
        if (target.contains(SpaceTypes.QUOTE)) result = applyQuote(result)
        if (target.contains(SpaceTypes.BRACKET_OUTSIDE)) result = applyBracketOutside(result)
        if (target.contains(SpaceTypes.MATH_OPERATOR)) result = applyMathOperator(result)
        if (target.contains(SpaceTypes.HYPHEN)) result = applyHyphen(result)
        if (target.contains(SpaceTypes.SLASH)) result = applySlash(result)
        if (target.contains(SpaceTypes.TRIM_INSIDE_SYMBOLS)) result = applyTrimInsideSymbols(result)
        if (target.contains(SpaceTypes.COMPRESS_SPACES)) result = applyCompressSpaces(result)
        return result.trim()
    }

    fun insertSpaceToLine(content: LineContent?, target: Set<String>) {
        if (content == null || content.words.isEmpty()) return
        val full = Runtime.getWordsText(content.words)
        val processed = insertSpace(full, target)
        val normalWords = content.words.filterIsInstance<WordNormal>()
        content.words = alignElements(normalWords, processed)
    }

    fun insertSpaceToExtended(content: LineContent?, target: Set<String>) {
        val annotation = content?.annotation ?: return
        for (item in annotation.translates) item.content = insertSpace(item.content, target)
        for (item in annotation.romans) item.content = insertSpace(item.content, target)
    }

    /** 将加空格后的字符串重新切回原 normal 词，插入 WordSpace。 */
    private fun alignElements(elements: List<WordNormal>, text: String): MutableList<Word> {
        val result = ArrayList<Word>()
        var pIndex = 0

        fun appendSpaces() {
            var count = 0
            while (pIndex < text.length && text[pIndex] == ' ') {
                count++
                pIndex++
            }
            if (count > 0) result.add(Runtime.makeWordSpace(count))
        }

        for (element in elements) {
            val nonSpaceChars = element.content.replace(" ", "")
            if (nonSpaceChars.isEmpty()) continue
            appendSpaces()
            val matchStart = pIndex
            var charIndex = 0
            while (pIndex < text.length && charIndex < nonSpaceChars.length) {
                if (text[pIndex] != ' ') charIndex++
                pIndex++
            }
            val matchedText = text.substring(matchStart, pIndex)
            result.add(
                Runtime.makeWordNormal(
                    content = matchedText,
                    time = element.time,
                    language = element.language,
                    annotation = element.annotation,
                    stress = element.stress,
                )
            )
        }
        appendSpaces()
        return result
    }
}
