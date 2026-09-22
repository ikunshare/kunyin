package com.ikunshare.sound.lyric.kit.transform

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.AgentItem
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.ConfigManager

data class AgentExtractConfig(val split: String = "/", val replace: Boolean = true)

/** transform-agent：识别行首 "歌手: 歌词" 前缀，创建/分配演唱者并跨行沿用。 */
class AgentExtract : ParserPlugin() {
    override val id: String get() = "TRANSFORM-AGENT-EXTRACT"
    override val stage: PluginStage get() = PluginStage.Transform
    val config = ConfigManager(AgentExtractConfig())

    override fun check(ctx: ParserContext): Boolean = true

    private val trailingDigits = Regex("(\\d+)$")
    private val leadingDigits = Regex("^(\\d+)")

    private fun createHash(str: String): String {
        var hash = 0
        for (c in str) hash = (hash shl 5) - hash + c.code
        val unsigned = hash.toLong() and 0xFFFFFFFFL
        return unsigned.toString(16).padStart(8, '0').uppercase()
    }

    private fun isClockTimeColon(before: String, after: String): Boolean {
        val hour = trailingDigits.find(before)?.groupValues?.get(1)
        val minute = leadingDigits.find(after)?.groupValues?.get(1)
        if (hour == null || minute == null) return false
        return hour.length <= 2 && hour.toInt() <= 24 && minute.length <= 2 && minute.toInt() <= 60
    }

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        val newLines = ArrayList<Line>()
        val agentMap = LinkedHashMap<String, AgentItem>()
        for (agent in ctx.result.agents) agentMap[agent.id] = agent
        var currentId: String? = null

        for (line in lines) {
            if (line !is LineNormal) {
                currentId = null
                newLines.add(line)
                continue
            }
            val content = line.content ?: Runtime.makeLineContent().also { line.content = it }
            val words = content.words
            val trimmed = Runtime.getLineText(line).trim()
            if (trimmed.isEmpty()) {
                currentId = null
                newLines.add(line)
                continue
            }

            val colonPos = trimmed.indexOfFirst { it == ':' || it == '：' }
            if (colonPos != -1 && isClockTimeColon(trimmed.substring(0, colonPos), trimmed.substring(colonPos + 1))) {
                if (currentId != null) content.agent = Runtime.makeLineAgent(currentId)
                newLines.add(line)
                continue
            }

            val colonWordIndex = words.indexOfFirst {
                it is WordNormal && (it.content.trim().contains('：') || it.content.trim().contains(':'))
            }

            if (colonWordIndex != -1) {
                val colonItem = words[colonWordIndex]
                if (colonItem !is WordNormal) {
                    newLines.add(line)
                    continue
                }
                val colonText = colonItem.content.trim()
                val ci1 = colonText.indexOf('：')
                val ci2 = colonText.indexOf(':')
                val colonIndex = if (ci1 != -1) ci1 else ci2
                if (colonIndex == -1) {
                    newLines.add(line)
                    continue
                }
                val beforeColon = colonText.substring(0, colonIndex)
                val afterColon = colonText.substring(colonIndex + 1).trim()
                val nameParts = words.subList(0, colonWordIndex).map { Runtime.getWordText(it) }.toMutableList()
                if (beforeColon.isNotEmpty()) nameParts.add(beforeColon)
                val name = nameParts.joinToString("").trim()
                if (name.isEmpty()) {
                    currentId = null
                    newLines.add(line)
                    continue
                }

                if (config.current.replace) {
                    if (afterColon.isNotEmpty()) {
                        colonItem.content = afterColon
                        content.words = words.subList(colonWordIndex, words.size).toMutableList()
                    } else {
                        content.words = words.subList(colonWordIndex + 1, words.size).toMutableList()
                    }
                }

                val id = createHash(name)
                currentId = id
                val existing = agentMap[id]
                if (existing == null) {
                    agentMap[id] = Runtime.makeAgentItem(id = id, names = mutableListOf(name))
                } else if (!existing.names.contains(name)) {
                    existing.names.add(name)
                }

                if (content.words.isEmpty()) continue
                // fall through to tail
            } else {
                if (currentId == null) {
                    newLines.add(line)
                    continue
                }
                // fall through to tail
            }

            content.agent = Runtime.makeLineAgent(currentId!!)
            newLines.add(line)
        }

        ctx.result.lines = newLines
        ctx.result.agents = agentMap.values.toMutableList()
        ctx.syncLineTimeWithWord()
    }
}
