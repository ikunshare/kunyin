package com.ikunshare.sound.model

/**
 * 逐字歌词中的单个字符信息
 * 
 * @property char 字符内容
 * @property startTime 字符开始时间（毫秒）
 * @property endTime 字符结束时间（毫秒）
 * @property stress 是否为重音/拖长音节
 */
data class LyricChar(
    val char: String,
    val startTime: Long,
    val endTime: Long,
    val stress: Boolean = false
) {
    /**
     * 字符持续时间（毫秒）
     */
    val duration: Long
        get() = endTime - startTime

    /**
     * 检查给定时间是否在此字符的播放时间范围内
     */
    fun isActiveAt(position: Long): Boolean {
        return position in startTime..<endTime
    }
}

/**
 * 对唱声部：主唱（左对齐）/ 对唱（右对齐）。
 */
enum class DuetSide {
    LEFT,
    RIGHT
}

/**
 * 歌词行类型：普通行 / 间奏行。
 */
enum class LyricLineType {
    NORMAL,
    INTERLUDE
}

/**
 * 背景/和声行（紧贴主歌词下方，字号更小）。
 *
 * @property text 背景行文本
 * @property timestamp 背景行开始时间（毫秒）
 * @property endTimestamp 背景行结束时间（毫秒）
 * @property characters 逐字歌词字符列表（可选，用于卡拉OK效果）
 */
data class LyricBackground(
    val text: String,
    val timestamp: Long,
    val endTimestamp: Long,
    val characters: List<LyricChar>? = null
) {
    /**
     * 是否有逐字歌词
     */
    val hasCharLyrics: Boolean
        get() = !characters.isNullOrEmpty()
}

/**
 * 歌词行信息
 * 
 * @property timestamp 行开始时间（毫秒）
 * @property text 歌词文本
 * @property translation 翻译文本（可选）
 * @property romanization 音译/罗马音文本（可选）
 * @property characters 逐字歌词字符列表（可选，用于卡拉OK效果）
 * @property duetSide 对唱声部（左/右对齐）
 * @property backgrounds 背景/和声行列表
 * @property type 行类型（普通/间奏）
 */
data class LyricLine(
    val timestamp: Long,
    val text: String,
    val translation: String? = null,
    val romanization: String? = null,
    val phonetic: String? = null,
    val characters: List<LyricChar>? = null,
    val charRomanization: List<LyricChar>? = null,
    val endTimestamp: Long? = null,
    val duetSide: DuetSide = DuetSide.LEFT,
    val backgrounds: List<LyricBackground> = emptyList(),
    val type: LyricLineType = LyricLineType.NORMAL
) {
    /**
     * 是否有逐字歌词
     */
    val hasCharLyrics: Boolean
        get() = !characters.isNullOrEmpty()

    /**
     * 是否有翻译
     */
    val hasTranslation: Boolean
        get() = !translation.isNullOrBlank()

    /**
     * 是否有音译
     */
    val hasRomanization: Boolean
        get() = !romanization.isNullOrBlank() || !charRomanization.isNullOrEmpty()

    /**
     * 是否有逐字音译
     */
    val hasCharRomanization: Boolean
        get() = !charRomanization.isNullOrEmpty()

    /**
     * 是否为间奏行
     */
    val isInterlude: Boolean
        get() = type == LyricLineType.INTERLUDE

    /**
     * 获取行结束时间（基于逐字歌词或估算）
     */
    fun getEndTime(nextLineTimestamp: Long? = null): Long {
        return when {
            endTimestamp != null -> endTimestamp
            !characters.isNullOrEmpty() -> characters.last().endTime
            nextLineTimestamp != null -> nextLineTimestamp
            else -> timestamp + 5000 // 默认 5 秒
        }
    }

    /**
     * 获取在给定播放位置应该高亮的字符索引
     * 返回 -1 表示没有字符应该高亮
     */
    fun getActiveCharIndex(position: Long): Int {
        if (characters.isNullOrEmpty()) return -1
        return characters.indexOfFirst { it.isActiveAt(position) }
    }
}

/**
 * 解析后的完整歌词信息
 * 
 * @property lines 歌词行列表（按时间戳排序）
 * @property hasTranslation 是否包含翻译歌词
 * @property hasRomanization 是否包含音译歌词
 * @property hasCharLyrics 是否包含逐字歌词
 */
data class ParsedLyric(
    val lines: List<LyricLine>,
    val hasTranslation: Boolean,
    val hasRomanization: Boolean,
    val hasCharLyrics: Boolean,
    val isSynced: Boolean = true
) {
    /**
     * 是否为空歌词
     */
    val isEmpty: Boolean
        get() = lines.isEmpty()

    /**
     * 歌词行数
     */
    val lineCount: Int
        get() = lines.size

    /**
     * 根据播放位置查找当前应该高亮的歌词行索引
     * 返回 timestamp <= position 的最大 timestamp 对应的行索引
     * 如果没有找到，返回 -1
     * 
     * **Validates: Requirements 4.1, 4.2**
     */
    fun findCurrentLineIndex(position: Long): Int {
        if (lines.isEmpty()) return -1

        // 二分查找最后一个 timestamp <= position 的行
        var low = 0
        var high = lines.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) / 2
            if (lines[mid].timestamp <= position) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        // 逐字歌词的首字符 startTime 可能早于行 timestamp，
        // 当播放位置已到达下一行首字符时提前切换
        val nextIndex = result + 1
        if (nextIndex in lines.indices) {
            val nextCharStart = lines[nextIndex].characters?.firstOrNull()?.startTime
            if (nextCharStart != null && position >= nextCharStart) {
                return nextIndex
            }
        }

        return result
    }

    /**
     * 获取当前播放位置对应的歌词行
     */
    fun getCurrentLine(position: Long): LyricLine? {
        val index = findCurrentLineIndex(position)
        return if (index >= 0) lines[index] else null
    }

    companion object {
        /**
         * 空歌词实例
         */
        val EMPTY = ParsedLyric(
            lines = emptyList(),
            hasTranslation = false,
            hasRomanization = false,
            hasCharLyrics = false
        )
    }
}
