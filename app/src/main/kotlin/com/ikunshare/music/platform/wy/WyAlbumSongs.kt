package com.ikunshare.music.platform.wy

import com.google.gson.JsonArray
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.platform.base.MusicListResult

internal fun parseWyAlbumSongs(songs: JsonArray?): MusicListResult {
    val items = mutableListOf<NeteaseMusicItem>()
    val cds = mutableListOf<String>()
    songs?.forEach { element ->
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val item = runCatching { NeteaseMusicItem.parseTrackInfo(obj) }.getOrNull() ?: return@forEach
        items += item
        cds += obj.get("cd")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
    }
    return MusicListResult(
        "wy",
        false,
        0,
        items.size,
        items,
        discs = buildWyAlbumDiscs(cds)
    )
}

internal fun buildWyAlbumDiscs(cds: List<String>): List<AlbumDisc>? {
    val discs = mutableListOf<AlbumDisc>()
    var lastRaw: String? = null
    for (raw in cds) {
        if (lastRaw != null && raw == lastRaw) {
            val last = discs.last()
            discs[discs.lastIndex] = last.copy(count = last.count + 1)
            continue
        }
        lastRaw = raw
        val text = raw.trim()
        val match = Regex("^(\\d+)\\s*(.*)$").find(text)
        val parsedNo = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val no = if (parsedNo > 0) parsedNo else discs.size + 1
        val custom = when {
            match != null -> match.groupValues[2].trim()
            text.equals("null", ignoreCase = true) -> ""
            else -> text
        }
        discs += AlbumDisc(no, custom.ifEmpty { "CD$no" }, 1)
    }
    return discs.takeIf { it.size > 1 }
}
