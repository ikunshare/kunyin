package com.ikunshare.sound.platform.base

/**
 * 歌手详情页加载前的占位缓存。搜索结果点击时 put 一份,
 * ArtistViewModel 进入详情页前可立刻拿到 avatar/name 等字段,
 * 避免空白等待;详情接口返回后做字段级合并(fresh 非空优先)。
 *
 * 仅 in-memory,进程死掉就清掉,不需要持久化。
 */
object ArtistPreviewCache {
    private val cache = mutableMapOf<String, ArtistInfoResult>()

    fun put(info: ArtistInfoResult) {
        cache["${info.source}:${info.artistId}"] = info
    }

    fun get(source: String, artistId: String): ArtistInfoResult? =
        cache["$source:$artistId"]

    fun merge(fresh: ArtistInfoResult?, cached: ArtistInfoResult?): ArtistInfoResult? {
        if (fresh == null) return cached
        if (cached == null) return fresh
        return fresh.copy(
            name = fresh.name.takeIf { it.isNotBlank() } ?: cached.name,
            avatar = fresh.avatar?.takeIf { it.isNotBlank() } ?: cached.avatar,
            albumCount = if (fresh.albumCount > 0) fresh.albumCount else cached.albumCount,
            musicCount = if (fresh.musicCount > 0) fresh.musicCount else cached.musicCount,
            fansCount = if (fresh.fansCount > 0) fresh.fansCount else cached.fansCount,
            description = fresh.description?.takeIf { it.isNotBlank() } ?: cached.description
        )
    }
}
