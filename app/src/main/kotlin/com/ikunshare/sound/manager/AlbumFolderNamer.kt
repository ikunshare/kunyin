package com.ikunshare.sound.manager

import com.ikunshare.sound.platform.base.AlbumInfoResult
import java.util.Calendar

/**
 * 整专下载子目录命名。
 *
 * 默认：`艺人 - 专辑名`。
 * 当 [withYear] 为 true 且能从 [AlbumInfoResult.publishTime] 取到年份时：`年份 艺人 - 专辑名`。
 * 多个艺人统一用 `&` 隔开（源数据可能用 、/ , 分隔）。所有字段都会清理非法文件名字符。
 */
object AlbumFolderNamer {

    private val illegalChars = Regex("[\\\\/:*?\"<>|]")
    private val artistSeparators = Regex("[、,，/＆&]+")

    fun buildSubDir(album: AlbumInfoResult, withYear: Boolean): String {
        val sanitize = { s: String -> s.replace(illegalChars, "_").trim() }

        val artist = album.artist
            ?.takeIf { it.isNotBlank() }
            ?.split(artistSeparators)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("&")
            ?.let { sanitize(it) }
            ?.takeIf { it.isNotBlank() }

        val name = sanitize(album.name)
        val year = if (withYear) extractYear(album.publishTime) else null

        return buildString {
            year?.let { append(it); append(' ') }
            artist?.let { append(it); append(" - ") }
            append(name)
        }
    }

    /** publishTime 为毫秒时间戳，取其年份；无效（null 或 <=0）时返回 null。 */
    private fun extractYear(publishTime: Long?): Int? {
        val ts = publishTime ?: return null
        if (ts <= 0) return null
        return Calendar.getInstance().apply { timeInMillis = ts }.get(Calendar.YEAR)
    }
}
