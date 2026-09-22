package com.ikunshare.sound.lyric.kit.transform

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.util.ConfigManager

data class InterludeCheckTime(val first: Int = 5000, val normal: Int = 10000)
data class InterludeInsertConfig(val checkTime: InterludeCheckTime = InterludeCheckTime())

/** transform-interlude：在行间空隙中插入间奏行。 */
class InterludeInsert : ParserPlugin() {
    override val id: String get() = "TRANSFORM-INTERLUDE-INSERT"
    override val stage: PluginStage get() = PluginStage.Transform
    override val priority: Int get() = 500
    val config = ConfigManager(InterludeInsertConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        val length = lines.size
        if (length == 0) return

        val firstThreshold = config.current.checkTime.first
        val normalThreshold = config.current.checkTime.normal
        val newLines = ArrayList<Line>()

        // 首行前的间奏
        val firstStart = Runtime.getLineTime(lines[0])?.start ?: 0
        if (firstStart > firstThreshold) {
            val start = 500
            val end = firstStart
            if (end > start) newLines.add(Runtime.makeLineInterlude(Time(start, end)))
        }

        for (i in 0 until length - 1) {
            val current = lines[i]
            val next = lines[i + 1]
            newLines.add(current)
            val currentEnd = Runtime.getLineTime(current)?.end ?: 0
            val nextStart = Runtime.getLineTime(next)?.start ?: 0
            val start = currentEnd + 100
            val duration = nextStart - start
            if (duration > normalThreshold) newLines.add(Runtime.makeLineInterlude(Time(start, nextStart)))
        }
        newLines.add(lines[length - 1])

        ctx.result.lines = newLines
    }
}
