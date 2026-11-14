package com.ikunshare.music.tool

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object ChineseConverter {
    private data class Tables(
        val tradToSimp: Map<Char, Char>,
        val simpToTrad: Map<Char, Char>
    )

    private lateinit var appContext: Context
    private val tableLock = Any()

    @Volatile
    private var tableCache: Tables? = null

    /** 注册应用上下文；对照表在首次转换时加载，资源缺失则使用空表。 */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun tables(): Tables {
        tableCache?.let { return it }
        synchronized(tableLock) {
            tableCache?.let { return it }
            val simpChars = runCatching { readLines(appContext, "cc/simp.txt") }.getOrDefault(emptyList())
            val tradChars = runCatching { readLines(appContext, "cc/trad.txt") }.getOrDefault(emptyList())
            val size = minOf(simpChars.size, tradChars.size)
            val t2s = HashMap<Char, Char>(size)
            val s2t = HashMap<Char, Char>(size)
            for (i in 0 until size) {
                val s = simpChars[i]
                val t = tradChars[i]
                if (s.length == 1 && t.length == 1 && s[0] != t[0]) {
                    t2s[t[0]] = s[0]
                    s2t[s[0]] = t[0]
                }
            }
            val loaded = Tables(t2s, s2t)
            tableCache = loaded
            return loaded
        }
    }

    fun toSimplified(text: String): String = convert(text, tables().tradToSimp)

    fun toTraditional(text: String): String = convert(text, tables().simpToTrad)

    private fun convert(text: String, table: Map<Char, Char>): String {
        var result: StringBuilder? = null
        for ((index, char) in text.withIndex()) {
            val converted = table.getOrDefault(char, char)
            val builder = result
            if (builder != null) {
                builder.append(converted)
            } else if (converted != char) {
                result = StringBuilder(text.length)
                    .append(text, 0, index)
                    .append(converted)
            }
        }
        return result?.toString() ?: text
    }

    private fun readLines(context: Context, assetPath: String): List<String> {
        return context.assets.open(assetPath).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readLines()
            }
        }
    }
}
