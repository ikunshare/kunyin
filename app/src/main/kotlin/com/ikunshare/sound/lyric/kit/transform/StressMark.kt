package com.ikunshare.sound.lyric.kit.transform

import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.core.PluginStage
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.LyricCommon
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.util.ConfigManager

data class StressMarkConfig(val checkTime: Int = 3000)

/** transform-stress：将持续时间超过阈值的词标记为重音。 */
class StressMark : ParserPlugin() {
    override val id: String get() = "TRANSFORM-STRESS-MARK"
    override val stage: PluginStage get() = PluginStage.Transform
    val config = ConfigManager(StressMarkConfig())

    override fun check(ctx: ParserContext): Boolean = true

    override fun exec(ctx: ParserContext) {
        val lines = ctx.result.lines
        if (lines.isEmpty()) return
        for (line in lines) {
            if (line !is LineNormal) continue
            handleMark(line.content)
            for (background in line.backgrounds) handleMark(background.content)
        }
    }

    private fun handleMark(content: LineContent?) {
        for (word in content?.words ?: emptyList()) {
            if (word !is WordNormal) continue
            val time = word.time
            if (time != null && LyricCommon.getTimeDuration(time) > config.current.checkTime) {
                word.stress = true
            }
        }
    }
}
