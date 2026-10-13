package com.ikunshare.music.ui.screens.player

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LooksOne
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.ikunshare.music.R
import com.ikunshare.music.common.AppSettings
import com.ikunshare.music.common.AppSettingsManager
import com.ikunshare.music.manager.DownloadManager
import com.ikunshare.music.model.MusicItem
import com.ikunshare.music.model.PlayMode
import com.ikunshare.music.model.Quality
import com.ikunshare.music.model.neteaseCoverUrl
import com.ikunshare.music.ui.screens.player.mesh.AlbumMeshBackground
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.AddToPlaylistDialog
import com.ikunshare.music.ui.components.CreatePlaylistDialog
import com.ikunshare.music.ui.components.ErrorState
import com.ikunshare.music.ui.components.LoadingIndicator
import com.ikunshare.music.ui.components.SleepTimerDialog
import com.ikunshare.music.ui.screens.lyrics.LyricsViewModel
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.launch

/**
 * 播放页面
 *
 * 手机竖屏切换封面与歌词；宽屏并排显示控制面板和歌词。
 * 保留播放、队列、音质、定时停止和歌曲操作入口。
 */
@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    songId: Long,
    viewModel: PlayerViewModel,
    viewModelFactory: KunyinViewModelFactory,
    downloadManager: DownloadManager,
    appSettingsManager: AppSettingsManager,
    songKey: String = "",
    windowEffectsActive: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentPosition by viewModel.currentPosition.collectAsState()
    val lyricsViewModel: LyricsViewModel = viewModel(factory = viewModelFactory)
    val lyricsState by lyricsViewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val customPlaylists by viewModel.customPlaylists.collectAsState()
    val context = LocalContext.current
    val appSettings by appSettingsManager.settings.collectAsState()

    PlayerWindowEffects(windowEffectsActive)

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var showPlayQueue by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var infoSong by remember { mutableStateOf<MusicItem?>(null) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showPlaybackQuality by remember { mutableStateOf(false) }
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()

    val downloadQueue = com.ikunshare.music.ui.utils.rememberDownloadQueueActions(downloadManager)

    // 权限请求相关
    var pendingDownloadRequest by remember { mutableStateOf<com.ikunshare.music.ui.utils.DownloadQueueRequest?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val request = pendingDownloadRequest
        pendingDownloadRequest = null
        if (granted) {
            request?.let { downloadQueue.addRequests(listOf(it)) {} }
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.storage_permission_needed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val onDownload: (Quality) -> Unit = download@{ quality ->
        if (downloadQueue.isEnqueuing || pendingDownloadRequest != null) return@download
        val song = uiState.currentSong
        Log.d("KunSound", "onDownload called: quality=${quality.id}, song=${song?.title}")
        if (song != null) {
            val request = com.ikunshare.music.ui.utils.DownloadQueueRequest(song, quality.id,
                appSettings.hideAiQualities, exactQuality = true)
            val hasPermission = if (appSettings.downloadPath.startsWith("content://")) {
                // 已授权的文档树通过持久 URI 权限写入，不需要全文件访问。
                true
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
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
                downloadQueue.addRequests(listOf(request)) {}
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
                pendingDownloadRequest = request
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
    LaunchedEffect(songId, songKey) {
        viewModel.loadSong(songId, songKey)
    }

    val onShareClick: () -> Unit = {
        uiState.currentSong?.let { song ->
            val shareText = com.ikunshare.music.MusicApplication.instance?.musicRepository
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
        uiState.currentSong?.let { lyricsViewModel.loadLyrics(it) } ?: lyricsViewModel.clearLyrics()
    }

    // 歌曲解析失败时显示错误提示
    LaunchedEffect(uiState.error) {
        val error = uiState.error
        if (error != null && uiState.currentSong != null) {
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    // 封面 Mesh Gradient 背景（Apple Music 同款）：加载原始封面位图，交给 GL 渲染器处理。
    val coverUrl = uiState.currentSong?.cover
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(coverUrl) {
        coverBitmap = null
        if (coverUrl.isNullOrEmpty()) {
            return@LaunchedEffect
        }
        try {
            val request = ImageRequest.Builder(context).data(coverUrl).size(512).allowHardware(false).build()
            val result = context.imageLoader.execute(request)
            val bitmap = (result as? SuccessResult)?.image?.toBitmap()
            if (bitmap != null) coverBitmap = bitmap
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
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

    val playerBackdrop = rememberLayerBackdrop()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(rootBgColor)
    ) {
        // 背景层
        Box(Modifier.fillMaxSize().layerBackdrop(playerBackdrop)) {
            PlayerBackground(appSettings = appSettings, coverBitmap = coverBitmap, coverKey = coverUrl ?: "")
        }
        when {
            uiState.isLoading && uiState.currentSong == null -> {
                LoadingIndicator(modifier = Modifier.fillMaxSize())
            }

            uiState.error != null && uiState.currentSong == null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.unknown_error),
                    onRetry = { viewModel.loadSong(songId, songKey) },
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                AdaptivePlayer(
                    state = uiState, lyrics = lyricsState, position = currentPosition,
                    backdrop = playerBackdrop, timerActive = sleepTimerState.isActive,
                    onClose = onClose,
                    onSeek = viewModel::seekTo, onPlayPause = viewModel::togglePlayPause,
                    onPrevious = viewModel::previous, onNext = viewModel::next,
                    onVolume = viewModel::setVolume, onSpeed = viewModel::setPlaybackSpeed,
                    onMode = viewModel::cyclePlayMode, onFavorite = viewModel::toggleFavorite,
                    onMore = { showMoreActions = true }, onQuality = { showPlaybackQuality = true },
                    onQueue = { showPlayQueue = true }, onTimer = { showSleepTimer = true },
                    onOutput = { openMediaOutputPanel(context) },
                    onTranslation = lyricsViewModel::toggleTranslation,
                    onRomanization = lyricsViewModel::toggleRomanization,
                    onPhonetic = lyricsViewModel::togglePhonetic,
                    onRetryLyrics = { uiState.currentSong?.let { lyricsViewModel.retry(it) } },
                    keepControlsVisible = showPlayQueue || showSleepTimer || showMoreActions ||
                        showPlaybackQuality || showPlaylistPicker || showCreateDialog || showComments
                )
            }
        }
        if (showPlaybackQuality) {
            QualityBottomSheet(
                currentQuality = uiState.currentQuality, availableQualities = uiState.availableQualities,
                onQualitySelect = { viewModel.setQuality(it); showPlaybackQuality = false },
                onDismiss = { showPlaybackQuality = false }
            )
        }
        val snackbarBottomPadding = 300.dp
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

        if (showComments) {
            val commentsViewModel: CommentsViewModel = viewModel(factory = viewModelFactory)
            CommentDialog(uiState.currentSong, commentsViewModel) { showComments = false }
        }

        infoSong?.let { com.ikunshare.music.ui.components.SongInfoDialog(it) { infoSong = null } }

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
                onCommentsClick = { showMoreActions = false; showComments = true },
                onInfoClick = { infoSong = uiState.currentSong; showMoreActions = false },
                onPlayModeClick = viewModel::cyclePlayMode,
                downloadBusy = downloadQueue.isEnqueuing || pendingDownloadRequest != null,
                onDownloadClick = { quality ->
                    showMoreActions = false
                    onDownload(quality)
                },
                onDismiss = { showMoreActions = false }
            )
        }

        // 播放列表 BottomSheet
        if (showPlayQueue) {
            val queueSongs by viewModel.queueSongs.collectAsState()
            val queueSource by viewModel.queueSource.collectAsState()
            PlayQueueSheet(
                playlist = queueSongs,
                sourceName = queueSource?.name.orEmpty(),
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

private fun openMediaOutputPanel(context: android.content.Context) {
    val intent = Intent("android.settings.panel.action.MEDIA_OUTPUT")
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
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
    onCommentsClick: () -> Unit,
    onInfoClick: () -> Unit,
    onPlayModeClick: () -> Unit,
    downloadBusy: Boolean,
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
                    icon = Icons.Filled.ChatBubbleOutline,
                    title = stringResource(R.string.song_comments),
                    iconTint = Color(0xFF64D2FF),
                    onClick = onCommentsClick
                )
                AppleMoreDivider()
                AppleMoreActionRow(
                    icon = Icons.Filled.Share,
                    title = stringResource(R.string.share),
                    iconTint = Color(0xFF30D158),
                    onClick = onShareClick
                )
                AppleMoreDivider()
                AppleMoreActionRow(icon = Icons.Filled.Info, title = stringResource(R.string.song_info_title),
                    enabled = song != null, onClick = onInfoClick)
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
                            busy = downloadBusy,
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
                            busy = downloadBusy,
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
    busy: Boolean,
    onClick: () -> Unit
) {
    val sizeText = quality.displaySize
        ?: if (quality.filesize > 0) formatFileSize(quality.filesize) else null
    AppleMoreActionRow(
        icon = Icons.Filled.Download,
        title = quality.name,
        subtitle = sizeText,
        trailingText = stringResource(if (busy) R.string.download_adding_tasks else R.string.download),
        enabled = !busy,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayQueueSheet(
    playlist: List<MusicItem>,
    sourceName: String,
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

            if (sourceName.isNotBlank()) Text(
                text = stringResource(R.string.play_queue_source, sourceName),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

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
internal fun PlayerBackground(
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
                        0f to Color(0xFF263D48),
                        0.55f to Color(0xFF405858),
                        1f to Color(0xFF292A37)
                    )
                )
        )
    }
}

