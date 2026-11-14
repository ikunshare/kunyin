package com.ikunshare.music.platform.kg

import com.google.gson.JsonParser
import com.ikunshare.music.utils.HTTPUtils
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.FutureTask

internal data class KgSpecial(
    val id: String,
    val name: String,
    val cover: String?,
    val description: String?,
    val hashes: List<String>
)

private val SPECIAL_REF = Regex("^id_(\\d+)$")
private val SPECIAL_URL = Regex("special/single/(\\d+)(?:-\\d+-\\d+)?\\.html")
private val SPECIAL_NUMERIC = Regex("\\d+")
private val SPECIAL_LIST_DATA = Regex("global\\.data = (\\[.+\\]);")
private val SPECIAL_LIST_INFO = Regex("global = \\{[\\s\\S]+?name: \"(.+)\"[\\s\\S]+?pic: \"(.+)\"[\\s\\S]+?\\};")
private val HTML_LINE_BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]*>")
private const val SPECIAL_DESC_PREFIX =
    "<div class=\"pc_specail_text pc_singer_tab_content\" id=\"specailIntroduceWrap\">"
private const val SPECIAL_CACHE_TTL = 5 * 60 * 1000L

private data class CachedKgSpecial(val createdAt: Long, val value: KgSpecial)

private val specialCache = ConcurrentHashMap<String, CachedKgSpecial>()

internal fun parseKgSpecialId(input: String): String? {
    val value = input.trim()
    val match = SPECIAL_REF.matchEntire(value) ?: SPECIAL_URL.find(value)
    if (match != null) return match.groupValues[1]
    return value.takeIf { SPECIAL_NUMERIC.matches(it) }
}

internal fun isKgSpecialRef(input: String): Boolean {
    val value = input.trim()
    return SPECIAL_REF.matches(value) || SPECIAL_URL.containsMatchIn(value)
}

private fun decodeKgSpecialText(value: String): String = value
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace(HTML_LINE_BREAK, "\n")
    .replace(HTML_TAG, "")
    .trim()

internal fun parseKgSpecial(specialId: String, html: String): KgSpecial? {
    val dataMatch = SPECIAL_LIST_DATA.find(html) ?: return null
    val hashes = try {
        JsonParser.parseString(dataMatch.groupValues[1]).asJsonArray.mapNotNull { element ->
            element.takeIf { it.isJsonObject }?.asJsonObject?.get("hash")
                ?.takeIf { !it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        }.distinct()
    } catch (_: Exception) {
        return null
    }
    val info = SPECIAL_LIST_INFO.find(html)
    var description: String? = null
    val descStart = html.indexOf(SPECIAL_DESC_PREFIX)
    if (descStart >= 0) {
        val rest = html.substring(descStart + SPECIAL_DESC_PREFIX.length)
        val descEnd = rest.indexOf("</div>")
        if (descEnd >= 0) {
            description = decodeKgSpecialText(rest.substring(0, descEnd)).takeIf { it.isNotEmpty() }
        }
    }
    return KgSpecial(
        id = specialId,
        name = info?.groupValues?.get(1)?.let(::decodeKgSpecialText) ?: "歌单 $specialId",
        cover = info?.groupValues?.get(2)?.replace("{size}", "480"),
        description = description,
        hashes = hashes
    )
}

private val specialRequests = ConcurrentHashMap<String, FutureTask<KgSpecial?>>()

internal fun fetchKgSpecial(specialId: String): KgSpecial? {
    val cached = specialCache[specialId]
    if (cached != null && System.currentTimeMillis() - cached.createdAt < SPECIAL_CACHE_TTL) {
        return cached.value
    }
    val task = FutureTask { fetchKgSpecialNetwork(specialId) }
    val pending = specialRequests.putIfAbsent(specialId, task)
    if (pending != null) return runCatching { pending.get() }.getOrNull()
    try {
        task.run()
        return task.get()
    } catch (_: Exception) {
        return null
    } finally {
        specialRequests.remove(specialId, task)
    }
}

private fun fetchKgSpecialNetwork(specialId: String): KgSpecial? {
    repeat(3) {
        val html = runCatching {
            val response = HTTPUtils.get(
                "http://www2.kugou.kugou.com/yueku/v9/special/single/$specialId-5-9999.html"
            )
            response.body?.takeIf { response.isSuccessful }
        }.getOrNull() ?: return@repeat
        val special = parseKgSpecial(specialId, html) ?: return@repeat
        specialCache[specialId] = CachedKgSpecial(System.currentTimeMillis(), special)
        return special
    }
    return null
}
