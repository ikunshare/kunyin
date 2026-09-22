package com.ikunshare.sound.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.MvPlayerActivity
import com.ikunshare.sound.R
import com.ikunshare.sound.SoundApplication
import com.ikunshare.sound.model.MusicItem
import com.ikunshare.sound.platform.base.MvQuality
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MV 清晰度选择弹窗。
 * 点击行 → 启动 [MvPlayerActivity] 播放；点击下载图标 → 入下载列表。
 */
@Composable
fun MvDialog(
    song: MusicItem,
    sourceTag: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = context.applicationContext as? SoundApplication

    var loading by remember { mutableStateOf(true) }
    var qualities by remember { mutableStateOf<List<MvQuality>>(emptyList()) }
    var resolvingFor by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(song.id, sourceTag) {
        if (app == null) {
            onDismiss(); return@LaunchedEffect
        }
        loading = true
        val provider = app.musicRepository.getProvider(sourceTag)
        if (provider == null || !provider.supportsMv(song)) {
            Toast.makeText(context, R.string.mv_no_quality, Toast.LENGTH_SHORT).show()
            onDismiss(); return@LaunchedEffect
        }
        val list = withContext(Dispatchers.IO) { provider.getMvQualities(song) }
        loading = false
        qualities = list
        if (list.isEmpty()) {
            Toast.makeText(context, R.string.mv_no_quality, Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    if (loading || qualities.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.mv_select_quality)) },
            text = {
                if (loading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.mv_loading),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (q in qualities) {
                            MvQualityRow(
                                quality = q,
                                isResolving = resolvingFor == q.quality,
                                onPlay = {
                                    if (resolvingFor != null) return@MvQualityRow
                                    val provider = app?.musicRepository?.getProvider(sourceTag)
                                        ?: return@MvQualityRow
                                    resolvingFor = q.quality
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            provider.getMvUrl(song, q.quality)
                                        }
                                        resolvingFor = null
                                        if (!result.playUrl.isNullOrBlank()) {
                                            MvPlayerActivity.launch(
                                                context = context,
                                                url = result.playUrl,
                                                title = song.title,
                                                song = song,
                                                sourceTag = sourceTag,
                                                qualities = qualities,
                                                currentQuality = q.quality
                                            )
                                            onDismiss()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                result.rejectReason
                                                    ?: context.getString(R.string.mv_play_failed),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                },
                                onDownload = {
                                    if (resolvingFor != null) return@MvQualityRow
                                    val provider = app?.musicRepository?.getProvider(sourceTag)
                                        ?: return@MvQualityRow
                                    resolvingFor = q.quality
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            provider.getMvUrl(song, q.quality)
                                        }
                                        resolvingFor = null
                                        if (!result.playUrl.isNullOrBlank()) {
                                            app.downloadManager.addVideoTask(
                                                song = song,
                                                sourceTag = sourceTag,
                                                rawQuality = q.quality,
                                                qualityName = q.displayName,
                                                url = result.playUrl
                                            )
                                            Toast.makeText(
                                                context,
                                                R.string.mv_download_added,
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onDismiss()
                                        } else {
                                            Toast.makeText(
                                                context,
                                                result.rejectReason
                                                    ?: context.getString(R.string.mv_play_failed),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun MvQualityRow(
    quality: MvQuality,
    isResolving: Boolean,
    onPlay: () -> Unit,
    onDownload: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isResolving) { onPlay() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(quality.displayName, style = MaterialTheme.typography.bodyLarge)
            if (quality.displaySize.isNotBlank()) {
                Text(
                    quality.displaySize,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isResolving) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(20.dp)
                    .padding(end = 4.dp),
                strokeWidth = 2.dp
            )
            Spacer(Modifier.width(4.dp))
        }
        IconButton(
            onClick = onDownload,
            enabled = !isResolving
        ) {
            Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = stringResource(R.string.download)
            )
        }
    }
}
