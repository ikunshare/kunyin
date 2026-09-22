package com.ikunshare.sound.tool

import com.ikunshare.sound.lyric.kit.LyricKitAdapter
import com.ikunshare.sound.lyric.kit.core.MusicInfo
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.LyricChar
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.ParsedLyric
import java.util.TreeMap
import java.util.regex.Pattern
import kotlin.math.abs

/**
 * 歌词解析器
 * 
 * 支持解析以下格式：
 * 1. 标准 LRC 格式: [mm:ss.xx]text 或 [mm:ss.xxx]text
 * 2. 逐字歌词格式: [mm:ss.xxx]<mm:ss.xxx>char1<mm:ss.xxx>char2...
 * 3. 翻译歌词和音译歌词
 * 
 * **Validates: Requirements 4.1, 4.6**
 */
object LyricParser {
    private const val STRESS_MARK_CHECK_TIME_MS = 3000L


    // 匹配单个时间标签: [mm:ss.xx] 或 [mm:ss.xxx]，用于提取行首连续多时间标签
    private val PATTERN_MULTI_TIME =
        Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{2,3}))?\\]")

    // 逐字歌词时间标签: <mm:ss.xxx>
    private val PATTERN_CHAR_TAG = Pattern.compile("<(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{2,3}))?>")

    // 尾部行结束时间标签: [mm:ss.xxx] 在行末
    private val PATTERN_TRAILING_TIME =
        Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{2,3}))?\\]$")

    // Metadata 标签: [ti:xxx], [ar:xxx], [al:xxx] 等
    private val PATTERN_METADATA = Pattern.compile("^\\[[a-zA-Z]+:.*\\]$")

    /**
     * 逐字歌词解析结果（含可选的行结束时间戳）
     */
    private data class CharLineResult(
        val characters: List<LyricChar>,
        val endTimestamp: Long? = null
    )

    /**
     * 解析 Lyric 对象为 ParsedLyric。
     *
     * 优先走歌词工具包（kit）的净化管线：剔除作词/作曲/制作等杂项信息、抽出背景和声行与间奏、
     * 识别对唱声部、补全逐字时长与重音。解析抛异常或产出为空时回退到旧的正则解析，保证不劣化。
     *
     * @param lyric 原始歌词数据
     * @param song 当前歌曲（可选）。提供后可用歌名/歌手辅助净化首行的标题与演唱者信息。
     * @return 解析后的歌词数据
     */
    fun parse(lyric: Lyric, song: MusicItem? = null): ParsedLyric {
        val kit = try {
            LyricKitAdapter.toParsedLyric(lyric, song?.toMusicInfo())
        } catch (_: Exception) {
            null
        }
        if (kit != null && !kit.isEmpty) return kit
        return parseLegacy(lyric)
    }

    /** 由歌曲信息构造 kit 的 [MusicInfo]，用于净化时匹配并剔除首行的歌名/歌手。 */
    private fun MusicItem.toMusicInfo(): MusicInfo {
        val singerNames = singers
            ?.mapNotNull { it.name.trim().ifBlank { null } }
            ?.takeIf { it.isNotEmpty() }
            ?: artist.split('/', '、', ',', '&', '，')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .takeIf { it.isNotEmpty() }
        return MusicInfo(name = title.trim().ifBlank { null }, singer = singerNames)
    }

    /**
     * 旧版正则解析（kit 解析失败或无结果时的回退）。
     *
     * @param lyric 原始歌词数据
     * @return 解析后的歌词数据
     */
    private fun parseLegacy(lyric: Lyric): ParsedLyric {
        return try {
            // 解析主歌词
            val mainLines = parseLrc(lyric.lrc)

            // 解析翻译歌词
            val transLines = if (lyric.trans.isNotBlank()) {
                parseLrc(lyric.trans)
            } else {
                emptyMap()
            }

            // 解析音译歌词
            val romaLines = if (lyric.roma.isNotBlank()) {
                parseLrc(lyric.roma)
            } else {
                emptyMap()
            }

            // 解析 AI 谐音
            val phoneticLines = if (lyric.phonetic.isNotBlank()) {
                parseLrc(lyric.phonetic)
            } else {
                emptyMap()
            }

            // 解析逐字歌词
            val charLines = if (lyric.char.isNotBlank()) {
                parseCharLyric(lyric.char)
            } else {
                emptyMap()
            }

            // 解析逐字音译
            val chromaLines = if (lyric.chroma.isNotBlank()) {
                parseCharLyric(lyric.chroma)
            } else {
                emptyMap()
            }

            // 合并所有歌词信息
            val lines = if (mainLines.isNotEmpty()) {
                mainLines.map { (timestamp, text) ->
                    val charResult = findMatchingCharLine(charLines, timestamp)
                    val chromaResult = findMatchingCharLine(chromaLines, timestamp)
                    LyricLine(
                        timestamp = timestamp,
                        text = text,
                        translation = findMatchingLine(transLines, timestamp),
                        romanization = findMatchingLine(romaLines, timestamp),
                        phonetic = findMatchingLine(phoneticLines, timestamp),
                        characters = charResult?.characters,
                        charRomanization = chromaResult?.characters,
                        endTimestamp = charResult?.endTimestamp
                    )
                }.sortedBy { it.timestamp }
            } else if (lyric.lrc.isNotBlank()) {
                // 纯文本歌词（无时间戳），每行赋 timestamp=0 使 UI 能展示
                lyric.lrc.lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { LyricLine(timestamp = 0L, text = it) }
            } else {
                emptyList()
            }

            ParsedLyric(
                lines = lines,
                hasTranslation = lines.any { it.hasTranslation },
                hasRomanization = lines.any { it.hasRomanization },
                hasCharLyrics = lines.any { it.hasCharLyrics },
                isSynced = mainLines.isNotEmpty()
            )
        } catch (e: Exception) {
            // 解析失败返回空歌词
            ParsedLyric.EMPTY
        }
    }

    /**
     * 解析标准 LRC 格式歌词
     * 
     * 格式: [mm:ss.xx]text 或 [mm:ss.xxx]text
     * 
     * @param lrc LRC 格式歌词字符串
     * @return 时间戳到歌词文本的映射
     * 
     * **Validates: Requirements 4.1**
     */
    fun parseLrc(lrc: String): Map<Long, String> {
        if (lrc.isBlank()) return emptyMap()

        val result = TreeMap<Long, String>()

        lrc.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // 跳过 Metadata 标签
            if (PATTERN_METADATA.matcher(trimmedLine).matches()) {
                return@forEach
            }

            // 提取行首所有连续时间标签和剩余文本
            val timestamps = mutableListOf<Long>()
            val multiMatcher = PATTERN_MULTI_TIME.matcher(trimmedLine)
            var lastMatchEnd = 0

            while (multiMatcher.find()) {
                if (multiMatcher.start() != lastMatchEnd) break
                val min = multiMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = multiMatcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = multiMatcher.group(3) ?: "0"
                val ms = normalizeMilliseconds(msStr)
                timestamps.add(min * 60000 + sec * 1000 + ms)
                lastMatchEnd = multiMatcher.end()
            }

            if (timestamps.isEmpty()) return@forEach

            val text = trimmedLine.substring(lastMatchEnd).trim()
            if (text.isNotEmpty()) {
                for (timestamp in timestamps) {
                    result[timestamp] = text
                }
            }
        }

        return result
    }

    /**
     * 解析逐字歌词
     *
     * 格式: [mm:ss.xxx]<mm:ss.xxx>char1<mm:ss.xxx>char2...[mm:ss.xxx]
     * 尾部 [mm:ss.xxx] 为行结束时间戳 (SPL 标准，可选)
     *
     * @param charLrc 逐字歌词字符串
     * @return 时间戳到 CharLineResult 的映射
     */
    private fun parseCharLyric(charLrc: String): Map<Long, CharLineResult> {
        if (charLrc.isBlank()) return emptyMap()

        val result = TreeMap<Long, CharLineResult>()

        charLrc.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEach

            // 跳过 Metadata 标签
            if (PATTERN_METADATA.matcher(trimmedLine).matches()) {
                return@forEach
            }

            // 提取行首所有连续时间标签
            val timestamps = mutableListOf<Long>()
            val multiMatcher = PATTERN_MULTI_TIME.matcher(trimmedLine)
            var lastMatchEnd = 0

            while (multiMatcher.find()) {
                if (multiMatcher.start() != lastMatchEnd) break
                val min = multiMatcher.group(1)?.toLongOrNull() ?: 0L
                val sec = multiMatcher.group(2)?.toLongOrNull() ?: 0L
                val msStr = multiMatcher.group(3) ?: "0"
                val ms = normalizeMilliseconds(msStr)
                timestamps.add(min * 60000 + sec * 1000 + ms)
                lastMatchEnd = multiMatcher.end()
            }

            if (timestamps.isEmpty()) return@forEach

            val content = trimmedLine.substring(lastMatchEnd)
            if (content.isEmpty()) return@forEach

            // 提取并剥离尾部行结束时间戳 [mm:ss.xxx]
            val trailingMatcher = PATTERN_TRAILING_TIME.matcher(content)
            var endTimestamp: Long? = null
            var charContent = content
            if (trailingMatcher.find()) {
                val tMin = trailingMatcher.group(1)?.toLongOrNull() ?: 0L
                val tSec = trailingMatcher.group(2)?.toLongOrNull() ?: 0L
                val tMsStr = trailingMatcher.group(3) ?: "0"
                endTimestamp = tMin * 60000 + tSec * 1000 + normalizeMilliseconds(tMsStr)
                charContent = content.substring(0, trailingMatcher.start())
            }

            for (lineTimestamp in timestamps) {
                val characters = parseCharacters(charContent, lineTimestamp, endTimestamp)
                if (characters.isNotEmpty()) {
                    result[lineTimestamp] = CharLineResult(characters, endTimestamp)
                }
            }
        }

        return result
    }

    /**
     * 解析行内的逐字字符
     * 
     * @param content 行内容（不含行时间标签）
     * @param lineTimestamp 行开始时间
     * @return 字符列表
     */
    private fun parseCharacters(
        content: String,
        lineTimestamp: Long,
        lineEndTimestamp: Long? = null
    ): List<LyricChar> {
        val characters = mutableListOf<LyricChar>()
        val matcher = PATTERN_CHAR_TAG.matcher(content)

        lineTimestamp
        val timestamps = mutableListOf<Long>()
        val positions = mutableListOf<Int>()

        // 收集所有时间标签和位置
        while (matcher.find()) {
            val min = matcher.group(1)?.toLongOrNull() ?: 0L
            val sec = matcher.group(2)?.toLongOrNull() ?: 0L
            val msStr = matcher.group(3) ?: "0"
            val ms = normalizeMilliseconds(msStr)
            val timestamp = min * 60000 + sec * 1000 + ms

            timestamps.add(timestamp)
            positions.add(matcher.start())
        }

        if (timestamps.isEmpty()) {
            // 没有逐字标签，返回空列表
            return emptyList()
        }

        // 解析每个字符
        for (i in timestamps.indices) {
            val startTime = timestamps[i]
            val tagStart = positions[i]

            // 计算字符文本的起始位置（标签结束后）
            val tagMatcher = PATTERN_CHAR_TAG.matcher(content.substring(tagStart))
            if (!tagMatcher.find()) continue

            val textStart = tagStart + tagMatcher.end()

            // 计算字符文本的结束位置（下一个标签开始前或行尾）
            val textEnd = if (i < positions.size - 1) {
                positions[i + 1]
            } else {
                content.length
            }

            // 提取字符文本
            val charText = if (textStart < textEnd) {
                content.substring(textStart, textEnd)
            } else {
                ""
            }

            // 计算结束时间
            val endTime = if (i < timestamps.size - 1) {
                timestamps[i + 1]
            } else if (lineEndTimestamp != null && lineEndTimestamp > startTime) {
                lineEndTimestamp
            } else {
                // 最后一个字符，估算持续时间
                startTime + estimateCharDuration(charText)
            }

            if (charText.isNotEmpty()) {
                characters.add(
                    LyricChar(
                        char = charText,
                        startTime = startTime,
                        endTime = endTime,
                        stress = endTime - startTime >= STRESS_MARK_CHECK_TIME_MS
                    )
                )
            }
        }

        return characters
    }

    /**
     * 标准化毫秒值
     * 处理 2 位或 3 位毫秒格式
     */
    private fun normalizeMilliseconds(msStr: String): Long {
        return when (msStr.length) {
            2 -> msStr.toLongOrNull()?.times(10) ?: 0L  // xx -> xxx0
            3 -> msStr.toLongOrNull() ?: 0L             // xxx
            else -> msStr.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        }
    }

    /**
     * 估算字符持续时间
     * 基于字符类型给出合理的默认持续时间
     */
    private fun estimateCharDuration(char: String): Long {
        return when {
            char.isBlank() -> 100L
            char.length == 1 && char[0].isLetterOrDigit() -> 200L
            else -> 300L
        }
    }

    /**
     * 在映射中查找匹配的行
     * 允许 100ms 的时间误差
     */
    private fun findMatchingLine(map: Map<Long, String>, timestamp: Long): String? {
        if (map.isEmpty()) return null

        // 精确匹配
        map[timestamp]?.let { return it }

        // 模糊匹配（100ms 误差范围）
        val tolerance = 100L
        return map.entries.find { abs(it.key - timestamp) <= tolerance }?.value
    }

    /**
     * 在逐字歌词映射中查找匹配的行，和普通翻译/音译保持相同的时间容错。
     */
    private fun findMatchingCharLine(
        map: Map<Long, CharLineResult>,
        timestamp: Long
    ): CharLineResult? {
        if (map.isEmpty()) return null

        map[timestamp]?.let { return it }

        val tolerance = 100L
        return map.entries.find { abs(it.key - timestamp) <= tolerance }?.value
    }

    /**
     * 格式化时间戳为 LRC 格式字符串
     * 
     * @param timeMs 时间戳（毫秒）
     * @return 格式化的时间字符串 mm:ss.xxx
     */
    fun formatTime(timeMs: Long): String {
        val min = timeMs / 60000
        val sec = (timeMs % 60000) / 1000
        val ms = timeMs % 1000
        return String.format("%02d:%02d.%03d", min, sec, ms)
    }

    /**
     * 将 ParsedLyric 转换回 LRC 格式字符串
     * 
     * @param parsedLyric 解析后的歌词
     * @param includeTranslation 是否包含翻译
     * @param includeRomanization 是否包含音译
     * @return LRC 格式字符串
     */
    fun toLrc(
        parsedLyric: ParsedLyric,
        includeTranslation: Boolean = false,
        includeRomanization: Boolean = false
    ): String {
        val sb = StringBuilder()

        for (line in parsedLyric.lines) {
            val timeStr = formatTime(line.timestamp)
            sb.append("[$timeStr]${line.text}\n")

            if (includeTranslation && line.translation != null) {
                sb.append("[$timeStr]${line.translation}\n")
            }

            if (includeRomanization && line.romanization != null) {
                sb.append("[$timeStr]${line.romanization}\n")
            }
        }

        return sb.toString().trim()
    }
}
