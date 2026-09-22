package com.ikunshare.sound.manager

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
class ExoPlayerMediaPlayer(
    private val context: Context,
    maxCacheSizeMb: Int = 500,
    handleAudioBecomingNoisy: Boolean = true
) : SoundMediaPlayer {

    companion object {
        private const val TAG = "KunSound"
        private const val CACHE_DIR = "exo_audio_cache"
    }

    private var onCompletionListener: (() -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onPreparedListener: ((Long) -> Unit)? = null
    private var currentVolume: Float = 1.0f
    private var currentSpeed: Float = 1.0f

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val cacheDir = File(context.cacheDir, CACHE_DIR)
    private val cacheEvictor = LeastRecentlyUsedCacheEvictor(
        maxCacheSizeMb.toLong().coerceAtLeast(50) * 1024L * 1024L
    )
    private val cache = SimpleCache(cacheDir, cacheEvictor, StandaloneDatabaseProvider(context))

    private val upstreamFactory: DataSource.Factory = OkHttpDataSource.Factory(okHttpClient)

    private val cacheDataSourceFactory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
        .setCacheReadDataSourceFactory(FileDataSource.Factory())
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(DefaultMediaSourceFactory(cacheDataSourceFactory))
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .setHandleAudioBecomingNoisy(handleAudioBecomingNoisy)
        .build()
        .apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            if (this@apply.playWhenReady) {
                                onPreparedListener?.invoke(this@apply.duration)
                            }
                        }

                        Player.STATE_ENDED -> {
                            onCompletionListener?.invoke()
                        }

                        else -> {}
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    Log.e(TAG, "ExoPlayer error: ${error.message}", error)
                    onErrorListener?.invoke(
                        error.message ?: "ExoPlayer error code=${error.errorCode}"
                    )
                }
            })
        }

    override val isPlaying: Boolean
        get() = player.isPlaying

    override val currentPosition: Long
        get() = player.currentPosition.coerceAtLeast(0L)

    override val duration: Long
        get() = player.duration.let { if (it == C.TIME_UNSET) 0L else it }

    override suspend fun prepareAndPlay(url: String, startPosition: Long) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "ExoPlayer.prepareAndPlay url=$url")
            player.stop()
            player.clearMediaItems()
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem, startPosition)
            player.playWhenReady = true
            player.prepare()
        }
    }

    override suspend fun prepareAndPlayWithDataSource(
        dataSource: MediaDataSource,
        startPosition: Long
    ) {
        withContext(Dispatchers.Main) {
            Log.d(TAG, "ExoPlayer.prepareAndPlayWithDataSource")
            player.stop()
            player.clearMediaItems()

            val bridgeFactory = MediaDataSourceBridgeFactory(dataSource)
            val mediaSource: MediaSource = ProgressiveMediaSource.Factory(bridgeFactory)
                .createMediaSource(MediaItem.fromUri(Uri.EMPTY))

            player.setMediaSource(mediaSource, startPosition)
            player.playWhenReady = true
            player.prepare()
        }
    }

    override fun play() {
        player.play()
    }

    override fun pause() {
        player.pause()
    }

    override fun stop() {
        player.stop()
        player.clearMediaItems()
    }

    override fun seekTo(position: Long) {
        player.seekTo(position)
    }

    override fun release() {
        player.release()
        cache.release()
    }

    override fun setOnCompletionListener(listener: () -> Unit) {
        onCompletionListener = listener
    }

    override fun setOnErrorListener(listener: (String) -> Unit) {
        onErrorListener = listener
    }

    override fun setOnPreparedListener(listener: (Long) -> Unit) {
        onPreparedListener = listener
    }

    override fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        player.volume = currentVolume
    }

    override fun getVolume(): Float = currentVolume

    override fun setPlaybackSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.25f, 3.0f)
        player.setPlaybackSpeed(currentSpeed)
    }

    override fun getPlaybackSpeed(): Float = currentSpeed

    override fun setHandleAudioBecomingNoisy(enabled: Boolean) {
        player.setHandleAudioBecomingNoisy(enabled)
    }

    fun getCacheSize(): Long = cache.cacheSpace

    fun clearCache() {
        try {
            cache.keys.toList().forEach { key ->
                cache.removeResource(key)
            }
        } catch (e: Exception) {
            Log.e(TAG, "clearCache failed", e)
        }
    }
}
