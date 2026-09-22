package com.ikunshare.sound.lyric.kit.transform.pure

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.util.ConfigManager
import com.ikunshare.sound.lyric.kit.util.TextUtil

private val PUNCTUATION_REGEXP = Regex("[\\p{P}\\p{S}\\p{C}]")

/** 对应 clean/utils.ts 的 processText。 */
private fun processText(text: String?): List<String> {
    if (text == null || text.trim().isEmpty()) return emptyList()
    val processed = PUNCTUATION_REGEXP.replace(TextUtil.removeTextSpaceToOne(text), "").trim().lowercase()
    if (processed.isEmpty()) return emptyList()
    return processed.split(" ").map { it.trim() }.filter { it.isNotEmpty() }
}

// region Clean

data class PureCleanConfig(
    val match: MatchOptions = MatchOptions(mode = "exact", exact = MatchExact(40)),
    val firstLineWithMusicInfo: Boolean = true,
)

/** transform-pure：删除版权/翻唱等非歌词行。 */
class PureClean : ParserPlugin() {
    override val id: String get() = "TRANSFORM-PURE-CLEAN"
    override val stage: PluginStage get() = PluginStage.Transform
    override val priority: Int get() = 50
    val config = ConfigManager(PureCleanConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val musicInfo = ctx.params.musicInfo
        val matcher = Matcher(config.current.match, PURE_CLEAN_DEFAULT_RULES)
        val newLines = ArrayList<Line>()

        for (i in lines.indices) {
            val line = lines[i]
            if (line !is LineNormal) {
                newLines.add(line)
                continue
            }
            var extra: List<String>? = null
            if (i == 0 && musicInfo != null && config.current.firstLineWithMusicInfo) {
                val e = ArrayList<String>()
                e.addAll(processText(musicInfo.name ?: ""))
                musicInfo.singer?.forEach { e.addAll(processText(it)) }
                extra = e
            }
            val clean = if (!extra.isNullOrEmpty()) processText(Runtime.getLineText(line)).joinToString("")
            else Runtime.getLineText(line)
            val matched = matcher.match(clean, extra)
            if (!matched) newLines.add(line)
        }
        ctx.result.lines = newLines
    }
}

// endregion

// region ExtractCreator

data class PureExtractCreatorConfig(
    val match: MatchOptions = MatchOptions(mode = "exact", exact = MatchExact(50)),
    val split: String = "/",
    val replace: Boolean = true,
)

private val CREATOR_REGEXP = Regex("^(.+?)\\s*[:：]\\s*(.*)$")

private fun extractCreator(line: String): Pair<String, String>? {
    val text = line.trim()
    if (text.isEmpty()) return null
    val m = CREATOR_REGEXP.matchEntire(text) ?: return null
    val role = m.groupValues[1].trim()
    val name = m.groupValues[2].trim()
    if (role.isEmpty()) return null
    return role to name
}

private fun splitNameWithRule(name: String, rule: String): List<String> =
    name.split(rule).map { it.trim() }.filter { it.isNotEmpty() }

private val ROLE_CJK_REGEXP = Regex("[\\u3400-\\u4dbf\\u4e00-\\u9fff]+")
private val ROLE_LATIN_REGEXP = Regex("[A-Za-z]+(?:\\s+[A-Za-z]+)*")

/**
 * 角色名的匹配候选：整体（删空格 / 合并空格）+ 中文段、英文段的脚本投影。
 *
 * 中英双语角色（如「艺人统筹Artist coordination」）里两种语言各自表达同一角色，但整体匹配会被
 * 另一语言的字符稀释到 50% 阈值以下而漏判。按脚本投影后各语言段独立匹配即可命中；各段仍走 50%
 * 阈值——普通歌词段落不会有一半字符是角色词，故不会误伤正文。
 */
private fun roleMatchTargets(role: String): List<String> {
    val targets = LinkedHashSet<String>()
    targets.add(TextUtil.removeTextSpaceAll(role))
    targets.add(TextUtil.removeTextSpaceToOne(role))
    ROLE_CJK_REGEXP.findAll(role).forEach { targets.add(it.value) }
    ROLE_LATIN_REGEXP.findAll(role).forEach { targets.add(it.value) }
    return targets.filter { it.isNotBlank() }
}

/** transform-pure：把 "角色: 姓名" 信息行提取为 meta.credits。 */
class PureExtractCreator : ParserPlugin() {
    override val id: String get() = "TRANSFORM-PURE-EXTRACT"
    override val stage: PluginStage get() = PluginStage.Transform
    override val priority: Int get() = 40
    val config = ConfigManager(PureExtractCreatorConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val meta = ctx.result.meta ?: Runtime.makeMeta().also { ctx.result.meta = it }
        val matcher = Matcher(config.current.match, DEFAULT_CREATOR_RULES_FULL)
        val newLines = ArrayList<Line>()

        for (line in lines) {
            if (line !is LineNormal) {
                newLines.add(line)
                continue
            }
            val target = extractCreator(Runtime.getLineText(line))
            if (target == null) {
                newLines.add(line)
                continue
            }
            val (role, name) = target
            // 角色名按「整体规范化 + 中/英脚本投影」多候选匹配，兼容中英双语角色（见 roleMatchTargets）。
            val matched = roleMatchTargets(role).any { matcher.match(it) }
            if (!matched) {
                newLines.add(line)
                continue
            }
            // 只有真正提取到「角色: 姓名」里的姓名时，才算制作信息并移除该行。
            // 冒号后为空的独立角色标签（如「戏腔：」「合唱：」「Rap：」）没有姓名可提取，不是制作信息，
            // 保留下来交给 agentExtract 建立对唱/分段结构；否则会被误删，导致对唱歌词丢失。
            val names = if (name.isNotEmpty()) splitNameWithRule(name, config.current.split) else emptyList()
            if (names.isEmpty()) {
                newLines.add(line)
                continue
            }
            meta.credits.add(
                Runtime.makeMetaCredit(role = role, names = names.map { Runtime.makeMetaText(it) }.toMutableList())
            )
            if (!config.current.replace) newLines.add(line)
        }
        ctx.result.lines = newLines
    }
}

// endregion
