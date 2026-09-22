package com.ikunshare.sound.lyric.kit

import com.ikunshare.sound.lyric.kit.core.MusicInfo
import com.ikunshare.sound.lyric.kit.format.lrc.LrcParserInput
import com.ikunshare.sound.lyric.kit.model.Info
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineInterlude
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Timing
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.model.WordSpace
import com.ikunshare.sound.lyric.kit.transform.StressMarkConfig
import com.ikunshare.sound.model.DuetSide
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.LyricBackground
import com.ikunshare.sound.model.LyricChar
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.LyricLineType
import com.ikunshare.sound.model.ParsedLyric
import kotlin.math.abs

/**
 * transform 插件开关。
 *
 * 说明：
 * - [spaceInsert]/[stressMark]：安全且在当前 [ParsedLyric] 模型下可见，默认开启。
 * - [agentExtract]/[pureClean]/[pureExtractCreator]：会改写/删除正文行，存在误伤普通歌词的概率，默认关闭。
 * - [backgroundExtract]/[interludeInsert]/[language]：产出结构化信息（背景行/间奏/语言占比），当前
 *   [ParsedLyric] 无对应字段无法显示（背景行还会导致括号文字消失），默认关闭。
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
 * 翻译/音译/逐字音译/AI 谐音各自解析后按「行数相同则索引对齐，否则时间就近对齐」挂到主歌词行上。
 */
object LyricKitAdapter {

    private const val ALIGN_TOLERANCE_MS = 150L

    fun toParsedLyric(
        lyric: Lyric,
        musicInfo: MusicInfo? = null,
        transforms: LyricTransformOptions = LyricTransformOptions(),
    ): ParsedLyric {
        val original = lyric.char.ifBlank { lyric.lrc }
        if (original.isBlank() && lyric.lrc.isBlank()) return ParsedLyric.EMPTY

        val mainInfo = runMain(original, musicInfo, transforms)
        val mainLines = mainInfo.lines.filterIsInstance<LineNormal>()

        if (mainLines.isEmpty()) {
            // 纯文本歌词（无时间戳）回退
            if (lyric.lrc.isNotBlank()) {
                val lines = lyric.lrc.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(timestamp = 0L, text = it) }
                return ParsedLyric(
                    lines = lines,
                    hasTranslation = false,
                    hasRomanization = false,
                    hasCharLyrics = false,
                    isSynced = false,
                )
            }
            return ParsedLyric.EMPTY
        }

        val mainTs = mainLines.map { (it.time?.start ?: 0).toLong() }

        // 各辅助内容解析
        val transAux = parseTextAux(lyric.trans)
        val romaAux = parseTextAux(lyric.roma)
        val chromaAux = parseCharAux(lyric.chroma)
        val phoneticAux = parseTextAux(lyric.phonetic)

        val transAligned = alignByIndexOrTime(mainTs, transAux)
        val romaAligned = alignByIndexOrTime(mainTs, romaAux)
        val chromaAligned = alignByIndexOrTime(mainTs, chromaAux)
        val phoneticAligned = alignByIndexOrTime(mainTs, phoneticAux)

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
                        translation = transAligned.getOrNull(index),
                        romanization = romaAligned.getOrNull(index),
                        phonetic = phoneticAligned.getOrNull(index),
                        characters = buildChars(line, mainInfo.timing, ts),
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

                else -> null
            }
        }.sortedBy { it.timestamp }

        return ParsedLyric(
            lines = lines,
            hasTranslation = lines.any { it.hasTranslation },
            hasRomanization = lines.any { it.hasRomanization },
            hasCharLyrics = lines.any { it.hasCharLyrics },
            isSynced = true,
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
        return com.ikunshare.sound.lyric.kit.util.TextUtil.parseTime(m.groupValues[1].trim())
    }

    /** 主歌词管线：LRC 解析 + 词时长补全 + 按选项执行 transform。 */
    private fun runMain(original: String, musicInfo: MusicInfo?, transforms: LyricTransformOptions): Info {
        val pipeline = LyricPipeline(
            LyricPipelineInput(content = LrcParserInput(preprocessSyllable(original)), musicInfo = musicInfo)
        )
        pipeline.infer()
        try {
            pipeline.parse()
        } catch (_: Exception) {
            return pipeline.final().result
        }
        // kit 的逐字解析对「绝对时间标记」格式不补词时长（每词 end=start），
        // 这里在桥接层按「下一词起始即上一词结束」补全，使卡拉OK时长与重音检测生效。
        reconstructWordDurations(pipeline.info)

        // 按选项执行 transform（顺序参考 kit 的 Transform→Post 阶段与优先级）。
        if (transforms.pureExtractCreator) pipeline.pureExtractCreator()
        if (transforms.pureClean) pipeline.pureClean()
        if (transforms.agentExtract) pipeline.agentExtract()
        if (transforms.backgroundExtract) pipeline.backgroundExtract()
        if (transforms.spaceInsert) pipeline.spaceInsert()
        if (transforms.stressMark) pipeline.stressMark(StressMarkConfig(checkTime = 3000))
        if (transforms.interludeInsert) pipeline.interludeInsert()
        if (transforms.language) pipeline.languageInfer()
        if (transforms.backgroundExtract) pipeline.backgroundClean()
        if (transforms.language) pipeline.languageCalculatePercent()

        return pipeline.final().result
    }

    /** 把每个 normal 词的结束时间补成下一 normal 词的起始时间；末词用估算时长。 */
    private fun reconstructWordDurations(info: Info) {
        for (line in info.lines) {
            if (line !is LineNormal) continue
            fixWordDurations(line.content?.words)
            for (background in line.backgrounds) fixWordDurations(background.content?.words)
        }
    }

    private fun fixWordDurations(words: List<*>?) {
        if (words == null) return
        val normals = words.filterIsInstance<WordNormal>()
        for (j in normals.indices) {
            val w = normals[j]
            val t = w.time ?: continue
            if (t.end <= t.start) {
                val next = normals.getOrNull(j + 1)?.time?.start
                t.end = if (next != null && next > t.start) next else t.start + estimateCharDuration(w.content)
            }
        }
    }

    private fun estimateCharDuration(char: String): Int = when {
        char.isBlank() -> 100
        char.length == 1 && char[0].isLetterOrDigit() -> 200
        else -> 300
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

    /** 行数相同则按索引对齐，否则按时间就近对齐（容错 150ms）。 */
    private fun <T> alignByIndexOrTime(mainTs: List<Long>, aux: List<Pair<Long, T>>): List<T?> {
        if (aux.isEmpty()) return List(mainTs.size) { null }
        if (aux.size == mainTs.size) return aux.map { it.second }
        return mainTs.map { ts ->
            val nearest = aux.minByOrNull { abs(it.first - ts) }
            if (nearest != null && abs(nearest.first - ts) <= ALIGN_TOLERANCE_MS) nearest.second else null
        }
    }
}
