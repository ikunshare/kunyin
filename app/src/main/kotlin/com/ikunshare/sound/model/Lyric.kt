package com.ikunshare.sound.model

data class Lyric(
    val lrc: String = "",    // 标准歌词，如果没有设置为空字符串
    val trans: String = "",  // 翻译歌词
    val roma: String = "",   // 音译歌词
    val char: String = "",   // 逐字歌词
    val chroma: String = "", // 音译逐字歌词
    val phonetic: String = "" // AI 谐音（中文注音）
) {
    fun isEmpty(): Boolean =
        lrc.isEmpty() && trans.isEmpty() && roma.isEmpty()
                && char.isEmpty() && chroma.isEmpty() && phonetic.isEmpty()
}
