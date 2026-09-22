package com.ikunshare.sound.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.sound.BuildConfig
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.manager.*
import com.ikunshare.sound.ui.KunyinViewModelFactory
import com.ikunshare.sound.ui.components.FolderPickerDialog
import com.ikunshare.sound.ui.components.NoticeDialog
import com.ikunshare.sound.ui.components.UpdateDialog
import com.ikunshare.sound.ui.screens.download.DownloadNamingStyle
import com.ikunshare.sound.ui.utils.LocalScrollOffsetReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页自定义对话框（Dialog + Surface）的统一圆角，与 Material3 AlertDialog 默认值一致 */
internal val DialogCornerRadius = 28.dp

private val standardQualityOptions = listOf(
    "" to "自动（最高标准音质）",
    "128k" to "标准 128kbps",
    "320k" to "高品 320kbps",
    "flac" to "无损 FLAC",
    "hires" to "Hi-Res"
)

private val aiQualityOptions = listOf(
    "atmos" to "臻品全景声（AI）",
    "atmos_plus" to "臻品全景声 2.0（AI）",
    "master" to "臻品母带（AI）"
)

private val cacheSizeOptions = listOf(
    1 to "",      // 禁用
    200 to "200 MB",
    500 to "500 MB",
    1024 to "1 GB",
    2048 to "2 GB",
    0 to "∞"
)

private val concurrentDownloadOptions = (1..10).map { it to "$it" }

private val namingStyleOptions = DownloadNamingStyle.entries.map { it.key to it.labelRes }

private val audioFocusBehaviorOptions = listOf(
    "none" to R.string.audio_focus_none,
    "duck" to R.string.audio_focus_duck,
    "pause" to R.string.audio_focus_pause
)

private enum class SettingsSection(@StringRes val titleRes: Int) {
    ACCOUNT(R.string.section_account),
    APPEARANCE(R.string.section_appearance),
    FONT(R.string.section_font),
    PLAYBACK(R.string.section_playback),
    FLOATING_LYRICS(R.string.section_floating_lyrics),
    LYRICON(R.string.section_lyricon),
    DOWNLOAD(R.string.section_download),
    CACHE(R.string.section_cache),
    BACKUP(R.string.section_backup),
    SYNC(R.string.section_sync),
    ABOUT(R.string.section_about)
}

@Composable
private fun RememberScrollOffsetReporter(
    lazyListState: androidx.compose.foundation.lazy.LazyListState
) {
    // 监听滚动偏移量并上报
    val scrollOffsetReporter = LocalScrollOffsetReporter.current

    var previousVisibleItemIndex by remember { mutableIntStateOf(0) }
    var previousScrollOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(lazyListState) {
        snapshotFlow {
            lazyListState.firstVisibleItemIndex to lazyListState.firstVisibleItemScrollOffset
        }.collect { (firstVisibleItemIndex, scrollOffset) ->
            val isScrollingUp = firstVisibleItemIndex < previousVisibleItemIndex ||
                    (firstVisibleItemIndex == previousVisibleItemIndex && scrollOffset < previousScrollOffset)
            scrollOffsetReporter?.invoke(firstVisibleItemIndex, scrollOffset, isScrollingUp)
            previousVisibleItemIndex = firstVisibleItemIndex
            previousScrollOffset = scrollOffset
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModelFactory: KunyinViewModelFactory
) {
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory)
    val creds by vm.credentials.collectAsState()
    val settings by vm.appSettings.collectAsState()
    var dialogProvider by remember { mutableStateOf<ProviderInfo?>(null) }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    if (screenWidthDp >= 600) {
        WideSettingsLayout(
            creds = creds,
            settings = settings,
            vm = vm,
            onProviderClick = { dialogProvider = it }
        )
    } else {
        NarrowSettingsLayout(
            creds = creds,
            settings = settings,
            vm = vm,
            onProviderClick = { dialogProvider = it }
        )
    }

    dialogProvider?.let { info ->
        when (info.key) {
            "wy" -> {
                LaunchedEffect(info) {
                    navController.navigate("wy_qr_login")
                    dialogProvider = null
                }
            }

            "qq" -> QQLoginMethodDialog(
                onDismiss = { dialogProvider = null },
                onQRLogin = {
                    navController.navigate("qq_qr_login"); dialogProvider = null
                },
                onWebViewLogin = {
                    navController.navigate("login_webview/qq"); dialogProvider = null
                }
            )

            "kg" -> {
                var showManual by remember { mutableStateOf(false) }
                if (showManual) {
                    KgCredentialDialog(
                        existing = creds["kg"],
                        onDismiss = { dialogProvider = null },
                        onSave = { cred -> vm.saveCredential("kg", cred); dialogProvider = null },
                        onClear = { vm.clearCredential("kg"); dialogProvider = null }
                    )
                } else {
                    KgLoginMethodDialog(
                        existing = creds["kg"],
                        onDismiss = { dialogProvider = null },
                        onQRLogin = {
                            navController.navigate("kg_qr_login"); dialogProvider = null
                        },
                        onManualLogin = { showManual = true },
                        onClear = { vm.clearCredential("kg"); dialogProvider = null }
                    )
                }
            }

            else -> {
                dialogProvider = null
            }
        }
    }
}

@Composable
private fun WideSettingsLayout(
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    val sections = remember { SettingsSection.entries }
    var selectedSection by rememberSaveable { mutableStateOf(sections.first()) }
    // If the selected section is no longer available, reset to first
    LaunchedEffect(sections) {
        if (selectedSection !in sections) {
            selectedSection = sections.first()
        }
    }

    // 滚动状态监听
    val settingsListState = rememberLazyListState()
    RememberScrollOffsetReporter(settingsListState)
    val detailListState = rememberLazyListState()
    RememberScrollOffsetReporter(detailListState)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Left navigation panel
        LazyColumn(
            state = settingsListState,
            modifier = Modifier
                .width(220.dp)
                .fillMaxHeight()
                .padding(top = 16.dp, start = 8.dp, end = 8.dp)
        ) {
            items(sections) { section ->
                NavigationDrawerItem(
                    label = { Text(stringResource(section.titleRes)) },
                    selected = section == selectedSection,
                    onClick = { selectedSection = section },
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        VerticalDivider()

        // Right content panel
        LazyColumn(
            state = detailListState,
            modifier = Modifier
                .fillMaxHeight()
                .padding(16.dp)
        ) {
            item {
                when (selectedSection) {
                    SettingsSection.ACCOUNT -> AccountSection(creds, settings, vm, onProviderClick)
                    SettingsSection.APPEARANCE -> AppearanceSection(settings, vm)
                    SettingsSection.FONT -> FontSection(settings, vm)
                    SettingsSection.PLAYBACK -> PlaybackSection(settings, vm)
                    SettingsSection.FLOATING_LYRICS -> FloatingLyricsSection(settings, vm)
                    SettingsSection.LYRICON -> LyriconSection(settings, vm)
                    SettingsSection.DOWNLOAD -> DownloadSection(settings, vm)
                    SettingsSection.CACHE -> CacheSection(settings, vm)
                    SettingsSection.BACKUP -> BackupSection(vm)
                    SettingsSection.SYNC -> SyncSection()
                    SettingsSection.ABOUT -> AboutSection()
                }
            }
            item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun sectionIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.ACCOUNT -> Icons.Filled.Person
    SettingsSection.APPEARANCE -> Icons.Filled.Palette
    SettingsSection.FONT -> Icons.Filled.FormatSize
    SettingsSection.PLAYBACK -> Icons.Filled.PlayCircle
    SettingsSection.FLOATING_LYRICS -> Icons.Filled.TextFields
    SettingsSection.LYRICON -> Icons.Filled.Lyrics
    SettingsSection.DOWNLOAD -> Icons.Filled.Download
    SettingsSection.CACHE -> Icons.Filled.Storage
    SettingsSection.BACKUP -> Icons.Filled.SettingsBackupRestore
    SettingsSection.SYNC -> Icons.Filled.Sync
    SettingsSection.ABOUT -> Icons.Filled.Info
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowSettingsLayout(
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    var selectedSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

    val sections = remember { SettingsSection.entries }

    // 滚动状态监听
    val settingsListState = rememberLazyListState()
    RememberScrollOffsetReporter(settingsListState)
    val detailListState = rememberLazyListState()
    RememberScrollOffsetReporter(detailListState)

    BackHandler(enabled = selectedSection != null) {
        selectedSection = null
    }

    AnimatedContent(
        targetState = selectedSection,
        transitionSpec = {
            if (targetState != null) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
            } else {
                slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
            }
        },
        label = "settings_nav"
    ) { section ->
        if (section == null) {
            // 分栏列表
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(R.string.settings_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { padding ->
                LazyColumn(
                    state = settingsListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item(key = "top_spacer") { Spacer(Modifier.height(4.dp)) }

                    items(items = sections, key = { it.name }) { sec ->
                        SettingsSectionCard(
                            title = stringResource(sec.titleRes),
                            icon = sectionIcon(sec),
                            onClick = { selectedSection = sec }
                        )
                    }

                    item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
                }
            }
        } else {
            // 详情页
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                stringResource(section.titleRes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = { selectedSection = null }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                    tint = MaterialTheme.colorScheme.onBackground
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                containerColor = Color.Transparent
            ) { padding ->
                LazyColumn(
                    state = detailListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp)
                ) {
                    item {
                        when (section) {
                            SettingsSection.ACCOUNT -> AccountSection(
                                creds,
                                settings,
                                vm,
                                onProviderClick
                            )

                            SettingsSection.APPEARANCE -> AppearanceSection(settings, vm)
                            SettingsSection.FONT -> FontSection(settings, vm)
                            SettingsSection.PLAYBACK -> PlaybackSection(settings, vm)
                            SettingsSection.FLOATING_LYRICS -> FloatingLyricsSection(settings, vm)
                            SettingsSection.LYRICON -> LyriconSection(settings, vm)
                            SettingsSection.DOWNLOAD -> DownloadSection(settings, vm)
                            SettingsSection.CACHE -> CacheSection(settings, vm)
                            SettingsSection.BACKUP -> BackupSection(vm)
                            SettingsSection.SYNC -> SyncSection()
                            SettingsSection.ABOUT -> AboutSection()
                        }
                    }

                    item(key = "bottom_spacer") { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(8.dp)
                ) {}
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// ===== Section Composables =====

@Composable
private fun PlaybackSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.section_playback))
    SettingSwitchItem(stringResource(R.string.show_translation), settings.showTranslation) {
        vm.updateSettings { copy(showTranslation = it) }
    }
    SettingSwitchItem(stringResource(R.string.show_romanization), settings.showRomanization) {
        vm.updateSettings { copy(showRomanization = it) }
    }
    SettingSwitchItem(stringResource(R.string.show_phonetic), settings.showPhonetic) {
        vm.updateSettings { copy(showPhonetic = it) }
    }
    SettingSwitchItem(stringResource(R.string.hide_ai_qualities), settings.hideAiQualities) {
        vm.updateSettings { copy(hideAiQualities = it) }
    }
    SettingSwitchItem(stringResource(R.string.auto_play_on_restart), settings.autoPlayOnRestart) {
        vm.updateSettings { copy(autoPlayOnRestart = it) }
    }
    SettingSwitchItem(
        stringResource(R.string.trial_list_add_to_head),
        settings.trialListAddToHead
    ) { vm.updateSettings { copy(trialListAddToHead = it) } }
    SettingSwitchItem(
        stringResource(R.string.auto_open_player_on_song_click),
        settings.autoOpenPlayerOnSongClick
    ) { vm.updateSettings { copy(autoOpenPlayerOnSongClick = it) } }
    DefaultQualitySelector(settings.defaultQualityId, settings.hideAiQualities) { id ->
        vm.updateSettings { copy(defaultQualityId = id) }
    }
    NetworkQualitySelector(
        label = "WiFi 默认音质",
        currentId = settings.wifiQualityId,
        hideAi = settings.hideAiQualities
    ) { id -> vm.updateSettings { copy(wifiQualityId = id) } }
    NetworkQualitySelector(
        label = "蜂窝网络默认音质",
        currentId = settings.cellularQualityId,
        hideAi = settings.hideAiQualities
    ) { id -> vm.updateSettings { copy(cellularQualityId = id) } }

    // ── 音频焦点 ──
    AudioFocusBehaviorSelector(settings.audioFocusBehavior) { behavior ->
        vm.updateSettings { copy(audioFocusBehavior = behavior) }
    }

    // 压低音量百分比（仅 duck 模式显示）
    if (settings.audioFocusBehavior == "duck") {
        val duckPct = settings.audioDuckVolume
        Text(
            stringResource(R.string.audio_duck_volume, duckPct),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 4.dp)
        )
        Slider(
            value = duckPct.toFloat(),
            onValueChange = { vm.updateSettings { copy(audioDuckVolume = it.toInt()) } },
            valueRange = 10f..80f,
            steps = 6,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.tertiary,
                activeTrackColor = MaterialTheme.colorScheme.tertiary
            )
        )
    }

    // ── 来电暂停（需要 READ_PHONE_STATE 权限）──
    val phonePermission = android.Manifest.permission.READ_PHONE_STATE
    val context = LocalContext.current
    val phonePermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            vm.updateSettings { copy(pauseOnPhoneCall = true) }
            // 权限刚授予，重新注册监听
            (context as? com.ikunshare.sound.MainActivity)?.playerViewModel?.registerPhoneStateListener()
        }
    }
    SettingSwitchItem(
        stringResource(R.string.pause_on_phone_call),
        settings.pauseOnPhoneCall
    ) { enabled ->
        if (enabled) {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context, phonePermission
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasPermission) {
                vm.updateSettings { copy(pauseOnPhoneCall = true) }
            } else {
                phonePermLauncher.launch(phonePermission)
            }
        } else {
            vm.updateSettings { copy(pauseOnPhoneCall = false) }
        }
    }

    // ── 耳机/蓝牙断开后继续播放 ──
    SettingSwitchItem(
        stringResource(R.string.keep_playing_on_headset_disconnect),
        settings.keepPlayingOnHeadsetDisconnect
    ) { enabled ->
        vm.updateSettings { copy(keepPlayingOnHeadsetDisconnect = enabled) }
    }
    Text(
        stringResource(R.string.keep_playing_on_headset_disconnect_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        modifier = Modifier.padding(bottom = 8.dp)
    )
    // 注：歌词字号/字重已移至「字体」设置页统一管理。
}

@Composable
private fun DownloadSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.section_download))

    val defaultPath = DownloadManager.defaultDownloadDir().absolutePath

    var showPathDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    val pathDisplay = settings.downloadPath.ifEmpty { defaultPath }

    // 下载路径
    SettingClickableItem(
        label = stringResource(R.string.download_path),
        value = pathDisplay,
        valueColor = MaterialTheme.colorScheme.tertiary,
        onClick = { showPathDialog = true }
    )

    // 最大同时下载数
    ConcurrentDownloadSelector(settings.maxConcurrentDownloads) { count ->
        vm.updateSettings { copy(maxConcurrentDownloads = count) }
    }

    // 文件命名方式
    NamingStyleSelector(settings.downloadNamingStyle) { style ->
        vm.updateSettings { copy(downloadNamingStyle = style) }
    }

    // 文件名带音轨号（整专下载时）
    SettingSwitchItem(
        stringResource(R.string.download_track_number),
        settings.downloadTrackNumber
    ) {
        vm.updateSettings { copy(downloadTrackNumber = it) }
    }

    // 整专文件夹名加年份前缀
    SettingSwitchItem(
        stringResource(R.string.download_album_folder_with_year),
        settings.downloadAlbumFolderWithYear
    ) {
        vm.updateSettings { copy(downloadAlbumFolderWithYear = it) }
    }

    // 整专下载单独保存封面文件（cover.jpg/cover.png）
    SettingSwitchItem(
        stringResource(R.string.download_album_cover),
        settings.downloadAlbumCover
    ) {
        vm.updateSettings { copy(downloadAlbumCover = it) }
    }

    // 文件名追加采样率/位深标记（仅无损 FLAC 生效）
    SettingSwitchItem(
        stringResource(R.string.download_sample_rate_tag),
        settings.downloadSampleRateTag
    ) {
        vm.updateSettings { copy(downloadSampleRateTag = it) }
    }

    // 覆盖同名文件
    SettingSwitchItem(
        stringResource(R.string.overwrite_existing),
        settings.downloadOverwriteExisting
    ) {
        vm.updateSettings { copy(downloadOverwriteExisting = it) }
    }

    // 写入歌词到 metadata
    SettingSwitchItem(stringResource(R.string.write_lyrics_meta), settings.downloadWriteLyricMeta) {
        vm.updateSettings { copy(downloadWriteLyricMeta = it) }
    }

    // 下载 .lrc 文件
    SettingSwitchItem(stringResource(R.string.download_lrc), settings.downloadLrcFile) {
        vm.updateSettings { copy(downloadLrcFile = it) }
    }

    // 歌词子选项（任一歌词开关开启时显示）
    if (settings.downloadWriteLyricMeta || settings.downloadLrcFile) {
        Column(modifier = Modifier.padding(start = 16.dp)) {
            SettingSwitchItem(
                stringResource(R.string.write_char_lyrics),
                settings.downloadCharLyrics
            ) {
                vm.updateSettings { copy(downloadCharLyrics = it) }
            }
            SettingSwitchItem(
                stringResource(R.string.write_translation_lyrics),
                settings.downloadTranslation
            ) {
                vm.updateSettings { copy(downloadTranslation = it) }
            }
            SettingSwitchItem(
                stringResource(R.string.write_romanization_lyrics),
                settings.downloadRomanization
            ) {
                vm.updateSettings { copy(downloadRomanization = it) }
            }
            if (settings.downloadRomanization) {
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    SettingSwitchItem(
                        stringResource(R.string.write_char_romanization),
                        settings.downloadCharRomanization
                    ) {
                        vm.updateSettings { copy(downloadCharRomanization = it) }
                    }
                }
            }

            SettingSwitchItem(
                stringResource(R.string.lrc_interludes),
                settings.downloadLrcInterludes
            ) {
                vm.updateSettings { copy(downloadLrcInterludes = it) }
            }
        }
    }

    // 下载路径输入对话框
    if (showPathDialog) {
        var pathInput by remember { mutableStateOf(settings.downloadPath) }
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.download_path),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = pathInput,
                        onValueChange = { pathInput = it },
                        label = { Text(stringResource(R.string.path_hint)) },
                        placeholder = { Text(defaultPath) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
                            focusedLabelColor = MaterialTheme.colorScheme.tertiary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            cursorColor = MaterialTheme.colorScheme.tertiary,
                            focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            showPathDialog = false
                            showFolderPicker = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.select_directory))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateSettings {
                        copy(
                            downloadPath = pathInput.trim(),
                            downloadTreeUri = ""
                        )
                    }
                    showPathDialog = false
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPathDialog = false
                }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    if (showFolderPicker) {
        FolderPickerDialog(
            initialPath = settings.downloadPath.ifEmpty { defaultPath },
            onDismiss = { showFolderPicker = false },
            onConfirm = { path ->
                vm.updateSettings {
                    copy(downloadPath = path, downloadTreeUri = "")
                }
                showFolderPicker = false
            }
        )
    }
}

private fun treeUriToPath(uri: Uri): String? {
    val docId = try {
        android.provider.DocumentsContract.getTreeDocumentId(uri)
    } catch (_: Exception) {
        return null
    }
    // "primary:Download/MPDL" → "/storage/emulated/0/Download/MPDL"
    if (docId.startsWith("primary:")) {
        val relative = docId.removePrefix("primary:")
        return Environment.getExternalStorageDirectory().resolve(relative).absolutePath
    }
    // SD 卡: "XXXX-XXXX:relative/path" → "/storage/XXXX-XXXX/relative/path"
    val colonIndex = docId.indexOf(':')
    if (colonIndex > 0) {
        val volumeId = docId.substring(0, colonIndex)
        val relative = docId.substring(colonIndex + 1)
        return "/storage/$volumeId" + if (relative.isNotEmpty()) "/$relative" else ""
    }
    return null
}

@Composable
private fun CacheSection(
    settings: com.ikunshare.sound.common.AppSettings,
    vm: SettingsViewModel
) {
    SectionHeader(stringResource(R.string.section_cache))
    val cacheSizeBytes by vm.cacheSizeBytes.collectAsState()
    val lyricCacheCount by vm.lyricCacheCount.collectAsState()
    val mediaInfoCacheCount by vm.mediaInfoCacheCount.collectAsState()
    LaunchedEffect(Unit) { vm.refreshCacheInfo() }

    val cacheSizeText = run {
        val mb = cacheSizeBytes / (1024.0 * 1024.0)
        if (mb < 1.0) "%.1f KB".format(cacheSizeBytes / 1024.0) else "%.1f MB".format(mb)
    }

    CacheSizeSelector(settings.maxCacheSizeMb) { mb ->
        vm.updateSettings { copy(maxCacheSizeMb = mb) }
    }

    CacheRow(
        label = stringResource(R.string.audio_cache),
        value = cacheSizeText
    ) { vm.clearAudioCache() }
    CacheRow(
        label = stringResource(R.string.lyrics_cache),
        value = stringResource(R.string.cache_count, lyricCacheCount)
    ) { vm.clearLyricCache() }
    CacheRow(
        label = stringResource(R.string.media_info_cache),
        value = stringResource(R.string.cache_count, mediaInfoCacheCount)
    ) { vm.clearMediaInfoCache() }

    Spacer(Modifier.height(8.dp))

    var showClearAllDialog by remember { mutableStateOf(false) }
    Button(
        onClick = { showClearAllDialog = true },
        modifier = Modifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    ) {
        Text(stringResource(R.string.clear_all_caches))
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.clear_all_caches),
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Text(
                    stringResource(R.string.clear_all_caches_confirm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearAllCaches()
                    showClearAllDialog = false
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearAllDialog = false
                }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }

    Spacer(Modifier.height(16.dp))

    // WebView 数据（独立于应用缓存）
    var showClearWebViewDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    SettingClickableItem(
        label = stringResource(R.string.webview_data),
        value = stringResource(R.string.cookie_cache),
        trailing = {
            IconButton(
                onClick = { showClearWebViewDialog = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
    if (showClearWebViewDialog) {
        AlertDialog(
            onDismissRequest = { showClearWebViewDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = {
                Text(
                    stringResource(R.string.clear_webview_data),
                    color = MaterialTheme.colorScheme.onBackground
                )
            },
            text = {
                Text(
                    stringResource(R.string.clear_webview_confirm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    android.webkit.CookieManager.getInstance().removeAllCookies(null)
                    android.webkit.CookieManager.getInstance().flush()
                    android.webkit.WebStorage.getInstance().deleteAllData()
                    android.webkit.WebView(context).clearCache(true)
                    showClearWebViewDialog = false
                }) {
                    Text(
                        stringResource(R.string.confirm),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showClearWebViewDialog = false
                }) {
                    Text(
                        stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val packageInfo = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: Exception) {
            null
        }
    }
    val versionName = packageInfo?.versionName ?: "unknown"

    @Suppress("DEPRECATION")
    val versionCode = packageInfo?.versionCode ?: 0
    val commitSha = BuildConfig.GIT_COMMIT

    // ── 调试入口彩蛋：点图标 5 次后 1 秒内点版本号 ──
    var iconClickCount by remember { mutableIntStateOf(0) }
    var debugUnlockedAt by remember { mutableLongStateOf(0L) }
    var showDebugScreen by remember { mutableStateOf(false) }

    SectionHeader(stringResource(R.string.section_about))

    // 应用图标和版本信息
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_foreground),
            contentDescription = stringResource(R.string.app_icon),
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .clickable {
                    iconClickCount++
                    if (iconClickCount >= 5) {
                        debugUnlockedAt = System.currentTimeMillis()
                        iconClickCount = 0
                    }
                }
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.version_info, "$versionName+$commitSha ($versionCode)"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable {
                if (debugUnlockedAt > 0 && System.currentTimeMillis() - debugUnlockedAt <= 1000) {
                    showDebugScreen = true
                }
                debugUnlockedAt = 0
            }
        )
    }

    Spacer(Modifier.height(24.dp))

    val scope = rememberCoroutineScope()

    // ── 激活 ──
    val authManager = remember { SoundApplication.instance?.authManager }
    val authState by (authManager?.state
        ?: MutableStateFlow(AuthState())).collectAsState()
    var showAuthDialog by remember { mutableStateOf(false) }
    var authInput by remember { mutableStateOf("") }

    AboutLinkRow(
        icon = Icons.Filled.VerifiedUser,
        label = if (authState.isValid) "已激活" else "激活"
    ) {
        if (!authState.isValid) {
            authInput = ""
            authManager?.clearError()
            showAuthDialog = true
        }
    }

    if (showAuthDialog && authManager != null) {
        AlertDialog(
            onDismissRequest = {
                showAuthDialog = false
                authInput = ""
                authManager.clearError()
            },
            title = { Text("激活") },
            text = {
                Column {
                    OutlinedTextField(
                        value = authInput,
                        onValueChange = {
                            authInput = it
                            authManager.clearError()
                        },
                        label = { Text("请输入卡密") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        isError = authState.errorMessage.isNotEmpty(),
                        supportingText = if (authState.errorMessage.isNotEmpty()) {
                            { Text(authState.errorMessage) }
                        } else null,
                        enabled = !authState.isChecking
                    )
                    if (authState.isChecking) {
                        Spacer(Modifier.height(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(24.dp)
                                .align(Alignment.CenterHorizontally)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (authInput.isNotBlank()) {
                            scope.launch(Dispatchers.IO) {
                                val (valid, _) = authManager.validateAndSave(authInput.trim())
                                if (valid) {
                                    launch(Dispatchers.Main) {
                                        showAuthDialog = false
                                        authInput = ""
                                    }
                                }
                            }
                        }
                    },
                    enabled = authInput.isNotBlank() && !authState.isChecking
                ) { Text("确认") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAuthDialog = false
                    authInput = ""
                    authManager.clearError()
                }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // 检查更新
    var isChecking by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadFailed by remember { mutableStateOf(false) }

    AboutLinkRow(
        icon = Icons.Filled.SystemUpdate,
        label = if (isChecking) stringResource(R.string.checking) else stringResource(R.string.check_update)
    ) {
        if (isChecking) return@AboutLinkRow
        isChecking = true
        scope.launch {
            val app = SoundApplication.instance ?: run { isChecking = false; return@launch }
            val info = withContext(Dispatchers.IO) {
                app.updateChecker.check(versionCode)
            }
            isChecking = false
            if (info != null && info.hasUpdate) {
                downloadProgress = null
                downloadFailed = false
                updateInfo = info
            } else {
                Toast.makeText(
                    context,
                    context.getString(R.string.already_latest),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // 更新弹窗
    updateInfo?.let { info ->
        UpdateDialog(
            updateInfo = info,
            onDismiss = { updateInfo = null },
            onSkipVersion = {
                SoundApplication.instance?.updateChecker?.skipVersion(info.title)
                updateInfo = null
            },
            onExitApp = { (context as? android.app.Activity)?.finish() },
            onDownload = {
                scope.launch {
                    downloadProgress = 0f
                    downloadFailed = false
                    withContext(Dispatchers.IO) {
                        val app = SoundApplication.instance ?: return@withContext
                        val file = app.updateChecker.downloadApk(
                            info, context.cacheDir
                        ) { downloadProgress = it }
                        if (file != null) {
                            withContext(Dispatchers.Main) {
                                (context as? com.ikunshare.sound.MainActivity)?.installApk(file)
                            }
                        } else {
                            downloadFailed = true
                        }
                    }
                }
            },
            onBrowserDownload = {
                context.startActivity(Intent(Intent.ACTION_VIEW, info.shareUrl.toUri()))
                if (!info.isCompulsory) updateInfo = null
            },
            downloadProgress = downloadProgress,
            downloadFailed = downloadFailed
        )
    }

    // 查看公告
    var isFetchingNotice by remember { mutableStateOf(false) }
    var noticeContent by remember { mutableStateOf<NoticeInfo?>(null) }

    AboutLinkRow(
        icon = Icons.Filled.Notifications,
        label = if (isFetchingNotice) stringResource(R.string.fetching) else stringResource(R.string.view_notice)
    ) {
        if (isFetchingNotice) return@AboutLinkRow
        isFetchingNotice = true
        scope.launch {
            val app = SoundApplication.instance ?: run { isFetchingNotice = false; return@launch }
            val notice = withContext(Dispatchers.IO) {
                app.noticeManager.fetch(NoticeManager.NOTICE_API_URL, versionName, versionCode)
            }
            isFetchingNotice = false
            if (notice != null) {
                noticeContent = notice
            } else {
                Toast.makeText(context, context.getString(R.string.no_notice), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    noticeContent?.let { info ->
        NoticeDialog(
            content = info.content,
            onDismiss = { noticeContent = null }
        )
    }

    // 链接列表
    AboutLinkRow(
        icon = Icons.Filled.Groups,
        label = stringResource(R.string.join_qq_group)
    ) {
        try {
            val key = "A2alOAv2htz2-egCuBfk7nWY5e05K22A"
            val qqIntent = Intent(
                Intent.ACTION_VIEW,
                "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3D$key".toUri()
            )
            context.startActivity(qqIntent)
        } catch (_: Exception) {
            val browserIntent = Intent(
                Intent.ACTION_VIEW,
                "https://qm.qq.com/cgi-bin/qm/qr?k=NZXI7sTKBUJewXFD0O5iRtHrtN2sHaOs&jump_from=webapi&authKey=1jOita0a4w2N4x71MSV86VfiU3xhyZibVJ4SCgoIaGtTtO+jkCW9He9VzNfJeWz4".toUri()
            )
            context.startActivity(browserIntent)
        }
    }

    AboutLinkRow(
        icon = Icons.AutoMirrored.Filled.Send,
        label = stringResource(R.string.join_telegram_group)
    ) {
        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                "https://t.me/ikunshare_qun".toUri()
            )
        )
    }

    Spacer(Modifier.height(16.dp))

    // ── 许可协议 ──
    var showEulaDialog by remember { mutableStateOf(false) }
    val eulaContent = remember {
        try {
            context.assets.open("eula.md").bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            ""
        }
    }

    Text(
        text = buildAnnotatedString {
            append("您已签署本软件的许可协议，")
            pushStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary))
            append("点击再次查看")
            pop()
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clickable { showEulaDialog = true }
    )

    if (showEulaDialog && eulaContent.isNotBlank()) {
        NoticeDialog(
            content = eulaContent,
            onDismiss = { showEulaDialog = false }
        )
    }

    Spacer(Modifier.height(16.dp))

    // ── 软件开发 ──
    Text(
        "软件开发:",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_mpdl),
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "MPDL-Official",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "由 Naiy_ / lerd / ikun0014 等人组成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_ikunshare),
            contentDescription = null,
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                "ikun分享",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "还是由 Naiy_ / lerd / ikun0014 等人组成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    // ── 调试设置弹窗 ──
    if (showDebugScreen) {
        DebugSettingsDialog(onDismiss = { showDebugScreen = false })
    }
}

@Composable
private fun DebugSettingsDialog(onDismiss: () -> Unit) {
    val app = SoundApplication.instance ?: return
    val settings by app.appSettingsManager.settings.collectAsState()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("调试设置", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column {
                // 记录请求日志
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            app.appSettingsManager.update { copy(debugHttpLog = !debugHttpLog) }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("记录请求日志", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "输出 HTTP 请求/响应到 Logcat (tag: HTTPUtils)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.debugHttpLog,
                        onCheckedChange = { app.appSettingsManager.update { copy(debugHttpLog = it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary)
                    )
                }

                HorizontalDivider()

                // 记录日志到文件
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            app.appSettingsManager.update { copy(debugLogcat = !debugLogcat) }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("记录日志", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "保存 Logcat 到 Android/data/${context.packageName}/cache/logcat/",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.debugLogcat,
                        onCheckedChange = { app.appSettingsManager.update { copy(debugLogcat = it) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary)
                    )
                }

                HorizontalDivider()

                // Lyricon localcentralapp
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            app.appSettingsManager.update { copy(debugLyriconLocalCentral = !debugLyriconLocalCentral) }
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lyricon localcentralapp", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "使用 localcentralapp 作为 Lyricon 广播接收器（更改后需重新开关 Lyricon 开关）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.debugLyriconLocalCentral,
                        onCheckedChange = {
                            app.appSettingsManager.update {
                                copy(
                                    debugLyriconLocalCentral = it
                                )
                            }
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.tertiary)
                    )
                }

                HorizontalDivider()

                // 测试崩溃
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            throw RuntimeException("Debug: 测试 Java 崩溃")
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.BugReport,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "测试 Java 崩溃",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = MaterialTheme.colorScheme.tertiary)
            }
        }
    )
}

@Composable
private fun AboutLinkRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    SettingClickableItem(label = label, icon = icon, onClick = onClick)
}

@Composable
private fun CacheRow(
    label: String,
    value: String,
    onClear: () -> Unit
) {
    SettingClickableItem(
        label = label,
        value = value,
        trailing = {
            IconButton(onClick = onClear, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

// ===== Shared UI Components =====

@Composable
internal fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
internal fun SettingSwitchItem(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * 通用的设置项行：左侧可选图标 + 标题，右侧可选值文本，尾部可选操作按钮。
 * 统一替换设置页里各处手写的 Row（下载路径、关于链接、缓存清理、值展示行等）。
 *
 * @param value 右侧值文本；为 null 时不显示。
 * @param valueColor 值文本颜色，默认 onSurfaceVariant。
 * @param icon 左侧图标；为 null 时不显示。
 * @param onClick 整行点击；为 null 时整行不可点击（用于仅靠尾部按钮操作的场景）。
 * @param trailing 尾部自定义内容（如删除按钮）。
 */
@Composable
internal fun SettingClickableItem(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(16.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun DefaultQualitySelector(currentId: String, hideAi: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (hideAi) standardQualityOptions else standardQualityOptions + aiQualityOptions
    val currentLabel = options.firstOrNull { it.first == currentId }?.second
        ?: standardQualityOptions[0].second

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.default_quality),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (id == currentId) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(id); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkQualitySelector(
    label: String,
    currentId: String,
    hideAi: Boolean,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (hideAi) standardQualityOptions else standardQualityOptions + aiQualityOptions
    val currentLabel = options.firstOrNull { it.first == currentId }?.second
        ?: standardQualityOptions[0].second

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, optLabel) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                optLabel,
                                color = if (id == currentId) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(id); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheSizeSelector(currentMb: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val unlimitedLabel = stringResource(R.string.unlimited)
    val disabledLabel = stringResource(R.string.cache_disabled)
    val currentLabel = when (currentMb) {
        0 -> unlimitedLabel
        1 -> disabledLabel
        else -> cacheSizeOptions.firstOrNull { it.first == currentMb }?.second
            ?: cacheSizeOptions[2].second // 默认 500MB
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.max_cache_size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                cacheSizeOptions.forEach { (mb, label) ->
                    val displayLabel = when (mb) {
                        0 -> unlimitedLabel
                        1 -> disabledLabel
                        else -> label
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                displayLabel,
                                color = if (mb == currentMb) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(mb); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun ConcurrentDownloadSelector(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel =
        concurrentDownloadOptions.firstOrNull { it.first == current }?.second ?: "$current"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.max_concurrent_downloads),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                concurrentDownloadOptions.forEach { (count, label) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                label,
                                color = if (count == current) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(count); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun NamingStyleSelector(currentKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes = namingStyleOptions.firstOrNull { it.first == currentKey }?.second
        ?: namingStyleOptions[0].second
    val currentLabel = stringResource(currentLabelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.naming_style),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                namingStyleOptions.forEach { (key, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (key == currentKey) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(key); expanded = false }
                    )
                }
            }
        }
    }
}

@Composable
internal fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            focusedBorderColor = MaterialTheme.colorScheme.tertiary,
            focusedLabelColor = MaterialTheme.colorScheme.tertiary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            cursorColor = MaterialTheme.colorScheme.tertiary
        )
    )
}

@Composable
private fun AudioFocusBehaviorSelector(currentBehavior: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes =
        audioFocusBehaviorOptions.firstOrNull { it.first == currentBehavior }?.second
            ?: audioFocusBehaviorOptions[2].second
    val currentLabel = stringResource(currentLabelRes)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 8.dp)
            .clickable { expanded = true },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            stringResource(R.string.audio_focus_behavior),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Box {
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.tertiary
            )
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                audioFocusBehaviorOptions.forEach { (behavior, labelRes) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(labelRes),
                                color = if (behavior == currentBehavior) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onBackground
                            )
                        },
                        onClick = { onSelect(behavior); expanded = false }
                    )
                }
            }
        }
    }
}
