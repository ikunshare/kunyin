package com.ikunshare.music.lyric.kit

/** Desktop-compatible source cleanup. Empty timed rows retain their alignment slots. */
internal object LyricTextRules {
    private val tags = Regex("^\\s*((?:\\[[^\\]]+\\])*)(.*)$")
    private val wordTags = Regex("<[^>]+>")
    private val whitespace = Regex("\\s+")
    private val noise = Regex(
        "音译标注|音譯標註|由\\s*AI\\s*工具|AI\\s*工具生产|AI\\s*工具生產|AI\\s*生成|以下.+AI",
        RegexOption.IGNORE_CASE
    )
    /** 制作人/职务行，仅用于副轨；主轨交给 kit 的 pureExtractCreator 提取到 credits。 */
    private val credit = Regex(
        "作[词詞曲]\\s*[:：]|作曲\\s*[:：]|编曲\\s*[:：]|編曲\\s*[:：]|填[词詞]\\s*[:：]|谱曲\\s*[:：]|譜曲\\s*[:：]|演唱\\s*[:：]|制作人?\\s*[:：]|製作人?\\s*[:：]|监制\\s*[:：]|監制\\s*[:：]|词曲\\s*[:：]|詞曲\\s*[:：]|lyricist\\s*[:：]|composer\\s*[:：]|arranger\\s*[:：]|additional\\s+vocals?\\s+by\\s*[:：]|repertoire\\s+owner\\s*[:：]",
        RegexOption.IGNORE_CASE
    )
    /** 中文职务后直接拼英文职务是 QQ/酷狗歌词常见格式，例如「和声Backing Vocals：…」。 */
    private val creditPrefix = Regex(
        "^(?:作[词詞曲]|曲|词|詞|编曲|編曲|填[词詞]|谱曲|譜曲|演唱|制作人?|製作人?|监制|監制|词曲|詞曲|吉他|混音师|混音師|母带后期混音师|母帶後期混音師|和声编写|和聲編寫|和声|和聲|配唱制作人|配唱製作人|录音师|錄音師|人声编辑|人聲編輯|视觉设计|視覺設計|艺人统筹|藝人統籌|推广策划|推廣策劃|特别合作|特別合作|混音棚|录音棚|錄音棚|企划营销|企劃營銷|制作公司|製作公司|蒙古长调|蒙古長調|OP|SP)(?:\\s*[A-Za-z][A-Za-z0-9 .&/'’()_-]*)?\\s*[:：]",
        RegexOption.IGNORE_CASE
    )
    /** QQ trans 用 `//` 表示该行没有翻译。 */
    private val placeholder = Regex("^[/\\\\]+$")
    private val timeTag = Regex("^\\s*\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")

    fun plainBody(text: String): String = whitespace.replace(wordTags.replace(text, ""), " ").trim()

    /**
     * 主轨只剔除噪音行；副轨（[annotation]）还剔除制作人行和占位行，
     * 清洗后正文全空的副轨整体丢弃，避免每行留出空注解位。
     */
    fun clean(text: String, annotation: Boolean = false): String {
        if (text.isBlank()) return ""
        val cleaned = text.lineSequence().map { line ->
            val match = tags.matchEntire(line) ?: return@map line
            val prefix = match.groupValues[1]
            val body = plainBody(match.groupValues[2])
            if (body.isEmpty()) line
            else if (noise.containsMatchIn(body) || (annotation && (credit.containsMatchIn(body) ||
                    creditPrefix.containsMatchIn(body) || (prefix.isNotEmpty() && placeholder.matches(body))))) {
                prefix
            } else line
        }.joinToString("\n")
        return if (annotation && cleaned.lineSequence().all { plainBody(tags.matchEntire(it)?.groupValues?.get(2) ?: it).isEmpty() }) ""
        else cleaned
    }

    fun stripWordTags(text: String): String = wordTags.replace(text, "")

    /**
     * Align before extracting credits, removing noise or inserting interludes.
     *
     * 副轨改写成主轨行的「实际起始时间」：kit 解析逐字行时以首个有字的词为行起点，
     * 再按 0 容差挂翻译/音译。QRC 常见行标签早于首字（如 `[01:28.393]<01:28.526>Proditi`），
     * 照抄行标签会让这一行的翻译挂不上。
     */
    fun align(original: String, annotation: String): String {
        if (annotation.isBlank()) return ""
        val main = original.lines().mapNotNull { line ->
            val tag = timeTag.find(line) ?: return@mapNotNull null
            val start = millis(tag)
            start to (firstWordStart(line.substring(tag.range.last + 1), start) ?: start)
        }
        if (main.isEmpty()) return annotation
        val auxiliary = annotation.lines()
        val timedCount = auxiliary.count { timeTag.containsMatchIn(it) }
        var index = 0
        return auxiliary.joinToString("\n") { line ->
            val tag = timeTag.find(line) ?: return@joinToString line
            val aligned = if (main.size == timedCount) main[index++] else {
                val start = millis(tag)
                main.minByOrNull { kotlin.math.abs(it.first - start) }
                    ?.takeIf { kotlin.math.abs(it.first - start) <= 50L }
            }
            if (aligned == null) line else formatTag(aligned.second) + line.substring(tag.range.last + 1)
        }
    }

    /** 首个有字词的起始时间；支持绝对 `<mm:ss.xxx>` 与相对行首的 `<offset,duration>`。 */
    private fun firstWordStart(body: String, lineStart: Long): Long? {
        val word = wordTimed.findAll(body).firstOrNull { it.groupValues[2].isNotBlank() } ?: return null
        val tag = word.groupValues[1]
        timeTag.find("[$tag]")?.let { return millis(it) }
        return relativeTag.matchEntire(tag)?.let { lineStart + it.groupValues[1].toLong() }
    }

    private val wordTimed = Regex("<([^>]+)>([^<]*)")
    private val relativeTag = Regex("(\\d+),-?\\d+(?:,-?\\d+)?")

    private fun formatTag(ms: Long): String =
        String.format(java.util.Locale.ROOT, "[%02d:%02d.%03d]", ms / 60_000L, ms / 1000L % 60L, ms % 1000L)

    private fun millis(match: MatchResult): Long {
        val fraction = match.groupValues[3]
        return match.groupValues[1].toLong() * 60_000L + match.groupValues[2].toLong() * 1000L +
            fraction.padEnd(3, '0').ifBlank { "0" }.toLong()
    }
}
