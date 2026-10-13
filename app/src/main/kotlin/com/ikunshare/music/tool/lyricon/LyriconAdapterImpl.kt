package com.ikunshare.music.tool.lyricon

import android.content.Context
import android.util.Log
import com.ikunshare.music.R
import com.ikunshare.music.model.LyricChar
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.ParsedLyric
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.CachedRemotePlayer
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderConstants
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.RemotePlayer
import io.github.proify.lyricon.provider.isDisconnectedByUser
import io.github.proify.lyricon.provider.providerMetadataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 根据 Lyricon Provider 文档（https://tomakino.github.io/lyricon/zh-cn/developer/provider/）：
 * - register() 只代表注册请求已发出，连接结果通过 ConnectionListener 回调
 * - autoSync = true 时 SDK 内部的 CachedRemotePlayer 会缓存最近状态，连接/重连后自动同步，
 *   因此未连接时也可以直接调用 player，无需自行缓存补发
 * - 推荐顺序：setSong → setPosition → setPlaybackState → 显示开关
 * - seekTo 用于用户主动跳转，setPosition 用于连续进度同步
 * - 释放时先 unregister() 再 destroy()
 */
class LyriconAdapterImpl : LyriconAdapter {

    @Volatile
    private var provider: LyriconProvider? = null
    private var centralPackageName: String? = null

    @Volatile
    private var isPlaying: Boolean = false

    /** 连接回调在主线程分发；重试逻辑也全部在主线程执行，避免竞态。 */
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var retryJob: Job? = null
    private var retryCount = 0

    override fun init(context: Context, useLocalCentral: Boolean) {
        val central =
            if (useLocalCentral) LOCAL_CENTRAL_PACKAGE else ProviderConstants.SYSTEM_UI_PACKAGE_NAME
        val old = provider
        if (old != null && central == centralPackageName) return

        val appContext = context.applicationContext
        val created = try {
            val logo = runCatching {
                ProviderLogo.fromDrawable(appContext, R.mipmap.ic_launcher)
            }.getOrNull()
            val appName = runCatching {
                appContext.getString(R.string.app_name)
            }.getOrNull() ?: "坤音"
            LyriconFactory.createProvider(
                context = appContext,
                logo = logo,
                metadata = providerMetadataOf("app_name" to appName),
                centralPackageName = central
            ).apply {
                autoSync = true
                service.addConnectionListener(connectionListener)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init Lyricon provider", t)
            return
        }

        // 切换中心服务（调试用）时把旧 provider 缓存的状态搬到新 provider，连接后由 autoSync 同步
        (old?.player as? CachedRemotePlayer)?.let { created.player.restoreFrom(it) }
        old?.let { release(it) }

        provider = created
        centralPackageName = central
        cancelRetry()
        Log.i(TAG, "provider register => ${created.register()}, central=$central")
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(provider: LyriconProvider) {
            Log.i(TAG, "onConnected")
            cancelRetry()
        }

        override fun onReconnected(provider: LyriconProvider) {
            Log.i(TAG, "onReconnected")
            cancelRetry()
        }

        override fun onDisconnected(provider: LyriconProvider) {
            val status = provider.service.connectionStatus
            Log.i(TAG, "onDisconnected status=$status")
            // DISCONNECTED_USER 是本端 unregister/destroy 触发的，不重试；
            // 中心服务重启时 SDK 收到启动广播会自动 register，这里的重试只是兜底。
            if (!status.isDisconnectedByUser()) scheduleRetry()
        }

        override fun onConnectTimeout(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon connect timeout")
            // 超时回调来自后台线程，切回主线程处理
            scope.launch { scheduleRetry() }
        }
    }

    override fun updateSong(song: MusicItem, lyrics: ParsedLyric?) {
        val player = provider?.player ?: return
        player.setSong(toLyriconSong(song, lyrics))
        // 文档推荐：setSong 后同步播放状态，让词幕侧拿到完整 state
        player.setPlaybackState(isPlaying)
    }

    override fun updatePosition(position: Long) {
        provider?.player?.setPosition(position.coerceAtLeast(0L))
    }

    override fun seekTo(position: Long) {
        provider?.player?.seekTo(position.coerceAtLeast(0L))
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        this.isPlaying = isPlaying
        provider?.player?.setPlaybackState(isPlaying)
    }

    override fun setDisplayTranslation(show: Boolean) {
        provider?.player?.setDisplayTranslation(show)
    }

    override fun setDisplayRoma(show: Boolean) {
        provider?.player?.setDisplayRoma(show)
    }

    override fun clearSong() {
        provider?.player?.setSong(null)
    }

    override fun destroy() {
        cancelRetry()
        val p = provider ?: return
        provider = null
        centralPackageName = null
        isPlaying = false
        release(p)
    }

    private fun release(p: LyriconProvider) {
        runCatching { p.service.removeConnectionListener(connectionListener) }
        runCatching { p.unregister() }
        runCatching { p.destroy() }
    }

    /**
     * 中心服务在 App 启动时可能尚未就绪（例如设备刚开机、SystemUI 还没加载完 Hook），
     * 此时注册会超时并停在 DISCONNECTED，而 SDK 只会对 DISCONNECTED_REMOTE 自动重连。
     * 这里按指数退避重新 register，连接成功后重置计数；超过上限就放弃，避免未安装词幕时无限发广播。
     */
    private fun scheduleRetry() {
        if (provider == null || retryJob?.isActive == true) return
        if (retryCount >= MAX_RETRY_COUNT) {
            Log.w(TAG, "give up re-register after $retryCount attempts")
            return
        }
        val delayMs = RETRY_BASE_DELAY_MS shl retryCount
        retryCount++
        retryJob = scope.launch {
            delay(delayMs)
            val p = provider ?: return@launch
            Log.i(TAG, "re-register #$retryCount => ${p.register()}")
        }
    }

    private fun cancelRetry() {
        retryJob?.cancel()
        retryJob = null
        retryCount = 0
    }

    private fun RemotePlayer.restoreFrom(cache: CachedRemotePlayer) {
        cache.lastSong?.let { setSong(it) }
        setPosition(cache.lastPosition)
        setPlaybackState(cache.isPlaying)
        cache.lastDisplayTranslation?.let { setDisplayTranslation(it) }
        cache.lastDisplayRoma?.let { setDisplayRoma(it) }
    }

    private fun toLyriconSong(song: MusicItem, lyrics: ParsedLyric?): Song {
        val lines = lyrics?.lines ?: emptyList()
        val richLines = lines.mapIndexed { index, line ->
            val nextTimestamp = lines.getOrNull(index + 1)?.timestamp
            toRichLyricLine(line, lyrics!!, nextTimestamp, song.duration)
        }
        return Song(
            // 文档要求 id 稳定且唯一；单纯的平台内 id 在不同平台间可能撞车
            id = song.uniqueKey,
            name = song.title,
            artist = song.artist,
            duration = song.duration,
            lyrics = richLines.ifEmpty { null }
        ).normalize()
    }

    private fun toRichLyricLine(
        line: LyricLine,
        source: ParsedLyric,
        nextTimestamp: Long?,
        songDuration: Long
    ): RichLyricLine {
        val end = line.endTimestamp
            ?: line.characters?.lastOrNull()?.endTime
            ?: nextTimestamp
            ?: (line.timestamp + DEFAULT_LINE_DURATION_MS).coerceAtMost(songDuration)
        val words = line.characters?.takeIf { it.isNotEmpty() }?.map { toLyricWord(it) }
        // duration 由 SDK 根据 begin/end 自动计算
        return RichLyricLine(
            begin = line.timestamp,
            end = end,
            text = line.text,
            words = words,
            translation = line.translation.takeIf { source.hasTranslation && !it.isNullOrBlank() },
            roma = line.romanization.takeIf { source.hasRomanization && !it.isNullOrBlank() }
        )
    }

    private fun toLyricWord(char: LyricChar): LyricWord = LyricWord(
        begin = char.startTime,
        end = char.endTime,
        text = char.char
    )

    companion object {
        private const val TAG = "LyriconAdapter"
        private const val DEFAULT_LINE_DURATION_MS = 5_000L
        private const val RETRY_BASE_DELAY_MS = 2_000L
        private const val MAX_RETRY_COUNT = 5

        /** 文档「本地测试」中的 LocalCentralService 包名，仅调试使用 */
        private const val LOCAL_CENTRAL_PACKAGE = "io.github.lyricon.localcentralapp"
    }
}
