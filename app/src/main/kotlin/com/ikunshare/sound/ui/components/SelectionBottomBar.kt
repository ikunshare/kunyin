package com.ikunshare.sound.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ikunshare.sound.R
import com.ikunshare.sound.ui.theme.LocalUiColors
import com.ikunshare.sound.ui.utils.LocalFloatingBottomBarReserve

private data class SelectionAction(
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val onClick: () -> Unit
)

@Composable
fun SelectionBottomBar(
    selectedCount: Int,
    onDownload: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onPlayNext: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = stringResource(R.string.delete),
    floatingBottomBar: Boolean = false,
    liquidGlass: Boolean = false
) {
    val tertiary = MaterialTheme.colorScheme.tertiary
    val error = MaterialTheme.colorScheme.error
    val actions = buildList {
        if (onPlayNext != null) {
            add(
                SelectionAction(
                    Icons.Filled.Queue,
                    stringResource(R.string.play_next),
                    tertiary,
                    onPlayNext
                )
            )
        }
        add(
            SelectionAction(
                Icons.Filled.Download,
                stringResource(R.string.download),
                tertiary,
                onDownload
            )
        )
        add(
            SelectionAction(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                stringResource(R.string.add_to_playlist),
                tertiary,
                onAddToPlaylist
            )
        )
        if (onRemove != null) {
            add(SelectionAction(Icons.Filled.Delete, removeLabel, error, onRemove))
        }
    }
    val countText = stringResource(R.string.selected_count_songs, selectedCount)

    if (floatingBottomBar) {
        FloatingSelectionBar(
            countText = countText,
            actions = actions,
            liquidGlass = liquidGlass
        )
    } else {
        ClassicSelectionBar(
            countText = countText,
            actions = actions
        )
    }
}

@Composable
private fun ClassicSelectionBar(
    countText: String,
    actions: List<SelectionAction>
) {
    // 与主底栏完全共用 bottomBarColor，连透明度一起继承，避免出现颜色断层。
    val barColor = LocalUiColors.current.bottomBarColor
    Surface(
        color = barColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = countText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                actions.forEach { action ->
                    CompactSelectionAction(action)
                }
            }
        }
    }
}

@Composable
private fun FloatingSelectionBar(
    countText: String,
    actions: List<SelectionAction>,
    liquidGlass: Boolean
) {
    val outerReserve = LocalFloatingBottomBarReserve.current
    val isLight = !isSystemInDarkTheme()
    val surfaceColor = when {
        liquidGlass && isLight -> Color.White.copy(alpha = 0.55f)
        liquidGlass -> Color.Black.copy(alpha = 0.45f)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }

    val bottomReserve: Dp = if (outerReserve > 0.dp) outerReserve + 8.dp else 16.dp

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(surfaceColor)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FloatingBarContent(countText = countText, actions = actions)
        }

        Spacer(modifier = Modifier.height(bottomReserve))
    }
}

@Composable
private fun RowScope.FloatingBarContent(
    countText: String,
    actions: List<SelectionAction>
) {
    Text(
        text = countText,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        maxLines = 1
    )
    Row(
        modifier = Modifier
            .weight(1f)
            .padding(end = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            CompactSelectionAction(action)
        }
    }
}

@Composable
private fun CompactSelectionAction(action: SelectionAction) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = action.onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            tint = action.color,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelSmall,
            color = action.color,
            maxLines = 1
        )
    }
}
