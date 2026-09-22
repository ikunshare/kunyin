package com.ikunshare.sound.lyric.kit.transform.pure

import kotlin.math.ceil
import kotlin.math.max

/** 匹配选项，对应 transform-pure 的 utils/match.ts 的 MatchOptions。 */
data class MatchExact(val check: Int = 50)
data class MatchRuleConfig(val useDefault: Boolean = true, val custom: List<Any> = emptyList())
data class MatchOptions(
    val mode: String = "fuzzy",
    val exact: MatchExact = MatchExact(),
    val rule: MatchRuleConfig = MatchRuleConfig(),
)

/** 规则匹配引擎，MatchRule = String | Regex。 */
class Matcher(private var options: MatchOptions, private val defaultRules: List<Any>) {
    private val stringRules = ArrayList<String>()
    private val regexRules = ArrayList<Regex>()
    private var combinedRegex: Regex? = null

    private val escapeRegex = Regex("[.*+?^${'$'}{}()|\\[\\]\\\\]")

    init {
        init()
    }

    private fun init() {
        stringRules.clear()
        regexRules.clear()
        combinedRegex = null
        val raw = if (options.rule.useDefault) defaultRules + options.rule.custom else options.rule.custom.toList()
        for (rule in raw) {
            when (rule) {
                is String -> stringRules.add(rule.lowercase())
                is Regex -> regexRules.add(rule)
            }
        }
        if (options.mode == "fuzzy" && stringRules.isNotEmpty()) {
            stringRules.sortByDescending { it.length }
            val escaped = stringRules.map { escapeRegex.replace(it) { m -> "\\" + m.value } }
            combinedRegex = Regex("(" + escaped.joinToString("|") + ")", RegexOption.IGNORE_CASE)
        }
    }

    fun update(options: MatchOptions) {
        this.options = options
        init()
    }

    fun match(line: String?, extra: List<String>? = null): Boolean {
        if (line.isNullOrEmpty()) return false
        val targetLine = line.trim().lowercase()
        if (targetLine.isEmpty()) return false
        return if (options.mode == "fuzzy") withFuzzy(targetLine, extra) else withExact(targetLine, extra)
    }

    private fun withFuzzy(line: String, extra: List<String>?): Boolean {
        if (combinedRegex?.containsMatchIn(line) == true) return true
        if (regexRules.any { it.containsMatchIn(line) }) return true
        if (extra != null && extra.any { it.isNotEmpty() && line.contains(it) }) return true
        return false
    }

    private fun withExact(line: String, extra: List<String>?): Boolean {
        val thresholdPercent = max(options.exact.check, 0)
        val targetMatchCount = ceil(thresholdPercent / 100.0 * line.length).toInt()
        if (targetMatchCount == 0) return true

        val matchedChars = BooleanArray(line.length)
        var matchedCount = 0

        val allStringRules = stringRules + (extra ?: emptyList())
        for (rule in allStringRules) {
            if (rule.isEmpty()) continue
            var pos = 0
            while (true) {
                val idx = line.indexOf(rule, pos)
                if (idx == -1) break
                for (k in idx until idx + rule.length) {
                    if (!matchedChars[k]) {
                        matchedChars[k] = true
                        matchedCount++
                    }
                }
                if (matchedCount >= targetMatchCount) return true
                pos = idx + rule.length
            }
        }

        for (regex in regexRules) {
            val m = regex.find(line) ?: continue
            for (k in m.range) {
                if (!matchedChars[k]) {
                    matchedChars[k] = true
                    matchedCount++
                }
            }
            if (matchedCount >= targetMatchCount) return true
        }
        return false
    }
}
