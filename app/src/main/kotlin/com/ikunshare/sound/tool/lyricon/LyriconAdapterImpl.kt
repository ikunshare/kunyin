package com.ikunshare.sound.tool.lyricon

import android.content.Context
import android.util.Log
import com.ikunshare.sound.R
import com.ikunshare.sound.model.LyricChar
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.ParsedLyric
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.provider.ConnectionListener
import io.github.proify.lyricon.provider.ConnectionStatus
import io.github.proify.lyricon.provider.LyriconFactory
import io.github.proify.lyricon.provider.LyriconProvider
import io.github.proify.lyricon.provider.ProviderLogo
import io.github.proify.lyricon.provider.isDisconnectedByUser
import io.github.proify.lyricon.provider.providerMetadataOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 根据 Lyricon Provider 文档：
 * - register() 注册中心服务；连接后通过 ConnectionListener 回调
 * - autoSync = true 时连接/重连成功后自动同步最近一次缓存的状态
 * - 推荐顺序：setSong → setPosition → setPlaybackState → 显示开关
 * - seekTo 用于用户主动跳转，setPosition 用于连续进度同步
 */
class LyriconAdapterImpl : LyriconAdapter {

    private var provider: LyriconProvider? = null

    @Volatile
    private var connected: Boolean = false

    @Volatile
    private var pendingSong: Song? = null

    @Volatile
    private var pendingPlaying: Boolean? = null

    @Volatile
    private var pendingPosition: Long? = null

    @Volatile
    private var pendingDisplayTranslation: Boolean? = null

    @Volatile
    private var pendingDisplayRoma: Boolean? = null

    /** Watchdog 协程作用域，destroy 时取消，避免在 provider 已经销毁后还在调 register。 */
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var watchdogJob: Job? = null

    override fun init(context: Context) {
        if (provider != null) return
        val appContext = context.applicationContext
        try {
            val logo = runCatching {
                ProviderLogo.fromDrawable(appContext, R.mipmap.ic_launcher)
            }.getOrNull()
            val appName = runCatching {
                appContext.getString(R.string.app_name)
            }.getOrNull() ?: "坤音"
            provider = LyriconFactory.createProvider(
                context = appContext,
                logo = logo,
                metadata = providerMetadataOf("app_name" to appName)
            ).apply {
                autoSync = true
                service.addConnectionListener(connectionListener)
                val ok = register()
                Log.i(TAG, "provider register => $ok, status=${service.connectionStatus}")
            }
            startWatchdog()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to init Lyricon provider", t)
            provider = null
        }
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnected(provider: LyriconProvider) {
            Log.i(TAG, "onConnected")
            connected = true
            stopWatchdog()
            flushPending()
        }

        override fun onReconnected(provider: LyriconProvider) {
            Log.i(TAG, "onReconnected")
            connected = true
            stopWatchdog()
            flushPending()
        }

        override fun onDisconnected(provider: LyriconProvider) {
            Log.i(TAG, "onDisconnected status=${provider.service.connectionStatus}")
            connected = false
            // 服务端主动断开（中心服务被杀/换源）后，会回到 DISCONNECTED_REMOTE，
            // SDK 自身不会再发起重连；这里启 watchdog 主动 register 兜底。
            // DISCONNECTED_USER 是用户在中心端关闭，尊重用户选择不再重试。
            if (!provider.service.connectionStatus.isDisconnectedByUser()) {
                startWatchdog()
            }
        }

        override fun onConnectTimeout(provider: LyriconProvider) {
            Log.w(TAG, "Lyricon connect timeout")
            connected = false
            startWatchdog()
        }
    }

    override fun updateSong(song: MusicItem, lyrics: ParsedLyric?) {
        val mapped = toLyriconSong(song, lyrics)
        pendingSong = mapped
        // 切歌后旧的 position 已无意义，清掉避免错误推送到新歌
        pendingPosition = null
        val p = provider ?: return
        // 即使 connected=false 也要喂给 SDK：autoSync=true 时由 SDK 内部缓存，
        // 等连接建立后会自动同步；早返回会导致首次启用时词幕端拿不到首歌。
        runCatching { p.player.setSong(mapped) }
            .onFailure { Log.w(TAG, "setSong failed", it) }
        // 文档推荐：setSong 后立即同步播放状态，让词幕侧拿到完整 state
        pendingPlaying?.let {
            runCatching { p.player.setPlaybackState(it) }
        }
        pendingDisplayTranslation?.let {
            runCatching { p.player.setDisplayTranslation(it) }
        }
        pendingDisplayRoma?.let {
            runCatching { p.player.setDisplayRoma(it) }
        }
    }

    override fun updatePosition(position: Long) {
        val safe = position.coerceAtLeast(0L)
        pendingPosition = safe
        val p = provider ?: return
        runCatching { p.player.setPosition(safe) }
    }

    override fun seekTo(position: Long) {
        val safe = position.coerceAtLeast(0L)
        pendingPosition = safe
        val p = provider ?: return
        runCatching { p.player.seekTo(safe) }
            .onFailure { Log.w(TAG, "seekTo failed", it) }
    }

    override fun setPlaybackState(isPlaying: Boolean) {
        pendingPlaying = isPlaying
        val p = provider ?: return
        runCatching { p.player.setPlaybackState(isPlaying) }
            .onFailure { Log.w(TAG, "setPlaybackState failed", it) }
    }

    override fun setDisplayTranslation(show: Boolean) {
        pendingDisplayTranslation = show
        val p = provider ?: return
        runCatching { p.player.setDisplayTranslation(show) }
            .onFailure { Log.w(TAG, "setDisplayTranslation failed", it) }
    }

    override fun setDisplayRoma(show: Boolean) {
        pendingDisplayRoma = show
        val p = provider ?: return
        runCatching { p.player.setDisplayRoma(show) }
            .onFailure { Log.w(TAG, "setDisplayRoma failed", it) }
    }

    override fun clearSong() {
        pendingSong = null
        pendingPosition = null
        val p = provider ?: return
        runCatching { p.player.setSong(null) }
    }

    override fun destroy() {
        stopWatchdog()
        val p = provider ?: return
        provider = null
        connected = false
        pendingSong = null
        pendingPlaying = null
        pendingPosition = null
        pendingDisplayTranslation = null
        pendingDisplayRoma = null
        runCatching { p.service.removeConnectionListener(connectionListener) }
        runCatching { p.destroy() }
    }

    /**
     * 中心服务在 App 启动时可能尚未就绪（例如设备刚开机、systemui 装饰器还没加载完）。
     * 初次 register 即便返回 true，也可能因为对端没收到导致连接卡在 CONNECTING/DISCONNECTED。
     * 这里以 1.5s 间隔轮询，最多 8 次（约 12s）；只要状态不是 CONNECTED/CONNECTING 就重发 register()。
     * 一旦 onConnected/onReconnected 触发会立刻取消，避免空跑。
     */
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = ioScope.launch {
            var attempt = 0
            while (isActive && attempt < MAX_REGISTER_ATTEMPTS) {
                delay(if (attempt == 0) FIRST_DELAY_MS else RETRY_INTERVAL_MS)
                val p = provider ?: return@launch
                val status = runCatching { p.service.connectionStatus }
                    .getOrNull()
                if (status == ConnectionStatus.CONNECTED) {
                    // 兜底：理论上 onConnected 已经把 watchdog 取消了，这里再保险刷一次缓存
                    if (!connected) {
                        connected = true
                        flushPending()
                    }
                    return@launch
                }
                if (status == ConnectionStatus.DISCONNECTED_USER) return@launch
                attempt++
                val ok = runCatching { p.register() }.getOrDefault(false)
                Log.i(TAG, "watchdog re-register #$attempt status=$status => $ok")
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    private fun flushPending() {
        val p = provider ?: return
        // 顺序：setSong → setPosition → setPlaybackState → display flags（按文档推荐）
        pendingSong?.let { s ->
            runCatching { p.player.setSong(s) }
                .onFailure { Log.w(TAG, "flush setSong failed", it) }
        }
        pendingPosition?.let { pos ->
            runCatching { p.player.setPosition(pos) }
        }
        pendingPlaying?.let { state ->
            runCatching { p.player.setPlaybackState(state) }
                .onFailure { Log.w(TAG, "flush setPlaybackState failed", it) }
        }
        pendingDisplayTranslation?.let { v ->
            runCatching { p.player.setDisplayTranslation(v) }
        }
        pendingDisplayRoma?.let { v ->
            runCatching { p.player.setDisplayRoma(v) }
        }
    }

    private fun toLyriconSong(song: MusicItem, lyrics: ParsedLyric?): Song {
        val lines = lyrics?.lines ?: emptyList()
        val richLines = lines.mapIndexed { index, line ->
            val nextTimestamp = lines.getOrNull(index + 1)?.timestamp
            toRichLyricLine(line, lyrics!!, nextTimestamp, song.duration)
        }
        return Song(
            id = song.id.toString(),
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
        return RichLyricLine(
            begin = line.timestamp,
            end = end,
            duration = (end - line.timestamp).coerceAtLeast(1L),
            text = line.text,
            words = words,
            translation = line.translation.takeIf { source.hasTranslation && !it.isNullOrBlank() },
            roma = line.romanization.takeIf { source.hasRomanization && !it.isNullOrBlank() }
        )
    }

    private fun toLyricWord(char: LyricChar): LyricWord = LyricWord(
        begin = char.startTime,
        end = char.endTime,
        duration = (char.endTime - char.startTime).coerceAtLeast(0L),
        text = char.char
    )

    companion object {
        private const val TAG = "LyriconAdapter"
        private const val DEFAULT_LINE_DURATION_MS = 5_000L
        private const val FIRST_DELAY_MS = 800L
        private const val RETRY_INTERVAL_MS = 1500L
        private const val MAX_REGISTER_ATTEMPTS = 8
    }
}
