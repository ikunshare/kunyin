package com.ikunshare.sound.lyric.kit.model

/**
 * 对应 music-lyric-model 的 `Lyric.Common` 辅助函数。
 */
object LyricCommon {
    fun makeTime(start: Int = 0, end: Int = 0): Time = Time(start, end)

    fun getTimeDuration(time: Time?): Int = if (time != null) time.end - time.start else 0

    fun getTimeProgress(time: Time?, ms: Int): Double {
        val duration = getTimeDuration(time)
        if (time == null || duration <= 0) return 0.0
        if (ms <= time.start) return 0.0
        if (ms >= time.end) return 1.0
        return (ms - time.start).toDouble() / duration
    }

    fun isTimeActive(time: Time?, ms: Int): Boolean =
        if (time != null) ms >= time.start && ms < time.end else false
}

/**
 * 对应 music-lyric-model 的 `Lyric.Runtime` 命名空间：模型构造器 + 访问器 + 派生函数。
 * 只移植管线/适配器实际用到的子集。
 */
object Runtime {

    // region 构造器

    fun makeInfo(type: InfoType = InfoType.UNSPECIFIED, timing: Timing = Timing.UNSPECIFIED): Info =
        Info(version = SCHEMA_VERSION, type = type, timing = timing)

    fun makeLineContent(): LineContent = LineContent()

    fun makeLineAgent(id: String): LineAgent = LineAgent(id)

    fun makeLineNormal(content: LineContent? = null, time: Time? = null): LineNormal {
        val line = LineNormal(content = content)
        line.time = time
        return line
    }

    fun makeLineInterlude(time: Time? = null): LineInterlude {
        val line = LineInterlude()
        line.time = time
        return line
    }

    fun makeLineBackground(time: Time? = null, content: LineContent? = null): LineBackground {
        val bg = LineBackground(content = content)
        bg.time = time
        return bg
    }

    fun makeLineAnnotation(): LineAnnotation = LineAnnotation()

    fun makeLineAnnotationUnknown(key: String, value: String, derived: Boolean = false): LineAnnotationUnknown =
        LineAnnotationUnknown(derived = derived, key = key, value = value)

    fun makeLineAnnotationRoman(content: String, language: String? = null, derived: Boolean = false): LineAnnotationRoman =
        LineAnnotationRoman(derived = derived, language = language, content = content)

    fun makeLineAnnotationTranslate(content: String, language: String? = null, derived: Boolean = false): LineAnnotationTranslate =
        LineAnnotationTranslate(derived = derived, language = language, content = content)

    fun makeWordNormal(
        content: String,
        time: Time? = null,
        language: String? = null,
        annotation: WordAnnotation? = null,
        stress: Boolean = false,
    ): WordNormal = WordNormal(content = content, time = time, language = language, annotation = annotation, stress = stress)

    fun makeWordSpace(count: Int): WordSpace = WordSpace(count)

    fun makeWordAnnotation(ruby: WordAnnotationRuby? = null): WordAnnotation = WordAnnotation(ruby = ruby)

    fun makeWordAnnotationContent(content: String, time: Time? = null): WordAnnotationContent =
        WordAnnotationContent(time = time, content = content)

    fun makeWordAnnotationRoman(words: MutableList<WordAnnotationContent>, time: Time? = null, language: String? = null): WordAnnotationRoman =
        WordAnnotationRoman(time = time, language = language, words = words)

    fun makeWordAnnotationRuby(
        words: MutableList<WordAnnotationContent>,
        time: Time? = null,
        phraseStart: Boolean = false,
        language: String? = null,
    ): WordAnnotationRuby = WordAnnotationRuby(time = time, language = language, words = words, phraseStart = phraseStart)

    fun makeMeta(): Meta = Meta()

    fun makeMetaText(content: String, language: String? = null): MetaText = MetaText(language = language, content = content)

    fun makeMetaCredit(role: String, names: MutableList<MetaText>): MetaCredit = MetaCredit(role = role, names = names)

    fun makeMetaUnknown(key: String, value: String): MetaUnknown = MetaUnknown(key = key, value = value)

    fun makeAgentItem(id: String, type: AgentType = AgentType.UNSPECIFIED, names: MutableList<String> = mutableListOf()): AgentItem =
        AgentItem(id = id, type = type, names = names)

    fun makeLanguageItem(tag: String, percent: Double): LanguageItem = LanguageItem(tag = tag, percent = percent)

    fun makePart(type: PartType, label: String? = null): Part = Part(type = type, label = label)

    // endregion

    // region 类型守卫

    fun isLineNormal(line: Line): Boolean = line is LineNormal
    fun isLineInterlude(line: Line): Boolean = line is LineInterlude
    fun isWordNormal(word: Word): Boolean = word is WordNormal
    fun isWordSpace(word: Word): Boolean = word is WordSpace

    // endregion

    // region 词访问器

    fun getWordText(word: Word): String = when (word) {
        is WordNormal -> word.content
        is WordSpace -> " ".repeat(word.count)
    }

    fun getWordTime(word: Word): Time? = (word as? WordNormal)?.time

    fun getWordDuration(word: Word): Int = LyricCommon.getTimeDuration(getWordTime(word))

    fun getWordsText(words: List<Word>): String {
        val sb = StringBuilder()
        for (w in words) sb.append(getWordText(w))
        return sb.toString()
    }

    fun getWordAnnotationText(item: WordAnnotationRoman): String {
        val sb = StringBuilder()
        for (w in item.words) sb.append(w.content)
        return sb.toString()
    }

    fun getWordsLanguages(words: List<Word>): List<String> {
        val set = LinkedHashSet<String>()
        for (w in words) {
            if (w is WordNormal) w.language?.let { set.add(it) }
        }
        return set.toList()
    }

    // endregion

    // region 行访问器

    fun getLineTime(line: Line): Time? = line.time

    fun getLineDuration(line: Line): Int = LyricCommon.getTimeDuration(getLineTime(line))

    fun getLineContent(line: Line): LineContent? = (line as? LineNormal)?.content

    fun getLineWords(line: Line): List<Word> = getLineContent(line)?.words ?: emptyList()

    fun getLineText(line: Line): String = getWordsText(getLineWords(line))

    fun getLineLanguages(line: Line): List<String> {
        val content = getLineContent(line) ?: return emptyList()
        return if (content.languages.isNotEmpty()) content.languages else getWordsLanguages(content.words)
    }

    fun getLineAnnotation(line: Line): LineAnnotation? = getLineContent(line)?.annotation

    fun <T> getFirstAnnotation(items: List<T>, language: String?, langOf: (T) -> String?): T? {
        if (language != null) {
            val found = items.firstOrNull { langOf(it) == language }
            if (found != null) return found
        }
        return items.firstOrNull()
    }

    fun getLineTranslate(line: Line, language: String? = null): String? {
        val annotation = getLineAnnotation(line) ?: return null
        return getFirstAnnotation(annotation.translates, language) { it.language }?.content
    }

    fun getLineRoman(line: Line, language: String? = null): String? {
        val annotation = getLineAnnotation(line) ?: return null
        return getFirstAnnotation(annotation.romans, language) { it.language }?.content
    }

    fun getActiveLineIndex(lines: List<Line>, ms: Int): Int {
        for (i in lines.indices) if (LyricCommon.isTimeActive(getLineTime(lines[i]), ms)) return i
        return -1
    }

    // endregion

    // region 代理（Agent）

    fun getAgentById(agents: List<AgentItem>, id: String): AgentItem? = agents.firstOrNull { it.id == id }

    fun resolveLineAgent(line: Line, agents: List<AgentItem>): AgentItem? {
        if (line !is LineNormal) return null
        val agent = line.content?.agent ?: return null
        return getAgentById(agents, agent.id)
    }

    fun getAgentLineCounts(lines: List<Line>): Map<String, Int> {
        val map = LinkedHashMap<String, Int>()
        for (line in lines) {
            if (line !is LineNormal) continue
            val agent = line.content?.agent ?: continue
            map[agent.id] = (map[agent.id] ?: 0) + 1
        }
        return map
    }

    fun getPrimaryAgent(info: Info): AgentItem? {
        val counts = getAgentLineCounts(info.lines)
        var result: AgentItem? = null
        var max = -1
        for (agent in info.agents) {
            val count = counts[agent.id] ?: 0
            if (count > max) {
                max = count
                result = agent
            }
        }
        return result
    }

    // endregion

    // region 排序 & 派生注解

    /** 按 time.start 升序排序所有行及其背景行。 */
    fun sortLinesByTime(info: Info) {
        info.lines.sortBy { getLineTime(it)?.start ?: 0 }
        for (line in info.lines) {
            if (line is LineNormal) {
                line.backgrounds.sortBy { it.time?.start ?: 0 }
            }
        }
    }

    /**
     * 泛型聚合：把行内各词的注解按 key 分组聚合成行级注解。对应原库的 `O` 函数。
     * 词之间根据累计的 WordSpace 数量插入相应数量的空格。
     */
    private fun <I, R> deriveGeneric(
        words: List<Word>,
        getItems: (WordNormal) -> List<I>?,
        getKey: (I) -> String,
        getText: (I) -> String,
        getLang: (I) -> String?,
        make: (key: String, content: String, lang: String?) -> R,
    ): MutableList<R> {
        // 1. 收集所有 normal 词注解 item 的有序去重 key
        val keys = ArrayList<String>()
        for (w in words) {
            if (w !is WordNormal) continue
            val items = getItems(w) ?: continue
            for (item in items) {
                val key = getKey(item)
                if (!keys.contains(key)) keys.add(key)
            }
        }

        val result = ArrayList<R>()
        for (key in keys) {
            val sb = StringBuilder()
            var spaceCount = 0
            var lang: String? = null
            var seen = false
            for (w in words) {
                if (w is WordSpace) {
                    spaceCount += w.count
                    continue
                }
                if (w !is WordNormal) continue
                val item = getItems(w)?.firstOrNull { getKey(it) == key }
                if (item != null) {
                    if (seen) sb.append(" ".repeat(spaceCount))
                    sb.append(getText(item))
                    if (lang == null) lang = getLang(item)
                    spaceCount = 0
                    seen = true
                }
            }
            if (seen) result.add(make(key, sb.toString(), lang))
        }
        return result
    }

    fun deriveLineRomans(words: List<Word>): MutableList<LineAnnotationRoman> = deriveGeneric(
        words,
        getItems = { it.annotation?.romans },
        getKey = { it.language ?: "" },
        getText = { getWordAnnotationText(it) },
        getLang = { it.language },
        make = { _, content, lang -> makeLineAnnotationRoman(content = content, language = lang, derived = true) },
    )

    fun deriveLineTranslates(words: List<Word>): MutableList<LineAnnotationTranslate> = deriveGeneric(
        words,
        getItems = { it.annotation?.translates },
        getKey = { it.language ?: "" },
        getText = { it.content },
        getLang = { it.language },
        make = { _, content, lang -> makeLineAnnotationTranslate(content = content, language = lang, derived = true) },
    )

    fun deriveLineUnknowns(words: List<Word>): MutableList<LineAnnotationUnknown> = deriveGeneric(
        words,
        getItems = { it.annotation?.unknowns },
        getKey = { it.key },
        getText = { it.value },
        getLang = { null },
        make = { key, value, _ -> makeLineAnnotationUnknown(key = key, value = value, derived = true) },
    )

    // endregion
}

/**
 * 刷新行级派生注解（romans、unknowns），保留显式设置项。对应 lyric 包的 refreshLineAnnotation。
 * 词级 ruby 无行级对应，故有意丢弃。
 */
fun refreshLineAnnotation(content: LineContent?) {
    if (content == null) return
    val annotation = content.annotation ?: Runtime.makeLineAnnotation().also { content.annotation = it }
    val words = content.words

    refreshDerivedRoman(annotation.romans) { Runtime.deriveLineRomans(words) }
    refreshDerivedUnknown(annotation.unknowns) { Runtime.deriveLineUnknowns(words) }
}

private fun refreshDerivedRoman(items: MutableList<LineAnnotationRoman>, derive: () -> List<LineAnnotationRoman>) {
    val kept = items.filter { !it.derived }
    items.clear()
    if (kept.isNotEmpty()) {
        items.addAll(kept)
    } else {
        items.addAll(derive())
    }
}

private fun refreshDerivedUnknown(items: MutableList<LineAnnotationUnknown>, derive: () -> List<LineAnnotationUnknown>) {
    val kept = items.filter { !it.derived }
    items.clear()
    if (kept.isNotEmpty()) {
        items.addAll(kept)
    } else {
        items.addAll(derive())
    }
}
