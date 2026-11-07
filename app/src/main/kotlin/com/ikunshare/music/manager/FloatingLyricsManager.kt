package com.ikunshare.music.manager

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import com.ikunshare.music.common.AppSettings
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.model.LyricLine
import com.ikunshare.music.service.PlaybackService
import com.ikunshare.music.ui.FloatingLyricsView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 桌面歌词浮窗生命周期管理器
 *
 * 监听 AppSettingsManager.settings 的变化，自动显示/隐藏浮窗。
 * 管理 WindowManager 添加/移除 FloatingLyricsView。
 * 不使用 WRAP_CONTENT，由 Manager 主动计算精确高度设置到 LayoutParams。
 */
class FloatingLyricsManager(
    private val context: Context,
    private val appSettingsManager: AppSettingsManager
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences =
        context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var view: FloatingLyricsView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false
    private var appliedSettings: AppSettings? = null

    private data class LyricState(
        val line: LyricLine?,
        val position: Long,
        val playing: Boolean,
        val playbackRate: Float,
    )

    private var lyricState = LyricState(null, 0L, false, 1f)

    // 记忆浮窗位置（持久化到 SharedPreferences）
    private var savedX = prefs.getInt("floating_lyrics_x", Int.MIN_VALUE)
    private var savedY = prefs.getInt("floating_lyrics_y", 100)

    init {
        scope.launch {
            appSettingsManager.settings.collect { refresh() }
        }
    }

    fun refresh() {
        val settings = appSettingsManager.settings.value
        PlaybackService.getInstance()?.updateFloatingLyricsState(settings.floatingLyricsEnabled)
        if (settings.floatingLyricsEnabled && hasOverlayPermission()) {
            if (!isShowing) show(settings)
            else if (settings != appliedSettings) updateViewSettings(settings)
        } else if (isShowing) {
            hide()
        }
    }

    private fun show(settings: AppSettings) {
        if (isShowing) return

        val floatingView = FloatingLyricsView(context)
        view = floatingView

        // 应用配置
        floatingView.setAlignment(settings.floatingLyricsAlignment)
        floatingView.setShowKaraoke(settings.floatingLyricsKaraoke)
        floatingView.setPlayedColor(settings.floatingLyricsPlayedColor)
        floatingView.setUnplayedColor(settings.floatingLyricsUnplayedColor)
        floatingView.setSubColor(settings.floatingLyricsSubColor)
        floatingView.setBgOpacity(settings.floatingLyricsBgOpacity)
        floatingView.setMaxLines(settings.floatingLyricsMaxLines)
        floatingView.setMainFontSize(settings.floatingLyricsFontSize.toFloat())
        floatingView.setSubFontSize(settings.floatingLyricsSubFontSize.toFloat())
        floatingView.setHideWhenNotPlaying(settings.floatingLyricsHideWhenNotPlaying)
        floatingView.setShowTranslation(settings.floatingLyricsTranslation)
        floatingView.setShowRomanization(settings.floatingLyricsRomanization)
        floatingView.setLocked(settings.floatingLyricsLocked)

        // 锁定图标点击回调
        floatingView.onLockClickListener = {
            appSettingsManager.update { copy(floatingLyricsLocked = true) }
        }

        val screenWidth = getScreenWidth()
        val viewWidth = (screenWidth * settings.floatingLyricsWidthPercent).toInt()

        // 初始 x 居中（仅首次无保存位置时）
        val initialX = if (savedX != Int.MIN_VALUE) savedX else ((screenWidth - viewWidth) / 2)
        savedX = initialX

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (settings.floatingLyricsLocked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        // 使用明确高度 1px（无歌词时最小），后续由 updateWindowHeight 同步更新
        val params = WindowManager.LayoutParams(
            viewWidth,
            1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = savedY
        }
        layoutParams = params
        constrainWindowPosition(params)

        // 拖拽回调（解锁时）
        if (!settings.floatingLyricsLocked) {
            setupDragCallback(floatingView, params)
        }
        floatingView.onDisplayConfigurationChanged = {
            if (isShowing) updateViewSettings(appSettingsManager.settings.value)
        }

        try {
            windowManager.addView(floatingView, params)
            isShowing = true
            appliedSettings = settings
            val state = lyricState
            floatingView.updateLyricData(state.line, state.position, state.playing, state.playbackRate)
            updateWindowHeight()
        } catch (_: Exception) {
            runCatching { windowManager.removeView(floatingView) }
            floatingView.release()
            isShowing = false
            appliedSettings = null
            view = null
            layoutParams = null
        }
    }

    private fun hide() {
        if (!isShowing) return
        layoutParams?.let(::constrainWindowPosition)
        view?.let { v ->
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            } finally {
                v.release()
            }
        }
        view = null
        layoutParams = null
        isShowing = false
        appliedSettings = null
    }

    private fun updateViewSettings(settings: AppSettings) {
        val v = view ?: return
        val params = layoutParams ?: return

        v.setAlignment(settings.floatingLyricsAlignment)
        v.setShowKaraoke(settings.floatingLyricsKaraoke)
        v.setPlayedColor(settings.floatingLyricsPlayedColor)
        v.setUnplayedColor(settings.floatingLyricsUnplayedColor)
        v.setSubColor(settings.floatingLyricsSubColor)
        v.setBgOpacity(settings.floatingLyricsBgOpacity)
        v.setMaxLines(settings.floatingLyricsMaxLines)
        v.setMainFontSize(settings.floatingLyricsFontSize.toFloat())
        v.setSubFontSize(settings.floatingLyricsSubFontSize.toFloat())
        v.setHideWhenNotPlaying(settings.floatingLyricsHideWhenNotPlaying)
        v.setShowTranslation(settings.floatingLyricsTranslation)
        v.setShowRomanization(settings.floatingLyricsRomanization)
        v.setLocked(settings.floatingLyricsLocked)

        // 更新宽度
        val screenWidth = getScreenWidth()
        params.width = (screenWidth * settings.floatingLyricsWidthPercent).toInt()

        // 更新 flags
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (settings.floatingLyricsLocked) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            v.setDragCallback(null)
        } else {
            setupDragCallback(v, params)
        }
        params.flags = flags
        constrainWindowPosition(params)

        // 宽度/配置变化后重新计算高度
        updateWindowHeight()

        try {
            windowManager.updateViewLayout(v, params)
            appliedSettings = settings
        } catch (_: Exception) {
        }
    }

    fun updateLyricState(
        currentLine: LyricLine?, position: Long, isPlaying: Boolean, playbackRate: Float
    ) {
        lyricState = LyricState(currentLine, position, isPlaying, playbackRate)
        view?.updateLyricData(currentLine, position, isPlaying, playbackRate)
        updateWindowHeight()
    }

    fun clearLyric() {
        lyricState = LyricState(null, 0L, false, 1f)
        view?.updateLyricData(null, 0L, false, 1f)
        updateWindowHeight()
    }

    /**
     * 主动计算 View 所需高度并更新 LayoutParams，同步生效。
     */
    private fun updateWindowHeight() {
        val v = view ?: return
        val params = layoutParams ?: return
        val desiredHeight = v.computeDesiredHeight(params.width).coerceAtLeast(1)
        if (params.height != desiredHeight) {
            params.height = desiredHeight
            constrainWindowPosition(params)
            try {
                windowManager.updateViewLayout(v, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun constrainWindowPosition(params: WindowManager.LayoutParams, persist: Boolean = true) {
        val bounds = getScreenBounds()
        val minVisible = (50 * context.resources.displayMetrics.density).toInt()
        val x = params.x.coerceIn(-(params.width - minVisible), bounds.width() - minVisible)
        val y = params.y.coerceIn(-params.height, bounds.height() - minVisible)
        params.x = x
        params.y = y
        if (persist && (savedX != x || savedY != y)) {
            savedX = x
            savedY = y
            prefs.edit()
                .putInt("floating_lyrics_x", x)
                .putInt("floating_lyrics_y", y)
                .apply()
        }
    }

    private fun setupDragCallback(v: FloatingLyricsView, params: WindowManager.LayoutParams) {
        v.setDragCallback(
            callback = { dx, dy ->
                params.x += dx.toInt()
                params.y += dy.toInt()
                constrainWindowPosition(params, persist = false)
                try {
                    windowManager.updateViewLayout(v, params)
                } catch (_: Exception) {
                }
            },
            onFinished = { constrainWindowPosition(params) }
        )
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun destroy() {
        hide()
    }

    private fun getScreenBounds(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return windowManager.currentWindowMetrics.bounds
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun getScreenWidth() = getScreenBounds().width()
    private fun getScreenHeight() = getScreenBounds().height()
}
