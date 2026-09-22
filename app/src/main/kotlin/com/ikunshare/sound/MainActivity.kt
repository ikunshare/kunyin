package com.ikunshare.sound

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.manager.CredentialManager
import com.ikunshare.sound.manager.DownloadManager
import com.ikunshare.sound.manager.NoticeInfo
import com.ikunshare.sound.manager.NoticeManager
import com.ikunshare.sound.manager.UpdateInfo
import com.ikunshare.sound.service.PlaybackService
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.ActivationDialog
import com.ikunshare.sound.ui.components.BottomBarWithMiniPlayer
import com.ikunshare.sound.ui.components.EulaDialog
import com.ikunshare.sound.ui.components.LiquidGlassBottomBar
import com.ikunshare.sound.ui.components.LiquidGlassSideNavBar
import com.ikunshare.sound.ui.components.NoticeDialog
import com.ikunshare.sound.ui.components.UpdateDialog
import com.ikunshare.sound.ui.navigation.Screen
import com.ikunshare.sound.ui.navigation.SoundNavHost
import com.ikunshare.sound.ui.navigation.TABS_ROUTE
import com.ikunshare.sound.ui.screens.player.PlayerViewModel
import com.ikunshare.sound.ui.theme.KunSoundTheme
import com.ikunshare.sound.ui.theme.resolveActiveThemeId
import com.ikunshare.sound.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.sound.ui.utils.LocalScrollOffsetReporter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

/**
 * 底部导航栏项定义
 */
data class BottomNavItem(
    @param:StringRes val labelRes: Int,
    val icon: ImageVector
)

/**
 * 底部导航栏项列表（顺序与 HorizontalPager 页码对应）
 */
val bottomNavItems = listOf(
    BottomNavItem(labelRes = R.string.nav_search, icon = Icons.Filled.Search),
    BottomNavItem(labelRes = R.string.nav_playlists, icon = Icons.AutoMirrored.Filled.QueueMusic),
    BottomNavItem(labelRes = R.string.nav_download, icon = Icons.Filled.Download),
    BottomNavItem(labelRes = R.string.nav_settings, icon = Icons.Filled.Settings)
)

private const val SMALL_SCREEN_WIDTH_THRESHOLD_DP = 600

class MainActivity : AppCompatActivity() {

    internal lateinit var playerViewModel: PlayerViewModel

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackService.ACTION_PLAY_PAUSE -> playerViewModel.togglePlayPause()
                PlaybackService.ACTION_NEXT -> playerViewModel.next()
                PlaybackService.ACTION_PREV -> playerViewModel.previous()
                PlaybackService.ACTION_TOGGLE_FAVORITE -> playerViewModel.toggleFavorite()
                PlaybackService.ACTION_TOGGLE_FLOATING_LYRICS -> toggleFloatingLyrics()
                PlaybackService.ACTION_STOP_AND_EXIT -> stopPlaybackAndExitApp()
                PlaybackService.ACTION_SEEK_TO -> {
                    val pos = intent.getLongExtra(PlaybackService.EXTRA_SEEK_POSITION, 0L)
                    playerViewModel.seekTo(pos)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as SoundApplication

        // 应用语言设置
        applyLanguage(app.appSettingsManager.settings.value.language)

        val factory = KunyinViewModelFactory(
            app.musicRepository,
            app.mediaPlayer,
            app.credentialManager,
            app.appSettingsManager,
            app.localMusicStore,
            app.audioCache,
            app.authManager,
            app.downloadManager,
            app.playbackStateStore,
            app.platformPlaylistStore
        )

        playerViewModel = ViewModelProvider(this, factory)[PlayerViewModel::class.java]

        // 注册全局播放控制器（MV 弹窗等会用到）
        app.playbackController = object : SoundApplication.PlaybackController {
            override fun isPlaying(): Boolean = playerViewModel.uiState.value.isPlaying
            override fun pause() {
                if (playerViewModel.uiState.value.isPlaying) playerViewModel.togglePlayPause()
            }

            override fun play() {
                if (!playerViewModel.uiState.value.isPlaying) playerViewModel.togglePlayPause()
            }
        }

        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAY_PAUSE)
            addAction(PlaybackService.ACTION_NEXT)
            addAction(PlaybackService.ACTION_PREV)
            addAction(PlaybackService.ACTION_TOGGLE_FAVORITE)
            addAction(PlaybackService.ACTION_TOGGLE_FLOATING_LYRICS)
            addAction(PlaybackService.ACTION_STOP_AND_EXIT)
            addAction(PlaybackService.ACTION_SEEK_TO)
        }
        ContextCompat.registerReceiver(
            this,
            playbackReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        lifecycleScope.launch {
            var lastSongId: Long? = null
            var lastDuration = 0L
            var lastCoverBitmap: Bitmap? = null
            var lastLyricText = ""
            var lastPushMediaSessionLyrics =
                app.appSettingsManager.settings.value.pushLyricsToMediaSession
            var lastPushMeizuStatusBarLyrics =
                app.appSettingsManager.settings.value.pushMeizuStatusBarLyrics
            var lastFavoriteState = false
            // 初始为 false，让首帧若实际开启则触发一次同步，保证通知图标与设置一致
            var lastFloatingLyricsState = false
            // combine 让设置开关变化也能实时驱动推送（不用等 uiState 下一帧）
            playerViewModel.uiState
                .combine(app.appSettingsManager.settings) { state, settings -> state to settings }
                .collect { (state, appSettings) ->
                    val service = PlaybackService.getInstance() ?: return@collect

                    val songId = state.currentSong?.id
                    val songChanged = songId != null && songId != lastSongId
                    val durationChanged = state.duration > 0L && state.duration != lastDuration
                    val pushMediaSessionLyrics = appSettings.pushLyricsToMediaSession
                    val pushMeizuStatusBarLyrics = appSettings.pushMeizuStatusBarLyrics
                    val lyricChanged = state.currentLyricPushText != lastLyricText
                    val pushConfigChanged =
                        pushMediaSessionLyrics != lastPushMediaSessionLyrics ||
                                pushMeizuStatusBarLyrics != lastPushMeizuStatusBarLyrics

                    if (songId != null && (songChanged || lyricChanged || pushConfigChanged)) {
                        service.updateLyricPush(
                            lyricText = state.currentLyricPushText,
                            pushToMediaSession = pushMediaSessionLyrics,
                            pushToMeizuStatusBar = pushMeizuStatusBarLyrics
                        )
                        lastLyricText = state.currentLyricPushText
                    } else if (songId == null && (lastLyricText.isNotEmpty() || pushConfigChanged)) {
                        service.updateLyricPush(
                            lyricText = "",
                            pushToMediaSession = pushMediaSessionLyrics,
                            pushToMeizuStatusBar = pushMeizuStatusBarLyrics
                        )
                        lastLyricText = ""
                    }
                    if (pushConfigChanged) {
                        lastPushMediaSessionLyrics = pushMediaSessionLyrics
                        lastPushMeizuStatusBarLyrics = pushMeizuStatusBarLyrics
                    }

                    if (songId != null) {
                        if (state.isFavorite != lastFavoriteState) {
                            service.updateFavoriteState(state.isFavorite)
                            lastFavoriteState = state.isFavorite
                        }
                    } else if (lastFavoriteState) {
                        service.updateFavoriteState(false)
                        lastFavoriteState = false
                    }

                    if (appSettings.floatingLyricsEnabled != lastFloatingLyricsState) {
                        service.updateFloatingLyricsState(appSettings.floatingLyricsEnabled)
                        lastFloatingLyricsState = appSettings.floatingLyricsEnabled
                    }

                    if (songId != null && (songChanged || durationChanged)) {
                        val song = state.currentSong
                        if (songChanged) {
                            lastCoverBitmap = loadCoverBitmap(song.cover)
                        }
                        lastSongId = songId
                        lastDuration = state.duration
                        service.updateMetadata(
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            duration = state.duration,
                            coverBitmap = lastCoverBitmap
                        )
                    }
                }
        }

        // MediaSession 播放进度同步：监听内部高频进度流，让系统通知/锁屏/车载等的进度条不再卡顿
        lifecycleScope.launch {
            playerViewModel.currentPosition
                .combine(playerViewModel.uiState) { position, state -> position to state.isPlaying }
                .distinctUntilChanged()
                .collect { (position, isPlaying) ->
                    PlaybackService.getInstance()?.updatePlaybackState(isPlaying, position)
                }
        }

        setContent {
            val settings by app.appSettingsManager.settings.collectAsState()
            val customThemes by app.appSettingsManager.customThemes.collectAsState()

            // EULA 同意状态
            val eulaPrefs = remember { getSharedPreferences("app_settings", MODE_PRIVATE) }
            var eulaAccepted by remember {
                mutableStateOf(
                    eulaPrefs.getBoolean(
                        "eula_accepted",
                        false
                    )
                )
            }

            // 公告检查
            var noticeContent by remember { mutableStateOf<NoticeInfo?>(null) }
            LaunchedEffect(Unit) {
                val apiUrl = NoticeManager.NOTICE_API_URL
                if (apiUrl.isNotBlank()) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val versionName = try {
                            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0"
                        } catch (_: Exception) {
                            "1.0"
                        }

                        @Suppress("DEPRECATION")
                        val versionCode = try {
                            packageManager.getPackageInfo(packageName, 0).versionCode
                        } catch (_: Exception) {
                            1
                        }
                        val notice =
                            app.noticeManager.fetchIfUpdated(apiUrl, versionName, versionCode)
                        if (notice != null) {
                            noticeContent = notice
                        }
                    }
                }
            }

            // 更新检查
            var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
            var downloadProgress by remember { mutableStateOf<Float?>(null) }
            var downloadFailed by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                val info = app.updateChecker.check(BuildConfig.VERSION_CODE)
                if (info != null && info.hasUpdate) {
                    if (info.isCompulsory || !app.updateChecker.isVersionSkipped(info.title)) {
                        updateInfo = info
                    }
                }
            }

            KunSoundTheme(
                themeMode = settings.themeMode,
                customThemes = customThemes,
                systemLightTheme = settings.systemLightTheme,
                systemDarkTheme = settings.systemDarkTheme,
                fontScale = settings.globalFontScale,
                fontWeightAdjust = settings.globalFontWeightAdjust
            ) {
                // EULA 同意检查
                if (!eulaAccepted) {
                    val eulaContent = remember {
                        try {
                            assets.open("eula.md").bufferedReader().use { it.readText() }
                        } catch (_: Exception) {
                            ""
                        }
                    }
                    if (eulaContent.isNotBlank()) {
                        EulaDialog(
                            content = eulaContent,
                            onAgree = {
                                eulaPrefs.edit { putBoolean("eula_accepted", true) }
                                eulaAccepted = true
                            },
                            onDisagree = { finishAffinity() }
                        )
                        return@KunSoundTheme
                    } else {
                        eulaPrefs.edit { putBoolean("eula_accepted", true) }
                        eulaAccepted = true
                    }
                }

                // 卡密激活门：未激活时强制阻塞进入应用
                val authState by app.authManager.state.collectAsState()
                if (!authState.isValid) {
                    // 启动时对已存卡密的后台重校验阶段：显示空白占位，避免激活框闪烁
                    val isInitialRecheck =
                        authState.isChecking && authState.authst.isNotEmpty() &&
                                authState.errorMessage.isEmpty()
                    if (isInitialRecheck) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        )
                    } else {
                        ActivationDialog(
                            authState = authState,
                            onSubmit = { input ->
                                lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                    app.authManager.validateAndSave(input)
                                }
                            },
                            onExit = { finishAffinity() },
                            onClearError = { app.authManager.clearError() }
                        )
                    }
                    return@KunSoundTheme
                }

                KunSoundApp(
                    playerViewModel = playerViewModel,
                    viewModelFactory = factory,
                    credentialManager = app.credentialManager,
                    downloadManager = app.downloadManager,
                    appSettingsManager = app.appSettingsManager
                )

                // 公告弹窗
                noticeContent?.let { info ->
                    NoticeDialog(
                        content = info.content,
                        onDismiss = { noticeContent = null }
                    )
                }

                // 更新弹窗
                updateInfo?.let { info ->
                    UpdateDialog(
                        updateInfo = info,
                        onDismiss = { updateInfo = null },
                        onSkipVersion = {
                            app.updateChecker.skipVersion(info.title)
                            updateInfo = null
                        },
                        onExitApp = { finish() },
                        onDownload = {
                            scope.launch {
                                downloadProgress = 0f
                                downloadFailed = false
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val file = app.updateChecker.downloadApk(
                                        info, cacheDir
                                    ) { downloadProgress = it }
                                    if (file != null) {
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                            installApk(file)
                                        }
                                    } else {
                                        downloadFailed = true
                                    }
                                }
                            }
                        },
                        onBrowserDownload = {
                            val url = info.shareUrl.takeIf { it.isNotBlank() }
                            if (url == null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.update_browser_url_empty,
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                runCatching {
                                    startActivity(Intent(ACTION_VIEW, url.toUri()))
                                }.onFailure {
                                    Toast.makeText(
                                        this@MainActivity,
                                        R.string.update_browser_no_app,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            if (!info.isCompulsory) updateInfo = null
                        },
                        downloadProgress = downloadProgress,
                        downloadFailed = downloadFailed
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, PlaybackService::class.java))
            } else {
                startService(Intent(this, PlaybackService::class.java))
            }
        }
    }

    override fun onDestroy() {
        unregisterReceiver(playbackReceiver)
        super.onDestroy()
    }

    private fun stopPlaybackAndExitApp() {
        val app = application as SoundApplication
        try {
            app.mediaPlayer.stop()
        } catch (_: Exception) {
        }
        try {
            app.floatingLyricsManager.clearLyric()
        } catch (_: Exception) {
        }
        stopService(Intent(this, PlaybackService::class.java))
        finishAndRemoveTask()
    }

    /**
     * 通知/灵动岛的"桌面歌词"功能键：切换悬浮歌词开关。
     * 无悬浮窗权限时引导用户去系统授权页，不静默失败。
     */
    private fun toggleFloatingLyrics() {
        val app = application as SoundApplication
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                getString(R.string.floating_lyrics_need_permission),
                Toast.LENGTH_SHORT
            ).show()
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
            return
        }
        app.appSettingsManager.update { copy(floatingLyricsEnabled = !floatingLyricsEnabled) }
    }

    private fun applyLanguage(language: String) {
        val locales = when (language) {
            "zh-CN" -> LocaleListCompat.forLanguageTags("zh-CN")
            "zh-TW" -> LocaleListCompat.forLanguageTags("zh-TW")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    private var pendingInstallFile: File? = null

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                doInstallApk(file)
            }
            pendingInstallFile = null
        }
    }

    fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallFile = file
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:$packageName".toUri()
            )
            installPermissionLauncher.launch(intent)
        } else {
            doInstallApk(file)
        }
    }

    private fun doInstallApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
        val intent = Intent(ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    private suspend fun loadCoverBitmap(url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return try {
            val request = ImageRequest.Builder(this).data(url).allowHardware(false).build()
            val result = imageLoader.execute(request)
            (result as? SuccessResult)?.image?.toBitmap()
        } catch (_: Exception) {
            null
        }
    }
}

@Composable
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
fun KunSoundApp(
    playerViewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    credentialManager: CredentialManager,
    downloadManager: DownloadManager,
    appSettingsManager: AppSettingsManager
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val appSettings by appSettingsManager.settings.collectAsState()
    val customThemes by appSettingsManager.customThemes.collectAsState()

    // 解析当前生效主题
    val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val activeThemeId = resolveActiveThemeId(
        appSettings.themeMode,
        appSettings.systemLightTheme,
        appSettings.systemDarkTheme,
        isSystemDark
    )
    val isCustomTheme = activeThemeId.startsWith("custom_")
    val activeBgImagePath = if (isCustomTheme) {
        val id = activeThemeId.removePrefix("custom_")
        customThemes.firstOrNull { it.id == id }?.bgImagePath ?: ""
    } else {
        appSettings.bgImagePath
    }
    val hasBgImage = activeBgImagePath.isNotEmpty()

    // 宽屏检测（≥600dp 视为宽屏）
    @Suppress("ConfigurationScreenWidthHeight")
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isWideScreen = screenWidthDp >= 600

    // 启动时请求存储和通知权限
    val context = LocalContext.current
    val smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp

    // 手机上的普通页面保持竖屏；进入全屏播放器后跟随用户的自动旋转设置。
    // 宽屏设备继续不限制方向。
    DisposableEffect(currentRoute, smallestScreenWidthDp, context) {
        val activity = context as? Activity
        if (activity != null) {
            activity.requestedOrientation = when {
                smallestScreenWidthDp >= SMALL_SCREEN_WIDTH_THRESHOLD_DP ->
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

                currentRoute == Screen.Player.route -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                else -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            }
        }
        onDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果不阻塞 UI，下载时再次检查 */ }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 请求所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            // Android 10 以下: 请求传统存储权限
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Android 13+: 请求通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val pagerState = rememberPagerState(pageCount = { bottomNavItems.size })
    val scope = rememberCoroutineScope()

    val hasSong by playerViewModel.hasCurrentSong.collectAsState()

    val isOnTabs = currentRoute == TABS_ROUTE
    val showBottomNav =
        isOnTabs || currentRoute?.startsWith("album/") == true || currentRoute?.startsWith("artist/") == true
    val showMiniPlayer = hasSong && currentRoute != Screen.Player.route

    // 滚动状态管理：跟踪底部栏紧凑模式
    var isCompactMode by remember { mutableStateOf(false) }
    var lastModeChangeTime by remember { mutableLongStateOf(0L) }
    val modeChangeDebounceMs = 180L
    val compactEnterThresholdPx = 100
    val compactExitThresholdPx = 48

    // 创建滚动偏移量上报函数
    // 逻辑：到顶部强制退出紧凑模式；使用进入/退出双阈值减少边界抖动
    val reportScrollOffset: (Int, Int, Boolean) -> Unit =
        fun(firstVisibleItemIndex, firstVisibleItemScrollOffset, isScrollingUp) {
            val currentTime = System.currentTimeMillis()
            val isAtTop = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
            val effectiveOffset = if (firstVisibleItemIndex > 0) {
                compactEnterThresholdPx + firstVisibleItemScrollOffset + 1
            } else {
                firstVisibleItemScrollOffset
            }

            if (isAtTop) {
                if (isCompactMode) {
                    isCompactMode = false
                }
                lastModeChangeTime = 0L
                return
            }

            val timeSinceLastChange = currentTime - lastModeChangeTime
            val canToggleMode = timeSinceLastChange >= modeChangeDebounceMs

            val shouldEnterCompact = !isScrollingUp && effectiveOffset >= compactEnterThresholdPx
            val shouldExitCompact = isScrollingUp && effectiveOffset <= compactExitThresholdPx

            val newMode = when {
                !isCompactMode && shouldEnterCompact && canToggleMode -> true
                isCompactMode && shouldExitCompact && canToggleMode -> false
                else -> isCompactMode
            }

            if (newMode != isCompactMode) {
                isCompactMode = newMode
                lastModeChangeTime = currentTime
            }
        }

    // 全局 backdrop，保证一致性
    val backdropBaseColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backdropBaseColor)
        drawContent()
    }

    Box(Modifier.fillMaxSize()) {
        // 全局背景图层
        if (hasBgImage) {
            val bgFile = remember(activeBgImagePath) { File(activeBgImagePath) }
            if (bgFile.exists()) {
                AsyncImage(
                    model = bgFile,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop),
                    contentScale = ContentScale.Crop
                )
                // 叠加半透明背景色遮罩
                // 自定义模式：使用自定义背景色（HEXA 含透明度）
                // 内置主题：使用 background + bgOverlayOpacity
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            if (isCustomTheme) {
                                MaterialTheme.colorScheme.background
                            } else {
                                MaterialTheme.colorScheme.background.copy(
                                    alpha = appSettings.bgOverlayOpacity
                                )
                            }
                        )
                )
            }
        }

        CompositionLocalProvider(
            LocalScrollOffsetReporter provides reportScrollOffset,
            LocalFloatingBottomBarReserve provides if (appSettings.floatingBottomBar) {
                when {
                    showBottomNav && showMiniPlayer && !isCompactMode -> 152.dp
                    showBottomNav && !isCompactMode -> 88.dp
                    showMiniPlayer -> 88.dp
                    else -> 0.dp
                }
            } else 0.dp
        ) {
            val bottomBar: @Composable () -> Unit = {
                if (showBottomNav || showMiniPlayer) {
                    if (appSettings.floatingBottomBar) {
                        LiquidGlassBottomBar(
                            navController = navController,
                            isOnTabs = showBottomNav,
                            showMiniPlayer = showMiniPlayer,
                            playerViewModel = playerViewModel,
                            selectedTabIndex = pagerState.currentPage,
                            onTabClick = { index ->
                                if (!isOnTabs) {
                                    navController.popBackStack(TABS_ROUTE, inclusive = false)
                                }
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        index
                                    )
                                }
                            },
                            isCustomTheme = isCustomTheme,
                            bottomBarOpacity = appSettings.bottomBarOpacity,
                            onExitCompactMode = { isCompactMode = false },
                            isCompactMode = isCompactMode,
                            backdrop = backdrop,
                            isWideScreen = isWideScreen,
                            enableBlur = appSettings.liquidGlassMode
                        )
                    } else {
                        BottomBarWithMiniPlayer(
                            navController = navController,
                            isOnTabs = showBottomNav,
                            showMiniPlayer = showMiniPlayer,
                            playerViewModel = playerViewModel,
                            selectedTabIndex = pagerState.currentPage,
                            onTabClick = { index ->
                                if (!isOnTabs) {
                                    navController.popBackStack(TABS_ROUTE, inclusive = false)
                                }
                                scope.launch {
                                    pagerState.animateScrollToPage(
                                        index
                                    )
                                }
                            },
                            isCustomTheme = isCustomTheme,
                            bottomBarOpacity = appSettings.bottomBarOpacity
                        )
                    }
                }
            }

            if (isWideScreen && appSettings.floatingBottomBar) {
                // 宽屏 + LiquidGlass：左侧竖向导航 + 右侧内容（底部仍有 MiniPlayer）
                Scaffold(
                    containerColor = if (hasBgImage) Color.Transparent else MaterialTheme.colorScheme.background,
                    bottomBar = bottomBar
                ) { _ ->
                    Row(Modifier.fillMaxSize()) {
                        // 左侧竖向导航栏
                        if (isOnTabs) {
                            LiquidGlassSideNavBar(
                                selectedIndex = pagerState.currentPage,
                                onTabClick = { index ->
                                    scope.launch {
                                        pagerState.animateScrollToPage(
                                            index
                                        )
                                    }
                                },
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxHeight(),
                                enableBlur = appSettings.liquidGlassMode
                            )
                        }
                        // 右侧主内容
                        SoundNavHost(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            viewModelFactory = viewModelFactory,
                            credentialManager = credentialManager,
                            downloadManager = downloadManager,
                            pagerState = pagerState,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .layerBackdrop(backdrop)
                        )
                    }
                }
            } else {
                // 窄屏或非 LiquidGlass：保持原有上下结构
                Scaffold(
                    containerColor = if (hasBgImage) Color.Transparent else MaterialTheme.colorScheme.background,
                    bottomBar = bottomBar
                ) { padding ->
                    SoundNavHost(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        viewModelFactory = viewModelFactory,
                        credentialManager = credentialManager,
                        downloadManager = downloadManager,
                        pagerState = pagerState,
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop)
                            .then(
                                if (!appSettings.floatingBottomBar) {
                                    Modifier.padding(bottom = padding.calculateBottomPadding())
                                } else Modifier
                            )
                    )
                }
            }
        }
    }
}
