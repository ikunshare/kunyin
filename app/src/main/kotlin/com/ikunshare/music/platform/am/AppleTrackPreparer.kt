package com.ikunshare.music.platform.am

import com.ikunshare.music.utils.HTTPUtils
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap

sealed interface AppleTrackSource

class CachedAppleTrack(val file: File) : AppleTrackSource {
    val size: Long get() = file.length()
}

class PreparedAppleTrack(
    val playlist: ApplePlaylist,
    val init: AppleInitSegment,
    val header: AppleTrackHeader,
    val templates: Map<String, AppleSampleDecryptor>,
    internal val cache: AppleAudioCache? = null,
    internal val cacheKey: String? = null,
    internal val rangeFetcher: (suspend (String, Long, Long) -> ByteArray)? = null
) : AutoCloseable, AppleTrackSource {
    val fileUrl: String get() = playlist.fileUrl

    @Volatile
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        closeAppleTemplates(templates, null)?.let { throw it }
    }

    fun newReader(
        client: AppleRangeClient = AppleRangeClient(),
        poolLimit: Long = 24 * 1024 * 1024
    ): AppleVirtualTrackReader = newReader(rangeFetcher ?: client::fetch, poolLimit)

    fun newReader(
        fetch: suspend (String, Long, Long) -> ByteArray,
        poolLimit: Long = 24 * 1024 * 1024
    ): AppleVirtualTrackReader {
        val recording = cache?.takeIf { it.enabled }?.let { AppleTrackRecording(it, requireNotNull(cacheKey), header) }
        return AppleVirtualTrackReader(this, fetch, poolLimit, recording)
    }
}

class AppleTrackPreparer(
    private val fetchPlaylist: suspend (String) -> String,
    private val fetchRange: suspend (String, Long, Long) -> ByteArray,
    private val templateLoader: AppleTemplateLoader,
    private val trackIndexer: AppleTrackIndexer,
    private val cache: AppleAudioCache? = null,
    private val cacheKey: (AppleAudioSpec) -> String = { it.adamId }
) {
    constructor(
        client: AppleRangeClient = AppleRangeClient(),
        templateLoader: AppleTemplateLoader = AppleTemplateLoader(factory = ::TemariTemplate),
        cache: AppleAudioCache? = null,
        cacheKey: (AppleAudioSpec) -> String = { it.adamId }
    ) : this(
        ::fetchApplePlaylist,
        client::fetch,
        templateLoader,
        AppleTrackIndexer(AppleSegmentIndexer(client)),
        cache,
        cacheKey
    )

    suspend fun prepareSource(spec: AppleAudioSpec): AppleTrackSource {
        cache?.get(cacheKey(spec))?.let { return CachedAppleTrack(it) }
        return prepare(spec)
    }

    suspend fun prepare(spec: AppleAudioSpec): PreparedAppleTrack {
        val playlist = parseApplePlaylist(spec.url, fetchPlaylist(spec.url))
        val segments = playlist.segments
        val initEnd = segments.first().start
        require(initEnd > 0L) { "HLS 清单缺少初始化段" }
        val init = parseAppleInitSegment(fetchRange(playlist.fileUrl, 0L, initEnd - 1L))
        val templates = templateLoader.create(spec, segments)
        try {
            val index = trackIndexer.readTrackIndex(
                playlist.fileUrl,
                segments,
                init.trex,
                init.ivSize
            )
            return PreparedAppleTrack(
                playlist,
                init,
                buildAppleTrackHeader(init, index),
                templates,
                cache,
                cacheKey(spec),
                fetchRange
            )
        } catch (error: Throwable) {
            closeAppleTemplates(templates, error)
            throw error
        }
    }
}

internal fun closeAppleTemplates(
    templates: Map<String, AppleSampleDecryptor>,
    failure: Throwable?
): Throwable? {
    var result = failure
    val seen = IdentityHashMap<AppleSampleDecryptor, Boolean>()
    for (template in templates.values) {
        if (seen.put(template, true) != null) continue
        try {
            template.close()
        } catch (error: Throwable) {
            val current = result
            if (current == null) result = error
            else current.addSuppressed(error)
        }
    }
    return result
}

private suspend fun fetchApplePlaylist(url: String): String {
    val response = HTTPUtils.getCancellable(url)
    if (!response.isSuccessful) throw IOException("Apple Music HLS 清单请求失败 (${response.status})")
    return response.body ?: throw IOException("Apple Music HLS 清单响应为空")
}
