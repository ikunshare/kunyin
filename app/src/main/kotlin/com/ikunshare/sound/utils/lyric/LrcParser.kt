package com.ikunshare.sound.utils.lyric

import android.text.TextUtils
import android.util.Base64
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.LinkedList
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.abs

object LrcParser {

    private val RXP_INFO = Pattern.compile("^\\{\"")
    private val RXP_LINE_TIME = Pattern.compile("^\\[(\\d+),(\\d+)(?:,\\d+)?]")
    private val RXP_WORD_TIME_ALL = Pattern.compile("(\\(\\d+,\\d+(?:,\\d+)?\\))")
    private val RXP_TIME_TAG = Pattern.compile("^\\[([\\d:.]+)]")

    data class LyricResult(
        var lyric: String = "",
        var trans: String = "",
        var roma: String = "",
        var chase: String = "",
        var chroma: String = "",
        var phonetic: String = ""
    )

    private data class ParsedInfo(
        var lyric: String = "",
        var lxlyric: String = ""
    )

    @JvmStatic
    fun parseWy(
        yrcStr: String?,
        yTransStr: String?,
        yRomaStr: String?,
        lrcStr: String?,
        tLrcStr: String?,
        rLrcStr: String?
    ): LyricResult {
        val result = LyricResult()

        // 修复畸形时间戳: [mm:ss:xx] -> [mm:ss.xx]，以及罗马音 [mm:ss.xx0] -> [mm:ss.xx]
        val fixedLrc = fixTimeLabel(lrcStr)
        val fixedTLrc = fixTimeLabel(tLrcStr)
        val fixedRLrc = fixTimeLabelRoma(rLrcStr)

        // 路径 1: 有 yrc，走逐字解析；ytlrc/yromalrc 是与 yrc 网格对齐的翻译/音译
        if (!TextUtils.isEmpty(yrcStr)) {
            val lines = parseHeaderInfo(yrcStr!!)
            if (lines.isNotEmpty()) {
                val yrcInfo = parseLyric(lines)
                if (!TextUtils.isEmpty(yrcInfo.lyric)) {
                    result.lyric = yrcInfo.lyric
                    result.chase = yrcInfo.lxlyric
                    if (!TextUtils.isEmpty(yTransStr)) {
                        val tLines = parseHeaderInfo(yTransStr!!)
                        if (tLines.isNotEmpty()) {
                            result.trans = fixTimeTag(result.lyric, TextUtils.join("\n", tLines))
                        }
                    }
                    if (!TextUtils.isEmpty(yRomaStr)) {
                        val rLines = parseHeaderInfo(yRomaStr!!)
                        if (rLines.isNotEmpty()) {
                            result.roma = fixTimeTag(result.lyric, TextUtils.join("\n", rLines))
                        }
                    }
                    return result
                }
            }
        }

        // 路径 2: 普通 lrc/tlrc/romalrc — 时间网格相同，直接 passthrough
        if (!TextUtils.isEmpty(fixedLrc)) {
            val lines = parseHeaderInfo(fixedLrc!!)
            if (lines.isNotEmpty()) result.lyric = TextUtils.join("\n", lines)
        }
        if (!TextUtils.isEmpty(fixedTLrc)) {
            val lines = parseHeaderInfo(fixedTLrc!!)
            if (lines.isNotEmpty()) result.trans = TextUtils.join("\n", lines)
        }
        if (!TextUtils.isEmpty(fixedRLrc)) {
            val lines = parseHeaderInfo(fixedRLrc!!)
            if (lines.isNotEmpty()) result.roma = TextUtils.join("\n", lines)
        }

        return result
    }

    private val RXP_FIX_LABEL = Regex("""\[(\d{2}:\d{2}):(\d{2})]""")
    private val RXP_FIX_LABEL_ROMA = Regex("""\[(\d{2}:\d{2}):(\d{2,3})]""")
    private val RXP_FIX_LABEL_ROMA_TRAIL = Regex("""\[(\d{2}:\d{2}\.\d{2})0]""")

    private fun fixTimeLabel(s: String?): String? =
        s?.let { RXP_FIX_LABEL.replace(it) { m -> "[${m.groupValues[1]}.${m.groupValues[2]}]" } }

    private fun fixTimeLabelRoma(s: String?): String? = s?.let {
        var r = RXP_FIX_LABEL_ROMA.replace(it) { m -> "[${m.groupValues[1]}.${m.groupValues[2]}]" }
        r = RXP_FIX_LABEL_ROMA_TRAIL.replace(r) { m -> "[${m.groupValues[1]}]" }
        r
    }

    @JvmStatic
    fun parseKg(krcData: ByteArray?): LyricResult {
        val result = LyricResult()
        if (krcData == null || krcData.isEmpty()) return result
        try {
            var rawStr = LrcNative.decryptKrc(krcData) ?: return result
            rawStr = rawStr.replace("\r", "").replace(Regex("^.*\\[id:$\\w+]\n"), "")

            val romaList = mutableListOf<String>()
            val romaCharList = mutableListOf<List<String>>()
            val transList = mutableListOf<String>()
            val phoneticList = mutableListOf<String>()
            val rxLang = Pattern.compile("\\[language:([\\w=/+]+)]")
            val langMatcher = rxLang.matcher(rawStr)

            if (langMatcher.find()) {
                val b64 = langMatcher.group(1)
                rawStr = langMatcher.replaceFirst("").trim()
                try {
                    val decodedJson =
                        String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8)
                    val json = JSONObject(decodedJson)
                    val content = json.optJSONArray("content")
                    if (content != null) {
                        for (i in 0 until content.length()) {
                            val item = content.getJSONObject(i)
                            val type = item.optInt("type")
                            val lyricContent = item.optJSONArray("lyricContent")
                            if (lyricContent != null) {
                                if (type == 0) {
                                    for (j in 0 until lyricContent.length()) {
                                        val innerArr = lyricContent.optJSONArray(j)
                                        if (innerArr != null && innerArr.length() > 0) {
                                            val chars = mutableListOf<String>()
                                            val sb = StringBuilder()
                                            for (k in 0 until innerArr.length()) {
                                                val s = innerArr.optString(k, "")
                                                chars.add(s)
                                                sb.append(s)
                                            }
                                            romaCharList.add(chars)
                                            romaList.add(sb.toString())
                                        } else {
                                            romaCharList.add(emptyList())
                                            romaList.add(lyricContent.optString(j, ""))
                                        }
                                    }
                                } else {
                                    for (j in 0 until lyricContent.length()) {
                                        val innerArr = lyricContent.optJSONArray(j)
                                        if (innerArr != null && innerArr.length() > 0) {
                                            val sb = StringBuilder()
                                            for (k in 0 until innerArr.length()) {
                                                sb.append(innerArr.optString(k, ""))
                                            }
                                            transList.add(sb.toString())
                                        } else {
                                            transList.add(lyricContent.optString(j, ""))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // contentV2: type 2 = AI 谐音
                    val contentV2 = json.optJSONArray("contentV2")
                    if (contentV2 != null) {
                        for (i in 0 until contentV2.length()) {
                            val item = contentV2.getJSONObject(i)
                            val type = item.optInt("type")
                            if (type == 2) {
                                val lyricContent = item.optJSONArray("lyricContent")
                                if (lyricContent != null) {
                                    for (j in 0 until lyricContent.length()) {
                                        val innerArr = lyricContent.optJSONArray(j)
                                        if (innerArr != null && innerArr.length() > 0) {
                                            val sb = StringBuilder()
                                            for (k in 0 until innerArr.length()) {
                                                sb.append(innerArr.optString(k, ""))
                                            }
                                            phoneticList.add(sb.toString())
                                        } else {
                                            phoneticList.add(lyricContent.optString(j, ""))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val lines = rawStr.split("\n")
            val sbLrc = StringBuilder()
            val sbChase = StringBuilder()
            val sbChroma = StringBuilder()
            val sbTrans = StringBuilder()
            val sbRoma = StringBuilder()
            val sbPhonetic = StringBuilder()
            val rxTime = Pattern.compile("\\[(\\d+),(\\d+)]")
            val rxWord = Pattern.compile("<(\\d+),(\\d+),(\\d+)>([^<]*)")
            var idx = 0

            for (line in lines) {
                if (TextUtils.isEmpty(line.trim())) continue
                val timeMatch = rxTime.matcher(line)
                if (!timeMatch.find()) continue
                val startMs = timeMatch.group(1)!!.toLong()
                val cleanLine = rxTime.matcher(line).replaceFirst("")
                val timeTag = formatTime(startMs)
                val wordMatch = rxWord.matcher(cleanLine)
                val textContent = StringBuilder()
                val chaseContent = StringBuilder()
                var lastEnd = startMs

                data class WordTiming(val absStart: Long, val absEnd: Long, val text: String)

                val wordTimings = mutableListOf<WordTiming>()

                while (wordMatch.find()) {
                    val offset = wordMatch.group(1)!!.toLong()
                    val dur = wordMatch.group(2)!!.toLong()
                    val text = wordMatch.group(4)!!
                    textContent.append(text)
                    val absStart = startMs + offset
                    chaseContent.append("<").append(formatElyricTime(absStart)).append(">")
                        .append(text)
                    lastEnd = absStart + dur
                    wordTimings.add(WordTiming(absStart, lastEnd, text))
                }
                if (chaseContent.isNotEmpty()) {
                    chaseContent.append("<").append(formatElyricTime(lastEnd)).append(">")
                }

                if (sbLrc.isNotEmpty()) sbLrc.append("\n")
                sbLrc.append(timeTag).append(textContent)
                if (sbChase.isNotEmpty()) sbChase.append("\n")
                sbChase.append(timeTag).append(chaseContent)

                if (idx < romaCharList.size && romaCharList[idx].isNotEmpty() && wordTimings.isNotEmpty()) {
                    val romaChars = romaCharList[idx]
                    val chromaContent = StringBuilder()
                    val count = minOf(romaChars.size, wordTimings.size)
                    for (w in 0 until count) {
                        chromaContent.append("<").append(formatElyricTime(wordTimings[w].absStart))
                            .append(">")
                            .append(romaChars[w])
                    }
                    if (count > 0) {
                        chromaContent.append("<")
                            .append(formatElyricTime(wordTimings[count - 1].absEnd)).append(">")
                    }
                    if (sbChroma.isNotEmpty()) sbChroma.append("\n")
                    sbChroma.append(timeTag).append(chromaContent)
                }

                if (idx < transList.size) {
                    if (sbTrans.isNotEmpty()) sbTrans.append("\n")
                    sbTrans.append(timeTag).append(transList[idx])
                }
                if (idx < romaList.size) {
                    if (sbRoma.isNotEmpty()) sbRoma.append("\n")
                    sbRoma.append(timeTag).append(romaList[idx])
                }
                if (idx < phoneticList.size) {
                    if (sbPhonetic.isNotEmpty()) sbPhonetic.append("\n")
                    sbPhonetic.append(timeTag).append(phoneticList[idx])
                }
                idx++
            }

            result.lyric = sbLrc.toString()
            result.chase = sbChase.toString()
            result.chroma = sbChroma.toString()
            result.trans = sbTrans.toString()
            result.roma = sbRoma.toString()
            result.phonetic = sbPhonetic.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    //MARK_PARSE_KW_BEGIN
    @JvmStatic
    fun parseKw(bytesData: ByteArray?): LyricResult {
        val result = LyricResult()
        if (bytesData == null || bytesData.isEmpty()) return result
        try {
            val decrypted = LrcNative.decryptKuwo(bytesData)
            if (decrypted == null || decrypted.isEmpty()) return result

            val lrcStr = try {
                String(decrypted, charset("GB18030"))
            } catch (_: Exception) {
                String(decrypted)
            }

            val lines = lrcStr.split("\n")
            val sbHead = StringBuilder()
            val rxTag = Pattern.compile("\\[(ver|ti|ar|al|offset|by|kuwo):(.*)]")
            val rxTime = Pattern.compile("^\\[(\\d{1,2}):(\\d{1,2})\\.(\\d{1,3})]")
            val rxWord = Pattern.compile("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>([^<]*)")
            val rxWordClean = Pattern.compile("<-?\\d+,-?\\d+(?:,-?\\d+)?>")

            var kwOffset1 = 1
            var kwOffset2 = 1
            var kwDecodeOk = false

            for (line in lines) {
                val trimmedLine = line.trim()
                val tagMatch = rxTag.matcher(trimmedLine)
                if (tagMatch.matches() && "kuwo" == tagMatch.group(1)) {
                    var content = tagMatch.group(2)
                    if (content != null && content.contains("][")) {
                        content = content.substring(0, content.indexOf("]["))
                    }
                    try {
                        val octalVal = Integer.parseInt(content!!.trim(), 8)
                        kwOffset1 = octalVal / 10
                        kwOffset2 = octalVal % 10
                        if (kwOffset1 != 0 && kwOffset2 != 0) {
                            kwDecodeOk = true
                        }
                    } catch (_: Exception) {
                    }
                    break
                }
            }

            val timeMsList = mutableListOf<Long>()
            val parsedList = mutableListOf<Array<String>>()
            var hasWordTiming = false

            for (line in lines) {
                val trimmedLine = line.trim()
                if (TextUtils.isEmpty(trimmedLine)) continue
                if (rxTag.matcher(trimmedLine).matches()) {
                    sbHead.append(trimmedLine).append("\n")
                    continue
                }
                val timeMatch = rxTime.matcher(trimmedLine)
                if (!timeMatch.find()) continue

                val timeStr = timeMatch.group(0)!!
                val content = trimmedLine.substring(timeStr.length)
                val min = timeMatch.group(1)!!.toInt()
                val sec = timeMatch.group(2)!!.toInt()
                val msStr = timeMatch.group(3)!!
                var msVal = msStr.toInt()
                when (msStr.length) {
                    2 -> msVal *= 10
                    1 -> msVal *= 100
                }
                val lineStartMs = min * 60000L + sec * 1000L + msVal

                val cleanContent = rxWordClean.matcher(content).replaceAll("")

                val m = rxWord.matcher(content)
                val chaseContent = StringBuilder()
                var lastEnd = 0L
                var lineHasWords = false
                var prevEnd = 0L

                while (m.find()) {
                    lineHasWords = true
                    val v1 = m.group(1)!!.toLong()
                    val v2 = m.group(2)!!.toLong()
                    val text = m.group(3)!!

                    val startTime: Long
                    val endTime: Long

                    if (kwDecodeOk) {
                        val relStart = abs((v1 + v2) / (kwOffset1 * 2))
                        val relDur = abs((v1 - v2) / (kwOffset2 * 2))
                        var absStart = lineStartMs + relStart
                        if (prevEnd > 0 && absStart < prevEnd) {
                            absStart = prevEnd
                        }
                        startTime = absStart
                        endTime = startTime + relDur
                    } else {
                        startTime = if (v1 < lineStartMs / 2) lineStartMs + v1 else v1
                        endTime = startTime + v2
                    }

                    chaseContent.append("<").append(formatElyricTime(startTime)).append(">")
                        .append(text)
                    lastEnd = endTime
                    prevEnd = endTime
                }
                if (lineHasWords && lastEnd > 0) {
                    chaseContent.append("<").append(formatElyricTime(lastEnd)).append(">")
                }
                if (lineHasWords) hasWordTiming = true

                timeMsList.add(lineStartMs)
                parsedList.add(
                    arrayOf(
                        timeStr,
                        cleanContent,
                        if (lineHasWords) chaseContent.toString() else cleanContent
                    )
                )
            }

            val lrcIdx = mutableListOf<Int>()
            val transIdx = mutableListOf<IntArray>()
            val seenTimes = mutableSetOf<Long>()

            for (i in parsedList.indices) {
                val timeMs = timeMsList[i]
                if (seenTimes.contains(timeMs)) {
                    if (lrcIdx.size >= 2) {
                        val transDataIdx = lrcIdx.removeAt(lrcIdx.size - 1)
                        val prevLrcDataIdx = lrcIdx[lrcIdx.size - 1]
                        transIdx.add(intArrayOf(transDataIdx, prevLrcDataIdx))
                        lrcIdx.add(i)
                    }
                } else {
                    lrcIdx.add(i)
                    seenTimes.add(timeMs)
                }
            }

            if (!hasWordTiming && transIdx.size > lrcIdx.size * 0.3
                && lrcIdx.size - transIdx.size > 6
            ) {
                transIdx.clear()
                lrcIdx.clear()
                for (i in parsedList.indices) lrcIdx.add(i)
            }

            val sbLrc = StringBuilder()
            val sbChase = StringBuilder()
            val sbTrans = StringBuilder()

            for (idx in lrcIdx) {
                val data = parsedList[idx]
                if (sbLrc.isNotEmpty()) sbLrc.append("\n")
                sbLrc.append(data[0]).append(data[1])
                if (sbChase.isNotEmpty()) sbChase.append("\n")
                sbChase.append(data[0]).append(data[2])
            }
            for (ti in transIdx) {
                val transData = parsedList[ti[0]]
                val lrcTimeStr = parsedList[ti[1]][0]
                if (sbTrans.isNotEmpty()) sbTrans.append("\n")
                sbTrans.append(lrcTimeStr).append(transData[1])
            }

            result.lyric = sbLrc.toString()
            result.chase = sbChase.toString()
            result.trans = sbTrans.toString()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    private val RXP_STANDARD_LRC = Pattern.compile("^\\[\\d{1,2}:\\d{1,2}[.:]\\d{1,3}]")

    @JvmStatic
    fun parseTx(qrcHex: String?, transHex: String?, romaHex: String?): LyricResult {
        val result = LyricResult()
        if (!TextUtils.isEmpty(qrcHex)) {
            val qrcDecrypted = LrcNative.decryptQrc(qrcHex!!)
            if (!TextUtils.isEmpty(qrcDecrypted)) {
                parseTxQrc(qrcDecrypted, result)
                if (result.lyric.isEmpty()) {
                    // QRC 解析无结果，尝试当标准 LRC 或纯文本使用
                    result.lyric = qrcDecrypted!!
                }
            }
        }
        if (!TextUtils.isEmpty(transHex)) {
            val transDecrypted = LrcNative.decryptQrc(transHex!!)
            result.trans = transDecrypted ?: ""
        }
        if (!TextUtils.isEmpty(romaHex)) {
            val romaDecrypted = LrcNative.decryptQrc(romaHex!!)
            if (!TextUtils.isEmpty(romaDecrypted)) {
                val romaResult = LyricResult()
                parseTxQrc(romaDecrypted, romaResult)
                if (romaResult.lyric.isEmpty() && isStandardLrc(romaDecrypted!!)) {
                    romaResult.lyric = romaDecrypted
                }
                result.roma = romaResult.lyric
                result.chroma = romaResult.chase
            }
        }
        return result
    }

    fun isStandardLrc(text: String): Boolean {
        return text.lines().any { RXP_STANDARD_LRC.matcher(it.trim()).find() }
    }

    fun parseTxQrc(text: String?, result: LyricResult) {
        if (TextUtils.isEmpty(text)) return
        val cleaned = text!!.replace(Regex(" LyricContent=\".*?\""), "")
        val lines = cleaned.split("\n")
        val sbLrc = StringBuilder()
        val sbChase = StringBuilder()
        val rxLine = Pattern.compile("^\\[(\\d+),(\\d+)]")
        val rxWordSplit = Pattern.compile("\\((\\d+),(\\d+)\\)")

        for (line in lines) {
            val trimmedLine = line.trim()
            val match = rxLine.matcher(trimmedLine)
            if (!match.find()) continue
            val startMs = match.group(1)!!.toLong()
            val timeTag = formatTime(startMs)
            val content = rxLine.matcher(trimmedLine).replaceFirst("")

            val cleanText = StringBuilder()
            val chaseText = StringBuilder()
            var lastIndex = 0
            val wordMatch = rxWordSplit.matcher(content)
            var lastEnd = 0L

            while (wordMatch.find()) {
                val word = content.substring(lastIndex, wordMatch.start())
                cleanText.append(word)
                try {
                    val wOffset = wordMatch.group(1)!!.toLong()
                    val wDur = wordMatch.group(2)!!.toLong()
                    chaseText.append("<").append(formatElyricTime(wOffset)).append(">").append(word)
                    lastEnd = wOffset + wDur
                } catch (_: Exception) {
                    chaseText.append(word)
                }
                lastIndex = wordMatch.end()
            }
            if (lastIndex < content.length) {
                cleanText.append(content.substring(lastIndex))
            }
            if (lastEnd > 0) {
                chaseText.append("<").append(formatElyricTime(lastEnd)).append(">")
            }

            if (sbLrc.isNotEmpty()) sbLrc.append("\n")
            sbLrc.append(timeTag).append(cleanText)
            if (sbChase.isNotEmpty()) sbChase.append("\n")
            sbChase.append(timeTag).append(chaseText)
        }
        result.lyric = sbLrc.toString()
        result.chase = sbChase.toString()
    }

    private fun parseHeaderInfo(str: String): List<String> {
        val trimmed = str.trim().replace("\r", "")
        if (trimmed.isEmpty()) return emptyList()
        val lines = trimmed.split("\n")
        val resultList = mutableListOf<String>()
        for (line in lines) {
            if (!RXP_INFO.matcher(line).find()) {
                resultList.add(line)
                continue
            }
            try {
                val info = JSONObject(line)
                val t = info.optLong("t")
                val c = info.optJSONArray("c")
                val timeTag = msFormat(t)
                if (!TextUtils.isEmpty(timeTag) && c != null) {
                    val sb = StringBuilder()
                    sb.append(timeTag)
                    for (i in 0 until c.length()) {
                        val item = c.getJSONObject(i)
                        sb.append(item.optString("tx", ""))
                    }
                    resultList.add(sb.toString())
                } else {
                    resultList.add("")
                }
            } catch (_: Exception) {
                resultList.add("")
            }
        }
        return resultList
    }

    private fun parseLyric(lines: List<String>): ParsedInfo {
        val info = ParsedInfo()
        val lxlrcLines = mutableListOf<String>()
        val lrcLines = mutableListOf<String>()

        for (line in lines) {
            val trimmedLine = line.trim()
            val lineTimeMatcher = RXP_LINE_TIME.matcher(trimmedLine)

            if (!lineTimeMatcher.find()) {
                if (trimmedLine.startsWith("[offset")) {
                    lxlrcLines.add(trimmedLine)
                    lrcLines.add(trimmedLine)
                }
                continue
            }

            val startMsTime = lineTimeMatcher.group(1)!!.toLong()
            val startTimeStr = formatTime(startMsTime)
            if (TextUtils.isEmpty(startTimeStr)) continue

            val words = RXP_LINE_TIME.matcher(trimmedLine).replaceFirst("")
            lrcLines.add(startTimeStr + RXP_WORD_TIME_ALL.matcher(words).replaceAll(""))

            val sbLx = StringBuilder()
            val mAll = Pattern.compile("(\\(\\d+,\\d+(?:,\\d+)?\\))([^(]*)").matcher(words)
            var lastEndAbs = startMsTime
            var hasMatches = false

            while (mAll.find()) {
                hasMatches = true
                val timePart = mAll.group(1)!!
                val wordPart = mAll.group(2)!!

                val mTime = Pattern.compile("\\((\\d+),(\\d+)(?:,\\d+)?\\)").matcher(timePart)
                if (mTime.find()) {
                    val tStart = mTime.group(1)!!.toLong()
                    val tDur = mTime.group(2)!!.toLong()
                    sbLx.append("<").append(formatElyricTime(tStart)).append(">").append(wordPart)
                    lastEndAbs = tStart + tDur
                }
            }

            if (hasMatches) {
                sbLx.append("<").append(formatElyricTime(lastEndAbs)).append(">")
                lxlrcLines.add(startTimeStr + sbLx)
            } else {
                lxlrcLines.add(startTimeStr + words)
            }
        }

        info.lyric = TextUtils.join("\n", lrcLines)
        info.lxlyric = TextUtils.join("\n", lxlrcLines)
        return info
    }

    private fun fixTimeTag(lrc: String, targetLrc: String): String {
        var lrcLines = LinkedList(lrc.split("\n"))
        val targetLines = targetLrc.split("\n")

        val temp = mutableListOf<String>()
        val newLrc = mutableListOf<String>()

        for (line in targetLines) {
            val matcher = RXP_TIME_TAG.matcher(line)
            if (!matcher.find()) continue
            val words = matcher.replaceFirst("")
            if (words.trim().isEmpty()) continue

            val t1 = getIntv(matcher.group(1))

            while (lrcLines.isNotEmpty()) {
                val lrcLine = lrcLines.poll()!!
                val lrcMatcher = RXP_TIME_TAG.matcher(lrcLine)
                if (!lrcMatcher.find()) continue
                val t2 = getIntv(lrcMatcher.group(1))
                if (abs(t1 - t2) < 100) {
                    val replaced =
                        RXP_TIME_TAG.matcher(line).replaceFirst(lrcMatcher.group(0)!!).trim()
                    if (replaced.isNotEmpty()) newLrc.add(replaced)
                    break
                }
                temp.add(lrcLine)
            }
            if (temp.isNotEmpty()) {
                val rebuilt = LinkedList<String>()
                rebuilt.addAll(temp)
                rebuilt.addAll(lrcLines)
                lrcLines = rebuilt
                temp.clear()
            }
        }

        return TextUtils.join("\n", newLrc)
    }

    private fun getIntv(interval: String?): Long {
        if (TextUtils.isEmpty(interval)) return 0
        var s = interval!!
        if (!s.contains(".")) s += ".0"
        val arr = s.split("[:.]+".toRegex())
        val list = arr.toMutableList()
        while (list.size < 3) list.add(0, "0")
        val m = list[0].toLong()
        val sec = list[1].toLong()
        val msStr = list[2]
        var ms = msStr.toLong()
        when (msStr.length) {
            2 -> ms *= 10
            1 -> ms *= 100
        }
        return m * 60000 + sec * 1000 + ms
    }

    private fun msFormat(timeMs: Long): String {
        var remaining = timeMs
        val ms = remaining % 1000
        remaining /= 1000
        val m = remaining / 60
        remaining %= 60
        val s = remaining
        return String.format(Locale.US, "[%02d:%02d.%03d]", m, s, ms)
    }

    private fun formatTime(ms: Long): String = msFormat(ms)

    private fun formatElyricTime(ms: Long): String {
        val totalSeconds = ms / 1000.0
        val minutes = (totalSeconds / 60).toInt()
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%06.3f", minutes, seconds)
    }
}

/**
 * 把 LrcParser 返回的 LyricResult 转成 model.Lyric 方便 UI 使用。
 * chase 为逐字 LRC，映射到 Lyric.char；chroma（音译逐字）原版无输出，保持空。
 */
fun LrcParser.LyricResult.toLyric(): com.ikunshare.sound.model.Lyric =
    com.ikunshare.sound.model.Lyric(
        lrc = lyric,
        trans = trans,
        roma = roma,
        char = chase,
        chroma = chroma,
        phonetic = phonetic
    )

