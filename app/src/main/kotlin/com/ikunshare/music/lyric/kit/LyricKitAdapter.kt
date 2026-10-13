package com.ikunshare.music.lyric.kit

import com.ikunshare.music.lyric.kit.core.MusicInfo
import com.ikunshare.music.lyric.kit.format.lrc.LrcParserInput
import com.ikunshare.music.lyric.kit.model.Info
import com.ikunshare.music.lyric.kit.model.LineContent
import com.ikunshare.music.lyric.kit.model.LineInterlude
import com.ikunshare.music.lyric.kit.model.LineNormal
import com.ikunshare.music.lyric.kit.model.Runtime
import com.ikunshare.music.lyric.kit.model.Timing
import com.ikunshare.music.lyric.kit.model.WordNormal
import com.ikunshare.music.lyric.kit.model.WordSpace
import com.ikunshare.music.lyric.kit.transform.StressMarkConfig
import com.ikunshare.music.model.DuetSide
import com.ikunshare.music.model.Lyric
import com.ikunshare.music.model.LyricBackground
import com.ikunshare.music.model.LyricChar
import com.ikunshare.music.model.LyricCredit
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.model.LyricLineType
import com.ikunshare.music.model.ParsedLyric
import kotlin.math.abs

/**
 * transform 插件开关。
 *
 * 默认启用桌面版的清理、制作信息、声部、背景人声、间奏和逐字处理。
 */
data class LyricTransformOptions(
    val spaceInsert: Boolean = true,
    val stressMark: Boolean = true,
    val agentExtract: Boolean = true,
    val pureClean: Boolean = true,
    val pureExtractCreator: Boolean = true,
    val backgroundExtract: Boolean = true,
    val interludeInsert: Boolean = true,
    val language: Boolean = true,
)

/**
 * 适配器：把歌词工具包（kit）解析出的 Runtime.Info 转换为 app 现有的 ParsedLyric。
 *
 * 主歌词走 kit 的 LRC 管线（自动识别逐字/逐行）+ 按 [LyricTransformOptions] 选择性执行 transform；
 * 辅助轨在变换前按原始行槽或 50ms 时间窗口对齐，避免提取制作信息后错位。
 */
object LyricKitAdapter {

    private const val ALIGN_TOLERANCE_MS = 50L

    fun toParsedLyric(
        lyric: Lyric,
        musicInfo: MusicInfo? = null,
        transforms: LyricTransformOptions = LyricTransformOptions(),
    ): ParsedLyric {
        val original = LyricTextRules.clean(lyric.char.ifBlank { lyric.lrc })
        if (original.isBlank() && lyric.lrc.isBlank()) return ParsedLyric.EMPTY

        val translation = LyricTextRules.align(original, LyricTextRules.clean(lyric.trans, annotation = true))
        val roman = LyricTextRules.align(original,
            LyricTextRules.clean(lyric.chroma.ifBlank { lyric.roma }, annotation = true))
        val mainInfo = runMain(original, musicInfo, transforms, translation, LyricTextRules.stripWordTags(roman))
        val mainLines = mainInfo.lines.filterIsInstance<LineNormal>()
        val credits = mainInfo.meta?.credits.orEmpty().groupBy { it.role.trim() }.mapNotNull { (role, rows) ->
            val names = rows.flatMap { it.names }.map { it.content.trim() }.filter { it.isNotEmpty() }.distinct()
            if (role.isBlank() || names.isEmpty()) null else LyricCredit(role, names)
        }

        if (mainLines.isEmpty()) {
            // 纯文本歌词（无时间戳）回退
            if (mainInfo.timing == Timing.NONE && credits.isEmpty()) {
                val lines = original.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith('[') }
                    .map { LyricLine(timestamp = 0L, text = it) }
                return ParsedLyric(
                    lines = lines,
                    hasTranslation = false,
                    hasRomanization = false,
                    hasCharLyrics = false,
                    isSynced = false,
                )
            }
            return ParsedLyric.EMPTY.copy(credits = credits)
        }

        val mainTs = mainLines.map { (it.time?.start ?: 0).toLong() }

        // 各辅助内容解析
        val chromaAux = if (roman.contains('<')) parseCharAux(roman) else emptyList()
        val phoneticAux = parseTextAux(LyricTextRules.align(original,
            LyricTextRules.clean(lyric.phonetic, annotation = true)))
        val chromaAligned = alignByTime(mainTs, chromaAux)
        val phoneticAligned = alignByTime(mainTs, phoneticAux)

        // 主唱声部：出现行数最多的 agent 视为主唱（左对齐），其余 agent 视为对唱（右对齐）。
        val primaryAgentId = Runtime.getPrimaryAgent(mainInfo)?.id

        // 保序遍历所有行：普通行按 normalIndex 对齐各辅助歌词，间奏行独立映射；背景/对唱一并带出。
        var normalIndex = 0
        val lines = mainInfo.lines.mapNotNull { line ->
            when (line) {
                is LineNormal -> {
                    val index = normalIndex++
                    val ts = (line.time?.start ?: 0).toLong()
                    val end = line.time?.end
                    val agentId = Runtime.resolveLineAgent(line, mainInfo.agents)?.id
                    val side = if (primaryAgentId == null || agentId == null || agentId == primaryAgentId) {
                        DuetSide.LEFT
                    } else {
                        DuetSide.RIGHT
                    }
                    LyricLine(
                        timestamp = ts,
                        text = Runtime.getLineText(line),
                        translation = line.content?.annotation?.translates.orEmpty()
                            .map { it.content }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null },
                        romanization = line.content?.annotation?.romans.orEmpty()
                            .map { it.content }.filter { it.isNotBlank() }.joinToString("\n").ifBlank { null },
                        phonetic = phoneticAligned.getOrNull(index),
                        characters = attachWordRomans(buildChars(line, mainInfo.timing, ts), chromaAligned.getOrNull(index)),
                        charRomanization = chromaAligned.getOrNull(index),
                        endTimestamp = if (end != null && end > 0) end.toLong() else null,
                        duetSide = side,
                        backgrounds = buildBackgrounds(line, mainInfo.timing),
                    )
                }

                is LineInterlude -> {
                    val start = (line.time?.start ?: 0).toLong()
                    val end = (line.time?.end ?: 0).toLong()
                    if (end > start) {
                        LyricLine(
                            timestamp = start,
                            text = "",
                            endTimestamp = end,
                            type = LyricLineType.INTERLUDE,
                        )
                    } else {
                        null
                    }
                }

            }
        }.sortedBy { it.timestamp }

        return ParsedLyric(
            lines = lines,
            hasTranslation = lines.any { it.hasTranslation },
            hasRomanization = lines.any { it.hasRomanization },
            hasCharLyrics = lines.any { it.hasCharLyrics },
            isSynced = true,
            credits = credits,
        )
    }

    private val leadingTagRegex = Regex("^\\s*(\\[[^\\]]*\\])")
    private val markerRegex = Regex("<([^>]+)>")
    private val markerInnerRegex = Regex("^[<\\[]([^>\\]]+)[>\\]]$")

    /**
     * 把 app 的逐字 chase 格式（绝对时间标记 `<mm:ss.xxx>word...<行尾结束标记>`）预转换为 kit
     * 能理解的相对格式 `<offset,duration>word`，使 kit 计算出完整词时长（含行尾结束标记折算进末词），
     * 从而卡拉OK时长与重音检测均生效。非逐字/无标记行原样返回。
     */
    private fun preprocessSyllable(text: String): String {
        if (text.isBlank() || !text.contains('<')) return text
        return text.split("\n").joinToString("\n") { convertSyllableLine(it) }
    }

    private fun convertSyllableLine(line: String): String {
        val tagMatch = leadingTagRegex.find(line) ?: return line
        val tag = tagMatch.groupValues[1]
        val lineStart = parseInner(tag) ?: return line
        val rest = line.substring(tagMatch.range.last + 1)
        val markers = markerRegex.findAll(rest).toList()
        if (markers.size < 2) return line
        val times = markers.map { parseInner(it.value) }
        if (times.any { it == null }) return line // 非绝对时间标记（可能已是 <off,dur>）
        val sb = StringBuilder(line.substring(0, tagMatch.range.last + 1))
        for (i in 0 until markers.size - 1) {
            val wStart = markers[i].range.last + 1
            val wEnd = markers[i + 1].range.first
            val word = rest.substring(wStart, wEnd)
            val off = maxOf(0, times[i]!! - lineStart)
            val dur = maxOf(0, times[i + 1]!! - times[i]!!)
            sb.append("<").append(off).append(",").append(dur).append(">").append(word)
        }
        return sb.toString()
    }

    private fun parseInner(tag: String): Int? {
        val m = markerInnerRegex.matchEntire(tag.trim()) ?: return null
        return com.ikunshare.music.lyric.kit.util.TextUtil.parseTime(m.groupValues[1].trim())
    }

    /** 主歌词管线：LRC 解析 + 词时长补全 + 按选项执行 transform。 */
    private fun runMain(original: String, musicInfo: MusicInfo?, transforms: LyricTransformOptions,
                        translate: String, roman: String): Info {
        val pipeline = LyricPipeline(
            LyricPipelineInput(content = LrcParserInput(preprocessSyllable(original), translate, roman), musicInfo = musicInfo)
        )
        pipeline.infer()
        pipeline.parse()
        // kit 的逐字解析对「绝对时间标记」格式不补词时长（每词 end=start），
        // 这里在桥接层按「下一词起始即上一词结束」补全，使卡拉OK时长与重音检测生效。
        reconstructWordDurations(pipeline.info)

        // 按选项执行 transform（顺序参考 kit 的 Transform→Post 阶段与优先级）。
        if (transforms.pureClean) pipeline.pureClean()
        if (transforms.pureExtractCreator) pipeline.pureExtractCreator()
        if (transforms.agentExtract) pipeline.agentExtract()
        if (transforms.backgroundExtract) pipeline.backgroundExtract()
        if (transforms.backgroundExtract) pipeline.backgroundClean()
        if (transforms.interludeInsert) pipeline.interludeInsert()
        if (transforms.spaceInsert) pipeline.spaceInsert()
        if (transforms.stressMark) pipeline.stressMark(StressMarkConfig(checkTime = 3000))
        if (transforms.language) pipeline.languageInfer()
        if (transforms.language) pipeline.languageCalculatePercent()

        return pipeline.final().result
    }

    /** 把每个 normal 词的结束时间补成下一 normal 词的起始时间；末词用估算时长。 */
    private fun reconstructWordDurations(info: Info) {
        for (line in info.lines) {
            if (line !is LineNormal) continue
            fixWordDurations(line.content?.words, line.time?.end)
            for (background in line.backgrounds) fixWordDurations(background.content?.words, background.time?.end)
            val end = (line.content?.words.orEmpty().filterIsInstance<WordNormal>() +
                line.backgrounds.flatMap { it.content?.words.orEmpty().filterIsInstance<WordNormal>() })
                .maxOfOrNull { it.time?.end ?: 0 }
            if (end != null && end > (line.time?.end ?: 0)) line.time?.end = end
        }
    }

    private fun fixWordDurations(words: List<*>?, lineEnd: Int?) {
        if (words == null) return
        val normals = words.filterIsInstance<WordNormal>()
        for (j in normals.indices) {
            val w = normals[j]
            val t = w.time ?: continue
            if (t.end <= t.start) {
                val next = normals.drop(j + 1).firstOrNull { (it.time?.start ?: 0) > t.start }?.time?.start
                t.end = next ?: lineEnd?.takeIf { it > t.start } ?: (t.start + estimateCharDuration(w.content))
            }
        }
    }

    private fun estimateCharDuration(char: String): Int = when {
        char.isBlank() -> 100
        char.length == 1 -> 250
        else -> minOf(800, 200 * char.length)
    }

    private fun attachWordRomans(main: List<LyricChar>?, roman: List<LyricChar>?): List<LyricChar>? {
        if (main.isNullOrEmpty() || roman.isNullOrEmpty()) return main
        val assignments = roman.groupBy { syllable ->
            main.indexOfFirst { it.startTime == syllable.startTime }.takeIf { it >= 0 } ?: main.indices.maxWithOrNull(
                compareBy<Int> { index ->
                val word = main[index]
                minOf(word.endTime.coerceAtLeast(word.startTime + 1), syllable.endTime.coerceAtLeast(syllable.startTime + 1)) -
                    maxOf(word.startTime, syllable.startTime)
                }.thenBy { -abs(main[it].startTime - syllable.startTime) }
            )?.takeIf { index ->
                minOf(main[index].endTime, syllable.endTime) > maxOf(main[index].startTime, syllable.startTime)
            } ?: main.indices.minByOrNull { abs(main[it].startTime - syllable.startTime) }
        }
        return main.mapIndexed { index, word ->
            val tokens = assignments[index].orEmpty().let { items ->
                if (items.size == 1) items.map { it.copy(startTime = word.startTime, endTime = word.endTime) }
                else items
            }
            word.copy(romanization = tokens.joinToString(" ") { it.char.trim() }.ifBlank { null },
                romanizationWords = tokens)
        }
    }

    /** 解析纯文本类辅助歌词（翻译/音译/谐音），返回 (行起始时间 -> 文本)。 */
    private fun parseTextAux(text: String): List<Pair<Long, String>> {
        if (text.isBlank()) return emptyList()
        val info = runAux(text) ?: return emptyList()
        return info.lines.filterIsInstance<LineNormal>().map { line ->
            (line.time?.start ?: 0).toLong() to Runtime.getLineText(line)
        }
    }

    /** 解析逐字类辅助歌词（逐字音译），返回 (行起始时间 -> 字符列表)。 */
    private fun parseCharAux(text: String): List<Pair<Long, List<LyricChar>>> {
        if (text.isBlank()) return emptyList()
        val info = runAux(text) ?: return emptyList()
        return info.lines.filterIsInstance<LineNormal>().map { line ->
            val ts = (line.time?.start ?: 0).toLong()
            ts to (buildChars(line, info.timing, ts) ?: emptyList())
        }
    }

    private fun runAux(text: String): Info? {
        val pipeline = LyricPipeline(LyricPipelineInput(content = LrcParserInput(preprocessSyllable(text))))
        pipeline.infer()
        return try {
            pipeline.parse()
            reconstructWordDurations(pipeline.info)
            pipeline.final().result
        } catch (_: Exception) {
            null
        }
    }

    /** 背景/和声行 -> LyricBackground 列表。 */
    private fun buildBackgrounds(line: LineNormal, timing: Timing): List<LyricBackground> {
        if (line.backgrounds.isEmpty()) return emptyList()
        return line.backgrounds.mapNotNull { bg ->
            val start = (bg.time?.start ?: 0).toLong()
            val end = (bg.time?.end ?: 0).toLong()
            val text = Runtime.getWordsText(bg.content?.words ?: emptyList())
            if (text.isBlank()) return@mapNotNull null
            LyricBackground(
                text = text,
                timestamp = start,
                endTimestamp = if (end > start) end else start,
                characters = buildCharsFromContent(bg.content, timing, start),
            )
        }
    }

    /** 逐字歌词行 -> LyricChar 列表；逐行歌词返回 null。 */
    private fun buildChars(line: LineNormal, timing: Timing, lineStart: Long): List<LyricChar>? =
        buildCharsFromContent(line.content, timing, lineStart)

    /** 从 LineContent 构建逐字列表（普通行与背景行共用）。 */
    private fun buildCharsFromContent(content: LineContent?, timing: Timing, lineStart: Long): List<LyricChar>? {
        if (timing != Timing.WORD) return null
        val words = content?.words ?: return null
        val result = ArrayList<LyricChar>()
        var pendingSpacePrefix = ""
        for (word in words) {
            when (word) {
                is WordNormal -> {
                    val text = if (pendingSpacePrefix.isNotEmpty()) {
                        val t = pendingSpacePrefix + word.content
                        pendingSpacePrefix = ""
                        t
                    } else word.content
                    val st = (word.time?.start ?: lineStart)
                    val et = (word.time?.end ?: st)
                    result.add(
                        LyricChar(
                            char = text,
                            startTime = st.toLong(),
                            endTime = et.toLong(),
                            stress = word.stress,
                        )
                    )
                }
                is WordSpace -> {
                    val spaces = " ".repeat(word.count)
                    if (result.isNotEmpty()) {
                        val last = result.removeAt(result.size - 1)
                        result.add(last.copy(char = last.char + spaces))
                    } else {
                        pendingSpacePrefix += spaces
                    }
                }
            }
        }
        return if (result.isEmpty()) null else result
    }

    /** 原始辅助轨已对齐，这里按时间查找变换后的正文行，容错 50ms。 */
    private fun <T> alignByTime(mainTs: List<Long>, aux: List<Pair<Long, T>>): List<T?> {
        if (aux.isEmpty()) return List(mainTs.size) { null }
        return mainTs.map { ts ->
            val nearest = aux.minByOrNull { abs(it.first - ts) }
            if (nearest != null && abs(nearest.first - ts) <= ALIGN_TOLERANCE_MS) nearest.second else null
        }
    }
}
