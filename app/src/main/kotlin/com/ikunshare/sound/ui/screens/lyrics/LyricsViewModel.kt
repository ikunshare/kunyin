package com.ikunshare.sound.ui.screens.lyrics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.manager.MusicRepository
import com.ikunshare.sound.model.Lyric
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.tool.LyricParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * 歌词页面 UI 状态
 *
 * @param lyrics 原始歌词数据，null 表示尚未加载或无歌词
 * @param parsedLyrics 解析后的歌词行列表，用于 UI 渲染
 * @param showTranslation 是否显示翻译歌词
 * @param showRomanization 是否显示音译歌词
 * @param hasTranslation 当前歌词是否包含翻译
 * @param hasRomanization 当前歌词是否包含音译
 * @param hasCharLyrics 当前歌词是否包含逐字歌词（卡拉OK效果）
 * @param isLoading 是否正在加载歌词
 * @param error 错误信息，null 表示无错误
 */
data class LyricsUiState(
    val lyrics: Lyric? = null,
    val parsedLyrics: List<LyricLine> = emptyList(),
    val showTranslation: Boolean = false,
    val showRomanization: Boolean = false,
    val showPhonetic: Boolean = false,
    val hasTranslation: Boolean = false,
    val hasRomanization: Boolean = false,
    val hasPhonetic: Boolean = false,
    val hasCharLyrics: Boolean = false,
    val isSynced: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null
)

/**
 * 歌词页面 ViewModel
 *
 * 管理歌词页面的 UI 状态，包括歌词加载、解析、翻译切换和音译切换。
 * 使用 MusicRepository 获取歌词数据，使用 LyricParser 解析歌词格式。
 *
 * **Validates: Requirements 4.1-4.10**
 * - 4.1: 歌词页面加载时显示与播放位置同步的歌词
 * - 4.2: 播放位置变化时自动滚动并高亮当前歌词行
 * - 4.3: 当前歌词行通过颜色和大小与其他行区分
 * - 4.4: 有翻译歌词时提供显示/隐藏翻译的切换按钮
 * - 4.5: 启用翻译切换时在每行原文下方显示翻译
 * - 4.6: 有逐字歌词时按播放进度逐字高亮动画
 * - 4.7: 点击歌词行跳转到该行的时间戳
 * - 4.8: 歌词页面底部显示迷你播放器控制
 * - 4.9: 无歌词时显示"暂无歌词"提示
 * - 4.10: 有音译歌词时提供显示/隐藏音译的切换按钮
 */
class LyricsViewModel(
    private val repository: MusicRepository,
    private val appSettingsManager: AppSettingsManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        LyricsUiState(
            showTranslation = appSettingsManager.settings.value.showTranslation &&
                    !appSettingsManager.settings.value.showRomanization,
            showRomanization = appSettingsManager.settings.value.showRomanization,
            showPhonetic = appSettingsManager.settings.value.showPhonetic
        )
    )
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    /** 当前已加载歌词的歌曲 ID，用于避免重复加载 */
    private var currentSongId: Long? = null

    /**
     * 加载歌词
     *
     * 从 Repository 获取指定歌曲的歌词数据，然后使用 LyricParser 解析。
     * 解析后更新 UI 状态，包括歌词行列表和各类歌词的可用性标志。
     * 如果歌曲与当前已加载的歌曲相同，则跳过重复加载。
     *
     * @param song 要加载歌词的歌曲
     *
     * **Validates: Requirements 4.1, 4.4, 4.6, 4.9, 4.10**
     */
    fun loadLyrics(song: MusicItem) {
        // 避免重复加载同一首歌的歌词
        if (song.id == currentSongId && _uiState.value.lyrics != null) {
            return
        }

        currentSongId = song.id

        // 立即清空旧歌词并显示 loading，不等协程调度
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                lyrics = null,
                parsedLyrics = emptyList(),
                hasTranslation = false,
                hasRomanization = false,
                hasCharLyrics = false
            )
        }

        viewModelScope.launch {
            try {
                // 从 Repository 获取歌词
                val lyric = repository.getLyric(song)

                // 使用 LyricParser 解析歌词
                val parsedLyric = LyricParser.parse(lyric, song)

                // 判断歌词是否为空：lrc 为空或解析后无歌词行
                if (parsedLyric.isEmpty) {
                    // 无歌词可用（不重置 showTranslation/showRomanization，保留用户偏好）
                    _uiState.update {
                        it.copy(
                            lyrics = null,
                            parsedLyrics = emptyList(),
                            hasTranslation = false,
                            hasRomanization = false,
                            hasCharLyrics = false,
                            isLoading = false,
                            error = null
                        )
                    }
                } else {
                    // 歌词加载成功，从持久化设置读取用户偏好
                    val settings = appSettingsManager.settings.value
                    val hasPhonetic =
                        parsedLyric.lines.any { it.phonetic != null && it.phonetic.isNotBlank() }
                    val showRomanization = settings.showRomanization && parsedLyric.hasRomanization
                    val showTranslation = settings.showTranslation &&
                            parsedLyric.hasTranslation &&
                            !showRomanization
                    _uiState.update {
                        it.copy(
                            lyrics = lyric,
                            parsedLyrics = parsedLyric.lines,
                            hasTranslation = parsedLyric.hasTranslation,
                            hasRomanization = parsedLyric.hasRomanization,
                            hasPhonetic = hasPhonetic,
                            hasCharLyrics = parsedLyric.hasCharLyrics,
                            isSynced = parsedLyric.isSynced,
                            showTranslation = showTranslation,
                            showRomanization = showRomanization,
                            showPhonetic = settings.showPhonetic && hasPhonetic,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            } catch (_: IOException) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(R.string.network_error)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = SoundApplication.instance!!.getString(
                            R.string.load_lyrics_failed,
                            e.message ?: ""
                        )
                    )
                }
            }
        }
    }

    /**
     * 切换翻译歌词显示状态
     *
     * 在显示和隐藏翻译歌词之间切换。
     * 仅当当前歌词包含翻译时才有效。
     *
     * **Validates: Requirements 4.4, 4.5**
     */
    fun toggleTranslation() {
        val currentState = _uiState.value
        if (!currentState.hasTranslation) return

        val newValue = !currentState.showTranslation
        appSettingsManager.update {
            copy(
                showTranslation = newValue,
                showRomanization = if (newValue) false else showRomanization
            )
        }
        _uiState.update {
            it.copy(
                showTranslation = newValue,
                showRomanization = if (newValue) false else it.showRomanization
            )
        }
    }

    /**
     * 切换音译歌词显示状态
     *
     * 在显示和隐藏音译歌词之间切换。
     * 仅当当前歌词包含音译时才有效。
     *
     * **Validates: Requirements 4.10**
     */
    fun toggleRomanization() {
        val currentState = _uiState.value
        if (!currentState.hasRomanization) return

        val newValue = !currentState.showRomanization
        appSettingsManager.update {
            copy(
                showRomanization = newValue,
                showTranslation = if (newValue) false else showTranslation
            )
        }
        _uiState.update {
            it.copy(
                showRomanization = newValue,
                showTranslation = if (newValue) false else it.showTranslation
            )
        }
    }

    fun togglePhonetic() {
        val currentState = _uiState.value
        if (!currentState.hasPhonetic) return

        val newValue = !currentState.showPhonetic
        appSettingsManager.update { copy(showPhonetic = newValue) }
        _uiState.update { it.copy(showPhonetic = newValue) }
    }

    /**
     * 重试加载歌词
     *
     * 当歌词加载失败时，重新尝试加载当前歌曲的歌词。
     * 需要外部传入当前歌曲信息。
     *
     * @param song 要重新加载歌词的歌曲
     */
    fun retry(song: MusicItem) {
        // 重置当前歌曲 ID 以强制重新加载
        currentSongId = null
        loadLyrics(song)
    }

}
