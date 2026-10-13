package com.ikunshare.music.platform.kg

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.ikunshare.music.MusicApplication

/** OpenCC tw2s, using the same normalization, segmentation and dictionaries as the desktop. */
internal class LyricSearchConverter(config: JsonObject) {
    private class Trie(dictionaries: List<String>) {
        private class Node {
            val children = mutableMapOf<Char, Node>()
            var replacement: String? = null
        }
        private val root = Node()

        init {
            // Earlier dictionaries take precedence, as in opencc-js loadDictGroup.
            dictionaries.asReversed().forEach { dictionary ->
                dictionary.split('|').forEach { row ->
                    val split = row.indexOf(' ')
                    require(split > 0)
                    var node = root
                    row.substring(0, split).forEach { node = node.children.getOrPut(it) { Node() } }
                    node.replacement = row.substring(split + 1).substringBefore(' ')
                }
            }
        }

        private fun match(text: String, start: Int): Pair<Int, String>? {
            var node = root
            var found: Pair<Int, String>? = null
            for (i in start until text.length) {
                node = node.children[text[i]] ?: break
                node.replacement?.let { found = (i + 1) to it }
            }
            return found
        }

        fun convert(text: String): String = buildString {
            var index = 0
            while (index < text.length) {
                val matched = match(text, index)
                if (matched == null) {
                    val end = unmatchedEnd(text, index)
                    append(text, index, end)
                    index = end
                } else {
                    append(matched.second)
                    index = matched.first
                }
            }
        }

        fun segments(text: String): List<String> = buildList {
            var index = 0
            var unmatchedStart = -1
            while (index < text.length) {
                val matched = match(text, index)
                if (matched != null) {
                    if (unmatchedStart >= 0) add(text.substring(unmatchedStart, index))
                    unmatchedStart = -1
                    add(text.substring(index, matched.first))
                    index = matched.first
                } else {
                    if (unmatchedStart < 0) unmatchedStart = index
                    index = unmatchedEnd(text, index)
                }
            }
            if (unmatchedStart >= 0) add(text.substring(unmatchedStart))
        }

        private fun unmatchedEnd(text: String, index: Int): Int {
            // Preserve unmatched ideographic description sequences as a whole.
            fun sequenceEnd(start: Int): Int? {
                if (start >= text.length) return null
                val cp = text.codePointAt(start)
                val arity = when (cp) { in 0x2ff0..0x2ff1, in 0x2ff4..0x2fff -> 2; in 0x2ff2..0x2ff3 -> 3; else -> return null }
                var end = start + Character.charCount(cp)
                repeat(arity) {
                    if (end >= text.length) return null
                    end = sequenceEnd(end) ?: (end + Character.charCount(text.codePointAt(end)))
                }
                return end
            }
            return sequenceEnd(index) ?: (index + Character.charCount(text.codePointAt(index)))
        }
    }

    private fun chain(config: JsonObject, key: String) = config.getAsJsonArray(key).map { group ->
        Trie(group.asJsonArray.map { it.asString })
    }
    private val normalization = chain(config, "normalizationChain")
    private val segmentation = Trie(config.getAsJsonArray("segmentation").map { it.asString })
    private val conversion = chain(config, "conversionChain")

    fun convert(text: String): String {
        val normalized = normalization.fold(text) { result, trie -> trie.convert(result) }
        return segmentation.segments(normalized).joinToString("") { segment ->
            conversion.fold(segment) { result, trie -> trie.convert(result) }
        }
    }
}

internal object LyricSearchText {
    // Constructed on the lyric worker, never during Application startup or UI composition.
    private val converter by lazy {
        val assets = requireNotNull(MusicApplication.instance).assets
        assets.open("cc/lyric-tw2s.json").bufferedReader(Charsets.UTF_8).use {
            LyricSearchConverter(JsonParser.parseReader(it).asJsonObject)
        }
    }

    fun keyword(title: String, artist: String): String {
        if (title.isBlank()) return ""
        val name = converter.convert(title).trim()
        val singer = converter.convert(artist).trim()
        return if (singer.isBlank()) name else "$singer - $name"
    }
}
