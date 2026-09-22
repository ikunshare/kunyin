package com.ikunshare.sound.lyric.kit.util

/**
 * 对应 utils 包的 string / time / number 辅助函数。
 */
object TextUtil {

    // region string

    private val SPACE_ALL = Regex("\\s+")
    private val SPACE_MULTI = Regex("[ ]{2,}")

    /** 移除所有空白后 trim。 */
    fun removeTextSpaceAll(content: String): String = content.replace(SPACE_ALL, "").trim()

    /** 将 2+ 个连续 ASCII 空格折叠为一个（不影响 tab/换行），然后 trim。 */
    fun removeTextSpaceToOne(content: String): String = content.replace(SPACE_MULTI, " ").trim()

    fun checkTextIsValid(content: Any?): Boolean = content is String && content.trim().isNotEmpty()

    /**
     * 按规则列表替换文本。string 规则按默认 flags（忽略大小写 + 全局）编译，Regex 规则原样使用。
     */
    fun replaceTextWithRule(
        text: String,
        target: String,
        rules: List<Any>,
        ignoreCase: Boolean = true,
    ): String {
        var result = text
        for (rule in rules) {
            try {
                val regex = when (rule) {
                    is Regex -> rule
                    is String -> if (ignoreCase) Regex(rule, RegexOption.IGNORE_CASE) else Regex(rule)
                    else -> continue
                }
                result = regex.replace(result, target)
            } catch (_: Exception) {
                continue
            }
        }
        return result
    }

    // endregion

    // region time

    private val TIME_REGEXP = Regex("^(?:(?:(\\d+):)?(\\d+):)?(\\d+)(?:\\.(\\d+))?$")
    private val DIGITS_REGEXP = Regex("^\\d+$")

    /** 右侧补 0 到至少 3 位，再取前 3 位。 */
    private fun parseMilliSecond(content: String): String {
        val padded = content.padEnd(3, '0')
        return padded.substring(0, 3)
    }

    /**
     * 解析时间字符串为毫秒，失败返回 null。支持 hh:mm:ss(.SSS) / mm:ss(.SSS) / ss.SSS / SSS / .SSS。
     * 注意：纯整数视为毫秒；单冒号视为 分:秒。
     */
    fun parseTime(content: String?): Int? {
        val trimmed = content?.trim()
        if (trimmed.isNullOrEmpty()) return null

        if (trimmed.startsWith(".")) {
            val value = trimmed.substring(1)
            if (!DIGITS_REGEXP.matches(value)) return null
            return parseMilliSecond(value).toIntOrNull()
        }

        if (DIGITS_REGEXP.matches(trimmed)) return trimmed.toIntOrNull()

        val match = TIME_REGEXP.matchEntire(trimmed) ?: return null
        val g = match.groupValues
        val hour = g[1].toIntOrNull() ?: 0
        val minute = g[2].toIntOrNull() ?: 0
        val second = g[3].toIntOrNull() ?: 0
        val ms = parseMilliSecond(g[4].ifEmpty { "0" }).toIntOrNull() ?: 0
        return ((hour * 60 + minute) * 60 + second) * 1000 + ms
    }

    fun checkTime(content: String?): Boolean = parseTime(content) != null

    /**
     * 格式化毫秒为时间串，默认 mm:ss.SSS。支持 token：hh/h、mm/m、ss/s、SSS/SS/S。
     * 无 h token 时分钟吸收小时。负数/非法返回全 0。
     */
    fun formatTime(time: Long, format: String = "mm:ss.SSS"): String {
        if (time < 0) {
            return format
                .replace(Regex("h+"), "0")
                .replace(Regex("m+"), "0")
                .replace(Regex("s+"), "0")
                .replace(Regex("S+"), "0")
        }
        val totalSeconds = time / 1000
        val milliSeconds = (time % 1000).toInt()
        val hours = (totalSeconds / 3600).toInt()
        val minutes = ((totalSeconds % 3600) / 60).toInt()
        val seconds = (totalSeconds % 60).toInt()

        fun pad(num: Int, len: Int): String = num.toString().padStart(len, '0')

        var result = format

        // hours
        if (result.contains("hh")) result = result.replace(Regex("hh"), pad(hours, 2))
        else if (result.contains("h")) result = result.replace(Regex("h"), hours.toString())

        // minutes
        val totalMinutes = if (!format.contains("h")) hours * 60 + minutes else minutes
        if (result.contains("mm")) result = result.replace(Regex("mm"), pad(totalMinutes, 2))
        else if (result.contains("m")) result = result.replace(Regex("m"), totalMinutes.toString())

        // seconds
        if (result.contains("ss")) result = result.replace(Regex("ss"), pad(seconds, 2))
        else if (result.contains("s")) result = result.replace(Regex("s"), seconds.toString())

        // milliseconds
        if (result.contains("SSS")) result = result.replace(Regex("SSS"), pad(milliSeconds, 3))
        else if (result.contains("SS")) result = result.replace(Regex("SS"), pad(milliSeconds / 10, 2))
        else if (result.contains("S")) result = result.replace(Regex("S"), (milliSeconds / 100).toString())

        return result
    }

    // endregion
}

/**
 * 对应 utils/number/align.ts 的 alignNumberArray。
 */
object NumberAlign {
    data class Target(val value: Int, val diff: Int)
    data class Result(val base: Int, val targets: List<Target>)

    fun alignNumberArray(base: List<Int>, target: List<Int>, fuzzyThreshold: Int = 0): List<Result> {
        val result = ArrayList<Result>()
        var pending = target.toMutableList()
        for (baseValue in base) {
            val matched = ArrayList<Target>()
            for (v in pending) {
                val diff = kotlin.math.abs(v - baseValue)
                if (diff <= fuzzyThreshold) matched.add(Target(v, diff))
            }
            if (matched.isNotEmpty()) {
                val matchedSet = matched.map { it.value }.toHashSet()
                pending = pending.filter { !matchedSet.contains(it) }.toMutableList()
            }
            result.add(Result(baseValue, matched))
        }
        return result
    }
}
