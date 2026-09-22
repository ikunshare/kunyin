package com.ikunshare.sound.tool

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

object ChineseConverter {

    /** 繁→简 查找表 */
    private lateinit var tradToSimp: Map<Char, Char>

    /** 简→繁 查找表 */
    private lateinit var simpToTrad: Map<Char, Char>

    /**
     * 从 assets/cc/ 加载对照表，Application.onCreate 中调用一次即可。
     * simp.txt 与 trad.txt 逐行对应，每行一个字。
     * 若资源缺失则降级为空表，convert() 直接原样返回字符。
     */
    fun init(context: Context) {
        val simpChars = runCatching { readLines(context, "cc/simp.txt") }.getOrDefault(emptyList())
        val tradChars = runCatching { readLines(context, "cc/trad.txt") }.getOrDefault(emptyList())
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
        tradToSimp = t2s
        simpToTrad = s2t
    }

    fun toSimplified(text: String): String = convert(text, tradToSimp)

    fun toTraditional(text: String): String = convert(text, simpToTrad)

    private fun convert(text: String, table: Map<Char, Char>): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            sb.append(table.getOrDefault(c, c))
        }
        return sb.toString()
    }

    private fun readLines(context: Context, assetPath: String): List<String> {
        return context.assets.open(assetPath).use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                reader.readLines()
            }
        }
    }
}
