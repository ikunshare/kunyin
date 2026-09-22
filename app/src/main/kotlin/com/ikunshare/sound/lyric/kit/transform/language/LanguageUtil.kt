package com.ikunshare.sound.lyric.kit.transform.language

import com.ikunshare.sound.lyric.kit.model.LanguageType

enum class Script { kana, hangul, han, cyrillic, latin }

/** 对应 transform-language 的 utils/constants.ts 与 utils/index.ts。 */
object LanguageUtil {

    private val SCRIPT_RANGES: Map<Script, List<IntArray>> = mapOf(
        Script.kana to listOf(intArrayOf(0x3040, 0x30ff), intArrayOf(0x31f0, 0x31ff), intArrayOf(0xff66, 0xff9f)),
        Script.hangul to listOf(
            intArrayOf(0x1100, 0x11ff), intArrayOf(0x3130, 0x318f), intArrayOf(0xa960, 0xa97f),
            intArrayOf(0xac00, 0xd7a3), intArrayOf(0xd7b0, 0xd7ff),
        ),
        Script.han to listOf(
            intArrayOf(0x3005, 0x3007), intArrayOf(0x3400, 0x4dbf), intArrayOf(0x4e00, 0x9fff),
            intArrayOf(0xf900, 0xfaff), intArrayOf(0x20000, 0x2ebef),
        ),
        Script.cyrillic to listOf(intArrayOf(0x0400, 0x04ff), intArrayOf(0x0500, 0x052f)),
        Script.latin to listOf(
            intArrayOf(0x0041, 0x005a), intArrayOf(0x0061, 0x007a), intArrayOf(0x00c0, 0x00d6),
            intArrayOf(0x00d8, 0x00f6), intArrayOf(0x00f8, 0x024f),
        ),
    )

    private val SCRIPT_PRIORITY = listOf(Script.kana, Script.hangul, Script.han, Script.cyrillic, Script.latin)

    private const val SIMPLIFIED_CHARS =
        "个们这来时国爱学龙价关当会实应体与东车马鸟鱼见贝页风飞长门问间阳阴队际难鸡欢观觉写让说话语读谁请谢边过还进远连万业习乡书买卖红级约经给续网罗义乐药园圆图团医动单样节总开闭闻员质贵费资购数转轻软输较尽层属张报担据击坚紧亲穷权伤胜师虽听乌响绣盐养钟众庄"
    private const val TRADITIONAL_CHARS =
        "個們這來時國愛學龍價關當會實應體與東車馬鳥魚見貝頁風飛長門問間陽陰隊際難雞歡觀覺寫讓說話語讀誰請謝邊過還進遠連萬業習鄉書買賣紅級約經給續網羅義樂藥園圓圖團醫動單樣節總開閉聞員質貴費資購數轉輕軟輸較盡層屬張報擔據擊堅緊親窮權傷勝師雖聽烏響繡鹽養鐘眾莊"

    private val SIMPLIFIED_SET: Set<Char> = SIMPLIFIED_CHARS.toHashSet()
    private val TRADITIONAL_SET: Set<Char> = TRADITIONAL_CHARS.toHashSet()

    const val JAPANESE_KANA_RATIO = 0.05

    private val LATIN_FEATURE_SCORES: Map<Char, Map<LanguageType, Double>> = buildMap {
        put('ß', mapOf(LanguageType.German to 3.0))
        put('ä', mapOf(LanguageType.German to 2.0))
        put('ö', mapOf(LanguageType.German to 1.0))
        put('ü', mapOf(LanguageType.German to 1.0, LanguageType.Spanish to 0.5))
        put('ñ', mapOf(LanguageType.Spanish to 3.0))
        put('¿', mapOf(LanguageType.Spanish to 3.0))
        put('¡', mapOf(LanguageType.Spanish to 3.0))
        put('œ', mapOf(LanguageType.French to 3.0))
        put('æ', mapOf(LanguageType.French to 3.0))
        put('ç', mapOf(LanguageType.French to 1.5, LanguageType.Portuguese to 1.0))
        put('ê', mapOf(LanguageType.French to 1.0, LanguageType.Portuguese to 1.0))
        put('ë', mapOf(LanguageType.French to 1.0))
        put('î', mapOf(LanguageType.French to 1.0))
        put('ï', mapOf(LanguageType.French to 1.0))
        put('û', mapOf(LanguageType.French to 1.0))
        put('è', mapOf(LanguageType.French to 1.0, LanguageType.Italian to 1.0))
        put('à', mapOf(LanguageType.French to 0.5, LanguageType.Italian to 1.0, LanguageType.Portuguese to 0.5))
        put('â', mapOf(LanguageType.French to 1.0, LanguageType.Portuguese to 1.0))
        put('ô', mapOf(LanguageType.French to 0.5, LanguageType.Portuguese to 1.0))
        put('ù', mapOf(LanguageType.French to 0.5, LanguageType.Italian to 1.0))
        put('ã', mapOf(LanguageType.Portuguese to 3.0))
        put('õ', mapOf(LanguageType.Portuguese to 3.0))
        put('ì', mapOf(LanguageType.Italian to 2.0))
        put('ò', mapOf(LanguageType.Italian to 1.5))
        put('á', mapOf(LanguageType.Spanish to 1.0, LanguageType.Portuguese to 1.0))
        put('é', mapOf(LanguageType.French to 0.5, LanguageType.Spanish to 0.5, LanguageType.Portuguese to 0.5, LanguageType.Italian to 0.5))
        put('í', mapOf(LanguageType.Spanish to 1.0, LanguageType.Italian to 0.5))
        put('ó', mapOf(LanguageType.Spanish to 1.0, LanguageType.Portuguese to 0.5))
        put('ú', mapOf(LanguageType.Spanish to 1.0))
    }

    private val CJK_LANGUAGES: Set<String> = setOf(
        LanguageType.ChineseSimplified.tag,
        LanguageType.ChineseTraditional.tag,
        LanguageType.Japanese.tag,
        LanguageType.Korean.tag,
    )

    private val LATIN_WORD_REGEXP = Regex("[A-Za-z\\u00c0-\\u00d6\\u00d8-\\u00f6\\u00f8-\\u024f\\u0400-\\u052f]+")
    private val APOSTROPHE = Regex("['’]")

    private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            action(cp)
            i += Character.charCount(cp)
        }
    }

    fun classifyCodePoint(cp: Int): Script? {
        for (script in SCRIPT_PRIORITY) {
            val ranges = SCRIPT_RANGES[script] ?: continue
            for (range in ranges) {
                if (cp >= range[0] && cp <= range[1]) return script
            }
        }
        return null
    }

    class ScriptCounts {
        var kana = 0
        var hangul = 0
        var han = 0
        var cyrillic = 0
        var latin = 0

        operator fun get(script: Script): Int = when (script) {
            Script.kana -> kana
            Script.hangul -> hangul
            Script.han -> han
            Script.cyrillic -> cyrillic
            Script.latin -> latin
        }

        fun inc(script: Script) {
            when (script) {
                Script.kana -> kana++
                Script.hangul -> hangul++
                Script.han -> han++
                Script.cyrillic -> cyrillic++
                Script.latin -> latin++
            }
        }
    }

    fun analyzeScripts(text: String): ScriptCounts {
        val counts = ScriptCounts()
        text.forEachCodePoint { cp ->
            val s = classifyCodePoint(cp)
            if (s != null) counts.inc(s)
        }
        return counts
    }

    fun dominantScript(counts: ScriptCounts): Script? {
        var max = 0
        var result: Script? = null
        for (script in SCRIPT_PRIORITY) {
            if (counts[script] > max) {
                max = counts[script]
                result = script
            }
        }
        return result
    }

    fun detectChineseVariant(text: String): String? {
        var simplified = 0
        var traditional = 0
        text.forEachCodePoint { cp ->
            if (cp <= 0xFFFF) {
                val c = cp.toChar()
                if (SIMPLIFIED_SET.contains(c)) simplified++
                else if (TRADITIONAL_SET.contains(c)) traditional++
            }
        }
        return when {
            traditional > simplified -> LanguageType.ChineseTraditional.tag
            simplified > traditional -> LanguageType.ChineseSimplified.tag
            else -> null
        }
    }

    fun detectLatinLanguage(text: String): String {
        val scores = LinkedHashMap<LanguageType, Double>()
        text.forEachCodePoint { cp ->
            if (cp <= 0xFFFF) {
                val feature = LATIN_FEATURE_SCORES[cp.toChar()]
                if (feature != null) {
                    for ((lang, score) in feature) scores[lang] = (scores[lang] ?: 0.0) + score
                }
            }
        }
        var result = LanguageType.English
        var max = 0.0
        for ((lang, score) in scores) {
            if (score > max) {
                max = score
                result = lang
            }
        }
        return result.tag
    }

    fun isCjkLanguage(lang: String): Boolean = CJK_LANGUAGES.contains(lang)

    fun countCjkChars(text: String): Int {
        var count = 0
        text.forEachCodePoint { cp ->
            val s = classifyCodePoint(cp)
            if (s == Script.kana || s == Script.hangul || s == Script.han) count++
        }
        return count
    }

    fun countLatinWords(text: String): Int {
        val cleaned = APOSTROPHE.replace(text, "")
        return LATIN_WORD_REGEXP.findAll(cleaned).count()
    }
}
