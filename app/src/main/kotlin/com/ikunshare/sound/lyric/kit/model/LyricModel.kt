package com.ikunshare.sound.lyric.kit.model

/**
 * Kotlin 移植的歌词运行时数据模型（对应 music-lyric-model 的 `Lyric.Runtime` / `Lyric.Common`）。
 *
 * 原库由 protobuf 生成，模型对象在解析/转换管线中被大量原地修改，因此这里全部使用
 * 可变字段（var）与 MutableList，以 1:1 复刻原库的可变语义。
 */

// region 公共枚举与时间

enum class Timing { UNSPECIFIED, NONE, LINE, WORD }

enum class InfoType { UNSPECIFIED, INVALID, VALID }

enum class AgentType { UNSPECIFIED, UNKNOWN, PERSON, GROUP, OTHER }

enum class PartType {
    UNSPECIFIED, OTHER, INTRO, VERSE, PRE_CHORUS, CHORUS,
    POST_CHORUS, BRIDGE, REFRAIN, INSTRUMENTAL, OUTRO
}

/** 时间范围，单位毫秒。可变，管线会原地修改 start/end。 */
data class Time(var start: Int = 0, var end: Int = 0)

// endregion

// region 词级（Word）

/** 词 token，normal 或 space 二选一。 */
sealed interface Word

class WordNormal(
    var content: String = "",
    var time: Time? = null,
    var language: String? = null,
    var annotation: WordAnnotation? = null,
    var stress: Boolean = false,
    val extra: MutableMap<String, String> = mutableMapOf(),
) : Word

class WordSpace(var count: Int = 0) : Word

// 词级注解

class WordAnnotationContent(
    var time: Time? = null,
    var content: String = "",
)

class WordAnnotationUnknown(
    var key: String = "",
    var value: String = "",
)

class WordAnnotationTranslate(
    var language: String? = null,
    var content: String = "",
)

class WordAnnotationRoman(
    var time: Time? = null,
    var language: String? = null,
    val words: MutableList<WordAnnotationContent> = mutableListOf(),
)

class WordAnnotationRuby(
    var time: Time? = null,
    var language: String? = null,
    val words: MutableList<WordAnnotationContent> = mutableListOf(),
    var phraseStart: Boolean = false,
)

class WordAnnotation(
    val unknowns: MutableList<WordAnnotationUnknown> = mutableListOf(),
    var ruby: WordAnnotationRuby? = null,
    val romans: MutableList<WordAnnotationRoman> = mutableListOf(),
    val translates: MutableList<WordAnnotationTranslate> = mutableListOf(),
)

// endregion

// region 行级注解（Line）

class LineAnnotationUnknown(
    var derived: Boolean = false,
    var key: String = "",
    var value: String = "",
)

class LineAnnotationRoman(
    var derived: Boolean = false,
    var language: String? = null,
    var content: String = "",
)

class LineAnnotationTranslate(
    var derived: Boolean = false,
    var language: String? = null,
    var content: String = "",
)

class LineAnnotation(
    val unknowns: MutableList<LineAnnotationUnknown> = mutableListOf(),
    val translates: MutableList<LineAnnotationTranslate> = mutableListOf(),
    val romans: MutableList<LineAnnotationRoman> = mutableListOf(),
)

class LineAgent(var id: String = "")

class LineContent(
    var agent: LineAgent? = null,
    var words: MutableList<Word> = mutableListOf(),
    var annotation: LineAnnotation? = null,
    val languages: MutableList<String> = mutableListOf(),
)

/** 承载 LineContent 的对象（LineNormal 与 LineBackground 共用），便于统一处理。 */
interface ContentCarrier {
    var content: LineContent?
}

// endregion

// region 行（Line）

/** 结构片段（Part）。 */
class Part(var type: PartType = PartType.UNSPECIFIED, var label: String? = null)

/** 一行歌词，normal 或 interlude 二选一。 */
sealed class Line {
    var time: Time? = null
    var part: Part? = null
    val extra: MutableMap<String, String> = mutableMapOf()
}

class LineNormal(
    override var content: LineContent? = null,
    val backgrounds: MutableList<LineBackground> = mutableListOf(),
) : Line(), ContentCarrier

class LineInterlude : Line()

/** 背景行（挂在 LineNormal 上）。 */
class LineBackground(
    override var content: LineContent? = null,
    val extra: MutableMap<String, String> = mutableMapOf(),
) : ContentCarrier {
    var time: Time? = null
}

// endregion

// region 元信息（Meta）

class MetaText(var language: String? = null, var content: String = "")

class MetaCredit(var role: String = "", val names: MutableList<MetaText> = mutableListOf())

class MetaUnknown(var key: String = "", var value: String = "")

class MetaReference(var platform: String = "", val ids: MutableList<String> = mutableListOf())

class Meta(
    val unknowns: MutableList<MetaUnknown> = mutableListOf(),
    var offset: Int = 0,
    var duration: Int = 0,
    val titles: MutableList<MetaText> = mutableListOf(),
    val artists: MutableList<MetaText> = mutableListOf(),
    val albums: MutableList<MetaText> = mutableListOf(),
    val authors: MutableList<MetaText> = mutableListOf(),
    val isrcs: MutableList<String> = mutableListOf(),
    val credits: MutableList<MetaCredit> = mutableListOf(),
    val references: MutableList<MetaReference> = mutableListOf(),
)

// endregion

// region 顶层

class AgentItem(
    var id: String = "",
    var type: AgentType = AgentType.UNSPECIFIED,
    val names: MutableList<String> = mutableListOf(),
)

class LanguageItem(var tag: String = "", var percent: Double = 0.0)

class Info(
    var version: String = SCHEMA_VERSION,
    var type: InfoType = InfoType.UNSPECIFIED,
    var timing: Timing = Timing.UNSPECIFIED,
    val extra: MutableMap<String, String> = mutableMapOf(),
    var meta: Meta? = null,
    var languages: MutableList<LanguageItem> = mutableListOf(),
    var agents: MutableList<AgentItem> = mutableListOf(),
    var lines: MutableList<Line> = mutableListOf(),
)

const val SCHEMA_VERSION = "3.0.0"

// endregion
