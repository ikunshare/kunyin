@file:Suppress("DEPRECATION")

package com.ikunshare.sound.ui.screens.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.database.LocalMusicStore
import com.ikunshare.sound.database.PlaybackStateStore
import com.ikunshare.sound.database.Playlist
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.manager.SoundMediaPlayer
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.ParsedLyric
import com.ikunshare.sound.model.PlayMode
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.service.PlaybackService
import com.ikunshare.sound.tool.DecryptingMediaDataSource
import com.ikunshare.sound.tool.LyricParser
import com.ikunshare.sound.tool.cache.AudioCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 定时停止模式
 */
enum class SleepTimerMode { TIME, SONGS }

/**
 * 定时停止动作
 */
enum class SleepTimerAction { PAUSE, EXIT }

/**
 * 定时停止状态
 */
data class SleepTimerState(
    val isActive: Boolean = false,
    val mode: SleepTimerMode = SleepTimerMode.TIME,
    val remainingMs: Long = 0L,
    val remainingSongs: Int = 0,
    val finishCurrentSong: Boolean = false,
    val action: SleepTimerAction = SleepTimerAction.PAUSE
)

/**
 * 播放页面 UI 状态
 */
data class PlayerUiState(
    val currentSong: MusicItem? = null,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val currentQuality: Quality? = null,
    val availableQualities: List<Quality> = emptyList(),
    val isFavorite: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val playMode: PlayMode = PlayMode.SEQUENTIAL,
    val currentLyricText: String = "",
    val currentLyricPushText: String = "",
    val volume: Float = 1.0f,
    val playbackSpeed: Float = 1.0f
)

/**
 * 播放页面 ViewModel
 *
 * 管理播放页面的 UI 状态，包括歌曲加载、播放控制、进度跳转、
 * 切歌、音质切换、收藏、播放模式和播放状态持久化。
 * 内部维护播放列表和当前索引，支持上一首/下一首切歌。
 */
class PlayerViewModel(
    private val repository: MusicRepository,
    private val mediaPlayer: SoundMediaPlayer,
    private val appSettingsManager: AppSettingsManager,
    private val localMusicStore: LocalMusicStore,
    private val playbackStateStore: PlaybackStateStore,
    private val audioCache: AudioCache? = null
) : ViewModel() {

    companion object {
        /** 进度更新间隔（毫秒） */
        private const val PROGRESS_UPDATE_INTERVAL_MS = 200L

        /** 播放状态保存间隔（毫秒） */
        private const val STATE_SAVE_INTERVAL_MS = 15_000L

        /** 加载失败后自动跳过延迟（毫秒） */
        private const val AUTO_SKIP_DELAY_MS = 5_000L

        /** MiniPlayer 歌词提前量（毫秒），让歌词切换更跟手 */
        private const val LYRIC_ADVANCE_MS = 30L

        /** 获取音质排序权重（值越大优先级越高） */
        fun getQualitySortWeight(qualityId: String): Int {
            val id = qualityId.lowercase()
            return when {
                id.contains("master") -> 6
                id.contains("atmos_plus") -> 5
                id.contains("atmos") -> 4
                id.contains("hires") -> 3
                id.contains("flac") -> 2
                id.contains("320") -> 1
                id.contains("128") -> 0
                else -> -1
            }
        }

        /** 判断是否为 AI 升频音质 */
        fun isAiQuality(qualityId: String): Boolean {
            val id = qualityId.lowercase()
            return id.contains("atmos") || id.contains("master")
        }
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    val hasCurrentSong: StateFlow<Boolean> = _uiState
        .map { it.currentSong != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 播放列表 */
    private val _playlist = mutableListOf<MusicItem>()

    /** 当前播放索引 */
    private var currentIndex: Int = -1

    /** 进度更新 Job */
    private var progressJob: Job? = null

    /** 加载歌曲 Job，用于防止快速切歌导致并发 */
    private var loadJob: Job? = null

    /** 用户偏好的音质 ID（记住上次选择） */
    private var preferredQualityId: String? = null

    /** 随机模式的打乱列表 */
    private var shuffledPlaylist = mutableListOf<MusicItem>()

    /** 随机模式当前索引 */
    private var shuffledIndex: Int = -1

    /** 稍后播放队列（独立于主播放列表） */
    private val _playNextQueue = mutableListOf<MusicItem>()

    /** 当前是否正在播放稍后播放队列中的歌曲 */
    private var _playingFromNextQueue = false

    /** 当前歌曲的解析歌词，用于 MiniPlayer 显示当前行 */
    private var currentParsedLyric: ParsedLyric = ParsedLyric.EMPTY

    /** 主播放列表清空后，是否在稍后播放队列耗尽后停止 */
    private var stopAfterQueueDrains = false

    /** 歌词加载 Job */
    private var lyricJob: Job? = null

    /** 自动跳过 Job（加载失败后延迟跳过） */
    private var autoSkipJob: Job? = null

    /** 后台缓存下载 Job（切歌时取消，避免脏下载占带宽） */
    private var cacheJob: Job? = null

    /** QQ 音乐播放流水：累计实际播放时间（毫秒），暂停和 seek 期间不计 */
    private var qqAccumulatedPlayMs: Long = 0L

    /** QQ 音乐播放流水：上一次开始计时的时间戳 */
    private var qqPlayStartTimestamp: Long = 0L

    // ── 定时停止 ──
    private val _sleepTimerState = MutableStateFlow(SleepTimerState())
    val sleepTimerState: StateFlow<SleepTimerState> = _sleepTimerState.asStateFlow()
    private var sleepTimerJob: Job? = null

    /** 时间模式倒计时结束但等待当前歌曲播完 */
    private var sleepTimerWaitingForSongEnd = false

    /** 上次保存状态的时间戳 */
    private var lastSaveTimeMs: Long = 0L

    /** MediaPlayer 是否已加载媒体（冷启动恢复后为 false，需要先 loadAndPlaySong） */
    private var mediaLoaded: Boolean = false

    /** 播放出错后是否正在重试，防止无限重试 */
    private var errorRetrying: Boolean = false

    // ── 音频焦点 ──
    private val audioManager =
        SoundApplication.instance!!.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wasPlayingBeforeFocusLoss = false
    private var isDucking = false

    /** 永久焦点丢失后，轮询其他应用是否停止播放以自动恢复焦点 */
    private var resumeWatchJob: Job? = null

    // ── 电话监听 ──
    private var wasPlayingBeforeCall = false
    private val telephonyManager =
        SoundApplication.instance!!.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private fun handleCallState(state: Int) {
        val settings = appSettingsManager.settings.value
        if (!settings.pauseOnPhoneCall) return
        when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (_uiState.value.isPlaying) {
                    wasPlayingBeforeCall = true
                    togglePlayPause()
                }
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (wasPlayingBeforeCall) {
                    wasPlayingBeforeCall = false
                    togglePlayPause()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private val legacyPhoneStateListener = object : PhoneStateListener() {
        @Deprecated("Required override on legacy API < S")
        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
            handleCallState(state)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private inner class ModernCallStateListener :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            handleCallState(state)
        }
    }

    private val modernCallStateListener: TelephonyCallback? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) ModernCallStateListener() else null

    /** 权限授予后重新注册电话状态监听 */
    fun registerPhoneStateListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallStateListener?.let {
                    telephonyManager?.registerTelephonyCallback(
                        SoundApplication.instance!!.mainExecutor,
                        it
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(
                    legacyPhoneStateListener,
                    PhoneStateListener.LISTEN_CALL_STATE
                )
            }
        } catch (_: Exception) {
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val behavior = appSettingsManager.settings.value.audioFocusBehavior
        if (behavior == "none") return@OnAudioFocusChangeListener
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                // 永久焦点丢失：先恢复音量，暂停播放，并启动看门狗待对方停止后重新申请焦点
                if (isDucking) {
                    mediaPlayer.setVolume(_uiState.value.volume)
                    isDucking = false
                }
                val wasPlaying = _uiState.value.isPlaying
                if (wasPlaying) togglePlayPause()
                if (wasPlaying) {
                    wasPlayingBeforeFocusLoss = true
                    startFocusResumeWatcher()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                when (behavior) {
                    "pause" -> {
                        val wasPlaying = _uiState.value.isPlaying
                        if (wasPlaying) togglePlayPause()
                        if (wasPlaying) wasPlayingBeforeFocusLoss = true
                    }

                    "duck" -> {
                        // 其他应用未允许 duck，但用户偏好压低音量：仍执行压低
                        val pct = appSettingsManager.settings.value.audioDuckVolume / 100f
                        mediaPlayer.setVolume(pct)
                        isDucking = true
                    }
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (behavior == "duck") {
                    val pct = appSettingsManager.settings.value.audioDuckVolume / 100f
                    mediaPlayer.setVolume(pct)
                    isDucking = true
                } else if (behavior == "pause") {
                    val wasPlaying = _uiState.value.isPlaying
                    if (wasPlaying) togglePlayPause()
                    if (wasPlaying) wasPlayingBeforeFocusLoss = true
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                cancelFocusResumeWatcher()
                if (isDucking) {
                    mediaPlayer.setVolume(_uiState.value.volume)
                    isDucking = false
                }
                if (wasPlayingBeforeFocusLoss && !_uiState.value.isPlaying) {
                    togglePlayPause()
                }
                wasPlayingBeforeFocusLoss = false
            }
        }
    }

    init {
        // 设置播放器回调
        setupMediaPlayerListeners()

        // 注册电话状态监听
        try {
            registerPhoneStateListener()
        } catch (_: Exception) {
        }

        // Activity 重建时从 Application 快照恢复播放状态
        val app = SoundApplication.instance
        val snapshot = app?.playbackSnapshot
        if (snapshot != null && _uiState.value.currentSong == null) {
            _playlist.clear()
            _playlist.addAll(snapshot.playlist)
            currentIndex = snapshot.currentIndex
            stopAfterQueueDrains = snapshot.stopAfterQueueDrains

            val settings = appSettingsManager.settings.value
            var availableQualities = snapshot.song.qualities.values.toList()
                .sortedBy { getQualitySortWeight(it.id) }
            if (settings.hideAiQualities) {
                availableQualities = availableQualities.filter { !isAiQuality(it.id) }
            }

            _currentPosition.value = mediaPlayer.currentPosition
            _uiState.update {
                it.copy(
                    currentSong = snapshot.song,
                    currentQuality = snapshot.quality,
                    availableQualities = availableQualities,
                    isPlaying = mediaPlayer.isPlaying,
                    currentPosition = mediaPlayer.currentPosition,
                    duration = mediaPlayer.duration,
                    isFavorite = localMusicStore.isFavorite(snapshot.song)
                )
            }
            if (mediaPlayer.isPlaying) {
                mediaLoaded = true
                startProgressUpdates()
            }
            // 恢复快照时加载歌词，确保 Lyricon 等外部推送能拿到歌词内容
            lyricJob?.cancel()
            lyricJob = viewModelScope.launch {
                try {
                    val lyric = repository.getLyric(snapshot.song)
                    ensureActive()
                    val parsed = LyricParser.parse(lyric, snapshot.song)
                    currentParsedLyric = parsed
                    if (isLyriconEnabled()) {
                        SoundApplication.instance?.lyriconAdapter?.updateSong(snapshot.song, parsed)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    currentParsedLyric = ParsedLyric.EMPTY
                }
            }
        } else if (snapshot == null) {
            // 冷启动：从 PlaybackStateStore 恢复
            val restored = playbackStateStore.restore()
            if (restored != null && restored.playlist.isNotEmpty()) {
                _playlist.clear()
                _playlist.addAll(restored.playlist)
                currentIndex = restored.currentIndex
                val song = _playlist[currentIndex]

                val settings = appSettingsManager.settings.value
                var availableQualities = song.qualities.values.toList()
                    .sortedBy { getQualitySortWeight(it.id) }
                if (settings.hideAiQualities) {
                    availableQualities = availableQualities.filter { !isAiQuality(it.id) }
                }
                val quality = selectQuality(availableQualities)

                stopAfterQueueDrains = restored.stopAfterQueueDrains
                _currentPosition.value = restored.positionMs
                _uiState.update {
                    it.copy(
                        currentSong = song,
                        currentQuality = quality,
                        availableQualities = availableQualities,
                        currentPosition = restored.positionMs,
                        duration = song.duration,
                        playMode = restored.playMode,
                        isFavorite = localMusicStore.isFavorite(song)
                    )
                }

                // 初始化随机播放列表
                if (restored.playMode == PlayMode.SHUFFLE) {
                    reshufflePlaylist()
                }

                // 根据设置决定是否自动播放
                if (settings.autoPlayOnRestart) {
                    loadAndPlaySong(song, startPosition = restored.positionMs)
                }
            }
        }

        // 监听 hideAiQualities 设置变化，实时更新可用音质列表
        viewModelScope.launch {
            appSettingsManager.settings
                .map { it.hideAiQualities }
                .distinctUntilChanged()
                .collect { hideAi ->
                    val song = _uiState.value.currentSong ?: return@collect
                    val allQualities = song.qualities.values.toList()
                        .sortedBy { getQualitySortWeight(it.id) }
                    val filtered = if (hideAi) {
                        allQualities.filter { !isAiQuality(it.id) }
                    } else {
                        allQualities
                    }
                    _uiState.update { it.copy(availableQualities = filtered) }
                }
        }

        // 监听 lyriconEnabled 变化，启用时推送当前歌曲和播放状态
        viewModelScope.launch {
            appSettingsManager.settings
                .map { it.lyriconEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    val adapter = SoundApplication.instance?.lyriconAdapter ?: return@collect
                    if (enabled) {
                        val s = appSettingsManager.settings.value
                        adapter.setDisplayTranslation(s.lyriconSubLine == "translation")
                        adapter.setDisplayRoma(s.lyriconSubLine == "romanization")
                        val song = _uiState.value.currentSong
                        if (song != null) {
                            adapter.updateSong(song, currentParsedLyric)
                            adapter.updatePosition(_currentPosition.value)
                            adapter.setPlaybackState(_uiState.value.isPlaying)
                        } else {
                            adapter.clearSong()
                        }
                    } else {
                        adapter.clearSong()
                    }
                }
        }

        // 监听词幕副行设置变化，实时同步给 Lyricon
        viewModelScope.launch {
            appSettingsManager.settings
                .map { it.lyriconSubLine }
                .distinctUntilChanged()
                .collect { subLine ->
                    if (!isLyriconEnabled()) return@collect
                    val adapter = SoundApplication.instance?.lyriconAdapter ?: return@collect
                    adapter.setDisplayTranslation(subLine == "translation")
                    adapter.setDisplayRoma(subLine == "romanization")
                }
        }

        // 监听歌词重定向变化：对当前歌重新加载歌词并重推（应用内 + 桌面歌词 + 词幕）
        viewModelScope.launch {
            localMusicStore.redirectKeysFlow
                .drop(1)
                .distinctUntilChanged()
                .collect { reloadCurrentLyric() }
        }
    }

    /**
     * 重新加载当前歌曲的歌词并推送到 Lyricon / 悬浮歌词。
     * 用于歌词重定向变更后立即生效，无需切歌。
     */
    private fun reloadCurrentLyric() {
        val song = _uiState.value.currentSong ?: return
        lyricJob?.cancel()
        lyricJob = viewModelScope.launch {
            try {
                val lyric = repository.getLyric(song)
                ensureActive()
                val parsed = LyricParser.parse(lyric, song)
                currentParsedLyric = parsed
                if (isLyriconEnabled()) {
                    SoundApplication.instance?.lyriconAdapter?.updateSong(song, parsed)
                }
                val position = mediaPlayer.currentPosition
                val currentLine = parsed.getCurrentLine(position + LYRIC_ADVANCE_MS)
                SoundApplication.instance?.floatingLyricsManager?.updateLyricState(
                    currentLine, position + LYRIC_ADVANCE_MS, mediaPlayer.isPlaying
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                currentParsedLyric = ParsedLyric.EMPTY
            }
        }
    }

    /**
     * 设置媒体播放器的各种回调监听
     */
    private fun setupMediaPlayerListeners() {
        // 播放完成回调
        mediaPlayer.setOnCompletionListener {
            onPlaybackCompleted()
        }

        // 播放错误回调：失效缓存并尝试从当前位置重新加载
        mediaPlayer.setOnErrorListener { errorMessage ->
            val song = _uiState.value.currentSong
            val quality = _uiState.value.currentQuality
            val position = _currentPosition.value
            if (song != null && quality != null) {
                repository.invalidateMediaInfoForSource(song.getTypeDiscriminator(), song, quality)
            }
            stopProgressUpdates()

            // 尝试重新加载（仅一次）
            if (song != null && !errorRetrying) {
                errorRetrying = true
                android.util.Log.w(
                    "MPDL",
                    SoundApplication.instance!!.getString(R.string.play_error_reload, errorMessage)
                )
                loadAndPlaySong(song, position)
            } else {
                errorRetrying = false
                mediaLoaded = false
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.play_error,
                            errorMessage
                        )
                    )
                }
                scheduleAutoSkip()
            }
        }

        // 媒体准备完成回调：更新时长并开始进度更新
        mediaPlayer.setOnPreparedListener { duration ->
            mediaLoaded = true
            qqAccumulatedPlayMs = 0L
            qqPlayStartTimestamp = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    duration = duration,
                    isPlaying = true,
                    isLoading = false
                )
            }
            if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.setPlaybackState(true)
            startProgressUpdates()
            reportQQListening(0)
            reportQQPlayRecently()
        }
    }

    /**
     * 加载并播放指定歌曲
     */
    fun loadSong(songId: Long) {
        errorRetrying = false
        // 如果已经在播放这首歌，不重新加载（避免进度重置）
        if (_uiState.value.currentSong?.id == songId && _uiState.value.currentSong != null) {
            return
        }

        // 在播放列表中查找歌曲
        val songIndex = _playlist.indexOfFirst { it.id == songId }
        val song: MusicItem?

        if (songIndex >= 0) {
            // 歌曲在播放列表中，更新索引
            currentIndex = songIndex
            song = _playlist[songIndex]
        } else {
            song = null
        }

        if (song == null) {
            _uiState.update {
                it.copy(error = SoundApplication.instance!!.getString(R.string.song_not_found))
            }
            return
        }

        loadAndPlaySong(song)
    }

    /**
     * 设置播放列表
     */
    fun setPlaylist(songs: List<MusicItem>, startIndex: Int = 0) {
        stopAfterQueueDrains = false
        _playlist.clear()
        _playlist.addAll(songs)
        currentIndex = startIndex.coerceIn(0, (_playlist.size - 1).coerceAtLeast(0))
        // 播放列表变更时，重建随机列表以避免使用旧歌单的歌曲
        if (_uiState.value.playMode == PlayMode.SHUFFLE) {
            reshufflePlaylist()
        } else {
            shuffledPlaylist.clear()
            shuffledIndex = -1
        }
    }

    /**
     * 设置播放列表并立即开始播放
     */
    fun setPlaylistAndPlay(songs: List<MusicItem>, startIndex: Int = 0) {
        setPlaylist(songs, startIndex)
        if (_playlist.isNotEmpty()) {
            loadAndPlaySong(_playlist[currentIndex])
        }
    }

    /**
     * 加载并播放指定歌曲（内部方法）
     */
    private fun loadAndPlaySong(
        song: MusicItem,
        startPosition: Long = 0L,
        retried: Boolean = false
    ) {
        // 取消前一个加载任务，防止快速切歌导致并发
        loadJob?.cancel()
        autoSkipJob?.cancel()
        cacheJob?.cancel()
        // 切歌时上报上一首的播放流水
        reportQQPlayStream()
        requestAudioFocus()
        loadJob = viewModelScope.launch {
            // 停止当前播放
            stopProgressUpdates()
            mediaPlayer.stop()
            SoundApplication.instance?.floatingLyricsManager?.clearLyric()

            val settings = appSettingsManager.settings.value

            // 获取歌曲的实际来源平台 key，用于跨平台播放路由
            val songSource = song.getTypeDiscriminator()

            // 获取可用音质列表并排序，按设置过滤 AI 音质
            var availableQualities = song.qualities.values.toList()
                .sortedBy { getQualitySortWeight(it.id) }
            if (settings.hideAiQualities) {
                availableQualities = availableQualities.filter { !isAiQuality(it.id) }
            }
            // 选择音质：优先使用当前音质，否则使用设置中的默认音质
            val quality = selectQuality(availableQualities)

            _currentPosition.value = startPosition
            _uiState.update {
                it.copy(
                    currentSong = song,
                    isLoading = true,
                    isPlaying = false,
                    error = null,
                    currentPosition = startPosition,
                    duration = 0L,
                    availableQualities = availableQualities,
                    currentQuality = quality,
                    isFavorite = localMusicStore.isFavorite(song),
                    currentLyricText = "",
                    currentLyricPushText = ""
                )
            }

            // 后台加载歌词
            lyricJob?.cancel()
            lyricJob = viewModelScope.launch {
                try {
                    val lyric = repository.getLyric(song)
                    ensureActive()
                    val parsed = LyricParser.parse(lyric, song)
                    currentParsedLyric = parsed
                    if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.updateSong(
                        song,
                        parsed
                    )
                    // 歌词加载完成后立即推送一次，避免进度循环先启动时歌词为空
                    val position = mediaPlayer.currentPosition
                    val currentLine = parsed.getCurrentLine(position + LYRIC_ADVANCE_MS)
                    SoundApplication.instance?.floatingLyricsManager?.updateLyricState(
                        currentLine, position + LYRIC_ADVANCE_MS, true
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    currentParsedLyric = ParsedLyric.EMPTY
                    if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.updateSong(
                        song,
                        null
                    )
                }
            }

            // 保存播放快照到 Application，用于 Activity 重建恢复
            SoundApplication.instance?.playbackSnapshot = SoundApplication.PlaybackSnapshot(
                song = song,
                playlist = _playlist.toList(),
                currentIndex = currentIndex,
                quality = quality,
                stopAfterQueueDrains = stopAfterQueueDrains
            )

            if (quality == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.no_quality_available)
                    )
                }
                scheduleAutoSkip()
                return@launch
            }

            try {
                val audioCacheKey = audioCache?.buildKey(songSource, song.id.toString(), quality.id)

                // 缓存命中：直接用 CachedChunkDataSource 播放，跳过 getMediaInfo
                val cachedDs = audioCacheKey?.let { audioCache.getCachedChunks(it) }
                if (cachedDs != null) {
                    android.util.Log.d(
                        "KunSound",
                        "Audio fully cached, playing from disk key=$audioCacheKey"
                    )
                    mediaPlayer.prepareAndPlayWithDataSource(cachedDs, startPosition)
                    return@launch
                }

                val mediaInfo = repository.getMediaInfoForSource(songSource, song, quality)
                ensureActive()

                if (!mediaInfo.isSuccess || mediaInfo.playUrl.isNullOrEmpty()) {
                    val reason = mediaInfo.rejectReason
                        ?: SoundApplication.instance!!.getString(R.string.cannot_get_play_url)
                    android.util.Log.e("KunSound", "getMediaInfo failed: $reason")
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = SoundApplication.instance!!.getString(
                                R.string.load_failed, reason
                            )
                        )
                    }
                    scheduleAutoSkip()
                    return@launch
                }

                val playUrl = mediaInfo.playUrl
                val ekey = mediaInfo.encryptionInfo?.ekey?.takeIf {
                    mediaInfo.encryptionInfo.isEncrypt && it.isNotEmpty()
                }

                android.util.Log.d("KunSound", "playUrl=$playUrl encrypted=${ekey != null}")

                if (ekey != null) {
                    val ds = withContext(Dispatchers.IO) {
                        DecryptingMediaDataSource(playUrl, ekey, audioCache, audioCacheKey).also {
                            it.awaitReady()
                        }
                    }
                    mediaPlayer.prepareAndPlayWithDataSource(ds, startPosition)
                } else {
                    mediaPlayer.prepareAndPlay(playUrl, startPosition)
                    if (audioCache != null && audioCacheKey != null) {
                        cacheJob?.cancel()
                        var pendingCall: okhttp3.Call? = null
                        cacheJob = viewModelScope.launch(Dispatchers.IO) {
                            // 延迟启动缓存下载，等系统 MediaPlayer 完成 prepare + 首段
                            // buffer 充满后再开始，避免双连接同时抢带宽导致大文件卡顿。
                            try {
                                delay(5_000)
                                audioCache.downloadToChunks(playUrl, audioCacheKey) {
                                    pendingCall = it
                                }
                            } finally {
                                if (!isActive) pendingCall?.cancel()
                            }
                        }.also { job ->
                            job.invokeOnCompletion { if (job.isCancelled) pendingCall?.cancel() }
                        }
                    }
                }

            } catch (_: IOException) {
                if (!retried) {
                    repository.invalidateMediaInfoForSource(songSource, song, quality)
                    loadAndPlaySong(song, startPosition, retried = true)
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
                scheduleAutoSkip()
            } catch (_: CancellationException) {
                // 快速切歌取消协程，不显示错误
                throw CancellationException()
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.load_song_failed,
                            e.message ?: ""
                        )
                    )
                }
                scheduleAutoSkip()
            }
        }
    }

    /**
     * 切换播放/暂停状态
     */
    fun togglePlayPause() {
        val currentState = _uiState.value
        if (currentState.currentSong == null) return

        // 加载中时忽略播放操作
        if (currentState.isLoading) return

        if (currentState.isPlaying) {
            mediaPlayer.pause()
            stopProgressUpdates()
            // 累计播放时间
            if (qqPlayStartTimestamp > 0) {
                qqAccumulatedPlayMs += System.currentTimeMillis() - qqPlayStartTimestamp
                qqPlayStartTimestamp = 0L
            }
            // 进入暂停态：清除焦点恢复标志，避免 watcher 在用户手动暂停后误触发恢复。
            // 焦点丢失路径会在调用本函数之后重新置位 wasPlayingBeforeFocusLoss。
            wasPlayingBeforeFocusLoss = false
            cancelFocusResumeWatcher()
            _uiState.update { it.copy(isPlaying = false) }
            if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.setPlaybackState(
                false
            )
        } else {
            // MediaPlayer 未加载媒体（冷启动恢复后首次按播放）
            if (!mediaLoaded) {
                val song = currentState.currentSong
                loadAndPlaySong(song, startPosition = _currentPosition.value)
                return
            }

            val playMode = currentState.playMode
            val atEnd =
                currentState.duration > 0L && _currentPosition.value >= currentState.duration - 500L

            if (atEnd) {
                when (playMode) {
                    PlayMode.SEQUENTIAL -> {
                        // 到列表末尾：回到列表开头重新播放
                        if (currentIndex >= _playlist.size - 1 && _playlist.isNotEmpty()) {
                            currentIndex = 0
                            loadAndPlaySong(_playlist[0])
                            return
                        }
                    }

                    PlayMode.SINGLE_PLAY -> {
                        // 单曲播放完暂停后再按：从头播放当前歌曲
                        seekTo(0L)
                        mediaPlayer.play()
                        qqAccumulatedPlayMs = 0L
                        qqPlayStartTimestamp = System.currentTimeMillis()
                        startProgressUpdates()
                        _uiState.update { it.copy(isPlaying = true) }
                        return
                    }

                    else -> { /* 其他模式正常恢复 */
                    }
                }
            }

            mediaPlayer.play()
            qqPlayStartTimestamp = System.currentTimeMillis()
            startProgressUpdates()
            _uiState.update { it.copy(isPlaying = true) }
            if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.setPlaybackState(true)
        }
    }

    /**
     * 跳转到指定播放位置
     */
    fun seekTo(position: Long) {
        val duration = _uiState.value.duration
        val clampedPosition = position.coerceIn(0L, if (duration > 0L) duration else Long.MAX_VALUE)

        mediaPlayer.seekTo(clampedPosition)
        _currentPosition.value = clampedPosition
        _uiState.update { it.copy(currentPosition = clampedPosition) }
        if (isLyriconEnabled()) {
            SoundApplication.instance?.lyriconAdapter?.seekTo(clampedPosition)
        }
        reportQQListening()
    }

    /**
     * 播放上一首歌曲
     */
    fun previous() {
        if (_playlist.isEmpty()) return
        errorRetrying = false

        val playMode = _uiState.value.playMode

        when (playMode) {
            PlayMode.SHUFFLE -> {
                if (shuffledPlaylist.isNotEmpty() && shuffledIndex > 0) {
                    shuffledIndex--
                    val song = shuffledPlaylist[shuffledIndex]
                    currentIndex = _playlist.indexOf(song).coerceAtLeast(0)
                    loadAndPlaySong(song)
                } else {
                    seekTo(0L)
                }
            }

            else -> {
                if (currentIndex > 0) {
                    currentIndex--
                    loadAndPlaySong(_playlist[currentIndex])
                } else {
                    seekTo(0L)
                }
            }
        }
    }

    /**
     * 播放下一首歌曲（用户按钮切歌，优先消费稍后播放队列）
     */
    fun next() {
        if (_playNextQueue.isNotEmpty()) {
            _playingFromNextQueue = true
            errorRetrying = false
            loadAndPlaySong(_playNextQueue.removeAt(0))
            return
        }
        _playingFromNextQueue = false
        nextInternal()
    }

    /**
     * 内部切歌逻辑（不清空稍后播放队列）
     */
    private fun nextInternal() {
        if (_playlist.isEmpty()) return
        errorRetrying = false

        val playMode = _uiState.value.playMode

        when (playMode) {
            PlayMode.SEQUENTIAL -> {
                if (currentIndex < _playlist.size - 1) {
                    currentIndex++
                    loadAndPlaySong(_playlist[currentIndex])
                } else {
                    // 到末尾暂停
                    mediaPlayer.stop()
                    stopProgressUpdates()
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }

            PlayMode.LIST_LOOP -> {
                if (currentIndex < _playlist.size - 1) {
                    currentIndex++
                } else {
                    currentIndex = 0
                }
                loadAndPlaySong(_playlist[currentIndex])
            }

            PlayMode.SHUFFLE -> {
                if (shuffledPlaylist.isEmpty()) {
                    reshufflePlaylist()
                }
                if (shuffledIndex < shuffledPlaylist.size - 1) {
                    shuffledIndex++
                } else {
                    // 播完一轮，重新打乱
                    reshufflePlaylist()
                    shuffledIndex = 0
                }
                val song = shuffledPlaylist[shuffledIndex]
                currentIndex = _playlist.indexOf(song).coerceAtLeast(0)
                loadAndPlaySong(song)
            }

            PlayMode.SINGLE_LOOP, PlayMode.SINGLE_PLAY -> {
                // 手动切歌：和列表循环一样顺序切到下一首
                if (currentIndex < _playlist.size - 1) {
                    currentIndex++
                } else {
                    currentIndex = 0
                }
                loadAndPlaySong(_playlist[currentIndex])
            }
        }
    }

    /**
     * 切换音质
     */
    fun setQuality(quality: Quality) {
        val currentState = _uiState.value
        val currentSong = currentState.currentSong ?: return

        if (currentState.currentQuality?.id == quality.id) return

        preferredQualityId = quality.id
        appSettingsManager.update { copy(defaultQualityId = quality.id) }

        val currentPosition = mediaPlayer.currentPosition

        _uiState.update { it.copy(currentQuality = quality) }

        loadAndPlaySong(currentSong, startPosition = currentPosition)
    }

    /**
     * 切换播放模式
     */
    fun cyclePlayMode() {
        val newMode = _uiState.value.playMode.next()
        _uiState.update { it.copy(playMode = newMode) }

        if (newMode == PlayMode.SHUFFLE) {
            reshufflePlaylist()
        }
    }

    /**
     * 设置播放器音量
     */
    fun setVolume(volume: Float) {
        mediaPlayer.setVolume(volume)
        _uiState.update { it.copy(volume = volume) }
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaPlayer.setPlaybackSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
        // 同步到 MediaSession
        PlaybackService.getInstance()?.updatePlaybackState(
            _uiState.value.isPlaying,
            mediaPlayer.currentPosition,
            speed
        )
    }

    /**
     * 播放列表中指定索引的歌曲
     */
    fun playAtIndex(index: Int) {
        errorRetrying = false
        _playNextQueue.clear()
        _playingFromNextQueue = false
        stopAfterQueueDrains = false
        if (index in _playlist.indices) {
            currentIndex = index
            loadAndPlaySong(_playlist[currentIndex])
        }
    }

    // ---- 稍后播放队列 ----

    fun addToPlayNext(song: MusicItem) {
        if (_uiState.value.currentSong == null || !mediaLoaded) {
            stopAfterQueueDrains = false
            _playlist.clear()
            _playlist.add(song)
            currentIndex = 0
            loadAndPlaySong(song)
        } else {
            _playNextQueue.add(song)
        }
    }

    fun addToPlayNext(songs: List<MusicItem>) {
        if (songs.isEmpty()) return
        if (_uiState.value.currentSong == null || !mediaLoaded) {
            stopAfterQueueDrains = false
            _playlist.clear()
            _playlist.addAll(songs)
            currentIndex = 0
            loadAndPlaySong(songs.first())
        } else {
            _playNextQueue.addAll(songs)
        }
    }

    fun clearPlayNext() {
        _playNextQueue.clear()
    }

    fun clearMainPlaylist() {
        val currentSong = _uiState.value.currentSong
        _playlist.clear()
        currentIndex = -1
        shuffledPlaylist.clear()
        shuffledIndex = -1
        stopAfterQueueDrains = currentSong != null
        playbackStateStore.clear()
        SoundApplication.instance?.playbackSnapshot = currentSong?.let { song ->
            SoundApplication.PlaybackSnapshot(
                song = song,
                playlist = emptyList(),
                currentIndex = -1,
                quality = _uiState.value.currentQuality,
                stopAfterQueueDrains = stopAfterQueueDrains
            )
        }
    }

    /**
     * 打乱播放列表（排除当前歌曲放到首位）
     */
    private fun reshufflePlaylist() {
        val currentSong = _uiState.value.currentSong
        shuffledPlaylist.clear()
        shuffledPlaylist.addAll(_playlist)
        shuffledPlaylist.shuffle()

        if (currentSong != null) {
            val idx = shuffledPlaylist.indexOfFirst { it.id == currentSong.id }
            if (idx > 0) {
                // 将当前歌曲移到首位
                shuffledPlaylist.removeAt(idx)
                shuffledPlaylist.add(0, currentSong)
            }
            shuffledIndex = 0
        } else {
            shuffledIndex = 0
        }
    }

    /**
     * 切换收藏状态
     */
    fun toggleFavorite() {
        val currentSong = _uiState.value.currentSong ?: return

        if (localMusicStore.isFavorite(currentSong)) {
            removeFavorite()
        } else {
            addFavorite()
        }
    }

    fun addFavorite() {
        val currentSong = _uiState.value.currentSong ?: return
        localMusicStore.addFavorite(currentSong)
        _uiState.update { it.copy(isFavorite = true) }
    }

    fun removeFavorite() {
        val currentSong = _uiState.value.currentSong ?: return
        localMusicStore.removeFavorite(currentSong)
        _uiState.update { it.copy(isFavorite = false) }
    }

    fun addToPlaylist(playlistId: Long) {
        val currentSong = _uiState.value.currentSong ?: return
        localMusicStore.addToPlaylist(playlistId, currentSong)
    }

    fun createPlaylistAndAdd(name: String) {
        val currentSong = _uiState.value.currentSong ?: return
        viewModelScope.launch(Dispatchers.IO) {
            localMusicStore.createPlaylistAndAdd(name, currentSong)
        }
    }

    val customPlaylists: StateFlow<List<Playlist>> = localMusicStore.playlistsFlow

    /**
     * 清除错误状态
     */
    fun clearError() {
        autoSkipJob?.cancel()
        _uiState.update { it.copy(error = null) }
    }

    /**
     * 安排自动跳过：加载失败后等待一段时间自动播放下一首
     *
     * 单曲循环/单曲播放模式下或播放列表只有一首时不自动跳过。
     */
    private fun scheduleAutoSkip() {
        autoSkipJob?.cancel()
        val playMode = _uiState.value.playMode
        if (_playlist.size <= 1) return
        if (playMode == PlayMode.SINGLE_LOOP || playMode == PlayMode.SINGLE_PLAY) return
        autoSkipJob = viewModelScope.launch {
            delay(AUTO_SKIP_DELAY_MS.milliseconds)
            next()
        }
    }

    /**
     * 播放完成回调处理
     */
    private fun onPlaybackCompleted() {
        stopProgressUpdates()

        val position = mediaPlayer.currentPosition
        val duration = _uiState.value.duration

        // 防止流媒体网络中断或缓存丢失导致 MediaPlayer 误触发 onCompletion。
        if (duration > 5000L && position < duration * 9 / 10) {
            val song = _uiState.value.currentSong
            val quality = _uiState.value.currentQuality
            if (song != null && quality != null) {
                repository.invalidateMediaInfoForSource(song.getTypeDiscriminator(), song, quality)
            }
            // 尝试从当前位置重新加载
            if (song != null && !errorRetrying) {
                errorRetrying = true
                android.util.Log.w(
                    "KunSound",
                    "播放中断(pos=$position, dur=$duration)，尝试重新加载"
                )
                loadAndPlaySong(song, position)
            } else {
                errorRetrying = false
                mediaLoaded = false
                _uiState.update {
                    it.copy(
                        isPlaying = false,
                        error = SoundApplication.instance!!.getString(R.string.play_interrupted)
                    )
                }
                scheduleAutoSkip()
            }
            return
        }

        // 更新进度到末尾
        _currentPosition.value = duration
        _uiState.update {
            it.copy(currentPosition = duration)
        }

        // QQ 音乐听歌上报
        reportQQListening(duration)
        reportQQPlayStream()

        // 定时停止：歌曲完成时检查，如果触发了停止则不再播下一首
        if (onSleepTimerSongCompleted()) return

        val playMode = _uiState.value.playMode

        // 单曲播放：停止，忽略稍后播放队列
        if (playMode == PlayMode.SINGLE_PLAY) {
            _uiState.update { it.copy(isPlaying = false) }
            return
        }

        // 稍后播放队列优先（除单曲播放外所有模式）
        if (_playNextQueue.isNotEmpty()) {
            _playingFromNextQueue = true
            loadAndPlaySong(_playNextQueue.removeAt(0))
            return
        }

        // 刚从稍后播放队列返回，恢复正常播放
        if (_playingFromNextQueue) {
            _playingFromNextQueue = false
            if (_playlist.isEmpty() && stopAfterQueueDrains) {
                stopAfterQueueDrains = false
                mediaLoaded = false
                _uiState.update { it.copy(isPlaying = false) }
                playbackStateStore.clear()
                SoundApplication.instance?.playbackSnapshot = null
            } else if (playMode == PlayMode.SINGLE_LOOP) {
                _playlist.getOrNull(currentIndex)?.let { loadAndPlaySong(it) }
            } else {
                nextInternal()
            }
            return
        }

        // 正常播放完成
        when (playMode) {
            PlayMode.SINGLE_LOOP -> {
                // 单曲循环：从头播放
                seekTo(0L)
                mediaPlayer.play()
                qqAccumulatedPlayMs = 0L
                qqPlayStartTimestamp = System.currentTimeMillis()
                startProgressUpdates()
                _uiState.update { it.copy(isPlaying = true) }
            }

            else -> {
                nextInternal()
            }
        }
    }

    /**
     * 选择音质
     */
    private fun selectQuality(availableQualities: List<Quality>): Quality? {
        if (availableQualities.isEmpty()) return null

        if (preferredQualityId != null) {
            val preferred = availableQualities.find { it.id == preferredQualityId }
            if (preferred != null) return preferred
        }

        val settings = appSettingsManager.settings.value
        val networkQualityId = getNetworkAwareQualityId(settings)
        if (networkQualityId.isNotEmpty()) {
            val byNetwork =
                availableQualities.find { it.id.contains(networkQualityId, ignoreCase = true) }
            if (byNetwork != null) return byNetwork
        }

        val defaultId = settings.defaultQualityId
        if (defaultId.isNotEmpty()) {
            val byDefault = availableQualities.find { it.id.contains(defaultId, ignoreCase = true) }
            if (byDefault != null) return byDefault
        }

        val standardQualities = availableQualities.filter { !isAiQuality(it.id) }
        return standardQualities.lastOrNull() ?: availableQualities.first()
    }

    private fun getNetworkAwareQualityId(settings: com.ikunshare.sound.common.AppSettings): String {
        val app = SoundApplication.instance ?: return settings.defaultQualityId
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return settings.defaultQualityId
        val network = cm.activeNetwork ?: return settings.defaultQualityId
        val caps = cm.getNetworkCapabilities(network) ?: return settings.defaultQualityId
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> settings.wifiQualityId

            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> settings.cellularQualityId
            else -> settings.defaultQualityId
        }
    }

    /**
     * 开始定时更新播放进度
     *
     * 每 200ms 更新进度，每 15 秒保存播放状态到本地。
     */
    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressJob = viewModelScope.launch {
            while (isActive) {
                val position = mediaPlayer.currentPosition
                _currentPosition.value = position
                val currentLine = currentParsedLyric.getCurrentLine(position + LYRIC_ADVANCE_MS)
                val lyricText = currentLine?.text ?: ""
                val pushSubLine = appSettingsManager.settings.value.pushLyricsSubLine
                val lyricPushText = when (pushSubLine) {
                    "translation" -> currentLine?.translation?.takeIf { it.isNotBlank() }
                        ?: lyricText

                    "romanization" -> currentLine?.romanization?.takeIf { it.isNotBlank() }
                        ?: lyricText

                    else -> lyricText
                }
                if (lyricText != _uiState.value.currentLyricText || lyricPushText != _uiState.value.currentLyricPushText) {
                    _uiState.update {
                        it.copy(
                            currentLyricText = lyricText,
                            currentLyricPushText = lyricPushText
                        )
                    }
                }

                // 推送桌面歌词
                val floatingManager = SoundApplication.instance?.floatingLyricsManager
                floatingManager?.updateLyricState(currentLine, position + LYRIC_ADVANCE_MS, true)

                // 更新 Lyricon 播放位置
                if (isLyriconEnabled()) SoundApplication.instance?.lyriconAdapter?.updatePosition(
                    position
                )

                // 每 15 秒保存播放状态
                val now = System.currentTimeMillis()
                if (now - lastSaveTimeMs >= STATE_SAVE_INTERVAL_MS) {
                    lastSaveTimeMs = now
                    savePlaybackState()
                }

                delay(PROGRESS_UPDATE_INTERVAL_MS.milliseconds)
            }
        }
    }

    /**
     * 停止进度更新
     */
    private fun stopProgressUpdates() {
        progressJob?.cancel()
        progressJob = null
        SoundApplication.instance?.floatingLyricsManager?.updateLyricState(
            currentParsedLyric.getCurrentLine(mediaPlayer.currentPosition),
            mediaPlayer.currentPosition, false
        )
    }

    /**
     * 保存播放状态到 PlaybackStateStore
     */
    private fun savePlaybackState() {
        if (_playlist.isNotEmpty() && currentIndex >= 0) {
            playbackStateStore.save(
                playlist = _playlist.toList(),
                currentIndex = currentIndex,
                positionMs = mediaPlayer.currentPosition,
                playMode = _uiState.value.playMode,
                stopAfterQueueDrains = stopAfterQueueDrains
            )
        }
    }

    fun getPlaylist(): List<MusicItem> = _playlist.toList()

    fun getCurrentIndex(): Int = currentIndex

    fun getPlayNextQueue(): List<MusicItem> = _playNextQueue.toList()

    fun isPlayingFromNextQueue(): Boolean = _playingFromNextQueue

    fun playFromNextQueue(index: Int) {
        if (index !in _playNextQueue.indices) return
        errorRetrying = false
        val song = _playNextQueue[index]
        repeat(index + 1) { _playNextQueue.removeAt(0) }
        _playingFromNextQueue = true
        loadAndPlaySong(song)
    }

    private fun requestAudioFocus(): Boolean {
        val behavior = appSettingsManager.settings.value.audioFocusBehavior
        abandonAudioFocus()
        if (behavior == "none") return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                // 关闭系统自动 duck：duck 时使用用户自定义音量，pause 时由我们暂停
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        audioFocusRequest = null
    }

    /**
     * 永久焦点丢失后启动看门狗：定期检查其他应用是否停止播放，
     * 一旦无人占用音频则尝试夺回焦点并恢复播放。
     */
    private fun startFocusResumeWatcher() {
        if (appSettingsManager.settings.value.audioFocusBehavior == "none") return
        cancelFocusResumeWatcher()
        resumeWatchJob = viewModelScope.launch {
            // 留出窗口让系统稳定播放状态
            delay(1500.milliseconds)
            while (isActive && wasPlayingBeforeFocusLoss) {
                if (!audioManager.isMusicActive) {
                    val granted = requestAudioFocus()
                    if (granted) {
                        if (wasPlayingBeforeFocusLoss && !_uiState.value.isPlaying) {
                            togglePlayPause()
                        }
                        wasPlayingBeforeFocusLoss = false
                        break
                    }
                }
                delay(2000.milliseconds)
            }
        }
    }

    private fun cancelFocusResumeWatcher() {
        resumeWatchJob?.cancel()
        resumeWatchJob = null
    }

    private fun isLyriconEnabled(): Boolean {
        return appSettingsManager.settings.value.lyriconEnabled
    }

    // ── 定时停止方法 ──

    /**
     * 按时间设置定时停止
     */
    fun setSleepTimer(minutes: Int, finishCurrentSong: Boolean, action: SleepTimerAction) {
        cancelSleepTimer()
        val totalMs = minutes * 60_000L
        _sleepTimerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.TIME,
            remainingMs = totalMs,
            finishCurrentSong = finishCurrentSong,
            action = action
        )
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalMs
            while (remaining > 0 && isActive) {
                delay(1000L.milliseconds)
                remaining -= 1000L
                if (remaining < 0) remaining = 0
                _sleepTimerState.update { it.copy(remainingMs = remaining) }
            }
            // 倒计时结束
            if (finishCurrentSong && _uiState.value.isPlaying) {
                sleepTimerWaitingForSongEnd = true
                _sleepTimerState.update { it.copy(remainingMs = 0L) }
            } else {
                executeSleepTimerAction()
            }
        }
    }

    /**
     * 按歌曲数设置定时停止
     */
    fun setSleepTimerBySongs(count: Int, finishCurrentSong: Boolean, action: SleepTimerAction) {
        cancelSleepTimer()
        _sleepTimerState.value = SleepTimerState(
            isActive = true,
            mode = SleepTimerMode.SONGS,
            remainingSongs = count,
            finishCurrentSong = finishCurrentSong,
            action = action
        )
    }

    /**
     * 取消定时停止
     */
    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerWaitingForSongEnd = false
        _sleepTimerState.value = SleepTimerState()
    }

    /**
     * 歌曲播放完成时递减歌曲计数
     * @return true 如果定时停止已触发，应中断后续播放
     */
    private fun onSleepTimerSongCompleted(): Boolean {
        val state = _sleepTimerState.value
        if (!state.isActive && !sleepTimerWaitingForSongEnd) return false

        // 时间模式：等待当前歌曲播完
        if (sleepTimerWaitingForSongEnd) {
            sleepTimerWaitingForSongEnd = false
            executeSleepTimerAction()
            return true
        }

        // 歌曲计数模式
        if (state.mode == SleepTimerMode.SONGS) {
            val newCount = state.remainingSongs - 1
            if (newCount <= 0) {
                executeSleepTimerAction()
                return true
            } else {
                _sleepTimerState.update { it.copy(remainingSongs = newCount) }
            }
        }
        return false
    }

    /**
     * 执行定时停止动作
     */
    private fun executeSleepTimerAction() {
        val action = _sleepTimerState.value.action
        cancelSleepTimer()
        when (action) {
            SleepTimerAction.PAUSE -> {
                if (_uiState.value.isPlaying) {
                    mediaPlayer.pause()
                    stopProgressUpdates()
                    _uiState.update { it.copy(isPlaying = false) }
                }
            }

            SleepTimerAction.EXIT -> {
                mediaPlayer.stop()
                _uiState.update { it.copy(isPlaying = false) }
                stopProgressUpdates()
                // 发送退出广播
                val context = SoundApplication.instance ?: return
                val intent =
                    android.content.Intent(PlaybackService.ACTION_STOP_AND_EXIT)
                        .setPackage(context.packageName)
                context.sendBroadcast(intent)
            }
        }
    }

    private fun reportQQListening(playTimeMs: Long? = null) {
        val song = _uiState.value.currentSong
        if (song == null || song.getTypeDiscriminator() != "qq") return
        val qqSong = song as? com.ikunshare.sound.platform.qq.QQMusicItem ?: return
        val qqProvider = repository.getProvider("qq")
                as? com.ikunshare.sound.platform.qq.QQProvider ?: return
        val songPlayTime = playTimeMs ?: _currentPosition.value
        val qqSongIds = _playlist
            .filterIsInstance<com.ikunshare.sound.platform.qq.QQMusicItem>()
            .map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            qqProvider.reportListening(qqSong, songPlayTime, qqSongIds)
        }
    }

    private fun reportQQPlayStream() {
        val song = _uiState.value.currentSong
        if (song == null || song.getTypeDiscriminator() != "qq") return
        val qqSong = song as? com.ikunshare.sound.platform.qq.QQMusicItem ?: return
        val qqProvider = repository.getProvider("qq")
                as? com.ikunshare.sound.platform.qq.QQProvider ?: return
        // 结算当前播放区间
        if (qqPlayStartTimestamp > 0) {
            qqAccumulatedPlayMs += System.currentTimeMillis() - qqPlayStartTimestamp
            qqPlayStartTimestamp = 0L
        }
        val playTimeSec = qqAccumulatedPlayMs / 1000
        if (playTimeSec <= 0) return
        val captured = playTimeSec
        qqAccumulatedPlayMs = 0L
        viewModelScope.launch(Dispatchers.IO) {
            qqProvider.reportPlayStream(qqSong, captured)
        }
    }

    private fun reportQQPlayRecently() {
        val song = _uiState.value.currentSong
        if (song == null || song.getTypeDiscriminator() != "qq") return
        val qqSong = song as? com.ikunshare.sound.platform.qq.QQMusicItem ?: return
        val qqProvider = repository.getProvider("qq")
                as? com.ikunshare.sound.platform.qq.QQProvider ?: return
        viewModelScope.launch(Dispatchers.IO) {
            qqProvider.reportPlayRecently(qqSong)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 销毁前保存一次状态
        savePlaybackState()
        stopProgressUpdates()
        sleepTimerJob?.cancel()
        cancelFocusResumeWatcher()
        abandonAudioFocus()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modernCallStateListener?.let {
                    telephonyManager?.unregisterTelephonyCallback(it)
                }
            } else {
                @Suppress("DEPRECATION")
                telephonyManager?.listen(legacyPhoneStateListener, PhoneStateListener.LISTEN_NONE)
            }
        } catch (_: Exception) {
        }
    }
}
