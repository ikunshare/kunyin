package com.ikunshare.music.ui.screens.settings

import com.ikunshare.music.R
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import com.ikunshare.music.ui.theme.Motion
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.ikunshare.music.MusicApplication
import com.ikunshare.music.manager.*
import com.ikunshare.music.ui.KunyinViewModelFactory
import com.ikunshare.music.ui.components.GlassAlertDialog
import com.ikunshare.music.ui.components.GlassIconButton
import com.ikunshare.music.ui.components.GlassTopBar
import com.ikunshare.music.ui.components.LiquidGlassScene
import com.ikunshare.music.ui.components.liquidGlass
import com.ikunshare.music.ui.utils.LocalFloatingBottomBarReserve
import com.kyant.shapes.RoundedRectangle
import com.ikunshare.music.ui.components.FolderPickerDialog
import com.ikunshare.music.ui.screens.download.DownloadNamingStyle
import com.ikunshare.music.ui.utils.LocalScrollOffsetReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    LIST(R.string.section_list),
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
    viewModelFactory: KunyinViewModelFactory,
    isActive: Boolean = true
) {
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory)
    val creds by vm.credentials.collectAsState()
    val settings by vm.appSettings.collectAsState()
    var dialogProvider by remember { mutableStateOf<ProviderInfo?>(null) }
    var selectedSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val detailStateHolder = rememberSaveableStateHolder()
    val detailStates = SettingsSection.entries.associateWith { section ->
        key(section) { rememberLazyListState() }
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    LiquidGlassScene {
        if (screenWidthDp >= 600) {
            WideSettingsLayout(
                selectedSection = selectedSection ?: SettingsSection.ACCOUNT,
                onSectionSelect = { selectedSection = it },
                detailStateHolder = detailStateHolder,
                detailStates = detailStates,
                creds = creds,
                settings = settings,
                vm = vm,
                onProviderClick = { dialogProvider = it }
            )
        } else {
            NarrowSettingsLayout(
                selectedSection = selectedSection,
                isActive = isActive,
                onSectionSelect = { selectedSection = it },
                detailStateHolder = detailStateHolder,
                detailStates = detailStates,
                creds = creds,
                settings = settings,
                vm = vm,
                onProviderClick = { dialogProvider = it }
            )
        }
    }

    dialogProvider?.let { info ->
        when (info.key) {
            "wy", "qq" -> WebLoginMethodDialog(
                providerName = info.displayName,
                onDismiss = { dialogProvider = null },
                onQRLogin = {
                    navController.navigate("${info.key}_qr_login"); dialogProvider = null
                },
                onWebViewLogin = {
                    navController.navigate("login_webview/${info.key}"); dialogProvider = null
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WideSettingsLayout(
    selectedSection: SettingsSection,
    onSectionSelect: (SettingsSection) -> Unit,
    detailStateHolder: SaveableStateHolder,
    detailStates: Map<SettingsSection, LazyListState>,
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    val navigationState = rememberLazyListState()
    val bottomReserve = LocalFloatingBottomBarReserve.current
    Scaffold(
        topBar = { GlassTopBar(title = { Text(stringResource(R.string.settings_title)) }) },
        containerColor = Color.Transparent
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(
                state = navigationState,
                modifier = Modifier.width(232.dp).fillMaxHeight(),
                contentPadding = PaddingValues(start = 16.dp, top = 8.dp, bottom = bottomReserve + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SettingsSection.entries.groupBy(::sectionGroup).forEach { (group, sections) ->
                    item(key = "group_$group") { SettingsGroupHeading(stringResource(group)) }
                    items(sections, key = { it.name }) { section ->
                        SettingsSectionCard(
                            title = stringResource(section.titleRes),
                            icon = sectionIcon(section),
                            selected = section == selectedSection,
                            onClick = { onSectionSelect(section) }
                        )
                    }
                }
            }
            detailStateHolder.SaveableStateProvider(selectedSection.name) {
                val detailState = detailStates.getValue(selectedSection)
                RememberScrollOffsetReporter(detailState)
                LazyColumn(
                    state = detailState,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(end = 16.dp, top = 8.dp, bottom = bottomReserve + 24.dp)
                ) {
                    item { SettingsSectionContent(selectedSection, creds, settings, vm, onProviderClick) }
                }
            }
        }
    }
}

private fun sectionIcon(section: SettingsSection): ImageVector = when (section) {
    SettingsSection.ACCOUNT -> Icons.Filled.Person
    SettingsSection.APPEARANCE -> Icons.Filled.Palette
    SettingsSection.FONT -> Icons.Filled.FormatSize
    SettingsSection.LIST -> Icons.AutoMirrored.Filled.List
    SettingsSection.PLAYBACK -> Icons.Filled.PlayCircle
    SettingsSection.FLOATING_LYRICS -> Icons.Filled.TextFields
    SettingsSection.LYRICON -> Icons.Filled.Lyrics
    SettingsSection.DOWNLOAD -> Icons.Filled.Download
    SettingsSection.CACHE -> Icons.Filled.Storage
    SettingsSection.BACKUP -> Icons.Filled.SettingsBackupRestore
    SettingsSection.SYNC -> Icons.Filled.Sync
    SettingsSection.ABOUT -> Icons.Filled.Info
}

@StringRes
private fun sectionGroup(section: SettingsSection): Int = when (section) {
    SettingsSection.ACCOUNT, SettingsSection.APPEARANCE, SettingsSection.FONT -> R.string.settings_group_personal
    SettingsSection.PLAYBACK, SettingsSection.FLOATING_LYRICS, SettingsSection.LYRICON -> R.string.settings_group_listening
    SettingsSection.LIST, SettingsSection.DOWNLOAD, SettingsSection.CACHE, SettingsSection.BACKUP, SettingsSection.SYNC -> R.string.settings_group_library
    SettingsSection.ABOUT -> R.string.settings_group_app
}

@StringRes
private fun sectionSummary(section: SettingsSection): Int = when (section) {
    SettingsSection.ACCOUNT -> R.string.settings_summary_account
    SettingsSection.APPEARANCE -> R.string.settings_summary_appearance
    SettingsSection.FONT -> R.string.settings_summary_font
    SettingsSection.LIST -> R.string.settings_summary_list
    SettingsSection.PLAYBACK -> R.string.settings_summary_playback
    SettingsSection.FLOATING_LYRICS -> R.string.settings_summary_floating_lyrics
    SettingsSection.LYRICON -> R.string.settings_summary_lyricon
    SettingsSection.DOWNLOAD -> R.string.settings_summary_download
    SettingsSection.CACHE -> R.string.settings_summary_cache
    SettingsSection.BACKUP -> R.string.settings_summary_backup
    SettingsSection.SYNC -> R.string.settings_summary_sync
    SettingsSection.ABOUT -> R.string.settings_summary_about
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NarrowSettingsLayout(
    selectedSection: SettingsSection?,
    isActive: Boolean,
    onSectionSelect: (SettingsSection?) -> Unit,
    detailStateHolder: SaveableStateHolder,
    detailStates: Map<SettingsSection, LazyListState>,
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    val settingsListState = rememberLazyListState()
    val bottomReserve = LocalFloatingBottomBarReserve.current
    RememberScrollOffsetReporter(settingsListState)
    val navigationState = remember { SeekableTransitionState(selectedSection) }
    val navigation = rememberTransition(navigationState, label = "settings_nav")
    val navigationScope = rememberCoroutineScope()
    LaunchedEffect(selectedSection) { navigationState.animateTo(selectedSection) }
    PredictiveBackHandler(enabled = isActive && selectedSection != null) { events ->
        try {
            if (navigationState.currentState != selectedSection) navigationState.snapTo(selectedSection)
            events.collect { event -> navigationState.seekTo(event.progress, targetState = null) }
            onSectionSelect(null)
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The gesture coroutine is cancelled too; restore from the composition's live scope.
            navigationScope.launch { navigationState.animateTo(selectedSection) }
        }
    }
    navigation.AnimatedContent(
        transitionSpec = {
            // Pages here are transparent over the glass scene, so the covered one fades as it drifts.
            val parallax = { full: Int -> (full * Motion.PARALLAX_FRACTION).toInt() }
            if (targetState != null) {
                slideInHorizontally(Motion.tracking()) { it } togetherWith
                    (slideOutHorizontally(Motion.tracking()) { -parallax(it) } + fadeOut(Motion.fadeOut(200)))
            } else {
                (slideInHorizontally(Motion.tracking()) { -parallax(it) } + fadeIn(Motion.fadeIn(260, delayMillis = 80))) togetherWith
                    slideOutHorizontally(Motion.tracking()) { it }
            }
        }
    ) { section ->
        Scaffold(
            topBar = {
                GlassTopBar(
                    title = { Text(stringResource(R.string.settings_title)) },
                    navigationIcon = {
                        if (section != null) {
                            GlassIconButton(onClick = { onSectionSelect(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                            }
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { padding ->
            if (section == null) {
                LazyColumn(
                    state = settingsListState,
                    modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomReserve + 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsSection.entries.groupBy(::sectionGroup).forEach { (group, sections) ->
                        item(key = "group_$group") { SettingsGroupHeading(stringResource(group)) }
                        items(sections, key = { it.name }) { sec ->
                            SettingsSectionCard(
                                title = stringResource(sec.titleRes),
                                subtitle = stringResource(sectionSummary(sec)),
                                icon = sectionIcon(sec),
                                onClick = { onSectionSelect(sec) }
                            )
                        }
                    }
                }
            } else {
                detailStateHolder.SaveableStateProvider(section.name) {
                    val detailState = detailStates.getValue(section)
                    RememberScrollOffsetReporter(detailState)
                    LazyColumn(
                        state = detailState,
                        modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomReserve + 24.dp)
                    ) {
                        item { SettingsSectionContent(section, creds, settings, vm, onProviderClick) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeading(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    subtitle: String? = null,
    selected: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().liquidGlass(RoundedRectangle(26.dp), selected = selected)
            .semantics { if (subtitle == null) this.selected = selected }
            .clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f), RoundedRectangle(16.dp)),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (subtitle != null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SettingsSectionContent(
    section: SettingsSection,
    creds: Map<String, CredentialEntry>,
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel,
    onProviderClick: (ProviderInfo) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(stringResource(section.titleRes))
        Text(stringResource(sectionSummary(section)), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 16.dp))
        when (section) {
            SettingsSection.ACCOUNT -> AccountSection(creds, settings, vm, onProviderClick)
            SettingsSection.APPEARANCE -> AppearanceSection(settings, vm)
            SettingsSection.FONT -> FontSection(settings, vm)
            SettingsSection.LIST -> ListSettingsSection(settings, vm)
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
}
@Composable
private fun PlaybackSection(
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel
) {

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
        SettingsSlider(
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
            (context as? com.ikunshare.music.MainActivity)?.playerViewModel?.registerPhoneStateListener()
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
private fun ListSettingsSection(settings: com.ikunshare.music.common.AppSettings, vm: SettingsViewModel) {
    SettingSwitchItem(stringResource(R.string.show_list_operation_buttons), settings.showListOperationButtons) {
        vm.updateSettings { copy(showListOperationButtons = it) }
    }
    Text(stringResource(R.string.list_settings_artist_hint), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp))
}

@Composable
private fun DownloadSection(
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel
) {

    val context = LocalContext.current
    val directoryScope = rememberCoroutineScope()
    val directoryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) directoryScope.launch {
            try {
                val selected = withContext(Dispatchers.IO) {
                    authorizeDownloadDirectory(uri,
                        { androidx.documentfile.provider.DocumentFile.fromTreeUri(context, it) },
                        { tree, flags -> context.contentResolver.takePersistableUriPermission(tree, flags) })
                }
                vm.updateSettings { copy(downloadTreeUri = selected) }
            } catch (failure: Exception) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                Toast.makeText(context, R.string.download_directory_permission_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    val defaultPath = DownloadManager.defaultDownloadDir().absolutePath

    var showPathDialog by remember { mutableStateOf(false) }
    var showFolderPicker by remember { mutableStateOf(false) }
    val pathDisplay = downloadDirectoryDisplay(settings.downloadTreeUri, settings.downloadPath, defaultPath)

    // 下载路径
    SettingClickableItem(
        label = stringResource(R.string.download_path),
        value = pathDisplay,
        valueColor = MaterialTheme.colorScheme.tertiary,
        onClick = { showPathDialog = true }
    )

    TextButton(onClick = {
        try {
            val intent = downloadDirectoryViewIntent(settings.downloadTreeUri, settings.downloadPath,
                defaultPath, Environment.getExternalStorageDirectory())
            if (intent == null) {
                Toast.makeText(context, R.string.download_directory_open_failed, Toast.LENGTH_LONG).show()
            } else context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, R.string.download_directory_open_failed, Toast.LENGTH_LONG).show()
        }
    }) {
        Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.download_open_directory))
    }

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

    SettingSwitchItem(stringResource(R.string.download_disc_sub_directory), settings.downloadDiscSubDir) {
        vm.updateSettings { copy(downloadDiscSubDir = it) }
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
        GlassAlertDialog(
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
                            directoryPicker.launch(settings.downloadTreeUri.takeIf { it.isNotBlank() }?.toUri())
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
                    TextButton(
                        onClick = { showPathDialog = false; showFolderPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.download_browse_local_directory))
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
    settings: com.ikunshare.music.common.AppSettings,
    vm: SettingsViewModel
) {

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
        GlassAlertDialog(
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
        GlassAlertDialog(
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
                    val cacheView = android.webkit.WebView(context)
                    try { cacheView.clearCache(true) } finally { cacheView.destroy() }
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
internal fun DebugSettingsDialog(onDismiss: () -> Unit) {
    val app = MusicApplication.instance ?: return
    val settings by app.appSettingsManager.settings.collectAsState()
    val context = LocalContext.current

    GlassAlertDialog(
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
                val setLyriconLocalCentral: (Boolean) -> Unit = { enabled ->
                    app.appSettingsManager.update { copy(debugLyriconLocalCentral = enabled) }
                    // 已启用词幕时立即切换中心服务，当前歌曲/播放状态会被保留
                    if (settings.lyriconEnabled) app.lyriconAdapter.init(app, enabled)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { setLyriconLocalCentral(!settings.debugLyriconLocalCentral) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Lyricon localcentralapp", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "连接 LocalCentralService 而非 SystemUI，用于无 LSPosed 环境测试，发布/日常使用请关闭",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.debugLyriconLocalCentral,
                        onCheckedChange = setLyriconLocalCentral,
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
private fun CacheRow(
    label: String,
    value: String,
    onClear: () -> Unit
) {
    SettingClickableItem(
        label = label,
        value = value,
        trailing = {
            GlassIconButton(onClick = onClear) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.clear),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    )
}

@Composable
private fun DefaultQualitySelector(currentId: String, hideAi: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val options = if (hideAi) standardQualityOptions else standardQualityOptions + aiQualityOptions
    val currentLabel = options.firstOrNull { it.first == currentId }?.second
        ?: standardQualityOptions[0].second

    SettingsDropdownRow(
        label = stringResource(R.string.default_quality),
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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

    SettingsDropdownRow(
        label = label,
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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

    SettingsDropdownRow(
        label = stringResource(R.string.max_cache_size),
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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

@Composable
private fun ConcurrentDownloadSelector(current: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabel =
        concurrentDownloadOptions.firstOrNull { it.first == current }?.second ?: "$current"

    SettingsDropdownRow(
        label = stringResource(R.string.max_concurrent_downloads),
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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

@Composable
private fun NamingStyleSelector(currentKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val currentLabelRes = namingStyleOptions.firstOrNull { it.first == currentKey }?.second
        ?: namingStyleOptions[0].second
    val currentLabel = stringResource(currentLabelRes)

    SettingsDropdownRow(
        label = stringResource(R.string.naming_style),
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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

    SettingsDropdownRow(
        label = stringResource(R.string.audio_focus_behavior),
        value = currentLabel,
        onClick = { expanded = true }
    ) {
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
