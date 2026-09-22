package com.ikunshare.sound.manager

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.WindowManager
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.model.LyricLine
import com.ikunshare.sound.ui.FloatingLyricsView
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

    // 记忆浮窗位置（持久化到 SharedPreferences）
    private var savedX = prefs.getInt("floating_lyrics_x", Int.MIN_VALUE)
    private var savedY = prefs.getInt("floating_lyrics_y", 100)

    init {
        scope.launch {
            appSettingsManager.settings
                .collect { settings ->
                    if (settings.floatingLyricsEnabled && hasOverlayPermission()) {
                        if (!isShowing) {
                            show(settings)
                        } else {
                            updateViewSettings(settings)
                        }
                    } else if (!settings.floatingLyricsEnabled && isShowing) {
                        hide()
                    }
                }
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

        // 拖拽回调（解锁时）
        if (!settings.floatingLyricsLocked) {
            setupDragCallback(floatingView, params)
        }

        try {
            windowManager.addView(floatingView, params)
            isShowing = true
        } catch (_: Exception) {
            view = null
            layoutParams = null
        }
    }

    private fun hide() {
        if (!isShowing) return
        view?.let { v ->
            v.release()
            try {
                windowManager.removeView(v)
            } catch (_: Exception) {
            }
        }
        view = null
        layoutParams = null
        isShowing = false
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

        // 宽度/配置变化后重新计算高度
        updateWindowHeight()

        try {
            windowManager.updateViewLayout(v, params)
        } catch (_: Exception) {
        }
    }

    fun updateLyricState(currentLine: LyricLine?, position: Long, isPlaying: Boolean) {
        val v = view ?: return
        v.updateLyricData(currentLine, position, isPlaying)
        updateWindowHeight()
    }

    fun clearLyric() {
        val v = view ?: return
        v.updateLyricData(null, 0L, false)
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
            try {
                windowManager.updateViewLayout(v, params)
            } catch (_: Exception) {
            }
        }
    }

    private fun setupDragCallback(v: FloatingLyricsView, params: WindowManager.LayoutParams) {
        v.setDragCallback { dx, dy ->
            val screenWidth = getScreenWidth()
            val screenHeight = getScreenHeight()

            // 计算新位置
            var newX = params.x + dx.toInt()
            var newY = params.y + dy.toInt()

            // 边界限制：至少保留 50dp 可见区域
            val minVisible = (50 * context.resources.displayMetrics.density).toInt()
            val maxX = screenWidth - minVisible
            val minX = -(params.width - minVisible)

            // Y 轴：允许拖到状态栏上方（负值），但不完全拖出屏幕
            val minY = -(params.height)
            val maxY = screenHeight - minVisible

            newX = newX.coerceIn(minX, maxX)
            newY = newY.coerceIn(minY, maxY)

            params.x = newX
            params.y = newY
            savedX = newX
            savedY = newY
            prefs.edit()
                .putInt("floating_lyrics_x", savedX)
                .putInt("floating_lyrics_y", savedY)
                .apply()
            try {
                windowManager.updateViewLayout(v, params)
            } catch (_: Exception) {
            }
        }
    }

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun destroy() {
        hide()
    }

    private fun getScreenWidth(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.widthPixels
    }

    private fun getScreenHeight(): Int {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)
        return metrics.heightPixels
    }
}
