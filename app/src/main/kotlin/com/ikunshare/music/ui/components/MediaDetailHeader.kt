package com.ikunshare.music.ui.components

import com.ikunshare.music.R
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/** Shared detail layout with wrapping actions and a description that stays in the scrolling content. */
@Composable
fun MediaDetailHeader(
    title: String, cover: String?, metadata: List<String>, description: String?,
    avatar: Boolean = false, actions: @Composable FlowRowScope.() -> Unit
) {
    var expanded by rememberSaveable(title, description) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp).liquidGlass().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BoxWithConstraints {
            val coverSize = if (maxWidth < 360.dp) 88.dp else 120.dp
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(cover, title, Modifier.size(coverSize)
                    .clip(if (avatar) CircleShape else RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                    metadata.filter { it.isNotBlank() }.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        description?.takeIf { it.isNotBlank() }?.let { text ->
            var truncated by remember(text) { mutableStateOf(false) }
            Text(text, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow })
            if (expanded || truncated) TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.playlist_description_collapse else R.string.playlist_description_expand))
            }
        }
    }
}

@Composable
fun DetailLoadStatus(loading: Boolean, error: String?, onRetry: () -> Unit) {
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.tertiary)
    if (error != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).liquidGlass().padding(12.dp)) {
        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onRetry, enabled = !loading) { Text(stringResource(R.string.retry)) }
    }
}
