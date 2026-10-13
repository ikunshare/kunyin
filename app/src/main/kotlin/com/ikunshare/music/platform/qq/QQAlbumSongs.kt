package com.ikunshare.music.platform.qq

import com.google.gson.JsonObject
import com.ikunshare.music.platform.base.AlbumDisc
import com.ikunshare.music.platform.base.MusicListResult

/** QQ index_cd starts at zero; the custom-name map uses one-based disc numbers. */
internal fun parseQqAlbumSongs(data: JsonObject?): MusicListResult {
    val songs = mutableListOf<QQMusicItem>()
    val discs = mutableListOf<AlbumDisc>()
    val customNames = data?.get("cdNameMap")?.takeIf { it.isJsonObject }?.asJsonObject
    val newStyle = runCatching { data?.get("cdNewStyle")?.asInt == 1 }.getOrDefault(false)
    data?.get("songList")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { element ->
        val info = element.takeIf { it.isJsonObject }?.asJsonObject?.get("songInfo")
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
        val song = runCatching { QQMusicItem.parseTrackInfo(info) }.getOrNull() ?: return@forEach
        val no = (runCatching { info.get("index_cd")?.asInt }.getOrNull() ?: 0) + 1
        songs.add(song)
        val last = discs.lastOrNull()
        if (last?.no == no) discs[discs.lastIndex] = last.copy(count = last.count + 1)
        else {
            val name = if (newStyle) customNames?.get(no.toString())?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }?.asString?.trim()?.takeIf { it.isNotEmpty() } else null
            discs.add(AlbumDisc(no, name ?: "CD$no", 1))
        }
    }
    return MusicListResult("qq", false, 0, songs.size.coerceAtLeast(1), songs,
        discs = discs.takeIf { it.size > 1 })
}
