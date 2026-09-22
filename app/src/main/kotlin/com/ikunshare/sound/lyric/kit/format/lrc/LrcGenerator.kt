package com.ikunshare.sound.lyric.kit.format.lrc

import com.ikunshare.sound.lyric.kit.model.Info
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Timing
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.model.WordSpace
import com.ikunshare.sound.lyric.kit.util.TextUtil

data class LrcGeneratorResult(
    val original: String,
    val translate: String,
    val roman: String,
    val syllable: String,
)

/**
 * LRC 生成器，对应 @music-lyric-kit/plugin-format-lrc 的 Generator。
 * 原库实现为 GeneratorPlugin；此处以独立函数形式移植其功能。
 */
object LrcGenerator {

    fun generate(info: Info): LrcGeneratorResult {
        val (original, syllable, translate, roman) = exportLines(info)
        val meta = exportMeta(info)

        val targetOriginal = listOf(meta.joinToString("\n"), "\n", "\n", original.joinToString("\n"))
        val targetSyllable = listOf(meta.joinToString("\n"), "\n", "\n", syllable.joinToString("\n"))

        return LrcGeneratorResult(
            original = targetOriginal.joinToString("").trim(),
            syllable = targetSyllable.joinToString("\n").trim(),
            translate = translate.joinToString("\n").trim(),
            roman = roman.joinToString("\n").trim(),
        )
    }

    private data class Lines(
        val original: List<String>,
        val syllable: List<String>,
        val translate: List<String>,
        val roman: List<String>,
    )

    private fun exportLines(info: Info): Lines {
        val original = ArrayList<String>()
        val syllable = ArrayList<String>()
        val translate = ArrayList<String>()
        val roman = ArrayList<String>()

        for (line in info.lines) {
            if (line !is LineNormal) continue
            val lineTime = "[" + TextUtil.formatTime((line.time?.start ?: 0).toLong()) + "]"
            if (info.timing == Timing.WORD) {
                val sb = StringBuilder()
                for (word in (line.content?.words ?: emptyList())) {
                    when (word) {
                        is WordNormal -> sb.append("<").append(TextUtil.formatTime((word.time?.start ?: 0).toLong())).append(">").append(word.content)
                        is WordSpace -> sb.append(" ".repeat(word.count))
                    }
                }
                syllable.add(lineTime + sb.toString())
                original.add(lineTime + Runtime.getLineText(line))
            } else {
                original.add(lineTime + Runtime.getLineText(line))
            }
            val annotation = line.content?.annotation
            if (annotation != null) {
                for (item in annotation.translates) translate.add(lineTime + item.content)
                for (item in annotation.romans) roman.add(lineTime + item.content)
            }
        }
        return Lines(original, syllable, translate, roman)
    }

    private fun renderItem(key: String, content: String): String = "[$key:$content]"

    private fun exportMeta(info: Info): List<String> {
        val meta = info.meta ?: return emptyList()
        val result = ArrayList<String>()
        if (meta.offset != 0) result.add(renderItem("offset", meta.offset.toString()))
        for (title in meta.titles) result.add(renderItem("ti", title.content))
        for (artist in meta.artists) result.add(renderItem("ar", artist.content))
        for (album in meta.albums) result.add(renderItem("al", album.content))
        if (meta.duration != 0) result.add(renderItem("length", TextUtil.formatTime(meta.duration.toLong())))
        return result
    }
}
