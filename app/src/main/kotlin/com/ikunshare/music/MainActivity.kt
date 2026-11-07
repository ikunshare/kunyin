package com.ikunshare.music

import com.ikunshare.music.utils.OrientationPolicy
import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.IntentFilter
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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.ikunshare.music.utils.BatteryOptimization
import kotlinx.coroutines.delay
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.manager.CredentialManager
import com.ikunshare.music.manager.DownloadManager
import com.ikunshare.music.manager.NoticeInfo
import com.ikunshare.music.manager.NoticeManager
import com.ikunshare.music.manager.UpdateInfo
import com.ikunshare.music.service.PlaybackService
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.ActivationDialog
import com.ikunshare.music.ui.components.EulaDialog
import com.ikunshare.music.ui.components.GlassDialogHostBox
import com.ikunshare.music.ui.components.LiquidGlassBottomBar
import com.ikunshare.music.ui.components.LocalGlassDialogHost
import com.ikunshare.music.ui.components.rememberGlassDialogHost
import com.ikunshare.music.ui.components.LiquidGlassSideNavBar
import com.ikunshare.music.ui.components.NoticeDialog
import com.ikunshare.music.ui.components.UpdateDialog
import com.ikunshare.music.ui.components.bottomNavItems
import com.ikunshare.music.ui.navigation.SoundNavHost
import com.ikunshare.music.ui.navigation.TABS_ROUTE
import com.ikunshare.music.ui.navigation.rememberTabSwitcher
import com.ikunshare.music.ui.screens.player.LocalPlayerSheet
import com.ikunshare.music.ui.screens.player.PlayerScreen
import com.ikunshare.music.ui.screens.player.PlayerSheetHost
import com.ikunshare.music.ui.screens.player.rememberPlayerSheetState
import com.ikunshare.music.ui.screens.player.PlayerViewModel
import com.ikunshare.music.ui.theme.KunSoundTheme
import com.ikunshare.music.ui.theme.resolveActiveThemeId
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.ikunshare.music.ui.utils.LocalScrollOffsetReporter
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.File

private const val STATE_PENDING_INSTALL_FILE = "pending_install_file"
private const val STATE_ENABLE_FLOATING_LYRICS = "enable_floating_lyrics"

class MainActivity : AppCompatActivity() {

    internal lateinit var playerViewModel: PlayerViewModel

    private val playbackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackService.ACTION_PLAY_PAUSE -> playerViewModel.togglePlayPause()
                PlaybackService.ACTION_NEXT -> playerViewModel.next()
                PlaybackService.ACTION_PREV -> playerViewModel.previous()
                PlaybackService.ACTION_TOGGLE_FAVORITE -> playerViewModel.toggleFavorite()
                PlaybackService.ACTION_FAVORITE_CHANGED -> playerViewModel.refreshFavoriteState()
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
        pendingInstallFile = savedInstanceState?.getString(STATE_PENDING_INSTALL_FILE)?.let(::File)
        pendingFloatingLyricsEnable = savedInstanceState?.getBoolean(STATE_ENABLE_FLOATING_LYRICS) == true
        OrientationPolicy.apply(this)
        enableEdgeToEdge()

        val app = application as MusicApplication

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
        app.playbackController = object : MusicApplication.PlaybackController {
            override fun isPlaying(): Boolean = playerViewModel.uiState.value.isPlaying
            override fun pause() {
                if (playerViewModel.uiState.value.isPlaying) playerViewModel.togglePlayPause()
            }

            override fun play() {
                if (!playerViewModel.uiState.value.isPlaying) playerViewModel.togglePlayPause()
            }

            override fun next() = playerViewModel.next()
            override fun previous() = playerViewModel.previous()
            override fun seekTo(positionMs: Long) = playerViewModel.seekTo(positionMs)
        }

        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAY_PAUSE)
            addAction(PlaybackService.ACTION_NEXT)
            addAction(PlaybackService.ACTION_PREV)
            addAction(PlaybackService.ACTION_TOGGLE_FAVORITE)
            addAction(PlaybackService.ACTION_FAVORITE_CHANGED)
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
            var lastSongKey: String? = null
            var metadataInitialized = false
            var lastDuration = 0L
            var lastCoverBitmap: Bitmap? = null
            var lastLyricText: String? = null
            var lastPushMediaSessionLyrics =
                app.appSettingsManager.settings.value.pushLyricsToMediaSession
            var lastPushMeizuStatusBarLyrics =
                app.appSettingsManager.settings.value.pushMeizuStatusBarLyrics
            var lastFavoriteState: Boolean? = null
            // 初始为 false，让首帧若实际开启则触发一次同步，保证通知图标与设置一致
            var lastFloatingLyricsState = false
            // combine 让设置开关变化也能实时驱动推送（不用等 uiState 下一帧）
            playerViewModel.uiState
                .combine(app.appSettingsManager.settings) { state, settings -> state to settings }
                .combine(PlaybackService.instanceFlow) { stateAndSettings, service ->
                    Triple(stateAndSettings.first, stateAndSettings.second, service)
                }
                .collect { (state, appSettings, service) ->
                    if (service == null) {
                        lastSongKey = null
                        metadataInitialized = false
                        lastDuration = 0L
                        lastLyricText = null
                        lastFavoriteState = null
                        lastFloatingLyricsState = false
                        return@collect
                    }

                    val songKey = state.currentSong?.uniqueKey
                    val songChanged = songKey != null && songKey != lastSongKey
                    val durationChanged = state.duration > 0L && state.duration != lastDuration
                    val pushMediaSessionLyrics = appSettings.pushLyricsToMediaSession
                    val pushMeizuStatusBarLyrics = appSettings.pushMeizuStatusBarLyrics
                    val lyricChanged = state.currentLyricPushText != lastLyricText
                    val pushConfigChanged =
                        pushMediaSessionLyrics != lastPushMediaSessionLyrics ||
                                pushMeizuStatusBarLyrics != lastPushMeizuStatusBarLyrics

                    if (songKey != null && (songChanged || lyricChanged || pushConfigChanged)) {
                        service.updateLyricPush(
                            lyricText = state.currentLyricPushText,
                            pushToMediaSession = pushMediaSessionLyrics,
                            pushToMeizuStatusBar = pushMeizuStatusBarLyrics
                        )
                        lastLyricText = state.currentLyricPushText
                    } else if (songKey == null && (lastLyricText == null || lastLyricText?.isNotEmpty() == true || pushConfigChanged)) {
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

                    val favorite = state.currentSong != null && state.isFavorite
                    if (favorite != lastFavoriteState) {
                        service.updateFavoriteState(favorite)
                        lastFavoriteState = favorite
                    }

                    if (appSettings.floatingLyricsEnabled != lastFloatingLyricsState) {
                        service.updateFloatingLyricsState(appSettings.floatingLyricsEnabled)
                        lastFloatingLyricsState = appSettings.floatingLyricsEnabled
                    }

                    if (songKey == null && (!metadataInitialized || lastSongKey != null)) {
                        lastSongKey = null
                        lastDuration = 0L
                        lastCoverBitmap = null
                        service.updateMetadata("", "", "", 0L, null)
                        metadataInitialized = true
                    }
                    if (songKey != null && (songChanged || durationChanged)) {
                        val song = state.currentSong
                        if (songChanged) {
                            lastCoverBitmap = loadCoverBitmap(song.cover)
                        }
                        lastSongKey = songKey
                        lastDuration = state.duration
                        service.updateMetadata(
                            title = song.title,
                            artist = song.artist,
                            album = song.album,
                            duration = state.duration,
                            coverBitmap = lastCoverBitmap
                        )
                        metadataInitialized = true
                    }
                }
        }

        // MediaSession 播放进度同步：监听内部高频进度流，让系统通知/锁屏/车载等的进度条不再卡顿
        lifecycleScope.launch {
            playerViewModel.currentPosition
                .combine(playerViewModel.uiState) { position, state ->
                    Triple(position, state.isPlaying, state.playbackSpeed)
                }
                .combine(PlaybackService.instanceFlow) { playback, service -> playback to service }
                .distinctUntilChanged()
                .collect { (playback, service) ->
                    service?.updatePlaybackState(playback.second, playback.first, playback.third)
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
            LaunchedEffect(eulaAccepted) {
                if (!eulaAccepted) return@LaunchedEffect
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
            var downloadFailed by updateDownloadFailed
            val scope = rememberCoroutineScope()

            LaunchedEffect(eulaAccepted) {
                if (!eulaAccepted) return@LaunchedEffect
                val info = app.updateChecker.check()
                if (info != null && info.hasUpdate) {
                    if (info.isCompulsory || !app.updateChecker.isVersionSkipped(info.version)) {
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

                // 更新优先，处理完成后再显示公告，避免两个 Dialog 叠加。
                if (updateInfo == null) noticeContent?.let { info ->
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
                            app.updateChecker.skipVersion(info.version)
                            updateInfo = null
                        },
                        onExitApp = { finish() },
                        onDownload = {
                            scope.launch {
                                if (downloadProgress != null && !downloadFailed) return@launch
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
        }.onFailure {
            android.util.Log.w("MainActivity", "Unable to start playback service", it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!pendingFloatingLyricsEnable) return
        pendingFloatingLyricsEnable = false
        if (Settings.canDrawOverlays(this)) {
            val app = application as MusicApplication
            app.appSettingsManager.update { copy(floatingLyricsEnabled = true) }
            app.floatingLyricsManager.refresh()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_INSTALL_FILE, pendingInstallFile?.path)
        outState.putBoolean(STATE_ENABLE_FLOATING_LYRICS, pendingFloatingLyricsEnable)
    }

    override fun onDestroy() {
        unregisterReceiver(playbackReceiver)
        super.onDestroy()
    }

    private fun stopPlaybackAndExitApp() {
        val app = application as MusicApplication
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
     * 仅在开启且没有权限时引导到系统授权页，关闭不需要权限。
     */
    private fun toggleFloatingLyrics() {
        val app = application as MusicApplication
        if (!Settings.canDrawOverlays(this) && !app.appSettingsManager.settings.value.floatingLyricsEnabled) {
            Toast.makeText(
                this,
                getString(R.string.floating_lyrics_need_permission),
                Toast.LENGTH_SHORT
            ).show()
            pendingFloatingLyricsEnable = true
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e: Exception) {
                pendingFloatingLyricsEnable = false
                android.util.Log.w("MainActivity", "Unable to open overlay permission settings", e)
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
    private var pendingFloatingLyricsEnable = false
    private val updateDownloadFailed = mutableStateOf(false)

    private val installApkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateDownloadFailed.value = true
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        pendingInstallFile?.let { file ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                doInstallApk(file)
            } else {
                updateDownloadFailed.value = true
            }
            pendingInstallFile = null
        }
    }

    fun installApk(file: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                pendingInstallFile = file
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:$packageName".toUri()
                )
                installPermissionLauncher.launch(intent)
            } catch (e: Exception) {
                pendingInstallFile = null
                android.util.Log.e("MainActivity", "Unable to launch install permission settings", e)
                updateDownloadFailed.value = true
                Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
            }
        } else {
            doInstallApk(file)
        }
    }

    private fun doInstallApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            installApkLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Unable to launch package installer", e)
            updateDownloadFailed.value = true
            Toast.makeText(this, R.string.install_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private suspend fun loadCoverBitmap(url: String?): Bitmap? {
        if (url.isNullOrEmpty()) return null
        return try {
            val request = ImageRequest.Builder(this).data(url).allowHardware(false).build()
            val result = imageLoader.execute(request)
            (result as? SuccessResult)?.image?.toBitmap()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
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
    val playerSheet = rememberPlayerSheetState()

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 权限结果不阻塞 UI */ }
    val requestNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { requestNotificationPermission() }
    val storageSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { requestNotificationPermission() }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 请求所有文件访问权限
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent =
                        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                            data = "package:${context.packageName}".toUri()
                        }
                    storageSettingsLauncher.launch(intent)
                } catch (_: Exception) {
                    try {
                        storageSettingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (e: Exception) {
                        android.util.Log.w("MainActivity", "Unable to open storage permission settings", e)
                        Toast.makeText(context, R.string.storage_settings_failed, Toast.LENGTH_SHORT).show()
                        requestNotificationPermission()
                    }
                }
            } else {
                requestNotificationPermission()
            }
        } else if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Android 10 以下: 请求传统存储权限
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            requestNotificationPermission()
        }
    }

    // 后台播放需要关闭电池优化：每次启动都检查，未授权就请求。
    // 等界面真正处于前台（没有文件权限页 / 通知权限框挡在前面）再弹，避免几个系统页面叠在一起。
    LifecycleResumeEffect(context) {
        (context.applicationContext as? MusicApplication)?.floatingLyricsManager?.refresh()
        val activity = context as? Activity
        val job = activity?.let {
            (it as? AppCompatActivity)?.lifecycleScope?.launch {
                delay(800)
                if (it.hasWindowFocus()) BatteryOptimization.requestIfNeeded(it)
            }
        }
        onPauseOrDispose { job?.cancel() }
    }

    val pagerState = rememberPagerState(pageCount = { bottomNavItems.size })
    val tabSwitcher = rememberTabSwitcher(pagerState)
    val onTabClick: (Int) -> Unit = remember(navController, tabSwitcher) {
        { index ->
            if (navController.currentDestination?.route != TABS_ROUTE) {
                navController.popBackStack(TABS_ROUTE, inclusive = false)
            }
            tabSwitcher.select(index)
        }
    }

    val hasSong by playerViewModel.hasCurrentSong.collectAsState()

    val isOnTabs = currentRoute == TABS_ROUTE
    val showBottomNav =
        isOnTabs || currentRoute?.startsWith("album/") == true || currentRoute?.startsWith("artist/") == true
    val bottomNavVisible = showBottomNav && !isWideScreen
    // The player sheet covers the mini player; keeping it shown lets a dismiss reveal it.
    val showMiniPlayer = hasSong
    val bottomBarInset = with(LocalDensity.current) { WindowInsets.safeDrawing.getBottom(this).toDp() }

    // 滚动状态管理：跟踪底部栏紧凑模式
    var isCompactMode by remember { mutableStateOf(false) }
    var lastModeChangeTime by remember { mutableLongStateOf(0L) }
    val modeChangeDebounceMs = 180L
    val compactEnterThresholdPx = 100
    val compactExitThresholdPx = 48

    // 创建滚动偏移量上报函数
    // 逻辑：到顶部强制退出紧凑模式；使用进入/退出双阈值减少边界抖动
    val reportScrollOffset: (Int, Int, Boolean) -> Unit = remember {
        fun(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int, isScrollingUp: Boolean) {
            val currentTime = System.nanoTime() / 1_000_000L
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
    }

    // 全局 backdrop，保证一致性
    val backdropBaseColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backdropBaseColor)
        drawContent()
    }

    val glassDialogHost = rememberGlassDialogHost()
    CompositionLocalProvider(LocalPlayerSheet provides playerSheet, LocalGlassDialogHost provides glassDialogHost) {
    GlassDialogHostBox(glassDialogHost) {
    PlayerSheetHost(
        state = playerSheet,
        sheet = { request ->
            PlayerScreen(
                onClose = playerSheet::close,
                songId = request.songId,
                songKey = request.songKey,
                viewModel = playerViewModel,
                viewModelFactory = viewModelFactory,
                downloadManager = downloadManager,
                appSettingsManager = appSettingsManager,
                windowEffectsActive = playerSheet.isSettledOpen
            )
        },
        content = {
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
            com.ikunshare.music.ui.components.LocalSongListOperationsVisible provides appSettings.showListOperationButtons,
            LocalScrollOffsetReporter provides reportScrollOffset,
            // Constant even in compact mode: if the reserve shrank when the bar morphs
            // into its pill, the clamped scroll offset would read as "back at top",
            // exit compact mode, and the two states would oscillate (flicker) near the
            // bottom of short lists like settings sections.
            LocalFloatingBottomBarReserve provides when {
                bottomNavVisible && showMiniPlayer -> 152.dp + bottomBarInset
                bottomNavVisible || showMiniPlayer -> 88.dp + bottomBarInset
                else -> 0.dp
            }
        ) {
            val bottomBar: @Composable () -> Unit = {
                if (showBottomNav || showMiniPlayer) {
                    LiquidGlassBottomBar(
                        isOnTabs = showBottomNav,
                        showMiniPlayer = showMiniPlayer,
                        playerViewModel = playerViewModel,
                        selectedTabIndex = pagerState.currentPage,
                        onTabClick = onTabClick,
                        isCustomTheme = isCustomTheme,
                        bottomBarOpacity = appSettings.bottomBarOpacity,
                        onExitCompactMode = { isCompactMode = false },
                        isCompactMode = isCompactMode,
                        backdrop = backdrop,
                        isWideScreen = isWideScreen,
                        hasSideNav = isWideScreen && isOnTabs
                    )
                }
            }

            if (isWideScreen) {
                // 宽屏：左侧竖向导航 + 右侧内容（底部仍有 MiniPlayer）
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    containerColor = if (hasBgImage) Color.Transparent else MaterialTheme.colorScheme.background,
                    bottomBar = bottomBar
                ) { _ ->
                    Row(Modifier.fillMaxSize()) {
                        if (isOnTabs) {
                            LiquidGlassSideNavBar(
                                selectedIndex = pagerState.currentPage,
                                onTabClick = onTabClick,
                                backdrop = backdrop,
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                        SoundNavHost(
                            navController = navController,
                            playerViewModel = playerViewModel,
                            viewModelFactory = viewModelFactory,
                            credentialManager = credentialManager,
                            tabSwitcher = tabSwitcher,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .layerBackdrop(backdrop)
                        )
                    }
                }
            } else {
                // 窄屏：内容铺满，悬浮底栏盖在内容之上，由 LocalFloatingBottomBarReserve 负责避让
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                    containerColor = if (hasBgImage) Color.Transparent else MaterialTheme.colorScheme.background,
                    bottomBar = bottomBar
                ) { _ ->
                    SoundNavHost(
                        navController = navController,
                        playerViewModel = playerViewModel,
                        viewModelFactory = viewModelFactory,
                        credentialManager = credentialManager,
                        tabSwitcher = tabSwitcher,
                        modifier = Modifier
                            .fillMaxSize()
                            .layerBackdrop(backdrop)
                    )
                }
            }
        }
    }
        }
    )
    }
    }
}
