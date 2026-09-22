package com.ikunshare.sound.manager

import android.media.MediaDataSource

/**
 * SoundMediaPlayer - 媒体播放器接口。
 *
 * 播放路径：
 * - [prepareAndPlay]（url）：非加密流式播放（ExoPlayer 自动缓存）。
 * - [prepareAndPlayWithDataSource]：兼容旧 DecryptingMediaDataSource 的桥接方法。
 */
interface SoundMediaPlayer {

    val isPlaying: Boolean
    val currentPosition: Long
    val duration: Long

    suspend fun prepareAndPlay(url: String, startPosition: Long = 0L)

    suspend fun prepareAndPlayWithDataSource(
        dataSource: MediaDataSource,
        startPosition: Long = 0L
    )

    fun play()
    fun pause()
    fun stop()
    fun seekTo(position: Long)
    fun release()

    fun setOnCompletionListener(listener: () -> Unit)
    fun setOnErrorListener(listener: (String) -> Unit)
    fun setOnPreparedListener(listener: (Long) -> Unit)

    fun setVolume(volume: Float)
    fun getVolume(): Float
    fun setPlaybackSpeed(speed: Float)
    fun getPlaybackSpeed(): Float

    /**
     * 控制耳机/蓝牙断开时是否自动暂停。
     * `true`：跟随系统的 BECOMING_NOISY 行为自动暂停（默认）。
     * `false`：忽略 BECOMING_NOISY，外放继续播放。
     */
    fun setHandleAudioBecomingNoisy(enabled: Boolean) {}
}
