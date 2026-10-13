package com.ikunshare.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.ikunshare.music.platform.base.PlayListInfoResult
import java.util.Locale

internal fun playlistPlayCount(count: Long): String = when {
    count >= 100_000_000 -> String.format(Locale.ROOT, "%.1f亿", count / 100_000_000.0)
    count >= 10_000 -> String.format(Locale.ROOT, "%.1f万", count / 10_000.0)
    else -> count.toString()
}

/** Kuwo sycdn HTTPS endpoints reject otherwise valid covers; preserve desktop's HTTP exception. */
internal fun playlistCoverUrl(url: String?): String {
    if (url.isNullOrBlank()) return ""
    if (Regex("^https?://[^/]+\\.sycdn\\.kuwo\\.cn/", RegexOption.IGNORE_CASE).containsMatchIn(url))
        return url.replaceFirst(Regex("^https:", RegexOption.IGNORE_CASE), "http:")
    return if (url.startsWith("http://")) "https://${url.substring(7)}" else url
}

@Composable
internal fun DiscoveryPlaylistCard(
    playlist: PlayListInfoResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showSource: Boolean = false,
    failedCovers: SnapshotStateMap<String, Boolean>? = null
) {
    val failures = failedCovers ?: remember { mutableStateMapOf<String, Boolean>() }
    val cover = remember(playlist.img) { playlistCoverUrl(playlist.img) }
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 420.dp).fillMaxWidth().liquidGlass().clickable(onClick = onClick).padding(10.dp)) {
            val artWidth = maxWidth * 0.4f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(artWidth).aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    if (cover.isNotEmpty() && failures[cover] != true) AsyncImage(cover, null,
                        Modifier.fillMaxSize(), contentScale = ContentScale.Crop, onError = { failures[cover] = true })
                    else Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(30.dp))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(playlist.title.orEmpty(), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!playlist.author.isNullOrBlank()) Text(playlist.author, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(playlist.totalSongs.takeIf { it > 0 }?.let { "$it 首" },
                        playlist.playCount.takeIf { it > 0 }?.let { "${playlistPlayCount(it)} 次播放" },
                        playlist.source.takeIf { showSource }?.uppercase(Locale.ROOT)).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
