package com.ikunshare.sound

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.directory
import coil3.request.crossfade
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.PlatformPlaylistStore
import com.ikunshare.sound.database.PlaybackStateStore
import com.ikunshare.sound.manager.AuthManager
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.DownloadManager
import com.ikunshare.sound.manager.ExoPlayerMediaPlayer
import com.ikunshare.sound.manager.FloatingLyricsManager
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.manager.NoticeManager
import com.ikunshare.sound.manager.SoundMediaPlayer
import com.ikunshare.sound.manager.UpdateChecker
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.platform.base.BaseProvider
import com.ikunshare.sound.platform.base.MediaInfoResult
import com.ikunshare.sound.platform.joox.JooxProvider
import com.ikunshare.sound.platform.kg.KgProvider
import com.ikunshare.sound.platform.kw.KwProvider
import com.ikunshare.sound.platform.qq.QQProvider
import com.ikunshare.sound.platform.wy.WyProvider
import com.ikunshare.sound.tool.cache.AudioCache
import com.ikunshare.sound.tool.cache.DiskCache
import com.ikunshare.sound.tool.lyricon.LyriconAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import coil3.disk.DiskCache as CoilDiskCache

/**
 * KunSound 应用 Application 类
 *
 * 管理全局依赖实例，包括 MusicRepository 和 SoundMediaPlayer。
 * 这些实例在整个应用生命周期内保持单例，供各 ViewModel 共享使用。
 *
 * Requirements:
 * - 5.9: 持久化 MiniPlayer 需要跨页面共享播放状态
 * - 5.10: 点击 MiniPlayer 展开全屏播放页
 * - 6.8: 支持多 Provider 切换
 */
class SoundApplication : Application(), SingletonImageLoader.Factory {

    /**
     * 全局 MusicRepository 单例
     * 封装所有音乐平台 Provider，提供统一的数据访问接口
     */
    lateinit var musicRepository: MusicRepository
        private set

    lateinit var credentialManager: CredentialManager
        private set

    lateinit var appSettingsManager: AppSettingsManager
        private set

    /**
     * 全局 LocalMusicStore 单例
     * 管理本地收藏和最近播放数据
     */
    lateinit var localMusicStore: LocalMusicStore
        private set

    /**
     * 全局 SoundMediaPlayer 单例
     * 管理音频播放，确保跨页面播放状态一致
     */
    lateinit var mediaPlayer: SoundMediaPlayer
        private set

    /**
     * 全局 AudioCache 单例
     * 缓存已解密的音频文件，避免重复下载
     */
    lateinit var audioCache: AudioCache
        private set

    lateinit var noticeManager: NoticeManager
        private set

    /** 卡密激活管理器（付费音源受限解锁） */
    lateinit var authManager: AuthManager
        private set

    lateinit var updateChecker: UpdateChecker
        private set

    lateinit var downloadManager: DownloadManager
        private set

    lateinit var floatingLyricsManager: FloatingLyricsManager
        private set

    /**
     * Lyricon 外部歌词服务适配器
     * 仅在 lyricon 变体中有真实实现
     */
    lateinit var lyriconAdapter: LyriconAdapter
        private set

    /**
     * 全局 PlatformPlaylistStore 单例
     * 缓存平台歌单和用户信息
     */
    lateinit var platformPlaylistStore: PlatformPlaylistStore
        private set

    /** LX Music 同步管理 */
    lateinit var syncManager: com.ikunshare.sound.sync.SyncManager
        private set

    /**
     * 全局 PlaybackStateStore 单例
     * 持久化播放状态（播放列表、进度、播放模式）
     */
    lateinit var playbackStateStore: PlaybackStateStore
        private set

    /**
     * 播放状态快照，用于 Activity 重建时恢复 PlayerViewModel 状态
     */
    data class PlaybackSnapshot(
        val song: MusicItem,
        val playlist: List<MusicItem>,
        val currentIndex: Int,
        val quality: Quality?,
        val stopAfterQueueDrains: Boolean = false
    )

    @Volatile
    var playbackSnapshot: PlaybackSnapshot? = null

    /** 简单的播放控制接口，供非 PlayerViewModel 上下文调用（如 MV 弹窗） */
    interface PlaybackController {
        fun isPlaying(): Boolean
        fun pause()
        fun play()
    }

    @Volatile
    var playbackController: PlaybackController? = null

    companion object {
        @Volatile
        var instance: SoundApplication? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局崩溃捕获 — 启动独立进程的崩溃提示页面
        setupCrashHandler()

        // 初始化简繁转换查找表
        com.ikunshare.sound.tool.ChineseConverter.init(this)

        // 初始化网易云加密模块
        com.ikunshare.sound.platform.wy.utils.NeteaseCrypto.init(this)

        // 初始化 QQ 虚拟设备（install 级别持久化，供上报等接口构造 comm）
        com.ikunshare.sound.platform.qq.device.QQDeviceManager.init(this)

        // 初始化音乐平台 Provider 映射
        val providers: Map<String, BaseProvider> = mapOf(
            "wy" to WyProvider(),
            "qq" to QQProvider(),
            "kg" to KgProvider(),
            "kw" to KwProvider(),
            "joox" to JooxProvider(),
        )

        // 初始化凭据管理器并注入到 Provider
        credentialManager = CredentialManager(this)
        credentialManager.applyToProviders(providers)

        // 启动时刷新 QQ 音乐凭据
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(5000)
            try {
                val qqProvider = providers["qq"] as? QQProvider
                val refreshed = qqProvider?.refreshLogin()
                if (refreshed != null) {
                    credentialManager.saveCredential("qq", refreshed)
                }
            } catch (_: Exception) {
            }
        }

        // 启动时刷新酷狗凭据
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(5000)
            try {
                val kgProvider = providers["kg"] as? KgProvider
                val refreshed = kgProvider?.refreshLogin()
                if (refreshed != null) {
                    credentialManager.saveCredential("kg", refreshed)
                }
            } catch (_: Exception) {
            }
        }

        // 初始化应用设置管理器
        appSettingsManager = AppSettingsManager(this)

        // 初始化播放状态持久化存储
        playbackStateStore = PlaybackStateStore(this)

        // 初始化本地音乐存储
        localMusicStore = LocalMusicStore(this)

        // 初始化平台歌单缓存
        platformPlaylistStore = PlatformPlaylistStore(this)

        // 初始化 LX Music 同步
        syncManager = com.ikunshare.sound.sync.SyncManager(this, localMusicStore)
        syncManager.startupConnectIfEnabled()

        // 初始化磁盘持久化缓存
        val cacheGson = DiskCache.createCacheGson()
        val lyricDiskCache = DiskCache<Lyric>(
            cacheFile = File(filesDir, "lyric_cache.json"),
            maxSize = MusicRepository.LYRIC_CACHE_SIZE,
            valueType = Lyric::class.java,
            gson = cacheGson
        )
        val mediaInfoDiskCache = DiskCache<MediaInfoResult>(
            cacheFile = File(filesDir, "media_cache.json"),
            maxSize = MusicRepository.MEDIA_INFO_CACHE_SIZE,
            valueType = MediaInfoResult::class.java,
            gson = cacheGson
        )

        // 初始化 MusicRepository，恢复上次使用的搜索平台
        musicRepository = MusicRepository(
            providers,
            lyricCache = lyricDiskCache,
            mediaInfoCache = mediaInfoDiskCache
        ).apply {
            val last = appSettingsManager.settings.value.lastSearchProvider
            val target = if (last.isNotBlank() && providers.containsKey(last)) last else "qq"
            setProvider(target)
        }

        // 初始化卡密激活管理器（后台校验）
        authManager = AuthManager(this)
        CoroutineScope(Dispatchers.IO).launch { authManager.checkOnStartup() }

        // 解析服务使用 AuthManager 的 authst 作为认证凭据
        musicRepository.authManager = authManager

        // 把本地存储注入仓库，让 getLyric 等方法走重定向
        musicRepository.localMusicStore = localMusicStore

        // 初始化 MediaPlayer
        mediaPlayer = ExoPlayerMediaPlayer(
            this,
            appSettingsManager.settings.value.maxCacheSizeMb,
            handleAudioBecomingNoisy = !appSettingsManager.settings.value.keepPlayingOnHeadsetDisconnect
        )

        // 初始化音频缓存
        audioCache = AudioCache(this, appSettingsManager)

        // 初始化公告管理器
        noticeManager = NoticeManager(this)

        // 初始化更新检查器
        updateChecker = UpdateChecker(this)

        // 初始化下载管理器
        downloadManager = DownloadManager(musicRepository, appSettingsManager, this)

        // 初始化桌面歌词管理器
        floatingLyricsManager = FloatingLyricsManager(this, appSettingsManager)

        // 初始化调试设置
        setupDebugSettings()

        // 初始化 Lyricon 适配器（按需初始化）
        lyriconAdapter = LyriconAdapter.create()
        if (appSettingsManager.settings.value.lyriconEnabled) {
            lyriconAdapter.init(this)
        }
    }

    private var logcatProcess: Process? = null

    private fun setupDebugSettings() {
        val settings = appSettingsManager.settings.value
        com.ikunshare.sound.utils.HTTPUtils.httpLogEnabled = settings.debugHttpLog
        if (settings.debugLogcat) startLogcat()

        // 监听变化
        val scope =
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        scope.launch {
            appSettingsManager.settings.collect { s ->
                com.ikunshare.sound.utils.HTTPUtils.httpLogEnabled = s.debugHttpLog
                if (s.debugLogcat && logcatProcess == null) startLogcat()
                else if (!s.debugLogcat && logcatProcess != null) stopLogcat()
                // 实时同步耳机/蓝牙断开的处理策略
                mediaPlayer.setHandleAudioBecomingNoisy(!s.keepPlayingOnHeadsetDisconnect)
            }
        }
    }

    private fun startLogcat() {
        try {
            val logDir = File(getExternalFilesDir(null), "cache/logcat")
            logDir.mkdirs()
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val logFile = File(logDir, "$timestamp.log")
            // 先清空 logcat 缓冲区再开始记录
            Runtime.getRuntime().exec("logcat -c").waitFor()
            logcatProcess = Runtime.getRuntime().exec(
                arrayOf("logcat", "-f", logFile.absolutePath, "--pid=${android.os.Process.myPid()}")
            )
            android.util.Log.i("KunSound", "Logcat recording started: ${logFile.absolutePath}")
        } catch (e: Exception) {
            android.util.Log.e("KunSound", "Failed to start logcat", e)
        }
    }

    private fun stopLogcat() {
        logcatProcess?.destroy()
        logcatProcess = null
        android.util.Log.i("KunSound", "Logcat recording stopped")
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashLog = buildString {
                    appendLine("Thread: ${thread.name}")
                    appendLine(throwable.stackTraceToString())
                }
                val intent = android.content.Intent(this, CrashHandlerActivity::class.java).apply {
                    putExtra("crash_log", crashLog)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                // 如果崩溃提示页也无法启动，回退到默认处理
                defaultHandler?.uncaughtException(thread, throwable)
            }
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val maxMb = appSettingsManager.settings.value.maxCacheSizeMb
        val maxBytes = when {
            maxMb <= 0 -> 10L * 1024L * 1024L * 1024L // 不限制: 10GB
            maxMb == 1 -> 1L                            // 禁用: 1B
            else -> maxMb.toLong() * 1024L * 1024L
        }
        return ImageLoader.Builder(this)
            .diskCache {
                CoilDiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(maxBytes)
                    .build()
            }
            .crossfade(true)
            .build()
    }
}
