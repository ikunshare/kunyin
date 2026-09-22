package com.ikunshare.sound.platform.base

/**
 * 专辑详情页加载前的占位缓存。搜索结果点击时 put 一份,
 * AlbumViewModel 进入详情页前可立刻拿到 cover/artist/publish_time 等字段,
 * 避免空白等待;详情接口返回后做字段级合并(fresh 非空优先)。
 *
 * 仅 in-memory,进程死掉就清掉,不需要持久化。
 */
object AlbumPreviewCache {
    private val cache = mutableMapOf<String, AlbumInfoResult>()

    fun put(info: AlbumInfoResult) {
        cache["${info.source}:${info.albumId}"] = info
    }

    fun get(source: String, albumId: String): AlbumInfoResult? =
        cache["$source:$albumId"]

    fun merge(fresh: AlbumInfoResult?, cached: AlbumInfoResult?): AlbumInfoResult? {
        if (fresh == null) return cached
        if (cached == null) return fresh
        return fresh.copy(
            cover = fresh.cover?.takeIf { it.isNotBlank() } ?: cached.cover,
            artist = fresh.artist?.takeIf { it.isNotBlank() } ?: cached.artist,
            artistId = fresh.artistId?.takeIf { it.isNotBlank() } ?: cached.artistId,
            publishTime = fresh.publishTime?.takeIf { it > 0 } ?: cached.publishTime,
            company = fresh.company?.takeIf { it.isNotBlank() } ?: cached.company,
            subType = fresh.subType?.takeIf { it.isNotBlank() } ?: cached.subType,
            description = fresh.description?.takeIf { it.isNotBlank() } ?: cached.description,
            size = if (fresh.size > 0) fresh.size else cached.size
        )
    }
}
