package com.ikunshare.sound.lyric.kit.transform.language

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.LanguageType
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.ConfigManager

data class LanguageInferConfig(val override: Boolean = false)

/** transform-language：逐词推断语言（word.language）。 */
class LanguageInfer : ParserPlugin() {
    override val id: String get() = "TRANSFORM-LANGUAGE-INFER"
    override val stage: PluginStage get() = PluginStage.Transform
    val config = ConfigManager(LanguageInferConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val override = config.current.override

        val targets = ArrayList<Pair<WordNormal, Script>>()
        var kanaCount = 0
        var hanCount = 0
        val hanText = StringBuilder()
        val latinText = StringBuilder()

        fun handleLine(content: LineContent?) {
            for (word in content?.words ?: emptyList()) {
                if (word !is WordNormal) continue
                if (word.language != null && !override) continue
                val counts = LanguageUtil.analyzeScripts(word.content)
                val script = LanguageUtil.dominantScript(counts) ?: continue
                kanaCount += counts.kana
                hanCount += counts.han
                if (script == Script.han) hanText.append(word.content)
                else if (script == Script.latin) latinText.append(word.content)
                targets.add(word to script)
            }
        }

        for (line in lines) {
            if (line !is LineNormal) continue
            handleLine(line.content)
            for (background in line.backgrounds) handleLine(background.content)
        }

        val japanese = kanaCount > 0 && kanaCount >= (kanaCount + hanCount) * LanguageUtil.JAPANESE_KANA_RATIO
        val hanLanguage = if (japanese) LanguageType.Japanese.tag
        else LanguageUtil.detectChineseVariant(hanText.toString()) ?: LanguageType.ChineseSimplified.tag
        val latinLanguage = LanguageUtil.detectLatinLanguage(latinText.toString())

        for ((word, script) in targets) {
            word.language = when (script) {
                Script.kana -> LanguageType.Japanese.tag
                Script.hangul -> LanguageType.Korean.tag
                Script.cyrillic -> LanguageType.Russian.tag
                Script.han -> hanLanguage
                Script.latin -> latinLanguage
            }
        }
    }
}

data class LanguageCalculateConfig(val background: Boolean = false)

/** transform-language：计算 info.languages 各语言占比。 */
class LanguageCalculatePercent : ParserPlugin() {
    override val id: String get() = "TRANSFORM-LANGUAGE-CALCULATE-PERCENT"
    override val stage: PluginStage get() = PluginStage.Post
    val config = ConfigManager(LanguageCalculateConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val includeBackground = config.current.background

        val counts = LinkedHashMap<String, Int>()
        var total = 0

        fun handleLine(content: LineContent?) {
            for (word in content?.words ?: emptyList()) {
                if (word !is WordNormal) continue
                val lang = word.language ?: continue
                val unit = if (LanguageUtil.isCjkLanguage(lang)) LanguageUtil.countCjkChars(word.content)
                else LanguageUtil.countLatinWords(word.content)
                if (unit <= 0) continue
                counts[lang] = (counts[lang] ?: 0) + unit
                total += unit
            }
        }

        for (line in lines) {
            if (line !is LineNormal) continue
            handleLine(line.content)
            if (includeBackground) for (background in line.backgrounds) handleLine(background.content)
        }

        if (total == 0) return

        val list = counts.map { (tag, count) ->
            Runtime.makeLanguageItem(tag, Math.round(count * 10000.0 / total) / 100.0)
        }.sortedByDescending { it.percent }.toMutableList()

        ctx.result.languages = list
    }
}
