package com.ikunshare.sound.lyric.kit.core

import com.ikunshare.sound.lyric.kit.model.Info
import com.ikunshare.sound.lyric.kit.model.LineBackground
import com.ikunshare.sound.lyric.kit.model.LineContent
import com.ikunshare.sound.lyric.kit.model.LineNormal
import com.ikunshare.sound.lyric.kit.model.Runtime
import com.ikunshare.sound.lyric.kit.model.Time
import com.ikunshare.sound.lyric.kit.model.Word
import com.ikunshare.sound.lyric.kit.model.WordNormal
import com.ikunshare.sound.lyric.kit.model.WordSpace
import com.ikunshare.sound.lyric.kit.model.refreshLineAnnotation

enum class PluginStage { Setup, Pre, Process, Transform, Post, Cleanup }

/** 解析入参，对应 core 的 ParserParams。 */
class ParserParams(
    val content: Any?,
    val musicInfo: MusicInfo? = null,
)

class MusicInfo(
    val name: String? = null,
    val singer: List<String>? = null,
)

/**
 * 解析上下文，对应 core 的 ParserContext。持有 params/result/runtime，并提供管线后处理方法。
 */
class ParserContext(val params: ParserParams, val result: Info) {
    val runtime: MutableMap<String, Any?> = mutableMapOf()

    /** 按 time.start 升序排序所有行及其背景行。 */
    fun sort() {
        Runtime.sortLinesByTime(result)
    }

    /** 将每行/背景行的时间同步为其首末 normal 词的时间范围。 */
    fun syncLineTimeWithWord() {
        for (line in result.lines) {
            if (line !is LineNormal) continue
            syncTimeWithWord(TimeHolder.of(line), line.content?.words ?: emptyList())
            for (background in line.backgrounds) {
                syncTimeWithWord(TimeHolder.of(background), background.content?.words ?: emptyList())
            }
        }
    }

    private fun syncTimeWithWord(holder: TimeHolder, words: List<Word>) {
        var first: WordNormal? = null
        var last: WordNormal? = null
        for (word in words) {
            if (word !is WordNormal) continue
            if (first == null) first = word
            last = word
        }
        if (first == null || last == null) return
        val startTime = first.time?.start ?: 0
        val endTime = last.time?.end ?: 0
        if (startTime <= 0 || endTime <= 0) return
        val current = holder.get()
        if (current != null) {
            current.start = startTime
            current.end = endTime
        } else {
            holder.set(Time(startTime, endTime))
        }
    }

    /** 扩展每行结束时间以覆盖其最后一个背景行的结束时间。 */
    fun syncLineTimeWithBackground() {
        for (line in result.lines) {
            if (line !is LineNormal) continue
            if (line.backgrounds.isEmpty() || line.time == null) continue
            val last = line.backgrounds.lastOrNull() ?: continue
            val lastTime = last.time ?: continue
            line.time!!.end = maxOf(line.time!!.end, lastTime.end)
        }
    }

    /** 移除每行/背景行首尾的空格词。 */
    fun cleanWord() {
        for (line in result.lines) {
            if (line !is LineNormal) continue
            cleanContentWord(line.content)
            for (background in line.backgrounds) cleanContentWord(background.content)
        }
    }

    private fun cleanContentWord(content: LineContent?) {
        if (content == null) return
        val words = content.words
        if (words.isEmpty()) return
        while (words.isNotEmpty() && words.last() is WordSpace) {
            words.removeAt(words.size - 1)
        }
        var startCount = 0
        while (startCount < words.size && words[startCount] is WordSpace) startCount++
        if (startCount > 0) {
            repeat(startCount) { words.removeAt(0) }
        }
    }

    /** 从词级注解构建行级注解。 */
    fun finalizeAnnotation() {
        for (line in result.lines) {
            if (line !is LineNormal) continue
            refreshLineAnnotation(line.content)
            for (background in line.backgrounds) refreshLineAnnotation(background.content)
        }
    }

    /** 抽象出对 line/background 上可变 time 字段的读写。 */
    private class TimeHolder(private val getter: () -> Time?, private val setter: (Time) -> Unit) {
        fun get(): Time? = getter()
        fun set(time: Time) = setter(time)

        companion object {
            fun of(line: LineNormal) = TimeHolder({ line.time }, { line.time = it })
            fun of(bg: LineBackground) = TimeHolder({ bg.time }, { bg.time = it })
        }
    }
}

/** 插件基类，对应 core 的 BasePlugin<ParserContext>。 */
abstract class ParserPlugin {
    abstract val id: String
    abstract val stage: PluginStage
    open val name: String get() = ""
    open val priority: Int get() = 100
    open val format: String get() = ""
    abstract fun check(ctx: ParserContext): Boolean
    abstract fun exec(ctx: ParserContext)
}
