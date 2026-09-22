package com.ikunshare.sound.lyric.kit

import com.ikunshare.sound.lyric.kit.core.MusicInfo
import com.ikunshare.sound.lyric.kit.core.ParserContext
import com.ikunshare.sound.lyric.kit.core.ParserParams
import com.ikunshare.sound.lyric.kit.core.ParserPlugin
import com.ikunshare.sound.lyric.kit.format.lrc.LrcParser
import com.ikunshare.sound.lyric.kit.format.ttml.TtmlAmllParser
import com.ikunshare.sound.lyric.kit.format.ttml.TtmlItunesParser
import com.ikunshare.sound.lyric.kit.model.Info
import com.ikunshare.sound.lyric.kit.model.InfoType
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Timing
import com.ikunshare.sound.lyric.kit.transform.AgentExtract
import com.ikunshare.sound.lyric.kit.transform.AgentExtractConfig
import com.ikunshare.sound.lyric.kit.transform.InterludeInsert
import com.ikunshare.sound.lyric.kit.transform.InterludeInsertConfig
import com.ikunshare.sound.lyric.kit.transform.SpaceInsert
import com.ikunshare.sound.lyric.kit.transform.SpaceInsertConfig
import com.ikunshare.sound.lyric.kit.transform.StressMark
import com.ikunshare.sound.lyric.kit.transform.StressMarkConfig
import com.ikunshare.sound.lyric.kit.transform.background.BackgroundClean
import com.ikunshare.sound.lyric.kit.transform.background.BackgroundExtract
import com.ikunshare.sound.lyric.kit.transform.background.BackgroundExtractConfig
import com.ikunshare.sound.lyric.kit.transform.language.LanguageCalculateConfig
import com.ikunshare.sound.lyric.kit.transform.language.LanguageCalculatePercent
import com.ikunshare.sound.lyric.kit.transform.language.LanguageInfer
import com.ikunshare.sound.lyric.kit.transform.language.LanguageInferConfig
import com.ikunshare.sound.lyric.kit.transform.pure.PureClean
import com.ikunshare.sound.lyric.kit.transform.pure.PureCleanConfig
import com.ikunshare.sound.lyric.kit.transform.pure.PureExtractCreator
import com.ikunshare.sound.lyric.kit.transform.pure.PureExtractCreatorConfig

/** 管线输入，对应 main 的 ParserPipelineInput。 */
class LyricPipelineInput(
    val content: Any?,
    val format: String = "",
    val musicInfo: MusicInfo? = null,
)

/** 管线结果。 */
class LyricPipelineResult(val format: String, val result: Info)

/**
 * 歌词解析管线，对应 music-lyric-kit main 包的 ParserPipeline。
 * 提供格式推断、解析与各 transform 插件的链式调用。
 */
class LyricPipeline(input: LyricPipelineInput) {
    private var format: String = input.format
    private var done = false
    private val context = ParserContext(
        ParserParams(input.content, input.musicInfo),
        Runtime.makeInfo(InfoType.INVALID, Timing.NONE),
    )

    private val builtInFormats: List<ParserPlugin> = listOf(LrcParser(), TtmlAmllParser(), TtmlItunesParser())

    /** 当前解析结果（供适配器在管线步骤间做桥接处理）。 */
    val info: Info get() = context.result

    private val agentExtract = AgentExtract()
    private val backgroundExtract = BackgroundExtract()
    private val backgroundClean = BackgroundClean()
    private val pureExtractCreator = PureExtractCreator()
    private val pureClean = PureClean()
    private val interludeInsert = InterludeInsert()
    private val spaceInsert = SpaceInsert()
    private val stressMark = StressMark()
    private val languageInfer = LanguageInfer()
    private val languageCalculatePercent = LanguageCalculatePercent()

    private fun execPlugin(plugin: ParserPlugin) {
        done = false
        try {
            if (!plugin.check(context)) return
        } catch (_: Exception) {
            return
        }
        try {
            plugin.exec(context)
        } catch (_: Exception) {
        }
    }

    // region transform 链式调用

    fun agentExtract(options: AgentExtractConfig? = null): LyricPipeline {
        agentExtract.config.apply(options); execPlugin(agentExtract); return this
    }

    fun backgroundExtract(options: BackgroundExtractConfig? = null): LyricPipeline {
        backgroundExtract.config.apply(options); execPlugin(backgroundExtract); return this
    }

    fun backgroundClean(): LyricPipeline {
        execPlugin(backgroundClean); return this
    }

    fun pureExtractCreator(options: PureExtractCreatorConfig? = null): LyricPipeline {
        pureExtractCreator.config.apply(options); execPlugin(pureExtractCreator); return this
    }

    fun pureClean(options: PureCleanConfig? = null): LyricPipeline {
        pureClean.config.apply(options); execPlugin(pureClean); return this
    }

    fun interludeInsert(options: InterludeInsertConfig? = null): LyricPipeline {
        interludeInsert.config.apply(options); execPlugin(interludeInsert); return this
    }

    fun spaceInsert(options: SpaceInsertConfig? = null): LyricPipeline {
        spaceInsert.config.apply(options); execPlugin(spaceInsert); return this
    }

    fun stressMark(options: StressMarkConfig? = null): LyricPipeline {
        stressMark.config.apply(options); execPlugin(stressMark); return this
    }

    fun languageInfer(options: LanguageInferConfig? = null): LyricPipeline {
        languageInfer.config.apply(options); execPlugin(languageInfer); return this
    }

    fun languageCalculatePercent(options: LanguageCalculateConfig? = null): LyricPipeline {
        languageCalculatePercent.config.apply(options); execPlugin(languageCalculatePercent); return this
    }

    // endregion

    /** 推断格式（未显式指定时）。 */
    fun infer(): LyricPipeline {
        if (format.isNotEmpty()) return this
        for (plugin in builtInFormats) {
            try {
                if (plugin.check(context)) {
                    format = plugin.format
                    break
                }
            } catch (_: Exception) {
                continue
            }
        }
        return this
    }

    /** 执行格式解析。 */
    fun parse(): LyricPipeline {
        if (format.isEmpty()) throw IllegalStateException("no format detected. call .infer() before .parse()")
        val parser = builtInFormats.find { it.format == format }
            ?: throw IllegalStateException("parser plugin not found: \"$format\"")
        done = false
        try {
            parser.exec(context)
        } catch (_: Exception) {
        }
        context.finalizeAnnotation()
        return this
    }

    /** 完成并输出结果（执行剩余后处理）。 */
    fun final(): LyricPipelineResult {
        if (!done) {
            context.cleanWord()
            context.finalizeAnnotation()
            context.syncLineTimeWithWord()
            context.sort()
            context.syncLineTimeWithBackground()
            done = true
        }
        return LyricPipelineResult(format, context.result)
    }
}
