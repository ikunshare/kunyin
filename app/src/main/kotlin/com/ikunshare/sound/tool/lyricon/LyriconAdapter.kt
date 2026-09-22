package com.ikunshare.sound.tool.lyricon

import android.content.Context
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.ParsedLyric

/**
 * Lyricon 外部歌词服务适配器接口
 *
 * 提供统一的接口，在不同的构建变体中使用不同的实现：
 * - standard 变体：空实现（不依赖 Lyricon 库）
 * - lyricon 变体：真实实现（依赖 Lyricon 库，minSdk 27）
 */
interface LyriconAdapter {
    /**
     * 初始化 Lyricon Provider
     */
    fun init(context: Context)

    /**
     * 更新当前歌曲和歌词
     */
    fun updateSong(song: MusicItem, lyrics: ParsedLyric?)

    /**
     * 更新播放位置（毫秒）
     */
    fun updatePosition(position: Long)

    /**
     * 用户主动跳转（拖动进度条/上下首切到的中段）
     */
    fun seekTo(position: Long)

    /**
     * 设置播放状态
     */
    fun setPlaybackState(isPlaying: Boolean)

    /**
     * 显示翻译/罗马音开关
     */
    fun setDisplayTranslation(show: Boolean)
    fun setDisplayRoma(show: Boolean)

    /**
     * 清空当前歌曲（停止播放/清空播放列表时）
     */
    fun clearSong()

    /**
     * 清理资源
     */
    fun destroy()

    companion object {
        fun create(): LyriconAdapter = LyriconAdapterImpl()
    }
}

/**
 * 空实现，用于 standard 变体
 */
internal class LyriconAdapterEmpty : LyriconAdapter {
    override fun init(context: Context) {}
    override fun updateSong(song: MusicItem, lyrics: ParsedLyric?) {}
    override fun updatePosition(position: Long) {}
    override fun seekTo(position: Long) {}
    override fun setPlaybackState(isPlaying: Boolean) {}
    override fun setDisplayTranslation(show: Boolean) {}
    override fun setDisplayRoma(show: Boolean) {}
    override fun clearSong() {}
    override fun destroy() {}
}
