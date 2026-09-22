package com.ikunshare.sound.ui.screens.player

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Abc
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ikunshare.sound.R
import com.ikunshare.sound.common.AppSettings
import com.ikunshare.sound.common.AppSettingsManager
import com.ikunshare.sound.manager.DownloadManager
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.model.PlayMode
import com.ikunshare.sound.model.Quality
import com.ikunshare.sound.model.neteaseCoverUrl
import com.ikunshare.sound.ui.screens.player.mesh.AlbumMeshBackground
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.AddToPlaylistDialog
import com.ikunshare.sound.ui.components.CreatePlaylistDialog
import com.ikunshare.sound.ui.components.ErrorState
import com.ikunshare.sound.ui.components.LiquidSlider
import com.ikunshare.sound.ui.components.LoadingIndicator
import com.ikunshare.sound.ui.components.NoLyricsState
import com.ikunshare.sound.ui.components.SleepTimerDialog
import com.ikunshare.sound.ui.screens.lyrics.WebLyricsContent
import com.ikunshare.sound.ui.screens.lyrics.LyricsViewModel
import com.kyant.backdrop.backdrops.LayerBackdrop
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * 播放页面
 *
 * 全屏布局，使用 HorizontalPager 集成播放详情（page 0）和歌词（page 1）。
 * 左滑显示歌词，右滑回播放详情。
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun PlayerScreen(
    navController: NavController,
    songId: Long,
    viewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    downloadManager: DownloadManager,
    appSettingsManager: AppSettingsManager
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val lyricsViewModel: LyricsViewModel = viewModel(factory = viewModelFactory)
    val lyricsState by lyricsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val context = LocalContext.current
    val view = LocalView.current
    val appSettings by appSettingsManager.settings.collectAsState()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.statusBars())
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showPlayQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()

    // 权限请求相关
    var pendingDownloadQuality by remember { mutableStateOf<Quality?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingDownloadQuality?.let { quality ->
                uiState.currentSong?.let { song ->
                    try {
                        downloadManager.addTask(song, quality.id)
                        Toast.makeText(
                            context,
                            context.getString(R.string.added_to_download),
                            Toast.LENGTH_SHORT
                        ).show()
                    } catch (e: Exception) {
                        Log.e("KunSound", "addTask failed after permission grant", e)
                        Toast.makeText(
                            context,
                            context.getString(R.string.download_failed_msg, e.message),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.storage_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
        pendingDownloadQuality = null
    }

    val onDownload: (Quality) -> Unit = { quality ->
        val song = uiState.currentSong
        Log.d("KunSound", "onDownload called: quality=${quality.id}, song=${song?.title}")
        if (song != null) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Android 11+: 检查 MANAGE_EXTERNAL_STORAGE
                android.os.Environment.isExternalStorageManager()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10: Scoped Storage，不需要额外权限
                true
            } else {
                // Android 9 及以下: 检查传统存储权限
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                try {
                    downloadManager.addTask(song, quality.id)
                    Toast.makeText(
                        context,
                        context.getString(R.string.added_to_download),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Log.e("KunSound", "addTask failed", e)
                    Toast.makeText(
                        context,
                        context.getString(R.string.download_failed_msg, e.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 引导用户授权所有文件访问
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                    ).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    context.startActivity(
                        Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    )
                }
                Toast.makeText(
                    context,
                    context.getString(R.string.file_access_permission),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                pendingDownloadQuality = quality
                permissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            Log.w("KunSound", "onDownload: currentSong is null")
            Toast.makeText(
                context,
                context.getString(R.string.song_info_not_loaded),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 加载歌曲
    LaunchedEffect(songId) {
        viewModel.loadSong(songId)
    }

    val onShareClick: () -> Unit = {
        uiState.currentSong?.let { song ->
            val shareText = com.ikunshare.sound.SoundApplication.instance?.musicRepository
                ?.getProvider(song.getTypeDiscriminator())?.share(song)
                ?: "${song.title} - ${song.artist}"
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(
                Intent.createChooser(
                    intent,
                    context.getString(R.string.share_song)
                )
            )
        }
    }

    // 当播放歌曲变化时加载歌词
    LaunchedEffect(uiState.currentSong) {
        uiState.currentSong?.let { lyricsViewModel.loadLyrics(it) }
    }

    // 歌曲解析失败时显示错误提示
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.currentSong != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 窄屏歌词翻页状态（提升到外层，分享按钮需要读取当前页）
    val playerPagerState = rememberPagerState(pageCount = { 2 })
    val playerPagerScope = rememberCoroutineScope()
    var lyricsControlsVisible by remember { mutableStateOf(true) }
    var lyricsControlsWakeKey by remember { mutableIntStateOf(0) }
    val isLyricsPage = playerPagerState.currentPage == 1
    val playbackDockVisible = !isLyricsPage || lyricsControlsVisible || !uiState.isPlaying
    val revealLyricsControls: () -> Unit = {
        lyricsControlsVisible = true
        lyricsControlsWakeKey++
    }

    LaunchedEffect(isLyricsPage, uiState.isPlaying, lyricsControlsWakeKey) {
        if (isLyricsPage) {
            lyricsControlsVisible = true
        }
        if (isLyricsPage && uiState.isPlaying) {
            delay(2600.milliseconds)
            lyricsControlsVisible = false
        }
    }

    // 封面 Mesh Gradient 背景（Apple Music 同款）：加载原始封面位图，交给 GL 渲染器处理。
    val coverUrl = uiState.currentSong?.cover
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl) {
        if (coverUrl.isNullOrEmpty()) {
            coverBitmap = null
            return@LaunchedEffect
        }
        try {
            val request = ImageRequest.Builder(context).data(coverUrl).allowHardware(false).build()
            val result = context.imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap != null) coverBitmap = bitmap
        } catch (_: Exception) {
        }
    }

    // 播放页使用自己的封面流体背景，根 Box 保持不透明，防止全局背景图透过来。
    val hasOwnBackground = appSettings.coverBlurBg
    val rootBgColor = if (hasOwnBackground) {
        // 取背景色但强制不透明
        MaterialTheme.colorScheme.background.copy(alpha = 1f)
    } else {
        MaterialTheme.colorScheme.background
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(rootBgColor)
    ) {
        // 背景层
        PlayerBackground(
            appSettings = appSettings,
            coverBitmap = coverBitmap,
            coverKey = coverUrl ?: ""
        )
        when {
            uiState.isLoading && uiState.currentSong == null -> {
                LoadingIndicator(modifier = Modifier.fillMaxSize())
            }

            uiState.error != null && uiState.currentSong == null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.unknown_error),
                    onRetry = { viewModel.loadSong(songId) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                if (isLandscape) {
                    AppleLandscapePlayer(
                        uiState = uiState,
                        lyricsState = lyricsState,
                        currentPosition = currentPosition,
                        onSeek = viewModel::seekTo,
                        onPlayPause = viewModel::togglePlayPause,
                        onPrevious = viewModel::previous,
                        onNext = viewModel::next,
                        onQualitySelect = viewModel::setQuality,
                        onVolumeChange = viewModel::setVolume,
                        onFavoriteClick = viewModel::toggleFavorite,
                        onMoreClick = { showMoreActions = true },
                        onLineClick = viewModel::seekTo,
                        onTranslationToggle = lyricsViewModel::toggleTranslation,
                        onRomanizationToggle = lyricsViewModel::toggleRomanization,
                        onPlaylistClick = { showPlayQueue = true },
                        onPlaybackDeviceClick = { openMediaOutputPanel(context) }
                    )
                } else {
                    HorizontalPager(
                        state = playerPagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> PlayerDetailPage(
                                uiState = uiState,
                                isLandscape = false,
                                onFavoriteClick = viewModel::toggleFavorite,
                                onMoreClick = { showMoreActions = true }
                            )

                            1 -> LyricsPage(
                                uiState = uiState,
                                lyricsState = lyricsState,
                                currentPosition = currentPosition,
                                isPlaying = uiState.isPlaying,
                                isLandscape = false,
                                controlsVisible = playbackDockVisible,
                                onLineClick = {
                                    revealLyricsControls()
                                    viewModel.seekTo(it)
                                },
                                onSurfaceTap = revealLyricsControls,
                                onFavoriteClick = viewModel::toggleFavorite,
                                onMoreClick = { showMoreActions = true },
                                onTranslationToggle = lyricsViewModel::toggleTranslation,
                                onRomanizationToggle = lyricsViewModel::toggleRomanization
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = playbackDockVisible,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        enter = fadeIn() + slideInVertically { it / 4 },
                        exit = fadeOut() + slideOutVertically { it / 4 }
                    ) {
                        AppleMusicPlaybackDock(
                            uiState = uiState,
                            currentPosition = currentPosition,
                            currentPage = playerPagerState.currentPage,
                            isLandscape = false,
                            sleepTimerActive = sleepTimerState.isActive,
                            onSeek = viewModel::seekTo,
                            onPlayPause = viewModel::togglePlayPause,
                            onPrevious = viewModel::previous,
                            onNext = viewModel::next,
                            onQualitySelect = viewModel::setQuality,
                            onLyricsClick = {
                                revealLyricsControls()
                                playerPagerScope.launch {
                                    playerPagerState.animateScrollToPage(
                                        if (playerPagerState.currentPage == 0) 1 else 0
                                    )
                                }
                            },
                            onSleepTimerClick = {
                                revealLyricsControls()
                                showSleepTimer = true
                            },
                            onPlaylistClick = {
                                revealLyricsControls()
                                showPlayQueue = true
                            }
                        )
                    }
                }
            }
        }

        val snackbarBottomPadding = if (!isLandscape && playbackDockVisible) 324.dp else 32.dp

        // 错误提示
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = snackbarBottomPadding)
        ) { data ->
            Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onBackground
            )
        }

        // 添加到歌单对话框
        if (showPlaylistPicker) {
            AddToPlaylistDialog(
                playlists = customPlaylists,
                isFavorite = uiState.isFavorite,
                onDismiss = { showPlaylistPicker = false },
                onFavoriteClick = {
                    viewModel.addFavorite()
                    showPlaylistPicker = false
                },
                onPlaylistClick = { playlistId ->
                    viewModel.addToPlaylist(playlistId)
                    showPlaylistPicker = false
                },
                onCreateNew = {
                    showPlaylistPicker = false
                    showCreateDialog = true
                }
            )
        }

        // 新建歌单对话框
        if (showCreateDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreateDialog = false },
                onConfirm = { name ->
                    viewModel.createPlaylistAndAdd(name)
                    showCreateDialog = false
                }
            )
        }

        // 定时停止对话框
        if (showSleepTimer) {
            SleepTimerDialog(
                timerState = sleepTimerState,
                onDismiss = { showSleepTimer = false },
                onSetTimer = { minutes, finishCurrent, act ->
                    viewModel.setSleepTimer(minutes, finishCurrent, act)
                },
                onSetTimerBySongs = { count, finishCurrent, act ->
                    viewModel.setSleepTimerBySongs(count, finishCurrent, act)
                },
                onCancelTimer = { viewModel.cancelSleepTimer() }
            )
        }

        if (showMoreActions) {
            ApplePlayerMoreSheet(
                song = uiState.currentSong,
                playMode = uiState.playMode,
                availableQualities = uiState.availableQualities,
                onAddToPlaylistClick = {
                    showMoreActions = false
                    showPlaylistPicker = true
                },
                onShareClick = {
                    showMoreActions = false
                    onShareClick()
                },
                onPlayModeClick = viewModel::cyclePlayMode,
                onDownloadClick = { quality ->
                    showMoreActions = false
                    onDownload(quality)
                },
                onDismiss = { showMoreActions = false }
            )
        }

        // 播放列表 BottomSheet
        if (showPlayQueue) {
            PlayQueueSheet(
                playlist = viewModel.getPlaylist(),
                currentIndex = viewModel.getCurrentIndex(),
                playNextQueue = viewModel.getPlayNextQueue(),
                currentSong = uiState.currentSong,
                isPlayingFromNextQueue = viewModel.isPlayingFromNextQueue(),
                onSongClick = { index ->
                    viewModel.playAtIndex(index)
                    showPlayQueue = false
                },
                onPlayNextClick = { index ->
                    viewModel.playFromNextQueue(index)
                    showPlayQueue = false
                },
                onClearMainQueue = viewModel::clearMainPlaylist,
                onClearPlayNextQueue = viewModel::clearPlayNext,
                onDismiss = { showPlayQueue = false }
            )
        }
    }
}

/** Apple Music 风格横屏：左侧封面 + 信息 + 控制，右侧常驻歌词。 */
@Composable
private fun AppleLandscapePlayer(
    uiState: PlayerUiState,
    lyricsState: com.ikunshare.sound.ui.screens.lyrics.LyricsUiState,
    currentPosition: Long,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQualitySelect: (Quality) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onFavoriteClick: () -> Unit,
    onMoreClick: () -> Unit,
    onLineClick: (Long) -> Unit,
    onTranslationToggle: () -> Unit,
    onRomanizationToggle: () -> Unit,
    onPlaylistClick: () -> Unit,
    onPlaybackDeviceClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 40.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp)
    ) {
        // ===== 左列：封面 + 信息 + 控制 =====
        Column(
            modifier = Modifier.weight(0.46f).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 封面：弹性填充剩余高度、保持正方形居中，适配不同横屏高度且不裁剪
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AlbumCover(
                    coverUrl = neteaseCoverUrl(uiState.currentSong?.cover),
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(1f)
                        .shadow(28.dp, RoundedCornerShape(16.dp), clip = false)
                )
            }
            Spacer(Modifier.height(18.dp))

            // 歌名 / 艺术家 + 收藏 / 更多（并入一行，节省纵向空间）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = uiState.currentSong?.title.orEmpty(),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 26.sp
                        ),
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = uiState.currentSong?.artist.orEmpty(),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 15.sp),
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                AppleCircleButton(onClick = onFavoriteClick, size = 42.dp) {
                    Icon(
                        imageVector = if (uiState.isFavorite) Icons.Filled.Favorite
                        else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(6.dp))
                AppleCircleButton(onClick = onMoreClick, size = 42.dp) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = "更多",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            ApplePlaybackProgress(
                currentPosition = currentPosition,
                duration = uiState.duration,
                currentQuality = uiState.currentQuality,
                availableQualities = uiState.availableQualities,
                onSeek = onSeek,
                onQualitySelect = onQualitySelect
            )
            Spacer(Modifier.height(2.dp))
            LandscapeTransportControls(
                isPlaying = uiState.isPlaying,
                isLoading = uiState.isLoading,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext
            )
            Spacer(Modifier.height(2.dp))
            LandscapeVolumeSlider(uiState.volume, onVolumeChange)
            Spacer(Modifier.height(10.dp))
            AppleLandscapeActionButton(onClick = onPlaybackDeviceClick) {
                Icon(Icons.Filled.GraphicEq, "播放设备", Modifier.size(22.dp))
            }
        }

        // ===== 右列：常驻歌词 =====
        Box(modifier = Modifier.weight(0.54f).fillMaxHeight()) {
            when {
                lyricsState.isLoading -> LoadingIndicator(Modifier.fillMaxSize())
                lyricsState.lyrics == null -> NoLyricsState(Modifier.fillMaxSize())
                else -> WebLyricsContent(
                    lyrics = lyricsState.parsedLyrics,
                    currentPosition = currentPosition,
                    isPlaying = uiState.isPlaying,
                    showTranslation = lyricsState.showTranslation,
                    showRomanization = lyricsState.showRomanization,
                    showPhonetic = lyricsState.showPhonetic,
                    hasCharLyrics = lyricsState.hasCharLyrics,
                    isSynced = lyricsState.isSynced,
                    onLineClick = onLineClick,
                    modifier = Modifier.fillMaxSize().padding(bottom = 58.dp)
                )
            }

            Row(
                modifier = Modifier.align(Alignment.BottomEnd),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppleLandscapeActionButton(onClick = {}, selected = true) {
                    Icon(Icons.Filled.FormatQuote, stringResource(R.string.lyrics_title))
                }
                AppleLandscapeActionButton(onClick = onPlaylistClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.QueueMusic,
                        stringResource(R.string.play_queue)
                    )
                }
                if (lyricsState.hasTranslation) {
                    AppleLandscapeActionButton(
                        onClick = onTranslationToggle,
                        selected = lyricsState.showTranslation
                    ) {
                        Icon(Icons.Filled.Translate, stringResource(R.string.show_translation_btn))
                    }
                }
                if (lyricsState.hasRomanization) {
                    AppleLandscapeActionButton(
                        onClick = onRomanizationToggle,
                        selected = lyricsState.showRomanization
                    ) {
                        Icon(Icons.Outlined.Abc, stringResource(R.string.show_romanization_btn))
                    }
                }
            }
        }
    }
}

@Composable
private fun LandscapeTransportControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.SkipPrevious,
                stringResource(R.string.previous),
                Modifier.size(38.dp),
                Color.White
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(62.dp)) {
            if (isLoading) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.pause)
                    else stringResource(R.string.play),
                    modifier = Modifier.size(54.dp),
                    tint = Color.White
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
            Icon(
                Icons.Filled.SkipNext,
                stringResource(R.string.next),
                Modifier.size(38.dp),
                Color.White
            )
        }
    }
}

/** 音量条：桌面版同款——图标（点击静音 / 恢复）+ 与进度条一致的细轨道 + 圆形 thumb。 */
@Composable
private fun LandscapeVolumeSlider(volume: Float, onVolumeChange: (Float) -> Unit) {
    var trackWidth by remember { mutableFloatStateOf(0f) }
    var lastNonZero by remember { mutableFloatStateOf(0.8f) }
    val ratio = volume.coerceIn(0f, 1f)
    val apply = { v: Float ->
        val c = v.coerceIn(0f, 1f)
        if (c > 0f) lastNonZero = c
        onVolumeChange(c)
    }

    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 音量图标：点击在静音 / 恢复上次音量之间切换
        IconButton(
            onClick = {
                if (ratio > 0f) {
                    lastNonZero = ratio
                    onVolumeChange(0f)
                } else {
                    onVolumeChange(lastNonZero)
                }
            },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = if (ratio == 0f) Icons.AutoMirrored.Filled.VolumeMute
                else Icons.AutoMirrored.Filled.VolumeDown,
                contentDescription = "音量",
                tint = Color.White.copy(alpha = 0.78f),
                modifier = Modifier.size(16.dp)
            )
        }

        // 细轨道（与进度条同款）：点击 / 拖动调节音量
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .clip(RoundedCornerShape(999))
                .background(Color.White.copy(alpha = 0.32f))
                .onSizeChanged { trackWidth = it.width.toFloat() }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        apply(offset.x / trackWidth)
                    }
                }
                .pointerInput(trackWidth) {
                    detectDragGestures(
                        onDragStart = { offset -> apply(offset.x / trackWidth) },
                        onDrag = { change, _ ->
                            change.consume()
                            apply(change.position.x / trackWidth)
                        }
                    )
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(999))
                    .background(Color.White)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((trackWidth * ratio).roundToInt() - 6, 0) }
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .shadow(4.dp, CircleShape)
            )
        }
    }
}

@Composable
private fun AppleLandscapeActionButton(
    onClick: () -> Unit,
    selected: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        color = Color.White.copy(alpha = if (selected) 0.94f else 0.16f),
        contentColor = if (selected) Color(0xFF56615F) else Color.White
    ) { Box(contentAlignment = Alignment.Center) { content() } }
}

private fun openMediaOutputPanel(context: android.content.Context) {
    val intent = Intent("android.settings.panel.action.MEDIA_OUTPUT")
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
    }
}

/**
 * 播放详情页（Pager page 0）
 */
@Composable
private fun PlayerDetailPage(
    uiState: PlayerUiState,
    isLandscape: Boolean,
    onFavoriteClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val coverSize = if (isLandscape) {
        (screenHeight * 0.48f).coerceIn(156.dp, 260.dp)
    } else {
        (screenWidth * 0.74f).coerceAtMost(340.dp)
    }

    if (isLandscape) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 48.dp, vertical = 20.dp)
                .padding(bottom = 158.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(48.dp)
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                AlbumCover(
                    coverUrl = neteaseCoverUrl(uiState.currentSong?.cover),
                    modifier = Modifier.size(coverSize)
                )
            }
            PlayerTitleActions(
                title = uiState.currentSong?.title.orEmpty(),
                artist = uiState.currentSong?.artist.orEmpty(),
                isFavorite = uiState.isFavorite,
                onFavoriteClick = onFavoriteClick,
                onMoreClick = onMoreClick,
                modifier = Modifier.weight(1f)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 36.dp)
            .padding(bottom = 312.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Spacer(modifier = Modifier.height(72.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            AlbumCover(
                coverUrl = neteaseCoverUrl(uiState.currentSong?.cover),
                modifier = Modifier.size(coverSize)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        PlayerTitleActions(
            title = uiState.currentSong?.title.orEmpty(),
            artist = uiState.currentSong?.artist.orEmpty(),
            isFavorite = uiState.isFavorite,
            onFavoriteClick = onFavoriteClick,
            onMoreClick = onMoreClick
        )
    }
}

/**
 * 歌词页（Pager page 1）
 */
@Composable
private fun LyricsPage(
    uiState: PlayerUiState,
    lyricsState: com.ikunshare.sound.ui.screens.lyrics.LyricsUiState,
    currentPosition: Long,
    isPlaying: Boolean,
    isLandscape: Boolean,
    controlsVisible: Boolean,
    onLineClick: (Long) -> Unit,
    onSurfaceTap: () -> Unit,
    onFavoriteClick: () -> Unit,
    onMoreClick: () -> Unit,
    onTranslationToggle: () -> Unit,
    onRomanizationToggle: () -> Unit
) {
    val lyricsBottomPadding by animateDpAsState(
        targetValue = if (controlsVisible) {
            if (isLandscape) 158.dp else 300.dp
        } else 42.dp,
        label = "lyricsBottomPadding"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSurfaceTap
            )
    ) {
        PlayerCompactHeader(
            song = uiState.currentSong,
            isLandscape = isLandscape,
            isFavorite = uiState.isFavorite,
            onFavoriteClick = onFavoriteClick,
            onMoreClick = onMoreClick
        )
        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = lyricsBottomPadding)
        ) {
            when {
                lyricsState.isLoading -> {
                    LoadingIndicator(modifier = Modifier.fillMaxSize())
                }

                lyricsState.lyrics == null -> {
                    NoLyricsState(modifier = Modifier.fillMaxSize())
                }

                else -> {
                    WebLyricsContent(
                        lyrics = lyricsState.parsedLyrics,
                        currentPosition = currentPosition,
                        isPlaying = isPlaying,
                        showTranslation = lyricsState.showTranslation,
                        showRomanization = lyricsState.showRomanization,
                        showPhonetic = lyricsState.showPhonetic,
                        hasCharLyrics = lyricsState.hasCharLyrics,
                        isSynced = lyricsState.isSynced,
                        onLineClick = onLineClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(
                        start = if (isLandscape) 24.dp else 36.dp,
                        bottom = if (isLandscape) 10.dp else 22.dp
                    )
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = controlsVisible && (lyricsState.hasTranslation || lyricsState.hasRomanization),
                    enter = fadeIn() + slideInVertically { it / 4 },
                    exit = fadeOut() + slideOutVertically { it / 4 }
                ) {
                    FloatingLyricsLanguageButton(
                        showTranslation = lyricsState.showTranslation,
                        showRomanization = lyricsState.showRomanization,
                        hasTranslation = lyricsState.hasTranslation,
                        hasRomanization = lyricsState.hasRomanization,
                        onTranslationToggle = onTranslationToggle,
                        onRomanizationToggle = onRomanizationToggle,
                        modifier = Modifier
                    )
                }
            }
        }
    }
}
// ==================== 子组件 ====================

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun PlayerTitleActions(
    title: String,
    artist: String,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
                style = MaterialTheme.typography.displaySmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Clip
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 25.sp),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        AppleCircleButton(onClick = onFavoriteClick) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) stringResource(R.string.unfavorite) else stringResource(
                    R.string.favorite
                ),
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        AppleCircleButton(onClick = onMoreClick) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun PlayerCompactHeader(
    song: MusicItem?,
    isLandscape: Boolean,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 24.dp,
                end = 24.dp,
                top = if (isLandscape) 18.dp else 76.dp,
                bottom = if (isLandscape) 10.dp else 26.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumCover(
            coverUrl = neteaseCoverUrl(song?.cover),
            modifier = Modifier.size(54.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song?.title.orEmpty(),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song?.artist.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        AppleCircleButton(onClick = onFavoriteClick, size = 44.dp) {
            Icon(
                imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                contentDescription = if (isFavorite) stringResource(R.string.unfavorite) else stringResource(
                    R.string.favorite
                ),
                tint = Color.White,
                modifier = Modifier.size(23.dp)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        AppleCircleButton(onClick = onMoreClick, size = 44.dp) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = Color.White,
                modifier = Modifier.size(23.dp)
            )
        }
    }
}

@Composable
private fun AppleCircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 56.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier.size(size),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.18f),
        contentColor = Color.White
    ) {
        Box(contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
private fun FloatingLyricsLanguageButton(
    showTranslation: Boolean,
    showRomanization: Boolean,
    hasTranslation: Boolean,
    hasRomanization: Boolean,
    onTranslationToggle: () -> Unit,
    onRomanizationToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = {
            when {
                hasTranslation && hasRomanization && showTranslation -> {
                    onTranslationToggle()
                    onRomanizationToggle()
                }

                hasTranslation && hasRomanization && showRomanization -> onRomanizationToggle()
                hasTranslation -> onTranslationToggle()
                hasRomanization -> onRomanizationToggle()
            }
        },
        modifier = modifier.size(56.dp),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = if (showTranslation || showRomanization) 0.96f else 0.82f),
        contentColor = Color(0xFF7F8E8A)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val icon = when {
                showRomanization || (!hasTranslation && hasRomanization) -> Icons.Outlined.Abc
                else -> Icons.Filled.Translate
            }
            val contentDescription = when {
                showRomanization -> stringResource(R.string.hide_romanization)
                showTranslation -> stringResource(R.string.hide_translation)
                hasTranslation -> stringResource(R.string.show_translation_btn)
                else -> stringResource(R.string.show_romanization_btn)
            }
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun AppleMusicPlaybackDock(
    uiState: PlayerUiState,
    currentPosition: Long,
    currentPage: Int,
    isLandscape: Boolean,
    sleepTimerActive: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQualitySelect: (Quality) -> Unit,
    onLyricsClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onPlaylistClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.24f to Color(0x446A7472),
                    1f to Color(0x996F7876)
                )
            )
            .padding(horizontal = if (isLandscape) 48.dp else 32.dp)
            .padding(
                top = if (isLandscape) 8.dp else 22.dp,
                bottom = if (isLandscape) 8.dp else 18.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ApplePlaybackProgress(
            currentPosition = currentPosition,
            duration = uiState.duration,
            currentQuality = uiState.currentQuality,
            availableQualities = uiState.availableQualities,
            onSeek = onSeek,
            onQualitySelect = onQualitySelect
        )
        if (isLandscape) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    AppleTransportControls(
                        isPlaying = uiState.isPlaying,
                        isLoading = uiState.isLoading,
                        onPlayPause = onPlayPause,
                        onPrevious = onPrevious,
                        onNext = onNext
                    )
                }
                Box(modifier = Modifier.weight(1f)) {
                    AppleBottomActions(
                        lyricsSelected = currentPage == 1,
                        sleepTimerActive = sleepTimerActive,
                        onLyricsClick = onLyricsClick,
                        onSleepTimerClick = onSleepTimerClick,
                        onPlaylistClick = onPlaylistClick
                    )
                }
            }
        } else {
            Spacer(modifier = Modifier.height(24.dp))
            AppleTransportControls(
                isPlaying = uiState.isPlaying,
                isLoading = uiState.isLoading,
                onPlayPause = onPlayPause,
                onPrevious = onPrevious,
                onNext = onNext
            )
            Spacer(modifier = Modifier.height(28.dp))
            AppleBottomActions(
                lyricsSelected = currentPage == 1,
                sleepTimerActive = sleepTimerActive,
                onLyricsClick = onLyricsClick,
                onSleepTimerClick = onSleepTimerClick,
                onPlaylistClick = onPlaylistClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplePlaybackProgress(
    currentPosition: Long,
    duration: Long,
    currentQuality: Quality?,
    availableQualities: List<Quality>,
    onSeek: (Long) -> Unit,
    onQualitySelect: (Quality) -> Unit
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }
    var showQualitySheet by remember { mutableStateOf(false) }

    val progress = if (duration > 0L) {
        if (isDragging) dragPosition
        else (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = progress,
            onValueChange = {
                isDragging = true
                dragPosition = it
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek((dragPosition * duration).toLong())
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = 14.dp, height = 14.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            },
            track = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.38f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .clip(RoundedCornerShape(50))
                            .background(Color.White)
                    )
                }
            }
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            Text(
                text = if (isDragging) formatDuration((dragPosition * duration).toLong())
                else formatDuration(currentPosition),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.align(Alignment.CenterStart)
            )
            if (availableQualities.isNotEmpty()) {
                AppleQualityChip(
                    currentQuality = currentQuality,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable { showQualitySheet = true }
                )
            }
            Text(
                text = if (duration > 0L) "-${
                    formatDuration(
                        (duration - currentPosition).coerceAtLeast(
                            0L
                        )
                    )
                }" else "-00:00",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }

    if (showQualitySheet) {
        QualityBottomSheet(
            currentQuality = currentQuality,
            availableQualities = availableQualities,
            onQualitySelect = { onQualitySelect(it); showQualitySheet = false },
            onDismiss = { showQualitySheet = false }
        )
    }
}

@Composable
private fun AppleTransportControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPrevious, modifier = Modifier.size(76.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipPrevious,
                contentDescription = stringResource(R.string.previous),
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(92.dp)) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = Color.White,
                    strokeWidth = 4.dp
                )
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) stringResource(R.string.pause) else stringResource(
                        R.string.play
                    ),
                    tint = Color.White,
                    modifier = Modifier.size(if (isPlaying) 68.dp else 78.dp)
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(76.dp)) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = stringResource(R.string.next),
                tint = Color.White,
                modifier = Modifier.size(60.dp)
            )
        }
    }
}

@Composable
private fun AppleBottomActions(
    lyricsSelected: Boolean,
    sleepTimerActive: Boolean,
    onLyricsClick: () -> Unit,
    onSleepTimerClick: () -> Unit,
    onPlaylistClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onLyricsClick, modifier = Modifier.size(54.dp)) {
            Icon(
                imageVector = Icons.Filled.FormatQuote,
                contentDescription = stringResource(R.string.lyrics_title),
                tint = Color.White.copy(alpha = if (lyricsSelected) 1f else 0.82f),
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onSleepTimerClick, modifier = Modifier.size(54.dp)) {
            Icon(
                imageVector = Icons.Filled.Timer,
                contentDescription = stringResource(R.string.sleep_timer),
                tint = Color.White.copy(alpha = if (sleepTimerActive) 1f else 0.82f),
                modifier = Modifier.size(32.dp)
            )
        }
        IconButton(onClick = onPlaylistClick, modifier = Modifier.size(54.dp)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = stringResource(R.string.play_queue),
                tint = Color.White.copy(alpha = 0.82f),
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun AppleQualityChip(
    currentQuality: Quality?,
    modifier: Modifier = Modifier
) {
    val label = currentQuality?.name ?: "无损"
    Surface(
        modifier = modifier.widthIn(max = 126.dp),
        shape = RoundedCornerShape(9.dp),
        color = Color.White.copy(alpha = 0.16f),
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.84f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                ),
                color = Color.White.copy(alpha = 0.86f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApplePlayerMoreSheet(
    song: MusicItem?,
    playMode: PlayMode,
    availableQualities: List<Quality>,
    onAddToPlaylistClick: () -> Unit,
    onShareClick: () -> Unit,
    onPlayModeClick: () -> Unit,
    onDownloadClick: (Quality) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScrollState = rememberScrollState()
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.82f).coerceAtMost(640.dp)
    val standardQualities = remember(availableQualities) {
        availableQualities.filter { !PlayerViewModel.isAiQuality(it.id) }
    }
    val aiQualities = remember(availableQualities) {
        availableQualities.filter { PlayerViewModel.isAiQuality(it.id) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        containerColor = Color(0xF21C1C1E),
        contentColor = Color.White,
        scrimColor = Color.Black.copy(alpha = 0.48f),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 10.dp, bottom = 8.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.28f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = maxSheetHeight)
                .verticalScroll(sheetScrollState)
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, bottom = 18.dp)
        ) {
            AppleMoreSheetHeader(song = song)

            AppleMoreGroup {
                AppleMoreActionRow(
                    icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                    title = stringResource(R.string.add_to_playlist),
                    iconTint = Color(0xFF64D2FF),
                    onClick = onAddToPlaylistClick
                )
                AppleMoreDivider()
                AppleMoreActionRow(
                    icon = Icons.Filled.Share,
                    title = stringResource(R.string.share),
                    iconTint = Color(0xFF30D158),
                    onClick = onShareClick
                )
                AppleMoreDivider()
                AppleMoreActionRow(
                    icon = playModeIcon(playMode),
                    title = "播放模式",
                    trailingText = playMode.label,
                    iconTint = Color(0xFFFF375F),
                    onClick = onPlayModeClick
                )
            }

            Spacer(modifier = Modifier.height(18.dp))
            AppleMoreSectionLabel(text = stringResource(R.string.download))

            AppleMoreGroup {
                if (availableQualities.isEmpty()) {
                    AppleMoreActionRow(
                        icon = Icons.Filled.Download,
                        title = stringResource(R.string.no_quality_available),
                        enabled = false,
                        onClick = {}
                    )
                } else {
                    standardQualities.forEachIndexed { index, quality ->
                        AppleMoreQualityRow(
                            quality = quality,
                            onClick = { onDownloadClick(quality) }
                        )
                        if (index != standardQualities.lastIndex || aiQualities.isNotEmpty()) {
                            AppleMoreDivider()
                        }
                    }
                    if (aiQualities.isNotEmpty()) {
                        AppleMoreSectionLabel(
                            text = "AI 升频",
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    aiQualities.forEachIndexed { index, quality ->
                        AppleMoreQualityRow(
                            quality = quality,
                            onClick = { onDownloadClick(quality) }
                        )
                        if (index != aiQualities.lastIndex) {
                            AppleMoreDivider()
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppleMoreSheetHeader(song: MusicItem?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AlbumCover(
            coverUrl = neteaseCoverUrl(song?.cover),
            modifier = Modifier.size(58.dp)
        )
        Spacer(modifier = Modifier.width(13.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song?.title?.takeIf { it.isNotBlank() } ?: "更多",
                modifier = Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp
                ),
                color = Color.White.copy(alpha = 0.96f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val artist = song?.artist.orEmpty()
            val album = song?.album.orEmpty()
            val subtitle = artist.ifBlank { album }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = Color.White.copy(alpha = 0.58f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AppleMoreGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color.White.copy(alpha = 0.105f),
        contentColor = Color.White
    ) {
        Column(content = content)
    }
}

@Composable
private fun AppleMoreSectionLabel(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp
        ),
        color = Color.White.copy(alpha = 0.46f),
        modifier = modifier.padding(start = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun AppleMoreQualityRow(
    quality: Quality,
    onClick: () -> Unit
) {
    val sizeText = quality.displaySize
        ?: if (quality.filesize > 0) formatFileSize(quality.filesize) else null
    AppleMoreActionRow(
        icon = Icons.Filled.Download,
        title = quality.name,
        subtitle = sizeText,
        trailingText = stringResource(R.string.download),
        iconTint = Color(0xFF0A84FF),
        onClick = onClick
    )
}

@Composable
private fun AppleMoreDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 58.dp),
        color = Color.White.copy(alpha = 0.075f),
        thickness = 0.7.dp
    )
}

@Composable
private fun AppleMoreActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailingText: String? = null,
    iconTint: Color = Color.White.copy(alpha = 0.92f),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(iconTint.copy(alpha = if (enabled) 0.18f else 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconTint.copy(alpha = contentAlpha)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp
                ),
                color = Color.White.copy(alpha = 0.94f * contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                    color = Color.White.copy(alpha = 0.48f * contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (!trailingText.isNullOrBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = trailingText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                color = Color.White.copy(alpha = 0.56f * contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun playModeIcon(playMode: PlayMode): ImageVector = when (playMode) {
    PlayMode.SEQUENTIAL -> Icons.AutoMirrored.Filled.TrendingFlat
    PlayMode.LIST_LOOP -> Icons.Filled.Repeat
    PlayMode.SHUFFLE -> Icons.Filled.Shuffle
    PlayMode.SINGLE_LOOP -> Icons.Filled.RepeatOne
    PlayMode.SINGLE_PLAY -> Icons.Filled.LooksOne
}

@Composable
private fun AlbumCover(
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    val coverShape = RoundedCornerShape(10.dp)
    if (coverUrl.isNullOrEmpty()) {
        Box(
            modifier = modifier.clip(coverShape),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(coverShape)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = coverShape
                ) {}
            }
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.no_cover),
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        AsyncImage(
            model = coverUrl,
            contentDescription = stringResource(R.string.album_cover),
            modifier = modifier.clip(coverShape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun SongInfo(title: String, artist: String, album: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold, fontSize = 22.sp
            ),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = artist,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        if (album.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    currentPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit,
    backdrop: LayerBackdrop,
    appSettings: AppSettings
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableFloatStateOf(0f) }

    val progress = if (duration > 0L) {
        if (isDragging) dragPosition
        else (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(modifier = Modifier.fillMaxWidth()) {
        if (appSettings.liquidGlassMode) {
            LiquidSlider(
                value = { progress },
                onValueChange = {
                    isDragging = true
                    dragPosition = it
                },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek((dragPosition * duration).toLong())
                },
                valueRange = 0f..1f,
                visibilityThreshold = 0.01f,
                backdrop = backdrop,
                accentColor = MaterialTheme.colorScheme.tertiary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                sliderSize = DpSize(30.dp, 18.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )
        } else {
            Slider(
                value = progress,
                onValueChange = { isDragging = true; dragPosition = it },
                onValueChangeFinished = {
                    isDragging = false
                    onSeek((dragPosition * duration).toLong())
                },
                modifier = Modifier.fillMaxWidth(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.tertiary,
                    activeTrackColor = MaterialTheme.colorScheme.tertiary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = if (isDragging) formatDuration((dragPosition * duration).toLong())
                else formatDuration(currentPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit,
    onPlaylistClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onFavoriteClick, modifier = Modifier.size(48.dp)) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                if (isFavorite) stringResource(R.string.unfavorite) else stringResource(R.string.favorite),
                tint = if (isFavorite) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.SkipPrevious,
                stringResource(R.string.previous),
                Modifier.size(36.dp),
                MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onPlayPause, modifier = Modifier.size(72.dp)) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.tertiary,
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                    Modifier.size(52.dp), MaterialTheme.colorScheme.tertiary
                )
            }
        }
        IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.SkipNext,
                stringResource(R.string.next),
                Modifier.size(36.dp),
                MaterialTheme.colorScheme.onBackground
            )
        }
        IconButton(onClick = onPlaylistClick, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                stringResource(R.string.play_queue),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AdditionalControls(
    availableQualities: List<Quality>,
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    playbackSpeed: Float,
    onSpeedChange: (Float) -> Unit,
    playMode: PlayMode,
    onPlayModeClick: () -> Unit,
    onDownloadClick: (Quality) -> Unit,
    onAddToPlaylistClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        VolumeButton(volume = volume, onVolumeChange = onVolumeChange)
        SpeedButton(speed = playbackSpeed, onSpeedChange = onSpeedChange)
        IconButton(onClick = onAddToPlaylistClick) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                stringResource(R.string.add_to_playlist),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DownloadButton(availableQualities, onDownloadClick)
        PlayModeButton(playMode, onPlayModeClick)
    }
}

@Composable
private fun DownloadButton(
    availableQualities: List<Quality>,
    onDownloadClick: (Quality) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val standardQualities = remember(availableQualities) {
        availableQualities.filter { !PlayerViewModel.isAiQuality(it.id) }
    }
    val aiQualities = remember(availableQualities) {
        availableQualities.filter { PlayerViewModel.isAiQuality(it.id) }
    }

    Box {
        IconButton(onClick = {
            Log.d(
                "KunSound",
                "DownloadButton clicked, qualities=${availableQualities.size}"
            )
            if (availableQualities.isNotEmpty()) expanded = true
        }) {
            Icon(
                Icons.Filled.Download, stringResource(R.string.download),
                tint = if (availableQualities.isNotEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            standardQualities.forEach { quality ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(quality.name, color = MaterialTheme.colorScheme.onBackground)
                            val sizeText = quality.displaySize
                                ?: if (quality.filesize > 0) formatFileSize(quality.filesize) else null
                            if (sizeText != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    sizeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = { onDownloadClick(quality); expanded = false }
                )
            }
            if (aiQualities.isNotEmpty() && standardQualities.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )
                Text(
                    "AI 升频",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            aiQualities.forEach { quality ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(quality.name, color = MaterialTheme.colorScheme.onBackground)
                            val sizeText = quality.displaySize
                                ?: if (quality.filesize > 0) formatFileSize(quality.filesize) else null
                            if (sizeText != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    sizeText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    onClick = { onDownloadClick(quality); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun PlayModeButton(
    playMode: PlayMode,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick) {
        if (playMode == PlayMode.SEQUENTIAL) {
            // 两个 TrendingFlat 箭头上下排列
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy((-4).dp)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.TrendingFlat,
                    stringResource(R.string.play_sequential),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
                Icon(
                    Icons.AutoMirrored.Filled.TrendingFlat, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            val (icon, description) = when (playMode) {
                PlayMode.LIST_LOOP -> Icons.Filled.Repeat to stringResource(R.string.play_list_loop)
                PlayMode.SHUFFLE -> Icons.Filled.Shuffle to stringResource(R.string.play_shuffle)
                PlayMode.SINGLE_LOOP -> Icons.Filled.RepeatOne to stringResource(R.string.play_single_loop)
                PlayMode.SINGLE_PLAY -> Icons.Filled.LooksOne to stringResource(R.string.play_single_play)
            }
            Icon(icon, description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InlineQualitySelector(
    currentQuality: Quality?,
    availableQualities: List<Quality>,
    onQualitySelect: (Quality) -> Unit
) {
    if (availableQualities.isEmpty()) return

    var showSheet by remember { mutableStateOf(false) }

    val label = if (currentQuality != null) {
        val sizeText = currentQuality.displaySize
            ?: if (currentQuality.filesize > 0) formatFileSize(currentQuality.filesize) else null
        if (sizeText != null) "${currentQuality.name} - $sizeText" else currentQuality.name
    } else {
        availableQualities.first().let { q ->
            val sizeText = q.displaySize
                ?: if (q.filesize > 0) formatFileSize(q.filesize) else null
            if (sizeText != null) "${q.name} - $sizeText" else q.name
        }
    }

    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clickable { showSheet = true }
                .padding(vertical = 4.dp, horizontal = 12.dp)
        )
    }

    if (showSheet) {
        QualityBottomSheet(
            currentQuality = currentQuality,
            availableQualities = availableQualities,
            onQualitySelect = { onQualitySelect(it); showSheet = false },
            onDismiss = { showSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QualityBottomSheet(
    currentQuality: Quality?,
    availableQualities: List<Quality>,
    onQualitySelect: (Quality) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val standardQualities = remember(availableQualities) {
        availableQualities.filter { !PlayerViewModel.isAiQuality(it.id) }
    }
    val aiQualities = remember(availableQualities) {
        availableQualities.filter { PlayerViewModel.isAiQuality(it.id) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            Text(
                stringResource(R.string.quality_select),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )

            standardQualities.forEach { quality ->
                QualitySheetItem(quality, currentQuality, onQualitySelect)
            }

            if (aiQualities.isNotEmpty() && standardQualities.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
                )
                Text(
                    "AI 升频",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )
            }

            aiQualities.forEach { quality ->
                QualitySheetItem(quality, currentQuality, onQualitySelect)
            }
        }
    }
}

@Composable
private fun QualitySheetItem(
    quality: Quality,
    currentQuality: Quality?,
    onClick: (Quality) -> Unit
) {
    val isSelected = currentQuality?.id == quality.id
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(quality) }
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            quality.name,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isSelected) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        val sizeText = quality.displaySize
            ?: if (quality.filesize > 0) formatFileSize(quality.filesize) else null
        if (sizeText != null) {
            Text(
                sizeText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== 工具函数 ====================

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun VolumeButton(volume: Float, onVolumeChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = !expanded }) {
            Icon(
                when {
                    volume == 0f -> Icons.AutoMirrored.Filled.VolumeMute
                    volume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                    else -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = stringResource(R.string.volume),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp)
                )
                Slider(
                    value = volume,
                    onValueChange = onVolumeChange,
                    modifier = Modifier.width(140.dp),
                    thumb = {},
                    track = {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(volume.coerceIn(0f, 1f))
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun SpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val speedOptions = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    val isNonDefault = speed != 1.0f

    Box {
        IconButton(onClick = { expanded = !expanded }) {
            Text(
                text = if (isNonDefault) "${speed}x" else "1x",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isNonDefault) FontWeight.Bold else FontWeight.Normal,
                color = if (isNonDefault) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            speedOptions.forEach { s ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${s}x",
                            color = if (s == speed) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = { onSpeedChange(s); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayQueueSheet(
    playlist: List<MusicItem>,
    currentIndex: Int,
    playNextQueue: List<MusicItem>,
    currentSong: MusicItem?,
    isPlayingFromNextQueue: Boolean,
    onSongClick: (Int) -> Unit,
    onPlayNextClick: (Int) -> Unit,
    onClearMainQueue: () -> Unit,
    onClearPlayNextQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val pagerState = rememberPagerState(pageCount = { 2 })
    val coroutineScope = rememberCoroutineScope()
    val playlistListState = rememberLazyListState()
    var showClearConfirm by remember { mutableStateOf(false) }
    val queueHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.58f)
        .coerceIn(180.dp, 400.dp)

    // 初始滚动到当前播放歌曲
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0 && currentIndex < playlist.size) {
            playlistListState.scrollToItem(currentIndex.coerceAtLeast(0))
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Tab 栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                // Tab: 播放列表
                Column(
                    modifier = Modifier.clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(0) }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.play_queue_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (pagerState.currentPage == 0) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                    )
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(
                                if (pagerState.currentPage == 0) MaterialTheme.colorScheme.tertiary
                                else Color.Transparent,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
                // Tab: 稍后播放
                Column(
                    modifier = Modifier.clickable {
                        coroutineScope.launch { pagerState.animateScrollToPage(1) }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (playNextQueue.isNotEmpty())
                            stringResource(R.string.play_next_queue_count, playNextQueue.size)
                        else stringResource(R.string.play_next_queue),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (pagerState.currentPage == 1) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                    )
                    TextButton(
                        onClick = onClearPlayNextQueue,
                        enabled = playNextQueue.isNotEmpty(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.clear_play_next_queue),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (playNextQueue.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(2.dp)
                            .background(
                                if (pagerState.currentPage == 1) MaterialTheme.colorScheme.tertiary
                                else Color.Transparent,
                                RoundedCornerShape(1.dp)
                            )
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.1f))

            // Pager
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(queueHeight)
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> {
                            // 播放列表
                            LazyColumn(
                                state = playlistListState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                itemsIndexed(playlist) { index, song ->
                                    QueueSongItem(
                                        index = index + 1,
                                        song = song,
                                        isCurrent = !isPlayingFromNextQueue && index == currentIndex,
                                        onClick = { onSongClick(index) }
                                    )
                                }
                            }
                        }

                        1 -> {
                            // 稍后播放
                            if (playNextQueue.isEmpty() && !(isPlayingFromNextQueue && currentSong != null)) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.no_data),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    // 当前正在播放的稍后播放歌曲
                                    if (isPlayingFromNextQueue && currentSong != null) {
                                        item {
                                            QueueSongItem(
                                                index = 1,
                                                song = currentSong,
                                                isCurrent = true,
                                                onClick = { }
                                            )
                                        }
                                    }
                                    itemsIndexed(playNextQueue) { index, song ->
                                        QueueSongItem(
                                            index = if (isPlayingFromNextQueue && currentSong != null) index + 2 else index + 1,
                                            song = song,
                                            isCurrent = false,
                                            onClick = { onPlayNextClick(index) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 右下角清空播放列表按钮（仅在播放列表页且非空时显示）
                if (pagerState.currentPage == 0 && playlist.isNotEmpty()) {
                    Surface(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Box(
                            modifier = Modifier.padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_main_queue)
                            )
                        }
                    }
                }
            }
        }
    }

    // 清空播放列表二次确认
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.clear_main_queue)) },
            text = { Text(stringResource(R.string.clear_main_queue_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    onClearMainQueue()
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun QueueSongItem(
    index: Int,
    song: MusicItem,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (isCurrent) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.1f)
                else Color.Transparent
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isCurrent) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.width(32.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodySmall,
                color = if (isCurrent) MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isCurrent) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.now_playing),
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

internal fun formatDuration(durationMs: Long): String {
    if (durationMs < 0) return "00:00"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "%.1fKB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
        else -> "%.1fGB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }
}

/**
 * 播放页背景层
 */
@Composable
private fun PlayerBackground(
    appSettings: AppSettings,
    coverBitmap: Bitmap?,
    coverKey: String
) {
    if (appSettings.coverBlurBg) {
        AlbumMeshBackground(
            coverBitmap = coverBitmap,
            coverKey = coverKey,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.24f),
                        0.42f to Color.Black.copy(alpha = 0.08f),
                        1f to Color.Black.copy(alpha = 0.42f)
                    )
                )
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0xFF95B3AE),
                        0.55f to Color(0xFF82AAA2),
                        1f to Color(0xFFB7BCBA)
                    )
                )
        )
    }
}

