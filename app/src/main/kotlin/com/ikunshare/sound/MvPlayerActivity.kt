package com.ikunshare.sound

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.google.gson.Gson
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.MvQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

/**
 * MV 播放 Activity。全屏沉浸 + 跟随设备方向旋转，进入暂停音乐、退出恢复。
 * 支持在播放页内直接切换清晰度（保留当前进度）。
 */
class MvPlayerActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_URL = "mv_url"
        private const val EXTRA_TITLE = "mv_title"
        private const val EXTRA_SONG = "mv_song"
        private const val EXTRA_SOURCE = "mv_source"
        private const val EXTRA_QUALITIES = "mv_qualities"
        private const val EXTRA_CURRENT_QUALITY = "mv_current_quality"

        fun launch(
            context: Context,
            url: String,
            title: String,
            song: MusicItem? = null,
            sourceTag: String? = null,
            qualities: List<MvQuality> = emptyList(),
            currentQuality: String? = null
        ) {
            val intent = Intent(context, MvPlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                song?.let { putExtra(EXTRA_SONG, it.toJson().toString()) }
                sourceTag?.let { putExtra(EXTRA_SOURCE, it) }
                if (qualities.isNotEmpty()) {
                    putExtra(EXTRA_QUALITIES, Gson().toJson(qualities))
                }
                currentQuality?.let { putExtra(EXTRA_CURRENT_QUALITY, it) }
            }
            context.startActivity(intent)
        }
    }

    private var wasPlaying = false
    private val pipMode = mutableStateOf(false)

    val supportsPip: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !supportsPip) return
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipMode.value = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (supportsPip && !isFinishing) enterPip()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val song = intent.getStringExtra(EXTRA_SONG)
            ?.let { runCatching { MusicItem.fromJsonString(it) }.getOrNull() }
        val sourceTag = intent.getStringExtra(EXTRA_SOURCE)
        val qualities: List<MvQuality> = intent.getStringExtra(EXTRA_QUALITIES)
            ?.let {
                runCatching { Gson().fromJson(it, Array<MvQuality>::class.java).toList() }
                    .getOrNull()
            } ?: emptyList()
        val currentQuality = intent.getStringExtra(EXTRA_CURRENT_QUALITY)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR

        val app = application as? SoundApplication
        wasPlaying = app?.playbackController?.isPlaying() == true
        if (wasPlaying) app?.playbackController?.pause()

        setContent {
            MvPlayerContent(
                initialUrl = url,
                title = title,
                song = song,
                sourceTag = sourceTag,
                qualities = qualities,
                initialQuality = currentQuality,
                inPipMode = pipMode.value,
                pipSupported = supportsPip,
                onEnterPip = { enterPip() },
                onClose = { finish() }
            )
        }

        enterImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (wasPlaying) {
            (application as? SoundApplication)?.playbackController?.play()
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }
}

@Composable
private fun MvPlayerContent(
    initialUrl: String,
    title: String,
    song: MusicItem?,
    sourceTag: String?,
    qualities: List<MvQuality>,
    initialQuality: String?,
    inPipMode: Boolean,
    pipSupported: Boolean,
    onEnterPip: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as? SoundApplication

    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var currentQuality by remember { mutableStateOf(initialQuality) }
    var pendingSeekMs by remember { mutableIntStateOf(0) }

    var isPlaying by remember { mutableStateOf(true) }
    var prepared by remember { mutableStateOf(false) }
    var hasError by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var currentPos by remember { mutableIntStateOf(0) }
    var userSeeking by remember { mutableStateOf(false) }

    var controlsVisible by remember { mutableStateOf(true) }
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var switchingQuality by remember { mutableStateOf(false) }

    LaunchedEffect(prepared, isPlaying) {
        while (prepared) {
            if (!userSeeking) currentPos = videoView?.currentPosition ?: 0
            delay(500.milliseconds)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, qualityMenuExpanded, userSeeking) {
        if (controlsVisible && isPlaying && !qualityMenuExpanded && !userSeeking) {
            delay(3500.milliseconds)
            controlsVisible = false
        }
    }

    LaunchedEffect(currentUrl) {
        videoView?.let { v ->
            prepared = false
            hasError = false
            v.setVideoURI(currentUrl.toUri())
        }
    }

    fun togglePlay() {
        val v = videoView ?: return
        if (v.isPlaying) {
            v.pause(); isPlaying = false
        } else {
            v.start(); isPlaying = true
        }
        controlsVisible = true
    }

    fun seekBy(deltaMs: Int) {
        val v = videoView ?: return
        if (!prepared) return
        val target = (currentPos + deltaMs).coerceIn(0, duration)
        v.seekTo(target)
        currentPos = target
        controlsVisible = true
    }

    @SuppressLint("LocalContextGetResourceValueCall")
    fun switchQuality(q: MvQuality) {
        if (switchingQuality || q.quality == currentQuality) return
        if (song == null || sourceTag == null || app == null) return
        val provider = app.musicRepository.getProvider(sourceTag) ?: return
        switchingQuality = true
        pendingSeekMs = currentPos
        Toast.makeText(context, R.string.mv_quality_switching, Toast.LENGTH_SHORT).show()
        scope.launch {
            val result = withContext(Dispatchers.IO) { provider.getMvUrl(song, q.quality) }
            switchingQuality = false
            if (!result.playUrl.isNullOrBlank()) {
                currentQuality = q.quality
                currentUrl = result.playUrl
                isPlaying = true
            } else {
                Toast.makeText(
                    context,
                    result.rejectReason ?: context.getString(R.string.mv_play_failed),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
    ) {
        AndroidView(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.Center),
            factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoURI(currentUrl.toUri())
                    setOnPreparedListener { mp ->
                        prepared = true
                        duration = mp.duration
                        if (pendingSeekMs > 0) {
                            seekTo(pendingSeekMs); pendingSeekMs = 0
                        }
                        if (isPlaying) start()
                    }
                    setOnCompletionListener {
                        isPlaying = false
                        currentPos = duration
                        controlsVisible = true
                    }
                    setOnErrorListener { _, _, _ ->
                        hasError = true
                        true
                    }
                    videoView = this
                }
            }
        )

        if ((!prepared || switchingQuality) && !hasError) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(48.dp)
                    .align(Alignment.Center),
                color = Color.White
            )
        }
        if (hasError) {
            Text(
                stringResource(R.string.mv_play_failed),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !inPipMode,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            TopBar(
                title = title,
                qualities = qualities,
                currentQuality = currentQuality,
                expanded = qualityMenuExpanded,
                canSwitch = song != null && sourceTag != null && qualities.isNotEmpty(),
                pipSupported = pipSupported,
                onEnterPip = onEnterPip,
                onExpandedChange = {
                    qualityMenuExpanded = it
                    controlsVisible = true
                },
                onSelectQuality = {
                    qualityMenuExpanded = false
                    switchQuality(it)
                },
                onClose = onClose
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && prepared && !inPipMode,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            BottomBar(
                isPlaying = isPlaying,
                currentPos = currentPos,
                duration = duration,
                onTogglePlay = ::togglePlay,
                onSeekBack = { seekBy(-10_000) },
                onSeekForward = { seekBy(10_000) },
                onSeekStart = {
                    userSeeking = true
                    controlsVisible = true
                },
                onSeekChange = { currentPos = it },
                onSeekFinished = {
                    videoView?.seekTo(currentPos)
                    userSeeking = false
                }
            )
        }
    }
}

@Composable
private fun TopBar(
    title: String,
    qualities: List<MvQuality>,
    currentQuality: String?,
    expanded: Boolean,
    canSwitch: Boolean,
    pipSupported: Boolean,
    onEnterPip: () -> Unit,
    onExpandedChange: (Boolean) -> Unit,
    onSelectQuality: (MvQuality) -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)
                )
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = Color.White
            )
        }
        Text(
            title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
        )
        if (pipSupported) {
            IconButton(onClick = onEnterPip) {
                Icon(
                    Icons.Filled.PictureInPictureAlt,
                    contentDescription = stringResource(R.string.mv_pip),
                    tint = Color.White
                )
            }
        }
        if (canSwitch) {
            Box {
                val currentName =
                    qualities.firstOrNull { it.quality == currentQuality }?.displayName
                AssistChip(
                    onClick = { onExpandedChange(true) },
                    label = {
                        Text(
                            currentName ?: stringResource(R.string.mv_change_quality),
                            color = Color.White
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.HighQuality,
                            contentDescription = null,
                            tint = Color.White
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = Color.White.copy(alpha = 0.15f)
                    )
                )
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { onExpandedChange(false) }
                ) {
                    qualities.forEach { q ->
                        val selected = q.quality == currentQuality
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        q.displayName,
                                        fontWeight = if (selected) FontWeight.Bold
                                        else FontWeight.Normal
                                    )
                                    if (q.displaySize.isNotBlank()) {
                                        Text(
                                            q.displaySize,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            onClick = { onSelectQuality(q) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    isPlaying: Boolean,
    currentPos: Int,
    duration: Int,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekStart: () -> Unit,
    onSeekChange: (Int) -> Unit,
    onSeekFinished: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f))
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                formatMillis(currentPos),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
            Slider(
                value = currentPos.toFloat(),
                onValueChange = {
                    onSeekStart()
                    onSeekChange(it.toInt())
                },
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            )
            Text(
                formatMillis(duration),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onSeekBack) {
                Icon(
                    Icons.Filled.Replay10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = onTogglePlay, modifier = Modifier.size(56.dp)) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            IconButton(onClick = onSeekForward) {
                Icon(
                    Icons.Filled.Forward10,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

private fun formatMillis(ms: Int): String {
    if (ms < 0) return "00:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%02d:%02d".format(m, s)
}
