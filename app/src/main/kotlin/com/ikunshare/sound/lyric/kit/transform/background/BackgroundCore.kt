package com.ikunshare.sound.lyric.kit.transform.background

import com.ikunshare.sound.lyric.kit.model.Line
import com.ikunshare.sound.lyric.kit.model.LineBackground
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordNormal

/** transform-background 的核心工具，对应 extract/core.ts。 */
internal object BackgroundCore {

    fun isOpenBracket(ch: Char?): Boolean = ch == '(' || ch == '（'
    fun isCloseBracket(ch: Char?): Boolean = ch == ')' || ch == '）'

    private fun copyTime(time: Time?): Time? = if (time != null) Time(time.start, time.end) else null

    fun copyWord(word: Word): Word? = when (word) {
        is com.ikunshare.sound.lyric.kit.model.WordSpace -> Runtime.makeWordSpace(word.count)
        is WordNormal -> Runtime.makeWordNormal(
            content = word.content,
            time = copyTime(word.time),
            annotation = word.annotation,
            stress = word.stress,
        )
    }

    fun copyNormalWord(word: WordNormal, content: String): Word = Runtime.makeWordNormal(
        content = content,
        time = copyTime(word.time),
        annotation = word.annotation,
        stress = word.stress,
    )

    fun toBackground(line: LineNormal): LineBackground =
        Runtime.makeLineBackground(time = copyTime(line.time), content = line.content)

    private fun findFirstNormalWord(line: LineNormal): WordNormal? =
        line.content?.words?.firstOrNull { it is WordNormal } as? WordNormal

    private fun findLastNormalWord(line: LineNormal): WordNormal? =
        line.content?.words?.lastOrNull { it is WordNormal } as? WordNormal

    fun addBackground(line: LineNormal, bg: LineBackground) {
        line.backgrounds.add(bg)
    }

    fun hasStartOpenBracket(line: LineNormal): Boolean {
        val first = findFirstNormalWord(line) ?: return false
        return isOpenBracket(first.content.firstOrNull())
    }

    fun hasEndCloseBracket(line: LineNormal): Boolean {
        val last = findLastNormalWord(line) ?: return false
        return isCloseBracket(last.content.lastOrNull())
    }

    fun isFullLine(line: LineNormal): Boolean {
        val start = findFirstNormalWord(line)
        val end = findLastNormalWord(line)
        if (start == null || end == null || start === end) return false
        return isOpenBracket(start.content.firstOrNull()) && isCloseBracket(end.content.lastOrNull())
    }

    /** 从纯字符串中提取括号内容，返回 [主文本, 各括号段]。 */
    fun extractInLineExtended(content: String): Pair<String, List<String>> {
        val main = StringBuilder()
        val result = ArrayList<String>()
        val current = StringBuilder()
        var inBracket = false
        var lastChar: Char? = null
        for (ch in content) {
            when {
                isOpenBracket(ch) -> {
                    if (inBracket) current.append(ch)
                    else {
                        inBracket = true
                        lastChar = ch
                    }
                }
                isCloseBracket(ch) -> {
                    if (inBracket) {
                        result.add(current.toString())
                        current.setLength(0)
                        inBracket = false
                    } else main.append(ch)
                }
                else -> if (inBracket) current.append(ch) else main.append(ch)
            }
        }
        if (inBracket) {
            if (lastChar != null) main.append(lastChar)
            main.append(current)
        }
        return main.toString() to result
    }

    /** 词级行内背景提取。 */
    fun extractInLine(line: LineNormal) {
        val content = line.content ?: return
        var hasOpen = false
        var hasClose = false
        for (word in content.words) {
            if (word is WordNormal) {
                for (c in word.content) {
                    if (isOpenBracket(c)) hasOpen = true
                    if (isCloseBracket(c)) hasClose = true
                }
            }
        }
        if (!hasOpen && !hasClose) return

        val mainWords = ArrayList<Word>()
        var currentBackground = ArrayList<Word>()
        val backgroundGroups = ArrayList<ArrayList<Word>>()
        var inBracket = false
        var lastOpenChar: Char? = null
        var lastOpenWord: WordNormal? = null

        for (word in content.words) {
            if (word !is WordNormal) {
                val copy = copyWord(word) ?: continue
                if (inBracket) currentBackground.add(copy) else mainWords.add(copy)
                continue
            }
            val value = word
            val buffer = StringBuilder()
            fun flush(target: ArrayList<Word>) {
                if (buffer.isNotEmpty()) {
                    target.add(copyNormalWord(value, buffer.toString()))
                    buffer.setLength(0)
                }
            }
            for (c in value.content) {
                when {
                    isOpenBracket(c) -> {
                        if (inBracket) buffer.append(c)
                        else {
                            flush(mainWords)
                            inBracket = true
                            lastOpenChar = c
                            lastOpenWord = value
                        }
                    }
                    isCloseBracket(c) -> {
                        if (inBracket) {
                            flush(currentBackground)
                            if (currentBackground.isNotEmpty()) {
                                backgroundGroups.add(currentBackground)
                                currentBackground = ArrayList()
                            }
                            inBracket = false
                        } else buffer.append(c)
                    }
                    else -> buffer.append(c)
                }
            }
            flush(if (inBracket) currentBackground else mainWords)
        }

        if (inBracket) {
            if (lastOpenWord != null && lastOpenChar != null) {
                mainWords.add(copyNormalWord(lastOpenWord!!, lastOpenChar.toString()))
            }
            mainWords.addAll(currentBackground)
            currentBackground = ArrayList()
        }

        if (backgroundGroups.isEmpty()) return

        content.words = mainWords
        for (group in backgroundGroups) {
            val normals = group.filterIsInstance<WordNormal>()
            val time = if (normals.isNotEmpty()) {
                Time(normals.first().time?.start ?: 0, normals.last().time?.end ?: 0)
            } else null
            addBackground(line, Runtime.makeLineBackground(time = time, content = LineContent(words = group)))
        }
    }

    /** 把括号内的注解文本拆分到对应背景行。 */
    fun assignBackgroundAnnotation(line: LineNormal) {
        val backgroundLines = line.backgrounds
        if (backgroundLines.isEmpty()) return
        val annotation = line.content?.annotation ?: return

        for (item in annotation.translates) {
            if (item.content.trim().isEmpty()) continue
            val (main, backgrounds) = extractInLineExtended(item.content)
            item.content = main
            for (i in backgrounds.indices) {
                if (i >= backgroundLines.size) continue
                val bg = backgroundLines[i]
                val bgContent = bg.content ?: Runtime.makeLineContent().also { bg.content = it }
                val bgAnnotation = bgContent.annotation ?: Runtime.makeLineAnnotation().also { bgContent.annotation = it }
                bgAnnotation.translates.add(Runtime.makeLineAnnotationTranslate(content = backgrounds[i], language = item.language))
            }
        }
        for (item in annotation.romans) {
            if (item.content.trim().isEmpty()) continue
            val (main, backgrounds) = extractInLineExtended(item.content)
            item.content = main
            for (i in backgrounds.indices) {
                if (i >= backgroundLines.size) continue
                val bg = backgroundLines[i]
                val bgContent = bg.content ?: Runtime.makeLineContent().also { bg.content = it }
                val bgAnnotation = bgContent.annotation ?: Runtime.makeLineAnnotation().also { bgContent.annotation = it }
                bgAnnotation.romans.add(Runtime.makeLineAnnotationRoman(content = backgrounds[i], language = item.language))
            }
        }
    }

    /** 跨行括号合并。 */
    fun extractCrossLine(lines: List<Line>): List<Line> {
        val result = ArrayList<Line>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]
            val next = lines.getOrNull(i + 1)
            if (next == null) {
                result.add(current); i++; continue
            }
            val prev = result.lastOrNull()
            if (prev == null) {
                result.add(current); i++; continue
            }
            if (current is LineNormal && next is LineNormal) {
                if (hasStartOpenBracket(current) && hasEndCloseBracket(current)) {
                    result.add(current); i++; continue
                }
                if (hasStartOpenBracket(current) && hasEndCloseBracket(next)) {
                    if (prev is LineNormal) {
                        addBackground(prev, toBackground(current))
                        addBackground(prev, toBackground(next))
                        i += 2
                        continue
                    }
                }
            }
            result.add(current); i++
        }
        return result
    }
}
