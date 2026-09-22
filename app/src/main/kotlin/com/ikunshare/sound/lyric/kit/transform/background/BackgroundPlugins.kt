package com.ikunshare.sound.lyric.kit.transform.background

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.ContentCarrier
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.ConfigManager

data class BackgroundExtractConfig(
    val fullLine: Boolean = true,
    val inLine: Boolean = true,
    val crossLine: Boolean = true,
)

/** transform-background：把括号内的和声/背景人声提取为背景行。 */
class BackgroundExtract : ParserPlugin() {
    override val id: String get() = "TRANSFORM-BACKGROUND-EXTRACT"
    override val stage: PluginStage get() = PluginStage.Transform
    val config = ConfigManager(BackgroundExtractConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val result = ArrayList<Line>()
        val processed = if (config.current.crossLine) BackgroundCore.extractCrossLine(lines) else lines

        for (line in processed) {
            if (line !is LineNormal) {
                result.add(line)
                continue
            }
            if (config.current.fullLine && BackgroundCore.isFullLine(line)) {
                val prev = result.lastOrNull()
                if (prev is LineNormal) {
                    BackgroundCore.addBackground(prev, BackgroundCore.toBackground(line))
                    continue
                }
            }
            if (config.current.inLine) BackgroundCore.extractInLine(line)
            BackgroundCore.assignBackgroundAnnotation(line)
            result.add(line)
        }
        ctx.result.lines = result
    }
}

/** transform-background：清理背景行残留的包裹括号。 */
class BackgroundClean : ParserPlugin() {
    override val id: String get() = "TRANSFORM-BACKGROUND-CLEAN"
    override val stage: PluginStage get() = PluginStage.Post

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        for (line in lines) {
            if (line !is LineNormal) continue
            if (line.backgrounds.isEmpty()) continue
            for (background in line.backgrounds) removeBrackets(background)
        }
        ctx.result.lines = lines
    }

    private fun removeBrackets(line: ContentCarrier, removeStart: Boolean = true, removeEnd: Boolean = true) {
        val content = line.content ?: return
        val words = content.words

        if (removeStart) {
            for (i in words.indices) {
                val w = words[i]
                if (w is WordNormal) {
                    if (w.content.isNotEmpty() && BackgroundCore.isOpenBracket(w.content[0])) {
                        w.content = w.content.substring(1)
                        if (w.content.isEmpty()) words.removeAt(i)
                    }
                    break
                }
            }
        }
        if (removeEnd) {
            for (i in words.indices.reversed()) {
                val w = words[i]
                if (w is WordNormal) {
                    if (w.content.isNotEmpty() && BackgroundCore.isCloseBracket(w.content.last())) {
                        w.content = w.content.substring(0, w.content.length - 1)
                        if (w.content.isEmpty()) words.removeAt(i)
                    }
                    break
                }
            }
        }

        val annotation = content.annotation ?: return
        for (item in annotation.translates) {
            var c = item.content
            if (c.trim().isEmpty()) continue
            if (removeStart && c.isNotEmpty() && BackgroundCore.isOpenBracket(c[0])) c = c.substring(1)
            if (removeEnd && c.isNotEmpty() && BackgroundCore.isCloseBracket(c.last())) c = c.substring(0, c.length - 1)
            item.content = c
        }
        for (item in annotation.romans) {
            var c = item.content
            if (c.trim().isEmpty()) continue
            if (removeStart && c.isNotEmpty() && BackgroundCore.isOpenBracket(c[0])) c = c.substring(1)
            if (removeEnd && c.isNotEmpty() && BackgroundCore.isCloseBracket(c.last())) c = c.substring(0, c.length - 1)
            item.content = c
        }
    }
}
